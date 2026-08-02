package net.eternallauncher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

public class SymlinkManager {

    /**
     * Подготавливает изолированные папки версии и создает симлинки (или копии) для общих данных.
     */
    public static void prepareSharedFolders(File gameDir, GameLauncher.ParsedVersion parsed) {
        boolean isModded = parsed.loaderType != null && !parsed.loaderType.equalsIgnoreCase("VANILLA") && !parsed.loaderType.isEmpty();
        if (!isModded) {
            return; // Ванильные версии используют общую папку напрямую без изоляции
        }

        Path root = gameDir.toPath();
        Path versionDir = getVersionDir(gameDir, parsed).toPath();

        try {
            Files.createDirectories(versionDir);
        } catch (IOException e) {
            throw new RuntimeException("Не удалось создать папку версии", e);
        }

        String[] sharedDirectories = {
                "saves",
                "resourcepacks",
                "screenshots",
                "shaderpacks",
                "datapacks"
        };

        String[] sharedFiles = {
                "options.txt",
                "servers.dat"
        };

        // Создаем собственную папку logs для этой версии
        Path versionLogsDir = versionDir.resolve("logs");
        try {
            if (Files.exists(versionLogsDir) && !Files.isDirectory(versionLogsDir) && !Files.isSymbolicLink(versionLogsDir)) {
                deleteRecursively(versionLogsDir);
            }
            Files.createDirectories(versionLogsDir);
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Общие папки
        for (String dirName : sharedDirectories) {
            Path global = root.resolve(dirName);
            Path local = versionDir.resolve(dirName);

            try {
                Files.createDirectories(global);

                if (Files.exists(local) || Files.isSymbolicLink(local)) {
                    deleteRecursively(local);
                }

                try {
                    Files.createSymbolicLink(local, global);
                } catch (UnsupportedOperationException | IOException ex) {
                    Files.createDirectories(local);
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // Общие файлы
        for (String fileName : sharedFiles) {
            Path global = root.resolve(fileName);
            Path local = versionDir.resolve(fileName);

            try {
                if (Files.notExists(global)) {
                    Files.createFile(global);
                }

                if (Files.exists(local) || Files.isSymbolicLink(local)) {
                    deleteRecursively(local);
                }

                try {
                    Files.createSymbolicLink(local, global);
                } catch (UnsupportedOperationException | IOException ex) {
                    Files.copy(global, local, StandardCopyOption.REPLACE_EXISTING);
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Возвращает директорию конкретной версии игры.
     */
    public static File getVersionDir(File gameDir, GameLauncher.ParsedVersion parsed) {
        boolean isModded = parsed.loaderType != null && !parsed.loaderType.equalsIgnoreCase("VANILLA") && !parsed.loaderType.isEmpty();
        if (!isModded) {
            return gameDir;
        }

        String versionFolderName = parsed.mcVersion + "-" + parsed.loaderType.toLowerCase() + "-" + (parsed.loaderVersion != null ? parsed.loaderVersion : "latest");
        return new File(new File(gameDir, "versions"), versionFolderName);
    }

    /**
     * Рекурсивное удаление файлов и папок или симлинков.
     */
    public static void deleteRecursively(Path path) throws IOException {
        if (Files.notExists(path) && !Files.isSymbolicLink(path)) {
            return;
        }

        if (Files.isSymbolicLink(path) || Files.isRegularFile(path)) {
            Files.deleteIfExists(path);
            return;
        }

        try (Stream<Path> stream = Files.list(path)) {
            for (Path child : stream.toList()) {
                deleteRecursively(child);
            }
        }

        Files.deleteIfExists(path);
    }
}