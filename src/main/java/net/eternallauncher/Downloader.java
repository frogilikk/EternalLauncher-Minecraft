package net.eternallauncher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Downloader {

    private static final String MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    /**
     * Получает список всех стабильных версий Minecraft (только type = "release").
     * Игнорирует снапшоты, бета- и альфа-версии.
     */
    public List<String> getReleaseVersions() {
        List<String> releaseVersions = new ArrayList<>();
        try {
            String manifestJson = sendGetRequest(MANIFEST_URL);
            JsonObject manifest = JsonParser.parseString(manifestJson).getAsJsonObject();
            JsonArray versions = manifest.getAsJsonArray("versions");

            for (JsonElement element : versions) {
                JsonObject versionObj = element.getAsJsonObject();
                String type = versionObj.get("type").getAsString();

                // Оставляем строго полноценные релизы
                if ("release".equals(type)) {
                    releaseVersions.add(versionObj.get("id").getAsString());
                }
            }
        } catch (Exception e) {
            System.err.println("[Downloader] Не удалось получить список версий из сети: " + e.getMessage());
            // Резервный список на случай запуска без интернета
            return List.of("1.21.1", "1.20.4", "1.20.1", "1.16.5", "1.12.2", "1.8.9");
        }
        return releaseVersions;
    }

    /**
     * Скачивает версию и возвращает ID индекса ассетов (assetIndex),
     * который понадобится для GameLauncher.
     */
    public String downloadVersion(String targetVersion, File gameDir) throws Exception {
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
        JsonObject versionObj = JsonParser.parseString(versionDataJson).getAsJsonObject();

        // 1. Сохраняем локальный JSON версии
        File versionFolder = new File(gameDir, "versions/" + targetVersion);
        versionFolder.mkdirs();
        Files.writeString(new File(versionFolder, targetVersion + ".json").toPath(), versionDataJson);

        // 2. Скачиваем сам client.jar
        JsonObject clientDownload = versionObj.getAsJsonObject("downloads").getAsJsonObject("client");
        String clientUrl = clientDownload.get("url").getAsString();
        File clientJar = new File(versionFolder, targetVersion + ".jar");

        if (!clientJar.exists()) {
            System.out.println("[Downloader] Скачивание " + targetVersion + ".jar...");
            downloadFile(clientUrl, clientJar.toPath());
        }

        // 3. Скачиваем библиотеки
        System.out.println("[Downloader] Проверка и скачивание библиотек...");
        JsonArray libraries = versionObj.getAsJsonArray("libraries");
        File nativesDir = new File(gameDir, "versions/" + targetVersion + "/natives");
        nativesDir.mkdirs();

        int downloadedCount = 0;

        for (JsonElement elem : libraries) {
            JsonObject lib = elem.getAsJsonObject();

            if (!shouldDownload(lib)) continue;

            JsonObject downloads = lib.getAsJsonObject("downloads");
            if (downloads == null) continue;

            // Скачивание стандартного артефакта
            if (downloads.has("artifact")) {
                JsonObject artifact = downloads.getAsJsonObject("artifact");
                String path = artifact.get("path").getAsString();
                String url = artifact.get("url").getAsString();

                File targetLibFile = new File(gameDir, "libraries/" + path);

                if (!targetLibFile.exists()) {
                    targetLibFile.getParentFile().mkdirs();
                    downloadFile(url, targetLibFile.toPath());
                    downloadedCount++;
                }

                // Извлекаем нативы (поддержка старых и новых путей)
                if (isNativeLibrary(lib, path)) {
                    extractNatives(targetLibFile, nativesDir);
                }
            }

            // Поддержка нативов для старых версий (секция classifiers)
            if (downloads.has("classifiers")) {
                JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                String osName = getOsKey();

                if (classifiers.has(osName)) {
                    JsonObject nativeArtifact = classifiers.getAsJsonObject(osName);
                    String path = nativeArtifact.get("path").getAsString();
                    String url = nativeArtifact.get("url").getAsString();

                    File targetNativeJar = new File(gameDir, "libraries/" + path);
                    if (!targetNativeJar.exists()) {
                        targetNativeJar.getParentFile().mkdirs();
                        downloadFile(url, targetNativeJar.toPath());
                        downloadedCount++;
                    }
                    extractNatives(targetNativeJar, nativesDir);
                }
            }
        }
        System.out.println("[Downloader] Готово! Скачано новых библиотек: " + downloadedCount);

        // 4. Скачиваем ресурсы (assets) и возвращаем assetId
        return downloadAssets(versionObj, gameDir);
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

    private void extractNatives(File jarFile, File destDir) {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jarFile.toPath()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();

                // Пропускаем метаданные и директории
                if (entry.isDirectory() || name.startsWith("META-INF")) {
                    continue;
                }

                if (name.endsWith(".dll") || name.endsWith(".so") || name.endsWith(".dylib")) {
                    File outFile = new File(destDir, name);

                    // Защита от Zip Slip уязвимости
                    if (!outFile.getCanonicalPath().startsWith(destDir.getCanonicalPath())) {
                        continue;
                    }

                    if (!outFile.exists()) {
                        Files.copy(zis, outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private boolean isNativeLibrary(JsonObject lib, String path) {
        return path.contains("native") || lib.has("natives");
    }

    private String getOsKey() {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) return "natives-windows";
        if (osName.contains("mac")) return "natives-macos";
        return "natives-linux";
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

    private String sendGetRequest(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private void downloadFile(String url, Path targetPath) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream is = response.body()) {
            Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}