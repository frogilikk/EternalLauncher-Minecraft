package net.eternallauncher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

public class LauncherConfig {

    private final File configFile;
    private final Properties properties = new Properties();

    public LauncherConfig(File gameDir) {
        // Гарантируем, что папка игры (run_minecraft) существует
        if (!gameDir.exists()) {
            gameDir.mkdirs();
        }
        // Конфиг ВСЕГДА живет внутри рабочей директории
        this.configFile = new File(gameDir, "config.properties");
        loadOrCreate();
    }

    private void loadOrCreate() {
        setDefaults();

        if (configFile.exists()) {
            try (FileInputStream in = new FileInputStream(configFile)) {
                properties.load(in);
            } catch (IOException e) {
                System.err.println("[Config] Ошибка чтения config.properties: " + e.getMessage());
            }
        } else {
            // Создаем файл только при самом первом запуске
            save();
        }
    }

    private void setDefaults() {
        properties.setProperty("launcher.defaultUsername", "Player");
        properties.setProperty("java.memory.min", "1024M");
        properties.setProperty("java.memory.max", "4096M");
        properties.setProperty("java.jvmArgs", "-XX:+UseG1GC");
        properties.setProperty("game.version", "1.21.1");
        properties.setProperty("game.window.width", "854");
        properties.setProperty("game.window.height", "480");
        properties.setProperty("game.window.fullscreen", "false");
        properties.setProperty("java.executable", "java");
    }

    public void save() {
        try (FileOutputStream out = new FileOutputStream(configFile)) {
            properties.store(out, "EternalLauncher Configuration");
        } catch (IOException e) {
            System.err.println("[Config] Ошибка сохранения config.properties: " + e.getMessage());
        }
    }

    // --- Геттеры ---
    public String getVersion() {
        return properties.getProperty("game.version", "1.21.1").trim();
    }

    public String getDefaultUsername() {
        return properties.getProperty("launcher.defaultUsername", "Player");
    }

    public String getMinMemory() {
        return properties.getProperty("java.memory.min", "1024M");
    }

    public String getMaxMemory() {
        return properties.getProperty("java.memory.max", "4096M");
    }

    public String getJvmArgs() {
        return properties.getProperty("java.jvmArgs", "");
    }

    public String getWindowWidth() {
        return properties.getProperty("game.window.width", "854");
    }

    public String getWindowHeight() {
        return properties.getProperty("game.window.height", "480");
    }

    public boolean isFullscreen() {
        return Boolean.parseBoolean(properties.getProperty("game.window.fullscreen", "false"));
    }

    public String getJavaExecutable() {
        return properties.getProperty("java.executable", "java");
    }

    // --- Сеттеры (ПРИГОДЯТСЯ ДЛЯ JAVAFX) ---

    /**
     * Вызывается из контроллера JavaFX при изменении выпадающего списка версий
     */
    public void setVersion(String version) {
        properties.setProperty("game.version", version);
        save(); // Автоматически сохраняем изменения на диск
    }

    public void setUsername(String username) {
        properties.setProperty("launcher.defaultUsername", username);
        save();
    }
}