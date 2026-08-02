package net.eternallauncher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class GameLauncher {

    public void launch(File gameDir, LauncherConfig config, String username, String assetIndex, Runnable onProcessEnd) {
        try {
            String selectedItem = config.getVersion();
            System.out.println("[GameLauncher] Подготовка к запуску версии: " + selectedItem);

            ParsedVersion parsed = new ParsedVersion(selectedItem);
            String versionId = getCustomVersionId(parsed.mcVersion, parsed.loaderType, parsed.loaderVersion);
            boolean isModded = parsed.loaderType != null && !parsed.loaderType.equalsIgnoreCase("VANILLA") && !parsed.loaderType.isEmpty();

            File canonicalGameDir = gameDir.getCanonicalFile();
            File activeGameDir = SymlinkManager.getVersionDir(canonicalGameDir, parsed);

            if (isModded) {
                if (!activeGameDir.exists()) {
                    activeGameDir.mkdirs();
                }

                File versionModsDir = new File(activeGameDir, "mods");
                File versionConfigDir = new File(activeGameDir, "config");
                if (!versionModsDir.exists()) versionModsDir.mkdirs();
                if (!versionConfigDir.exists()) versionConfigDir.mkdirs();

                SymlinkManager.prepareSharedFolders(canonicalGameDir, parsed);
            }

            File nativesDir = new File(canonicalGameDir, "versions/" + versionId + "/natives");
            if (!nativesDir.exists()) {
                nativesDir = new File(canonicalGameDir, "versions/" + parsed.mcVersion + "/natives");
            }

            String uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8))
                    .toString().replace("-", "");

            File vanillaJsonFile = new File(canonicalGameDir, "versions/" + parsed.mcVersion + "/" + parsed.mcVersion + ".json");
            JsonObject vanillaJson = loadJson(vanillaJsonFile);

            JsonObject customJson = null;
            if (isModded) {
                File customJsonFile = new File(canonicalGameDir, "versions/" + versionId + "/" + versionId + ".json");
                customJson = loadJson(customJsonFile);
            }

            String classpath = buildClasspath(canonicalGameDir, parsed.mcVersion, vanillaJson, customJson);
            String mainClass = readMainClass(customJson, vanillaJson);

            Map<String, String> substitutions = buildSubstitutions(
                    parsed.mcVersion, versionId, username, uuid, assetIndex,
                    canonicalGameDir, activeGameDir, nativesDir, classpath, config
            );
            Map<String, Boolean> features = buildFeatures();

            List<String> jvmArgs = new ArrayList<>();
            List<String> gameArgs = new ArrayList<>();
            boolean modernFormat = collectAllArguments(vanillaJson, customJson, features, substitutions, jvmArgs, gameArgs);

            List<String> command = new ArrayList<>();

            command.add(config.getJavaExecutable());
            command.add("-Xms" + config.getMinMemory());
            command.add("-Xmx" + config.getMaxMemory());

            if (!config.getJvmArgs().isBlank()) {
                for (String arg : config.getJvmArgs().split(" ")) {
                    if (!arg.isBlank()) command.add(arg);
                }
            }

            command.add("-Djava.library.path=" + nativesDir.getAbsolutePath());
            command.add("-Dorg.lwjgl.librarypath=" + nativesDir.getAbsolutePath());

            if (modernFormat) {
                command.addAll(jvmArgs);

                if (isModded) {
                    File clientJar = new File(canonicalGameDir, "versions/" + parsed.mcVersion + "/" + parsed.mcVersion + ".jar");
                    if (clientJar.exists()) {
                        String jarPath = clientJar.getAbsolutePath();
                        boolean hasFmlJarArg = command.stream().anyMatch(a -> a.contains("fml.minecraftJar"));
                        if (!hasFmlJarArg) {
                            command.add("-Dfml.minecraftJar=" + jarPath);
                        }
                    }
                }

                boolean hasCpFlag = command.stream().anyMatch(a -> a.equals("-cp") || a.equals("--class-path"));
                if (!hasCpFlag) {
                    command.add("-cp");
                    command.add(classpath);
                }
            } else {
                command.add("-cp");
                command.add(classpath);
            }

            command.add(mainClass);

            if (modernFormat) {
                command.addAll(gameArgs);
            } else {
                command.add("--username");
                command.add(username);
                command.add("--version");
                command.add(parsed.mcVersion);
                command.add("--gameDir");
                command.add(activeGameDir.getAbsolutePath());
                command.add("--assetsDir");
                command.add(new File(canonicalGameDir, "assets").getAbsolutePath());
                command.add("--assetIndex");
                command.add(assetIndex);
                command.add("--uuid");
                command.add(uuid);
                command.add("--accessToken");
                command.add("0");
                command.add("--userType");
                command.add("legacy");
                command.add("--width");
                command.add(config.getWindowWidth());
                command.add("--height");
                command.add(config.getWindowHeight());
            }

            if (config.isFullscreen()) {
                command.add("--fullscreen");
            }

            System.out.println("[GameLauncher] Главный класс: " + mainClass);
            System.out.println("[GameLauncher] Рабочая папка игры (gameDir): " + activeGameDir.getAbsolutePath());

            System.out.println("[GameLauncher] Запуск процесса...");
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(activeGameDir);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Читаем вывод игры в отдельном потоке, чтобы буфер не переполнялся
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[GAME] " + line);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();

            // Ожидаем реального завершения процесса игры в текущем фоновом потоке лаунчера
            int exitCode = process.waitFor();
            System.out.println("[GameLauncher] Игра закрыта с кодом: " + exitCode);

            if (onProcessEnd != null) {
                onProcessEnd.run();
            }

        } catch (Exception e) {
            e.printStackTrace();
            if (onProcessEnd != null) {
                onProcessEnd.run();
            }
        }
    }

    private JsonObject loadJson(File file) {
        if (file == null || !file.exists()) return null;
        try {
            String content = Files.readString(file.toPath());
            return JsonParser.parseString(content).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private String buildClasspath(File gameDir, String mcVersion, JsonObject vanillaJson, JsonObject customJson) {
        List<String> jars = new ArrayList<>();
        Map<String, String> libraryArtifacts = new HashMap<>();

        addLibrariesToMap(gameDir, vanillaJson, libraryArtifacts);
        if (customJson != null) {
            addLibrariesToMap(gameDir, customJson, libraryArtifacts);
        }

        jars.addAll(libraryArtifacts.values());

        File clientJar = new File(gameDir, "versions/" + mcVersion + "/" + mcVersion + ".jar");
        if (clientJar.exists() && !jars.contains(clientJar.getAbsolutePath())) {
            jars.add(clientJar.getAbsolutePath());
        }

        return String.join(File.pathSeparator, jars);
    }

    private void addLibrariesToMap(File gameDir, JsonObject versionObj, Map<String, String> libraryArtifacts) {
        if (versionObj == null) return;
        if (!versionObj.has("libraries")) return;

        JsonArray libraries = versionObj.getAsJsonArray("libraries");

        for (JsonElement elem : libraries) {
            JsonObject lib = elem.getAsJsonObject();
            if (!shouldUseLibrary(lib)) continue;

            File libFile = null;
            String artifactKey = null;

            if (lib.has("name")) {
                String name = lib.get("name").getAsString();
                String mavenPath = mavenToPath(name);
                if (!mavenPath.isEmpty()) {
                    libFile = new File(gameDir, "libraries/" + mavenPath);
                    artifactKey = getArtifactKeyFromName(name);
                }
            }

            if ((libFile == null || !libFile.exists()) && lib.has("downloads")) {
                JsonObject downloads = lib.getAsJsonObject("downloads");
                if (downloads != null && downloads.has("artifact")) {
                    JsonObject artifact = downloads.getAsJsonObject("artifact");
                    if (artifact.has("path")) {
                        String path = artifact.get("path").getAsString();
                        libFile = new File(gameDir, "libraries/" + path);
                        artifactKey = getArtifactKeyFromPath(path);
                    }
                }
            }

            if (libFile != null && libFile.exists()) {
                String absolutePath = libFile.getAbsolutePath();
                if (artifactKey != null) {
                    libraryArtifacts.put(artifactKey, absolutePath);
                } else {
                    libraryArtifacts.put(absolutePath, absolutePath);
                }
            }
        }
    }

    private String getArtifactKeyFromPath(String path) {
        String[] segments = path.split("/");
        if (segments.length < 3) return path;

        String fileName = segments[segments.length - 1];
        String version = segments[segments.length - 2];
        String artifactId = segments[segments.length - 3];

        String base = fileName.endsWith(".jar") ? fileName.substring(0, fileName.length() - 4) : fileName;
        String prefix = artifactId + "-" + version;

        String classifier = "";
        if (base.startsWith(prefix) && base.length() > prefix.length() && base.charAt(prefix.length()) == '-') {
            classifier = base.substring(prefix.length() + 1);
        }

        StringBuilder groupPath = new StringBuilder();
        for (int i = 0; i < segments.length - 3; i++) {
            if (groupPath.length() > 0) groupPath.append('/');
            groupPath.append(segments[i]);
        }

        return groupPath + "/" + artifactId + ":" + classifier;
    }

    private String getArtifactKeyFromName(String name) {
        String[] parts = name.split(":");
        if (parts.length >= 4) {
            return parts[0] + ":" + parts[1] + ":" + parts[3];
        }
        if (parts.length >= 2) {
            return parts[0] + ":" + parts[1] + ":";
        }
        return name;
    }

    private String readMainClass(JsonObject customJson, JsonObject vanillaJson) {
        if (customJson != null && customJson.has("mainClass")) {
            String customMain = customJson.get("mainClass").getAsString();
            if (customMain != null && !customMain.isBlank()) {
                return customMain;
            }
        }
        if (vanillaJson != null && vanillaJson.has("mainClass")) {
            return vanillaJson.get("mainClass").getAsString();
        }
        return "net.minecraft.client.main.Main";
    }

    private boolean collectAllArguments(JsonObject vanillaJson, JsonObject customJson,
                                        Map<String, Boolean> features, Map<String, String> substitutions,
                                        List<String> jvmArgsOut, List<String> gameArgsOut) {
        boolean modernFormat = false;

        if (vanillaJson != null && vanillaJson.has("arguments")) {
            modernFormat = true;
            JsonObject vanillaArgs = vanillaJson.getAsJsonObject("arguments");
            collectArguments(vanillaArgs.has("jvm") ? vanillaArgs.getAsJsonArray("jvm") : null, features, jvmArgsOut, substitutions);
            collectArguments(vanillaArgs.has("game") ? vanillaArgs.getAsJsonArray("game") : null, features, gameArgsOut, substitutions);
        }

        if (customJson != null) {
            if (customJson.has("arguments")) {
                modernFormat = true;
                JsonObject customArgs = customJson.getAsJsonObject("arguments");
                collectArguments(customArgs.has("jvm") ? customArgs.getAsJsonArray("jvm") : null, features, jvmArgsOut, substitutions);
                collectArguments(customArgs.has("game") ? customArgs.getAsJsonArray("game") : null, features, gameArgsOut, substitutions);
            } else if (customJson.has("minecraftArguments")) {
                String legacy = customJson.get("minecraftArguments").getAsString();
                for (String token : legacy.split("\\s+")) {
                    if (!token.isBlank()) gameArgsOut.add(substitute(token, substitutions));
                }
            }
        }

        if (!modernFormat && vanillaJson != null && vanillaJson.has("minecraftArguments")) {
            String legacy = vanillaJson.get("minecraftArguments").getAsString();
            for (String token : legacy.split("\\s+")) {
                if (!token.isBlank()) gameArgsOut.add(substitute(token, substitutions));
            }
        }

        return modernFormat;
    }

    private void collectArguments(JsonArray array, Map<String, Boolean> features, List<String> out, Map<String, String> substitutions) {
        if (array == null) return;
        for (JsonElement el : array) {
            if (el.isJsonPrimitive()) {
                out.add(substitute(el.getAsString(), substitutions));
            } else if (el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                if (!argumentRulesAllow(obj, features)) continue;
                if (obj.has("value")) {
                    JsonElement value = obj.get("value");
                    if (value.isJsonArray()) {
                        for (JsonElement v : value.getAsJsonArray()) {
                            if (v.isJsonPrimitive()) {
                                out.add(substitute(v.getAsString(), substitutions));
                            }
                        }
                    } else if (value.isJsonPrimitive()) {
                        out.add(substitute(value.getAsString(), substitutions));
                    }
                }
            }
        }
    }

    private boolean argumentRulesAllow(JsonObject entry, Map<String, Boolean> features) {
        if (!entry.has("rules")) return true;

        String osName = System.getProperty("os.name").toLowerCase();
        boolean allow = false;

        for (JsonElement ruleElem : entry.getAsJsonArray("rules")) {
            JsonObject rule = ruleElem.getAsJsonObject();
            String action = rule.get("action").getAsString();
            boolean matches = true;

            if (rule.has("os")) {
                JsonObject osObj = rule.getAsJsonObject("os");
                if (osObj.has("name")) {
                    String targetOs = osObj.get("name").getAsString();
                    boolean osMatch = (targetOs.equals("windows") && osName.contains("win")) ||
                            (targetOs.equals("osx") && osName.contains("mac")) ||
                            (targetOs.equals("linux") && osName.contains("linux"));
                    matches = osMatch;
                }
            }

            if (matches && rule.has("features")) {
                JsonObject featuresObj = rule.getAsJsonObject("features");
                for (String key : featuresObj.keySet()) {
                    boolean required = featuresObj.get(key).getAsBoolean();
                    boolean active = features.getOrDefault(key, false);
                    if (required != active) {
                        matches = false;
                        break;
                    }
                }
            }

            if (matches) {
                allow = action.equals("allow");
            }
        }
        return allow;
    }

    private Map<String, String> buildSubstitutions(String mcVersion, String versionId, String username, String uuid,
                                                   String assetIndex, File canonicalGameDir, File activeGameDir, File nativesDir,
                                                   String classpath, LauncherConfig config) {
        Map<String, String> m = new HashMap<>();
        m.put("auth_player_name", username);
        m.put("version_name", versionId);

        m.put("game_directory", activeGameDir.getAbsolutePath());
        m.put("assets_root", new File(canonicalGameDir, "assets").getAbsolutePath());

        File assetsVirtualLegacy = new File(canonicalGameDir, "assets/virtual/legacy");
        if (isLegacyVersion(mcVersion) && assetsVirtualLegacy.exists()) {
            m.put("game_assets", assetsVirtualLegacy.getAbsolutePath());
        } else {
            m.put("game_assets", new File(canonicalGameDir, "assets").getAbsolutePath());
        }

        m.put("assets_index_name", assetIndex);
        m.put("auth_uuid", uuid);
        m.put("auth_access_token", "0");
        m.put("auth_session", "0");
        m.put("clientid", "0");
        m.put("auth_xuid", "0");
        m.put("user_type", "legacy");
        m.put("user_properties", "{}");
        m.put("version_type", "release");
        m.put("resolution_width", config.getWindowWidth());
        m.put("resolution_height", config.getWindowHeight());
        m.put("natives_directory", nativesDir.getAbsolutePath());
        m.put("launcher_name", "EternalLauncher");
        m.put("launcher_version", "1.0");
        m.put("classpath", classpath);
        m.put("classpath_separator", File.pathSeparator);
        m.put("library_directory", new File(canonicalGameDir, "libraries").getAbsolutePath());

        File clientJar = new File(canonicalGameDir, "versions/" + mcVersion + "/" + mcVersion + ".jar");
        m.put("minecraft_jar", clientJar.getAbsolutePath());

        return m;
    }

    private boolean isLegacyVersion(String mcVersion) {
        try {
            if (mcVersion.startsWith("1.")) {
                String[] parts = mcVersion.split("\\.");
                if (parts.length >= 2) {
                    int minor = Integer.parseInt(parts[1]);
                    return minor < 7;
                }
            }
            return mcVersion.startsWith("b") || mcVersion.startsWith("a") || mcVersion.startsWith("inf");
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, Boolean> buildFeatures() {
        Map<String, Boolean> f = new HashMap<>();
        f.put("has_custom_resolution", true);
        f.put("is_demo_user", false);
        f.put("has_quick_plays_support", false);
        f.put("is_quick_play_singleplayer", false);
        f.put("is_quick_play_multiplayer", false);
        f.put("is_quick_play_realms", false);
        return f;
    }

    private String substitute(String token, Map<String, String> subs) {
        if (token == null) return "";
        String result = token;
        for (Map.Entry<String, String> e : subs.entrySet()) {
            String key = "${" + e.getKey() + "}";
            if (result.contains(key)) {
                result = result.replace(key, e.getValue());
            }
        }
        return result;
    }

    private String mavenToPath(String mavenCoord) {
        String[] parts = mavenCoord.split(":");
        if (parts.length < 3) return "";
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = (parts.length >= 4) ? "-" + parts[3] : "";
        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + ".jar";
    }

    private boolean shouldUseLibrary(JsonObject lib) {
        if (!lib.has("rules")) return true;

        String osName = System.getProperty("os.name").toLowerCase();
        boolean allow = false;

        for (JsonElement ruleElem : lib.getAsJsonArray("rules")) {
            JsonObject rule = ruleElem.getAsJsonObject();
            String action = rule.get("action").getAsString();

            if (rule.has("os")) {
                JsonObject osObj = rule.getAsJsonObject("os");
                if (osObj.has("name")) {
                    String targetOs = osObj.get("name").getAsString();
                    boolean matches = (targetOs.equals("windows") && osName.contains("win")) ||
                            (targetOs.equals("osx") && osName.contains("mac")) ||
                            (targetOs.equals("linux") && osName.contains("linux"));

                    if (matches) {
                        allow = action.equals("allow");
                    }
                }
            } else {
                allow = action.equals("allow");
            }
        }
        return allow;
    }

    private String getCustomVersionId(String targetVersion, String loaderType, String loaderVersion) {
        if (loaderType == null || loaderType.equalsIgnoreCase("VANILLA") || loaderType.isEmpty()) {
            return targetVersion;
        }
        String typeUnder = loaderType.toUpperCase();
        switch (typeUnder) {
            case "FABRIC":
                return targetVersion + "-fabric-" + (loaderVersion != null ? loaderVersion : "latest");
            case "NEOFORGE":
                return targetVersion + "-neoforge-" + (loaderVersion != null ? loaderVersion : "installed");
            case "FORGE":
                return targetVersion + "-forge-" + (loaderVersion != null ? loaderVersion : "installed");
            default:
                return targetVersion;
        }
    }

    public static class ParsedVersion {
        public String mcVersion;
        public String loaderType = "Vanilla";
        public String loaderVersion = null;

        public ParsedVersion(String item) {
            if (item != null && item.contains(" - ")) {
                String[] parts = item.split(" - ");
                this.mcVersion = parts[0].trim();
                if (parts.length > 1) {
                    String loaderTypePart = parts[1].trim();

                    int bracketOpen = loaderTypePart.indexOf(" [");
                    int bracketClose = loaderTypePart.lastIndexOf("]");

                    if (bracketOpen != -1 && bracketClose != -1 && bracketClose > bracketOpen) {
                        this.loaderType = loaderTypePart.substring(0, bracketOpen).trim();
                        this.loaderVersion = loaderTypePart.substring(bracketOpen + 2, bracketClose).trim();
                    } else {
                        this.loaderType = loaderTypePart;
                    }
                }
            } else if (item != null) {
                this.mcVersion = item.trim();
            } else {
                this.mcVersion = "";
            }
        }
    }
}