package net.eternallauncher;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

import java.io.File;
import java.util.List;

public class MainController {

    @FXML private TextField usernameField;
    @FXML private ComboBox<String> versionComboBox;
    @FXML private Button playButton;
    @FXML private Button settingsButton;
    @FXML private Label statusLabel;

    private LauncherConfig config;
    private File gameDir;
    private Downloader downloader;

    @FXML
    public void initialize() {
        gameDir = new File("run_minecraft");
        config = new LauncherConfig(gameDir);
        downloader = new Downloader();

        usernameField.setText(config.getDefaultUsername());
        versionComboBox.setPromptText("Версия не выбрана");

        versionComboBox.setCellFactory(listView -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item);
                    setStyle("-fx-background-color: rgba(18, 21, 30, 0.95); " +
                            "-fx-text-fill: white; " +
                            "-fx-padding: 8px 12px; " +
                            "-fx-font-size: 14px;");
                }
            }
        });

        versionComboBox.showingProperty().addListener((obs, wasShowing, isShowing) -> {
            if (isShowing) {
                Platform.runLater(() -> {
                    var popup = versionComboBox.lookup(".combo-box-popup .list-view");
                    if (popup != null) {
                        popup.setStyle("-fx-background-color: rgba(18, 21, 30, 0.95); " +
                                "-fx-background-radius: 8px; " +
                                "-fx-border-color: rgba(255, 255, 255, 0.15); " +
                                "-fx-border-radius: 8px;");
                    }
                });
            }
        });

        versionComboBox.valueProperty().addListener((observable, oldValue, newValue) -> {
            updateStatusLabel(newValue);
            if (newValue != null && !newValue.isEmpty()) {
                config.setVersion(newValue);
            }
        });

        statusLabel.setText("Загрузка списка версий...");

        new Thread(() -> {
            List<String> releaseVersions = downloader.getReleaseVersions();

            Platform.runLater(() -> {
                versionComboBox.setItems(FXCollections.observableArrayList(releaseVersions));

                String savedVersion = config.getVersion();
                if (savedVersion != null && releaseVersions.contains(savedVersion)) {
                    versionComboBox.setValue(savedVersion);
                } else {
                    updateStatusLabel(null);
                }
            });
        }).start();
    }

    private void updateStatusLabel(String selectedVersion) {
        if (selectedVersion == null || selectedVersion.trim().isEmpty()) {
            statusLabel.setText("Версия не выбрана");
        } else {
            statusLabel.setText("Выбрана версия: " + selectedVersion);
        }
    }

    @FXML
    private void onPlayButtonClick() {
        String username = usernameField.getText().trim();
        if (username.isEmpty()) {
            username = config.getDefaultUsername();
        }

        String selectedVersion = versionComboBox.getValue();

        if (selectedVersion == null || selectedVersion.isEmpty()) {
            statusLabel.setText("Ошибка: выберите версию перед запуском!");
            return;
        }

        final String finalUsername = username;
        final String finalVersion = selectedVersion;

        setUiDisabled(true);
        statusLabel.setText("Скачивание / Запуск версии " + finalVersion + "...");

        new Thread(() -> {
            try {
                String assetIndex = downloader.downloadVersion(finalVersion, gameDir);

                Platform.runLater(() -> statusLabel.setText("Запуск игры..."));

                GameLauncher launcher = new GameLauncher();

                // Передаем Runnable, который сработает при завершении процесса игры
                launcher.launch(gameDir, config, finalUsername, assetIndex, () -> {
                    Platform.runLater(() -> {
                        updateStatusLabel(versionComboBox.getValue());
                        setUiDisabled(false);
                    });
                });

            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> {
                    statusLabel.setText("Ошибка при запуске!");
                    setUiDisabled(false);
                });
            }
        }).start();
    }

    private void setUiDisabled(boolean disabled) {
        playButton.setDisable(disabled);
        usernameField.setDisable(disabled);
        versionComboBox.setDisable(disabled);
        settingsButton.setDisable(disabled);
    }
}