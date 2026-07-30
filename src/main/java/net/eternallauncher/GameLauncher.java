package net.eternallauncher;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class GameLauncher {

    public void launch(File gameDir, String version, String username) {
        try {
            System.out.println("[GameLauncher] Подготовка параметров...");

            // Приводим директорию игры к каноничному пути
            File canonicalGameDir = gameDir.getCanonicalFile();

            // Папка с извлеченными .dll / .so / .dylib
            File nativesDir = new File(canonicalGameDir, "versions/" + version + "/natives");

            // 1. Генерируем оффлайн UUID из никнейма
            String uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8))
                    .toString().replace("-", "");

            // 2. Собираем classpath (список всех .jar)
            String classpath = buildClasspath(canonicalGameDir, version);

            // 3. Формируем список аргументов JVM и Minecraft
            List<String> command = new ArrayList<>();
            command.add("java");

            // Указываем путь к нативным библиотекам LWJGL
            command.add("-Djava.library.path=" + nativesDir.getAbsolutePath());

            command.add("-Xms1024M"); // Минимальная ОЗУ (1 ГБ)
            command.add("-Xmx4096M"); // Максимальная ОЗУ (4 ГБ)
            command.add("-cp");
            command.add(classpath);

            // Главный класс игры
            command.add("net.minecraft.client.main.Main");

            // --- АРГУМЕНТЫ MINECRAFT ---
            command.add("--username");
            command.add(username);

            command.add("--version");
            command.add(version);

            command.add("--gameDir");
            command.add(canonicalGameDir.getAbsolutePath());

            command.add("--assetsDir");
            command.add(new File(canonicalGameDir, "assets").getAbsolutePath());

            command.add("--assetIndex");
            command.add("5"); // Индекс ресурсов для 1.20.1

            command.add("--uuid");
            command.add(uuid);

            command.add("--accessToken");
            command.add("0"); // Фейковый токен для оффлайн-режима

            command.add("--userType");
            command.add("legacy");

            System.out.println("[GameLauncher] Запуск процесса Minecraft...");
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(canonicalGameDir);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // 4. Считываем логи игры в консоль
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[GAME] " + line);
                }
            }

            int exitCode = process.waitFor();
            System.out.println("[GameLauncher] Игра закрыта с кодом: " + exitCode);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String buildClasspath(File gameDir, String version) {
        List<String> jars = new ArrayList<>();

        // Добавляем файл самой версии (1.20.1.jar)
        File clientJar = new File(gameDir, "versions/" + version + "/" + version + ".jar");
        jars.add(clientJar.getAbsolutePath());

        // Рекурсивно собираем все .jar из папки libraries
        File libDir = new File(gameDir, "libraries");
        collectJars(libDir, jars);

        return String.join(File.pathSeparator, jars);
    }

    private void collectJars(File dir, List<String> jars) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                collectJars(file, jars);
            } else if (file.getName().endsWith(".jar")) {
                jars.add(file.getAbsolutePath());
            }
        }
    }
}