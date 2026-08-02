package net.eternallauncher;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.List;

public class MainController {

    @FXML private TextField usernameField;
    @FXML private ComboBox<String> versionComboBox;
    @FXML private Button playButton;
    @FXML private Button settingsButton;
    @FXML private Button openFolderButton;
    @FXML private StackPane progressContainer;

    private LauncherConfig config;
    private File gameDir;
    private Downloader downloader;
    private TrayIcon trayIcon;
    private DownloadProgressPane progressPane;

    @FXML
    public void initialize() {
        gameDir = new File("run_minecraft");
        config = new LauncherConfig(gameDir);
        downloader = new Downloader();

        // Инициализируем твой кастомный прогресс-бар для нижней панели
        if (progressContainer != null) {
            progressPane = new DownloadProgressPane(progressContainer);
        }

        usernameField.setText(config.getDefaultUsername());
        Platform.runLater(() -> playButton.requestFocus());

        usernameField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                config.setUsername(newValue.trim());
            }
        });

        versionComboBox.setPromptText("Версия не выбрана");

        versionComboBox.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item);
                    GameLauncher.ParsedVersion parsed = new GameLauncher.ParsedVersion(item);
                    boolean isDownloaded = downloader.isModdedVersionDownloaded(
                            parsed.mcVersion, parsed.loaderType, parsed.loaderVersion, gameDir
                    );
                    String baseStyle = "-fx-background-color: transparent; -fx-padding: 8px 12px; -fx-font-size: 14px; ";
                    if (isDownloaded) {
                        setStyle(baseStyle + "-fx-text-fill: white; -fx-font-weight: normal;");
                    } else {
                        setStyle(baseStyle + "-fx-text-fill: rgba(255, 255, 255, 0.35); -fx-font-weight: normal;");
                    }
                }
            }
        });

        versionComboBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item);
                    GameLauncher.ParsedVersion parsed = new GameLauncher.ParsedVersion(item);
                    boolean isDownloaded = downloader.isModdedVersionDownloaded(
                            parsed.mcVersion, parsed.loaderType, parsed.loaderVersion, gameDir
                    );
                    if (isDownloaded) {
                        setStyle("-fx-text-fill: white; -fx-font-weight: normal;");
                    } else {
                        setStyle("-fx-text-fill: rgba(255, 255, 255, 0.35); -fx-font-weight: normal;");
                    }
                }
            }
        });

        versionComboBox.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && !newValue.isEmpty()) {
                config.setVersion(newValue);
            }
        });

        new Thread(() -> {
            List<String> allInstallableVersions = downloader.getAllInstallableVersions();
            Platform.runLater(() -> {
                versionComboBox.setItems(FXCollections.observableArrayList(allInstallableVersions));
                String savedVersion = config.getVersion();
                if (savedVersion != null && allInstallableVersions.contains(savedVersion)) {
                    versionComboBox.setValue(savedVersion);
                } else if (!allInstallableVersions.isEmpty()) {
                    versionComboBox.setValue(allInstallableVersions.get(0));
                }
            });
        }).start();
    }

    @FXML
    private void onPlayButtonClick() {
        String username = usernameField.getText().trim();
        if (username.isEmpty()) {
            username = config.getDefaultUsername();
        }

        String selectedItem = versionComboBox.getValue();
        if (selectedItem == null || selectedItem.isEmpty()) {
            return;
        }

        GameLauncher.ParsedVersion parsed = parseSelectedVersion(selectedItem);
        final String finalUsername = username;
        final String finalVersion = parsed.mcVersion;
        final String finalLoader = parsed.loaderType;
        final String finalLoaderVersion = parsed.loaderVersion;

        setUiDisabled(true);

        if (progressPane != null) {
            progressPane.update(0.05, "Проверка файлов версии...");
        }

        Stage stage = (Stage) playButton.getScene().getWindow();
        Platform.setImplicitExit(false);

        new Thread(() -> {
            try {
                if (progressPane != null) {
                    progressPane.update(0.2, "Загрузка компонентов и модов...");
                }

                String assetIndex = downloader.downloadVersion(finalVersion, finalLoader, finalLoaderVersion, gameDir);

                if (progressPane != null) {
                    progressPane.update(0.8, "Подготовка папок игры...");
                }
                SymlinkManager.prepareSharedFolders(gameDir, parsed);

                if (progressPane != null) {
                    progressPane.update(1.0, "Запуск игры...");
                    Thread.sleep(600); // Короткая пауза для отображения 100%
                    progressPane.hide();
                }

                // Скрываем лаунчер и уводим в трей перед запуском игры
                Platform.runLater(() -> {
                    stage.close();
                    minimizeToTray(stage);
                });

                GameLauncher launcher = new GameLauncher();
                launcher.launch(gameDir, config, finalUsername, assetIndex, () -> {
                    Platform.runLater(() -> {
                        removeTrayIcon();
                        stage.show();
                        stage.toFront();
                        stage.requestFocus();
                        setUiDisabled(false);
                    });
                });
            } catch (Exception e) {
                e.printStackTrace();
                if (progressPane != null) {
                    progressPane.hide();
                }
                Platform.runLater(() -> {
                    removeTrayIcon();
                    stage.show();
                    stage.toFront();
                    stage.requestFocus();
                    setUiDisabled(false);
                });
            }
        }).start();
    }

    /**
     * Сворачивает лаунчер в системный трей (SystemTray)
     */
    private void minimizeToTray(Stage stage) {
        if (!SystemTray.isSupported()) {
            return;
        }

        try {
            SystemTray tray = SystemTray.getSystemTray();

            URL imageURL = MainController.class.getResource("/net/eternallauncher/icon.png");
            Image image;
            if (imageURL != null) {
                image = Toolkit.getDefaultToolkit().getImage(imageURL);
            } else {
                image = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            }

            PopupMenu popup = new PopupMenu();
            MenuItem openItem = new MenuItem("Открыть EternalLauncher");
            openItem.addActionListener(e -> Platform.runLater(() -> {
                removeTrayIcon();
                stage.show();
                stage.toFront();
                stage.requestFocus();
            }));

            MenuItem exitItem = new MenuItem("Выход");
            exitItem.addActionListener(e -> System.exit(0));

            popup.add(openItem);
            popup.addSeparator();
            popup.add(exitItem);

            trayIcon = new TrayIcon(image, "EternalLauncher (Игра запущена)", popup);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> Platform.runLater(() -> {
                removeTrayIcon();
                stage.show();
                stage.toFront();
                stage.requestFocus();
            }));

            tray.add(trayIcon);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void removeTrayIcon() {
        if (trayIcon != null && SystemTray.isSupported()) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
            } catch (Exception ignored) {}
            trayIcon = null;
        }
    }

    @FXML
    private void onOpenVersionFolderClick() {
        String selectedItem = versionComboBox.getValue();
        if (selectedItem == null || selectedItem.isEmpty()) {
            openDirectory(gameDir);
            return;
        }

        GameLauncher.ParsedVersion parsed = parseSelectedVersion(selectedItem);
        File versionDir = SymlinkManager.getVersionDir(gameDir, parsed);

        if (!versionDir.exists()) {
            versionDir.mkdirs();
        }

        SymlinkManager.prepareSharedFolders(gameDir, parsed);
        openDirectory(versionDir);
    }

    private void openDirectory(File dir) {
        if (!dir.exists()) {
            dir.mkdirs();
        }
        new Thread(() -> {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(dir);
                } else {
                    String os = System.getProperty("os.name").toLowerCase();
                    if (os.contains("nix") || os.contains("nux")) {
                        Runtime.getRuntime().exec(new String[]{"xdg-open", dir.getAbsolutePath()});
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private GameLauncher.ParsedVersion parseSelectedVersion(String item) {
        return new GameLauncher.ParsedVersion(item);
    }

    private void setUiDisabled(boolean disabled) {
        playButton.setDisable(disabled);
        usernameField.setDisable(disabled);
        versionComboBox.setDisable(disabled);
        settingsButton.setDisable(disabled);
        if (openFolderButton != null) openFolderButton.setDisable(disabled);
    }
}