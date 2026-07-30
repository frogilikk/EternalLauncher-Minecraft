package net.eternallauncher;

import java.io.File;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("==========================================");
        System.out.println("         ETERNAL LAUNCHER v1.0            ");
        System.out.println("==========================================");

        System.out.print("Введите ваш никнейм: ");
        String username = scanner.nextLine().trim();

        if (username.isEmpty()) {
            username = "Steve";
        }

        String targetVersion = "1.20.1";
        File gameDir = new File("./run_minecraft");

        Downloader downloader = new Downloader();
        GameLauncher launcher = new GameLauncher();

        try {
            // 1. Скачиваем файлы
            downloader.downloadVersion(targetVersion, gameDir);

            // 2. Запускаем игру
            launcher.launch(gameDir, targetVersion, username);

        } catch (Exception e) {
            System.err.println("Произошла ошибка:");
            e.printStackTrace();
        }
    }
}