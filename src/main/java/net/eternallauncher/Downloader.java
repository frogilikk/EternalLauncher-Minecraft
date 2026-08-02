package net.eternallauncher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Downloader {

    private static final String MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String FABRIC_META_URL = "https://meta.fabricmc.net/v2/versions/loader/";
    private static final String FABRIC_GAME_VERSIONS_URL = "https://meta.fabricmc.net/v2/versions/game";
    private static final String FABRIC_LOADER_VERSIONS_URL = "https://meta.fabricmc.net/v2/versions/loader";
    private static final String FORGE_METADATA_URL = "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml";
    private static final String FORGE_PROMOTIONS_URL = "https://maven.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
    private static final String NEOFORGE_VERSIONS_URL = "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    public List<String> getReleaseVersions() {
        List<String> releaseVersions = new ArrayList<>();
        try {
            String manifestJson = sendGetRequest(MANIFEST_URL);
            JsonObject manifest = JsonParser.parseString(manifestJson).getAsJsonObject();
            JsonArray versions = manifest.getAsJsonArray("versions");

            for (JsonElement element : versions) {
                JsonObject versionObj = element.getAsJsonObject();
                String type = versionObj.get("type").getAsString();
                String id = versionObj.get("id").getAsString();

                if ("release".equals(type)) {
                    releaseVersions.add(id);
                }
            }
        } catch (Exception e) {
            System.err.println("[Downloader] Не удалось получить список версий из сети: " + e.getMessage());
            return List.of("1.21.1", "1.20.4", "1.20.1", "1.16.5");
        }
        return releaseVersions;
    }

    public boolean isVersionDownloaded(String targetVersion, File gameDir) {
        File versionFolder = new File(gameDir, "versions/" + targetVersion);
        File versionJson = new File(versionFolder, targetVersion + ".json");
        File clientJar = new File(versionFolder, targetVersion + ".jar");
        return versionFolder.exists() && versionJson.exists() && clientJar.exists();
    }

    public boolean isModdedVersionDownloaded(String targetVersion, String loaderType, String loaderVersion, File gameDir) {
        if (!isVersionDownloaded(targetVersion, gameDir)) {
            return false;
        }

        if (loaderType == null || loaderType.equalsIgnoreCase("VANILLA") || loaderType.isEmpty()) {
            return true;
        }

        String customVersionId = getCustomVersionId(targetVersion, loaderType, loaderVersion);
        File customVersionDir = new File(gameDir, "versions/" + customVersionId);
        File customJson = new File(customVersionDir, customVersionId + ".json");

        if (!customVersionDir.exists() || !customJson.exists()) {
            return false;
        }

        // Профиль есть - но это не значит, что установка рабочая (например, если раньше был
        // прерван/неполный запуск инсталлятора). Проверяем, что все библиотеки профиля реально
        // лежат на диске, иначе считаем установку неполной и заставляем переустановить.
        try {
            JsonObject profileObj = JsonParser.parseString(Files.readString(customJson.toPath())).getAsJsonObject();
            return allLibrariesPresent(profileObj, gameDir);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean allLibrariesPresent(JsonObject versionObj, File gameDir) {
        if (!versionObj.has("libraries")) return true;
        JsonArray libraries = versionObj.getAsJsonArray("libraries");

        for (JsonElement elem : libraries) {
            JsonObject lib = elem.getAsJsonObject();
            if (!shouldDownload(lib)) continue;

            if (lib.has("name")) {
                String path = mavenToPath(lib.get("name").getAsString());
                if (!path.isEmpty() && !new File(gameDir, "libraries/" + path).exists()) {
                    return false;
                }
            } else if (lib.has("downloads")) {
                JsonObject downloads = lib.getAsJsonObject("downloads");
                if (downloads != null && downloads.has("artifact")) {
                    JsonObject artifact = downloads.getAsJsonObject("artifact");
                    if (artifact.has("path") && !new File(gameDir, "libraries/" + artifact.get("path").getAsString()).exists()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public String getCustomVersionId(String targetVersion, String loaderType, String loaderVersion) {
        if (loaderType == null || loaderType.equalsIgnoreCase("VANILLA") || loaderType.isEmpty()) {
            return targetVersion;
        }
        String typeUpper = loaderType.toUpperCase();
        switch (typeUpper) {
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

    public File getModsDirForVersion(File gameDir, String targetVersion, String loaderType, String loaderVersion) {
        String versionId = getCustomVersionId(targetVersion, loaderType, loaderVersion);
        File modsDir = new File(gameDir, "versions/" + versionId + "/mods");
        modsDir.mkdirs();
        return modsDir;
    }

    public File getConfigDirForVersion(File gameDir, String targetVersion, String loaderType, String loaderVersion) {
        String versionId = getCustomVersionId(targetVersion, loaderType, loaderVersion);
        File configDir = new File(gameDir, "versions/" + versionId + "/config");
        configDir.mkdirs();
        return configDir;
    }

    public String downloadVersion(String targetVersion, String loaderType, String loaderVersion, File gameDir) throws Exception {
        if (isModdedVersionDownloaded(targetVersion, loaderType, loaderVersion, gameDir)) {
            System.out.println("[Downloader] Версия Minecraft " + targetVersion + " [" + (loaderType != null ? loaderType : "Vanilla") + "] уже установлена. Пропускаем загрузку.");
            return getCachedAssetId(targetVersion, gameDir);
        }

        System.out.println("[Downloader] Запуск установки: Minecraft " + targetVersion + " [Модлоадер: " + loaderType + "]");

        String assetId = downloadVanillaVersion(targetVersion, gameDir);

        if (loaderType == null || loaderType.equalsIgnoreCase("VANILLA") || loaderType.isEmpty()) {
            return assetId;
        }

        String typeUpper = loaderType.toUpperCase();
        switch (typeUpper) {
            case "FABRIC":
                downloadFabric(targetVersion, loaderVersion, gameDir);
                break;
            case "NEOFORGE":
                downloadNeoForge(targetVersion, loaderVersion, gameDir);
                break;
            case "FORGE":
                downloadForge(targetVersion, loaderVersion, gameDir);
                break;
            default:
                System.err.println("[Downloader] Неизвестный тип модлоадера: " + loaderType);
        }

        getModsDirForVersion(gameDir, targetVersion, loaderType, loaderVersion);
        getConfigDirForVersion(gameDir, targetVersion, loaderType, loaderVersion);

        return assetId;
    }

    private String getCachedAssetId(String targetVersion, File gameDir) {
        try {
            File versionJson = new File(gameDir, "versions/" + targetVersion + "/" + targetVersion + ".json");
            if (versionJson.exists()) {
                String content = Files.readString(versionJson.toPath());
                JsonObject obj = JsonParser.parseString(content).getAsJsonObject();
                if (obj.has("assetIndex")) {
                    return obj.getAsJsonObject("assetIndex").get("id").getAsString();
                }
            }
        } catch (Exception ignored) {}
        return "legacy";
    }

    private String downloadVanillaVersion(String targetVersion, File gameDir) throws Exception {
        File versionFolder = new File(gameDir, "versions/" + targetVersion);
        File clientJar = new File(versionFolder, targetVersion + ".jar");
        File versionJson = new File(versionFolder, targetVersion + ".json");

        JsonObject versionObj;

        if (versionFolder.exists() && versionJson.exists()) {
            System.out.println("[Downloader] Манифест ванили " + targetVersion + " уже на диске.");
            String versionDataJson = Files.readString(versionJson.toPath());
            versionObj = JsonParser.parseString(versionDataJson).getAsJsonObject();
        } else {
            System.out.println("[Downloader] Получение главного манифеста версий...");
            String manifestJson = sendGetRequest(MANIFEST_URL);

            JsonObject manifest = JsonParser.parseString(manifestJson).getAsJsonObject();
            JsonArray versions = manifest.getAsJsonArray("versions");

            String versionJsonUrl = null;
            for (JsonElement elem : versions) {
                JsonObject ver = elem.getAsJsonObject();
                if (ver.get("id").getAsString().equals(targetVersion)) {
                    versionJsonUrl = ver.get("url").getAsString();
                    break;
                }
            }

            if (versionJsonUrl == null) {
                throw new RuntimeException("Версия " + targetVersion + " не найдена в манифесте Mojang!");
            }

            System.out.println("[Downloader] Скачивание манифеста версии " + targetVersion + "...");
            String versionDataJson = sendGetRequest(versionJsonUrl);
            versionObj = JsonParser.parseString(versionDataJson).getAsJsonObject();

            versionFolder.mkdirs();
            Files.writeString(versionJson.toPath(), versionDataJson);
        }

        if (!clientJar.exists()) {
            JsonObject clientDownload = versionObj.getAsJsonObject("downloads").getAsJsonObject("client");
            String clientUrl = clientDownload.get("url").getAsString();
            System.out.println("[Downloader] Скачивание " + targetVersion + ".jar...");
            downloadFile(clientUrl, clientJar.toPath());
        }

        downloadLibrariesFromJson(versionObj, gameDir, versionFolder);

        return downloadAssets(versionObj, gameDir);
    }

    private void downloadLibrariesFromJson(JsonObject versionObj, File gameDir, File versionFolder) throws Exception {
        System.out.println("[Downloader] Проверка и скачивание библиотек...");
        JsonArray libraries = versionObj.getAsJsonArray("libraries");
        if (libraries == null) return;

        File nativesDir = new File(versionFolder, "natives");
        nativesDir.mkdirs();

        int downloadedCount = 0;

        for (JsonElement elem : libraries) {
            JsonObject lib = elem.getAsJsonObject();
            if (!shouldDownload(lib)) continue;

            JsonObject downloads = lib.getAsJsonObject("downloads");
            if (downloads != null) {
                if (downloads.has("artifact")) {
                    JsonObject artifact = downloads.getAsJsonObject("artifact");
                    if (artifact.has("path") && artifact.has("url")) {
                        String path = artifact.get("path").getAsString();
                        String url = artifact.get("url").getAsString();
                        if (url != null && !url.trim().isEmpty()) {
                            File targetFile = new File(gameDir, "libraries/" + path);
                            if (!targetFile.exists()) {
                                targetFile.getParentFile().mkdirs();
                                downloadFile(url, targetFile.toPath());
                                downloadedCount++;
                            }
                            if (isNativeLibrary(lib, path)) {
                                extractNatives(targetFile, nativesDir);
                            }
                        }
                    }
                }

                if (downloads.has("classifiers")) {
                    JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                    String nativeKey = classifiers.has("natives-linux") ? "natives-linux" : (classifiers.has("linux") ? "linux" : null);

                    if (nativeKey != null) {
                        JsonObject nativeObj = classifiers.getAsJsonObject(nativeKey);
                        if (nativeObj.has("path") && nativeObj.has("url")) {
                            String path = nativeObj.get("path").getAsString();
                            String url = nativeObj.get("url").getAsString();
                            if (url != null && !url.trim().isEmpty()) {
                                File nativeJar = new File(gameDir, "libraries/" + path);
                                if (!nativeJar.exists()) {
                                    nativeJar.getParentFile().mkdirs();
                                    downloadFile(url, nativeJar.toPath());
                                    downloadedCount++;
                                }
                                extractNatives(nativeJar, nativesDir);
                            }
                        }
                    }
                }
            }

            if (lib.has("name") && (downloads == null || !downloads.has("artifact"))) {
                String name = lib.get("name").getAsString();
                String libPath = mavenToPath(name);
                if (!libPath.isEmpty()) {
                    File targetLibFile = new File(gameDir, "libraries/" + libPath);
                    if (!targetLibFile.exists()) {
                        targetLibFile.getParentFile().mkdirs();
                        String url = "https://libraries.minecraft.net/" + libPath;
                        if (lib.has("url")) {
                            String customBase = lib.get("url").getAsString();
                            if (!customBase.isEmpty()) {
                                url = customBase.endsWith("/") ? customBase + libPath : customBase + "/" + libPath;
                            }
                        }
                        try {
                            if (url != null && !url.trim().isEmpty()) {
                                downloadFile(url, targetLibFile.toPath());
                                downloadedCount++;
                            }
                        } catch (Exception e) {
                            try {
                                downloadFile("https://repo1.maven.org/maven2/" + libPath, targetLibFile.toPath());
                                downloadedCount++;
                            } catch (Exception ignored) {}
                        }
                    }
                    if (isNativeLibrary(lib, libPath)) {
                        extractNatives(targetLibFile, nativesDir);
                    }
                }
            }
        }

        ensureNativesExtractedManually(gameDir, nativesDir);
        System.out.println("[Downloader] Библиотеки успешно проверены. Скачано новых: " + downloadedCount);
    }

    private void ensureNativesExtractedManually(File gameDir, File nativesDir) {
        File libDir = new File(gameDir, "libraries");
        if (!libDir.exists()) return;
        walkAndExtractNatives(libDir, nativesDir);
    }

    private void walkAndExtractNatives(File dir, File nativesDir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                walkAndExtractNatives(f, nativesDir);
            } else if (f.getName().contains("natives-linux") && f.getName().endsWith(".jar")) {
                extractNatives(f, nativesDir);
            }
        }
    }

    private void extractNatives(File jarFile, File destDir) {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jarFile.toPath()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();

                if (entry.isDirectory() || name.startsWith("META-INF") || name.endsWith(".sha1") || name.endsWith(".git")) {
                    continue;
                }

                if (name.endsWith(".dll") || name.endsWith(".so") || name.endsWith(".dylib")) {
                    File outFile = new File(destDir, new File(name).getName());
                    if (!outFile.getCanonicalPath().startsWith(destDir.getCanonicalPath())) {
                        continue;
                    }
                    Files.copy(zis, outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (Exception e) {
            System.err.println("[Downloader] Ошибка распаковки нативных файлов из " + jarFile.getName() + ": " + e.getMessage());
        }
    }

    private boolean isNativeLibrary(JsonObject lib, String path) {
        return path.contains("native") || lib.has("natives") || path.contains("lwjgl") || path.contains("jemalloc") || path.contains("tinyfd");
    }

    private void downloadFabric(String mcVersion, String loaderVersion, File gameDir) throws Exception {
        System.out.println("[Downloader] Установка Fabric для Minecraft " + mcVersion + "...");

        if (loaderVersion == null || loaderVersion.isEmpty()) {
            String metaUrl = FABRIC_META_URL + mcVersion;
            String response = sendGetRequest(metaUrl);
            JsonArray array = JsonParser.parseString(response).getAsJsonArray();
            if (array.size() == 0) throw new RuntimeException("Не удалось найти Fabric loader для версии " + mcVersion);
            loaderVersion = array.get(0).getAsJsonObject().getAsJsonObject("loader").get("version").getAsString();
        }

        String profileUrl = "https://meta.fabricmc.net/v2/versions/loader/" + mcVersion + "/" + loaderVersion + "/profile/json";
        String profileJsonText = sendGetRequest(profileUrl);

        String customVersionId = mcVersion + "-fabric-" + loaderVersion;
        File customVersionDir = new File(gameDir, "versions/" + customVersionId);
        customVersionDir.mkdirs();

        Files.writeString(new File(customVersionDir, customVersionId + ".json").toPath(), profileJsonText);

        JsonObject profileObj = JsonParser.parseString(profileJsonText).getAsJsonObject();
        downloadLibrariesFromJson(profileObj, gameDir, customVersionDir);
        System.out.println("[Downloader] Fabric успешно установлен! Профиль: " + customVersionId);
    }

    private void downloadNeoForge(String mcVersion, String loaderVersion, File gameDir) throws Exception {
        System.out.println("[Downloader] Установка NeoForge для " + mcVersion + "...");

        if (loaderVersion == null || loaderVersion.isEmpty()) {
            List<String> neoVersions = fetchNeoForgeVersions();
            loaderVersion = findBestNeoForgeMatch(neoVersions, mcVersion);
            if (loaderVersion == null) {
                throw new RuntimeException("Не удалось найти подходящую версию NeoForge для " + mcVersion);
            }
        }

        String targetDirName = getCustomVersionId(mcVersion, "NEOFORGE", loaderVersion);
        String installerCoord = "net.neoforged:neoforge:" + loaderVersion + ":installer";
        ensureMavenLibraryDownloaded(installerCoord, "https://maven.neoforged.net/releases/", gameDir);

        File installerFile = new File(gameDir, "libraries/" + mavenToPath(installerCoord));
        if (!installerFile.exists()) {
            throw new RuntimeException("Не удалось скачать инсталлятор NeoForge: " + installerCoord);
        }

        // Та же причина, что и у Forge: без запуска настоящего инсталлятора не будет
        // пропатченного/переименованного (SRG) клиентского jar'а, и FML выдаст
        // "Your NeoForge installation is corrupted".
        runOfficialInstaller(installerFile, gameDir, targetDirName);

        File versionJsonFile = new File(gameDir, "versions/" + targetDirName + "/" + targetDirName + ".json");
        if (versionJsonFile.exists()) {
            JsonObject profileObj = JsonParser.parseString(Files.readString(versionJsonFile.toPath())).getAsJsonObject();
            downloadLibrariesFromJson(profileObj, gameDir, new File(gameDir, "versions/" + targetDirName));
        }

        System.out.println("[Downloader] Профиль NeoForge успешно создан: " + targetDirName);
    }

    /**
     * Запускает официальный installer.jar (Forge/NeoForge используют один и тот же CLI-флаг)
     * в headless-режиме: "java -jar installer.jar --installClient <gameDir>". Это ровно тот
     * же самый механизм, который использует официальный установщик с GUI - он сам скачивает
     * недостающее, ремаппит ванильный jar в SRG-имена, применяет ASM-патчи и кладёт готовый
     * профиль версии в <gameDir>/versions/. Переизобретать этот процессор-пайплайн вручную
     * ненадёжно (формат install_profile.json меняется от версии к версии), поэтому мы просто
     * доверяем его официальной реализации.
     *
     * Инсталлятор сам решает, как назвать созданную версию (не всегда совпадает с нашей
     * схемой именования versionId) - определяем реально созданную папку по diff'у versions/
     * до/после запуска и, если имя отличается, переименовываем в targetDirName, чтобы
     * остальной код (GameLauncher, isModdedVersionDownloaded) продолжил работать как обычно.
     */
    private void ensureLauncherProfileExists(File gameDir) throws IOException {
        File profileFile = new File(gameDir, "launcher_profiles.json");
        if (profileFile.exists()) return;

        gameDir.mkdirs();
        String minimalProfile = "{\"profiles\":{},\"settings\":{\"enableSnapshots\":false,\"enableAdvanced\":false,"
                + "\"keepLauncherOpen\":false,\"soundOn\":false,\"showGameLog\":false,\"showMenu\":false,\"profileSorting\":\"ByLastPlayed\","
                + "\"enableHistorical\":false,\"enableReleases\":false,\"crashAssistance\":false},\"version\":3}";
        Files.writeString(profileFile.toPath(), minimalProfile);
    }

    private void runOfficialInstaller(File installerJar, File gameDir, String targetDirName) throws Exception {
        // Официальный инсталлятор Forge/NeoForge проверяет, что целевая папка "похожа" на
        // настоящую папку Mojang-лаунчера - для этого ищет launcher_profiles.json (его создаёт
        // сам Mojang-лаунчер при первом запуске) и отказывается работать без него ("There is no
        // minecraft launcher profile ... you need to run the launcher first!"). Наша gameDir -
        // кастомная папка, этого файла там никогда не будет, поэтому создаём минимальный сами.
        ensureLauncherProfileExists(gameDir);

        File versionsDir = new File(gameDir, "versions");
        versionsDir.mkdirs();

        Set<String> before = new HashSet<>();
        File[] existingDirs = versionsDir.listFiles(File::isDirectory);
        if (existingDirs != null) {
            for (File d : existingDirs) before.add(d.getName());
        }

        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";

        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-jar");
        command.add(installerJar.getAbsolutePath());
        command.add("--installClient");
        command.add(gameDir.getAbsolutePath());

        System.out.println("[Downloader] Запуск официального инсталлятора: " + installerJar.getName());
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(gameDir);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("[Installer] " + line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Официальный инсталлятор завершился с кодом " + exitCode
                    + " - смотрите вывод [Installer] выше для точной причины.");
        }

        File[] afterDirs = versionsDir.listFiles(File::isDirectory);
        String createdVersionId = null;
        if (afterDirs != null) {
            for (File d : afterDirs) {
                if (!before.contains(d.getName()) && new File(d, d.getName() + ".json").exists()) {
                    createdVersionId = d.getName();
                    break;
                }
            }
        }

        if (createdVersionId == null) {
            throw new RuntimeException("Инсталлятор завершился успешно, но созданный профиль версии не найден в versions/");
        }

        if (!createdVersionId.equals(targetDirName)) {
            renameVersionFolder(gameDir, createdVersionId, targetDirName);
        }
    }

    private void renameVersionFolder(File gameDir, String fromId, String toId) throws Exception {
        File fromDir = new File(gameDir, "versions/" + fromId);
        File toDir = new File(gameDir, "versions/" + toId);
        File fromJson = new File(fromDir, fromId + ".json");

        String content = Files.readString(fromJson.toPath());
        content = content.replaceFirst("\"id\"\\s*:\\s*\"[^\"]*\"", "\"id\": \"" + toId + "\"");

        try {
            Files.move(fromDir.toPath(), toDir.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            copyDirectoryRecursively(fromDir, toDir);
            deleteRecursively(fromDir);
        }

        File oldJsonAtNewLoc = new File(toDir, fromId + ".json");
        File newJson = new File(toDir, toId + ".json");
        Files.writeString(newJson.toPath(), content);
        if (oldJsonAtNewLoc.exists() && !oldJsonAtNewLoc.getName().equals(newJson.getName())) {
            oldJsonAtNewLoc.delete();
        }

        System.out.println("[Downloader] Профиль версии переименован: " + fromId + " -> " + toId);
    }

    private void copyDirectoryRecursively(File source, File target) throws IOException {
        if (source.isDirectory()) {
            if (!target.exists()) target.mkdirs();
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyDirectoryRecursively(child, new File(target, child.getName()));
                }
            }
        } else {
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        file.delete();
    }

    private void downloadForge(String mcVersion, String loaderVersion, File gameDir) throws Exception {
        System.out.println("[Downloader] Установка Forge для " + mcVersion + "...");

        if (loaderVersion == null || loaderVersion.isEmpty()) {
            throw new RuntimeException("Версия Forge не указана для версии Minecraft: " + mcVersion);
        }

        String targetDirName = getCustomVersionId(mcVersion, "FORGE", loaderVersion);
        String fullForgeVersion = mcVersion + "-" + loaderVersion;
        String installerCoord = "net.minecraftforge:forge:" + fullForgeVersion + ":installer";
        ensureMavenLibraryDownloaded(installerCoord, "https://maven.minecraftforge.net/", gameDir);

        File installerFile = new File(gameDir, "libraries/" + mavenToPath(installerCoord));
        if (!installerFile.exists()) {
            throw new RuntimeException("Не удалось скачать инсталлятор Forge: " + installerCoord);
        }

        // Современный Forge (модульный FML/ModLauncher) требует ре-маппинг ванильного jar'а в
        // SRG-имена и применение ASM-патчей - это делает ТОЛЬКО сам инсталлятор через свой
        // install_profile.json процессор-пайплайн (SpecialSource/BinaryPatcher и т.д.). Просто
        // скопировать файлы из jar'а (как раньше) недостаточно - без пропатченного jar'а FML не
        // находит net.minecraft.client.Minecraft.class и падает. Поэтому запускаем настоящий
        // инсталлятор в headless-режиме и даём ему сделать всю эту работу самому.
        runOfficialInstaller(installerFile, gameDir, targetDirName);

        File versionJsonFile = new File(gameDir, "versions/" + targetDirName + "/" + targetDirName + ".json");
        if (versionJsonFile.exists()) {
            JsonObject profileObj = JsonParser.parseString(Files.readString(versionJsonFile.toPath())).getAsJsonObject();
            downloadLibrariesFromJson(profileObj, gameDir, new File(gameDir, "versions/" + targetDirName));
        }

        System.out.println("[Downloader] Профиль Forge успешно создан: " + targetDirName);
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

    private void ensureMavenLibraryDownloaded(String mavenCoord, String baseUrl, File gameDir) {
        try {
            String libPath = mavenToPath(mavenCoord);
            if (libPath.isEmpty()) return;
            File targetLib = new File(gameDir, "libraries/" + libPath);
            if (!targetLib.exists()) {
                targetLib.getParentFile().mkdirs();
                String url = baseUrl.endsWith("/") ? baseUrl + libPath : baseUrl + "/" + libPath;
                downloadFile(url, targetLib.toPath());
                System.out.println("[Downloader] Скачана библиотека лоадера: " + mavenCoord);
            }
        } catch (Exception e) {
            System.err.println("[Downloader] Не удалось скачать библиотеку " + mavenCoord + ": " + e.getMessage());
        }
    }

    private String downloadAssets(JsonObject versionObj, File gameDir) throws Exception {
        if (!versionObj.has("assetIndex")) return "legacy";

        JsonObject assetIndex = versionObj.getAsJsonObject("assetIndex");
        String assetId = assetIndex.get("id").getAsString();
        String assetIndexUrl = assetIndex.get("url").getAsString();

        File indexesDir = new File(gameDir, "assets/indexes");
        indexesDir.mkdirs();
        File indexFile = new File(indexesDir, assetId + ".json");

        if (!indexFile.exists()) {
            System.out.println("[Downloader] Скачивание индекса ассетов (" + assetId + ".json)...");
            String assetsJsonText = sendGetRequest(assetIndexUrl);
            Files.writeString(indexFile.toPath(), assetsJsonText);
        }

        String assetsJsonText = Files.readString(indexFile.toPath());
        JsonObject assetsObj = JsonParser.parseString(assetsJsonText).getAsJsonObject();
        JsonObject objects = assetsObj.getAsJsonObject("objects");

        File objectsDir = new File(gameDir, "assets/objects");
        int downloadedAssets = 0;

        System.out.println("[Downloader] Проверка ресурсов (всего элементов: " + objects.size() + ")...");

        for (String assetPath : objects.keySet()) {
            JsonObject assetData = objects.getAsJsonObject(assetPath);
            String hash = assetData.get("hash").getAsString();
            String subFolder = hash.substring(0, 2);

            File targetAssetFile = new File(objectsDir, subFolder + "/" + hash);

            if (!targetAssetFile.exists()) {
                targetAssetFile.getParentFile().mkdirs();
                String downloadUrl = "https://resources.download.minecraft.net/" + subFolder + "/" + hash;

                try {
                    downloadFile(downloadUrl, targetAssetFile.toPath());
                    downloadedAssets++;
                } catch (Exception e) {
                    System.err.println("Не удалось скачать ассет: " + assetPath);
                }
            }
        }

        System.out.println("[Downloader] Загрузка ассетов завершена! Скачано новых: " + downloadedAssets);
        return assetId;
    }

    private boolean shouldDownload(JsonObject lib) {
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

    public List<String> getAllInstallableVersions() {
        List<String> result = new ArrayList<>();
        try {
            List<String> releaseVersions = getReleaseVersions();
            List<String> neoForgeVersions = fetchNeoForgeVersions();
            List<String> forgeVersions = fetchForgeVersions();
            Map<String, String> forgePromotions = fetchForgePromotions();

            Set<String> fabricSupportedVersions = fetchFabricSupportedGameVersions();
            String fabricLatestLoader = fetchFabricLatestLoaderVersion();

            for (String mcVer : releaseVersions) {
                result.add(mcVer);

                if (isSupportedByLoaders(mcVer)) {
                    if (fabricLatestLoader != null && fabricSupportedVersions.contains(mcVer)) {
                        result.add(mcVer + " - Fabric [" + fabricLatestLoader + "]");
                    }

                    String matchingNeo = findBestNeoForgeMatch(neoForgeVersions, mcVer);
                    if (matchingNeo != null) {
                        result.add(mcVer + " - NeoForge [" + matchingNeo + "]");
                    }

                    String matchingForge = findBestForgeMatch(forgePromotions, forgeVersions, mcVer);
                    if (matchingForge != null) {
                        result.add(mcVer + " - Forge [" + matchingForge + "]");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Downloader] Ошибка формирования списка версий: " + e.getMessage());
            return List.of("1.21.1", "1.21.1 - Fabric [0.16.5]", "1.16.5");
        }
        return result;
    }

    private boolean isSupportedByLoaders(String version) {
        try {
            String[] parts = version.split("\\.");
            if (parts.length < 2) return false;

            int major = Integer.parseInt(parts[0].replaceAll("[^0-9]", ""));
            if (major > 1) return true;

            if (major == 1) {
                int minor = Integer.parseInt(parts[1].replaceAll("[^0-9]", ""));
                return minor >= 14;
            }

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private String findBestNeoForgeMatch(List<String> allNeoVersions, String mcVersion) {
        String best = pickHighestMatching(allNeoVersions, mcVersion + "-");
        if (best == null) {
            best = pickHighestMatching(allNeoVersions, mcVersion + ".");
        }
        if (best != null) {
            return best;
        }

        String prefix = deriveNeoForgePrefix(mcVersion);
        if (prefix == null) return null;

        return pickHighestMatching(allNeoVersions, prefix);
    }

    private String deriveNeoForgePrefix(String mcVersion) {
        if (!mcVersion.startsWith("1.")) return null;
        String rest = mcVersion.substring(2);
        String[] parts = rest.split("\\.");
        if (parts.length == 0 || parts[0].isEmpty()) return null;
        String minor = parts[0];
        String patch = parts.length >= 2 && !parts[1].isEmpty() ? parts[1] : "0";
        return minor + "." + patch + ".";
    }

    private String findBestForgeMatch(Map<String, String> forgePromotions, List<String> allForgeVersions, String mcVersion) {
        String promoted = forgePromotions.get(mcVersion);
        if (promoted != null) {
            return promoted;
        }

        String prefix = mcVersion + "-";
        String best = null;
        for (String v : allForgeVersions) {
            if (v.startsWith(prefix)) {
                String loaderPart = v.substring(prefix.length());
                if (best == null || compareVersionStrings(loaderPart, best) > 0) {
                    best = loaderPart;
                }
            }
        }
        return best;
    }

    private String pickHighestMatching(List<String> versions, String prefix) {
        String best = null;
        for (String v : versions) {
            if (v.startsWith(prefix)) {
                if (best == null || compareVersionStrings(v, best) > 0) {
                    best = v;
                }
            }
        }
        return best;
    }

    private int compareVersionStrings(String a, String b) {
        String[] partsA = a.split("[.\\-]");
        String[] partsB = b.split("[.\\-]");
        int len = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < len; i++) {
            long na = i < partsA.length ? extractLeadingNumber(partsA[i]) : 0L;
            long nb = i < partsB.length ? extractLeadingNumber(partsB[i]) : 0L;
            if (na != nb) return Long.compare(na, nb);
        }
        return 0;
    }

    private long extractLeadingNumber(String segment) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (Character.isDigit(c)) {
                sb.append(c);
            } else {
                break;
            }
        }
        if (sb.length() == 0) return 0L;
        try {
            return Long.parseLong(sb.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private Set<String> fetchFabricSupportedGameVersions() {
        Set<String> set = new HashSet<>();
        try {
            String response = sendGetRequest(FABRIC_GAME_VERSIONS_URL);
            JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("version")) {
                    set.add(obj.get("version").getAsString());
                }
            }
        } catch (Exception e) {
            System.err.println("[Downloader] Не удалось получить список версий, поддерживаемых Fabric: " + e.getMessage());
        }
        return set;
    }

    private String fetchFabricLatestLoaderVersion() {
        try {
            String response = sendGetRequest(FABRIC_LOADER_VERSIONS_URL);
            JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("stable") && obj.get("stable").getAsBoolean() && obj.has("version")) {
                    return obj.get("version").getAsString();
                }
            }
            if (arr.size() > 0) {
                JsonObject first = arr.get(0).getAsJsonObject();
                if (first.has("version")) {
                    return first.get("version").getAsString();
                }
            }
        } catch (Exception e) {
            System.err.println("[Downloader] Не удалось получить список версий Fabric Loader: " + e.getMessage());
        }
        return null;
    }

    private List<String> fetchNeoForgeVersions() {
        List<String> list = new ArrayList<>();
        try {
            String response = sendGetRequest(NEOFORGE_VERSIONS_URL);
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            JsonArray arr = json.getAsJsonArray("versions");
            for (JsonElement el : arr) {
                list.add(el.getAsString());
            }
        } catch (Exception e) {
            System.err.println("[Downloader] Не удалось получить список версий NeoForge: " + e.getMessage());
        }
        return list;
    }

    private List<String> fetchForgeVersions() {
        List<String> list = new ArrayList<>();
        try {
            String xml = sendGetRequest(FORGE_METADATA_URL);
            int index = 0;
            while ((index = xml.indexOf("<version>", index)) != -1) {
                int endIndex = xml.indexOf("</version>", index);
                if (endIndex != -1) {
                    list.add(xml.substring(index + 9, endIndex));
                    index = endIndex;
                } else {
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("[Downloader] Не удалось получить список версий Forge: " + e.getMessage());
        }
        return list;
    }

    private Map<String, String> fetchForgePromotions() {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            String json = sendGetRequest(FORGE_PROMOTIONS_URL);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject promos = root.getAsJsonObject("promos");
            if (promos == null) return map;

            for (String key : promos.keySet()) {
                if (key.endsWith("-latest")) {
                    String mc = key.substring(0, key.length() - "-latest".length());
                    map.put(mc, promos.get(key).getAsString());
                }
            }
            for (String key : promos.keySet()) {
                if (key.endsWith("-recommended")) {
                    String mc = key.substring(0, key.length() - "-recommended".length());
                    map.put(mc, promos.get(key).getAsString());
                }
            }
        } catch (Exception e) {
            // Без падений при 404
        }
        return map;
    }

    private String sendGetRequest(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            String body = response.body();
            String snippet = (body == null || body.isEmpty())
                    ? ""
                    : " | ответ: " + body.substring(0, Math.min(200, body.length())).replace("\n", " ");
            throw new RuntimeException("HTTP " + response.statusCode() + " от " + url + snippet);
        }

        return response.body();
    }

    private void downloadFile(String url, Path targetPath) throws Exception {
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            _throwHttpError(response, url);
        }
        try (InputStream is = response.body()) {
            Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void _throwHttpError(HttpResponse<InputStream> response, String url) {
        throw new RuntimeException("HTTP " + response.statusCode() + " при скачивании файла: " + url);
    }
}