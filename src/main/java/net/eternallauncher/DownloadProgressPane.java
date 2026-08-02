package net.eternallauncher;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

public class DownloadProgressPane {

    private final Pane container;

    private final Rectangle background;
    private final Rectangle progress;
    private final Rectangle clip;

    private final Label statusLabel;

    private Timeline progressTimeline;
    private double currentProgress = 0.0;
    private double targetProgress = 0.0;

    public DownloadProgressPane(Pane container) {
        this.container = container;

        background = new Rectangle();
        background.setManaged(false);
        background.setArcWidth(8);
        background.setArcHeight(8);
        background.setFill(Color.rgb(0, 0, 0, 0.40));
        background.setStroke(Color.rgb(255, 255, 255, 0.15));

        progress = new Rectangle();
        progress.setManaged(false);
        progress.setArcWidth(8);
        progress.setArcHeight(8);
        progress.setFill(Color.rgb(46, 204, 113, 0.45));

        clip = new Rectangle();
        clip.setManaged(false);
        clip.setArcWidth(8);
        clip.setArcHeight(8);

        progress.setClip(clip);

        statusLabel = new Label();
        statusLabel.setManaged(false);
        statusLabel.setAlignment(Pos.CENTER);
        statusLabel.setStyle("""
                -fx-text-fill: white;
                -fx-font-size: 14px;
                -fx-font-weight: bold;
                """);

        container.getChildren().addAll(background, progress, statusLabel);

        container.widthProperty().addListener((obs, oldV, newV) -> layout());
        container.heightProperty().addListener((obs, oldV, newV) -> layout());

        layout();

        // Запускаем непрерывный анимационный цикл, который постоянно двигает прогресс к цели
        progressTimeline = new Timeline(new KeyFrame(Duration.millis(30), e -> tick()));
        progressTimeline.setCycleCount(Timeline.INDEFINITE);
        progressTimeline.play();

        hide();
    }

    private void layout() {
        double w = container.getWidth();
        double h = container.getHeight();

        background.setWidth(w);
        background.setHeight(h);

        clip.setWidth(w);
        clip.setHeight(h);

        progress.setHeight(h);

        statusLabel.resizeRelocate(0, 0, w, h);
    }

    private void tick() {
        if (!container.isVisible()) return;

        // Если мы не достигли цели, плавно приближаемся
        if (currentProgress < targetProgress) {
            // Чем ближе к цели, тем мягче замедление, но ползет всегда
            double step = (targetProgress - currentProgress) * 0.1;
            if (step < 0.001) step = 0.001;
            currentProgress += step;
            if (currentProgress > targetProgress) {
                currentProgress = targetProgress;
            }
        } else if (targetProgress < 1.0) {
            // Небольшая авто-подтяжка: если долго висим на одном месте (например, проверка 3911 файлов),
            // шкала сама медленно ползет вперед к следующему порогу, создавая жизнь
            double autoDriveTarget = Math.min(targetProgress + 0.15, 0.90);
            if (currentProgress < autoDriveTarget) {
                currentProgress += 0.0008; // Медленное фоновое подражание движению
            }
        }

        double width = container.getWidth();
        if (width > 0) {
            progress.setWidth(width * currentProgress);
        }
    }

    public void update(double newTargetProgress, String text) {
        Platform.runLater(() -> {
            container.setVisible(true);
            background.setVisible(true);
            progress.setVisible(true);
            statusLabel.setVisible(true);

            statusLabel.setText(text);
            targetProgress = Math.max(0.0, Math.min(1.0, newTargetProgress));

            if (targetProgress < currentProgress) {
                currentProgress = targetProgress;
            }
        });
    }

    public void setVisible(boolean visible) {
        Platform.runLater(() -> {
            container.setVisible(visible);
            background.setVisible(visible);
            progress.setVisible(visible);
            statusLabel.setVisible(visible);
        });
    }

    public void hide() {
        Platform.runLater(() -> {
            currentProgress = 0.0;
            targetProgress = 0.0;
            progress.setWidth(0);

            container.setVisible(false);
            background.setVisible(false);
            progress.setVisible(false);
            statusLabel.setVisible(false);
        });
    }
}