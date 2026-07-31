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
import java.util.List;
import java.util.UUID;

public class GameLauncher {

    public void launch(File gameDir, LauncherConfig config, String username, String assetIndex, Runnable onProcessEnd) {
        try {
            String version = config.getVersion();
            System.out.println("[GameLauncher] Подготовка к запуску версии " + version + "...");

            File canonicalGameDir = gameDir.getCanonicalFile();
            File nativesDir = new File(canonicalGameDir, "versions/" + version + "/natives");

            String uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8))
                    .toString().replace("-", "");

            String classpath = buildClasspath(canonicalGameDir, version);

            List<String> command = new ArrayList<>();

            // Java & Память
            command.add(config.getJavaExecutable());
            command.add("-Xms" + config.getMinMemory());
            command.add("-Xmx" + config.getMaxMemory());

            if (!config.getJvmArgs().isBlank()) {
                for (String arg : config.getJvmArgs().split(" ")) {
                    if (!arg.isBlank()) command.add(arg);
                }
            }

            command.add("-Djava.library.path=" + nativesDir.getAbsolutePath());
            command.add("-cp");
            command.add(classpath);

            // Главный класс
            command.add("net.minecraft.client.main.Main");

            // Аргументы игры
            command.add("--username");
            command.add(username);

            command.add("--version");
            command.add(version);

            command.add("--gameDir");
            command.add(canonicalGameDir.getAbsolutePath());

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

            if (config.isFullscreen()) {
                command.add("--fullscreen");
            }

            System.out.println("[GameLauncher] Запуск процесса...");
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(canonicalGameDir);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Чтение логов в ОТДЕЛЬНОМ фоновом потоке, чтобы UI не зависал
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[GAME] " + line);
                    }
                    int exitCode = process.waitFor();
                    System.out.println("[GameLauncher] Игра закрыта с кодом: " + exitCode);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    if (onProcessEnd != null) {
                        onProcessEnd.run();
                    }
                }
            }).start();

        } catch (Exception e) {
            e.printStackTrace();
            if (onProcessEnd != null) {
                onProcessEnd.run();
            }
        }
    }

    private String buildClasspath(File gameDir, String version) {
        List<String> jars = new ArrayList<>();
        File versionJsonFile = new File(gameDir, "versions/" + version + "/" + version + ".json");

        if (versionJsonFile.exists()) {
            try {
                String jsonContent = Files.readString(versionJsonFile.toPath());
                JsonObject versionObj = JsonParser.parseString(jsonContent).getAsJsonObject();
                JsonArray libraries = versionObj.getAsJsonArray("libraries");

                for (JsonElement elem : libraries) {
                    JsonObject lib = elem.getAsJsonObject();
                    if (!shouldUseLibrary(lib)) continue;

                    if (lib.has("downloads")) {
                        JsonObject downloads = lib.getAsJsonObject("downloads");
                        if (downloads.has("artifact")) {
                            String path = downloads.getAsJsonObject("artifact").get("path").getAsString();
                            File libFile = new File(gameDir, "libraries/" + path);
                            if (libFile.exists()) {
                                jars.add(libFile.getAbsolutePath());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[GameLauncher] Ошибка чтения JSON версии при сборке Classpath: " + e.getMessage());
            }
        }

        File clientJar = new File(gameDir, "versions/" + version + "/" + version + ".jar");
        jars.add(clientJar.getAbsolutePath());

        return String.join(File.pathSeparator, jars);
    }

    private boolean shouldUseLibrary(JsonObject lib) {
        if (!lib.has("rules")) return true;

        String osName = System.getProperty("os.name").toLowerCase();
        boolean allow = false;

        for (JsonElement ruleElem : lib.getAsJsonArray("rules")) {
            JsonObject rule = ruleElem.getAsJsonObject();
            String action = rule.get("action").getAsString();

            if (rule.has("os")) {
                String targetOs = rule.getAsJsonObject("os").get("name").getAsString();
                boolean matches = (targetOs.equals("windows") && osName.contains("win")) ||
                        (targetOs.equals("osx") && osName.contains("mac")) ||
                        (targetOs.equals("linux") && osName.contains("linux"));

                if (matches) {
                    allow = action.equals("allow");
                }
            } else {
                allow = action.equals("allow");
            }
        }
        return allow;
    }
}