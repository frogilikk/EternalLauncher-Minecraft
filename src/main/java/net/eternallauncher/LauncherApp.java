package net.eternallauncher;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class LauncherApp extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/net/eternallauncher/main.fxml"));

        Scene scene = new Scene(loader.load(), 1000, 600);

        primaryStage.setTitle("Eternal Launcher");
        primaryStage.setScene(scene);
        primaryStage.setResizable(false); // Запрещаем растягивать окно
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}