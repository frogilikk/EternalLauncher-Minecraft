package net.eternallauncher.ui.background;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.BlendMode;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;

import java.util.Random;

public class SpaceBackgroundCanvas extends Canvas {

    // --- РЕЖИМЫ ОТОБРАЖЕНИЯ ---
    public enum Mode {
        SUN,
        EARTH,
        BLACK_HOLE
    }

    // -------------------------------------------------------------
    // МЕНЯЙ ЗДЕСЬ: Mode.EARTH или Mode.SUN
    // -------------------------------------------------------------
    private static final Mode CURRENT_MODE = Mode.SUN;

    private double accumTime = 0;
    private long lastNanoTime = 0;

    private final Star[] stars = new Star[250];
    private final SunRenderer sunRenderer;
    private final EarthRenderer earthRenderer;
    private final BlackHoleRenderer blackHoleRenderer;

    public SpaceBackgroundCanvas() {
        // Инициализируем рендереры
        this.sunRenderer = new SunRenderer();
        this.earthRenderer = new EarthRenderer(520); // Адекватный диаметр планеты в px
        this.blackHoleRenderer = new BlackHoleRenderer();

        // Автоматическая привязка размеров к родительскому контейнеру
        parentProperty().addListener((obs, oldParent, newParent) -> {
            if (newParent instanceof javafx.scene.layout.Region region) {
                widthProperty().bind(region.widthProperty());
                heightProperty().bind(region.heightProperty());
            }
        });

        widthProperty().addListener(evt -> draw(0.016));
        heightProperty().addListener(evt -> draw(0.016));

        // Генерация звёздного неба
        Random rnd = new Random();
        for (int i = 0; i < stars.length; i++) {
            stars[i] = new Star(
                    rnd.nextDouble(),
                    rnd.nextDouble(),
                    rnd.nextDouble() * 1.8 + 0.2,
                    rnd.nextDouble() * Math.PI * 2
            );
        }

        // Запуск таймера
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (lastNanoTime == 0) {
                    lastNanoTime = now;
                    return;
                }

                double deltaSeconds = (now - lastNanoTime) / 1_000_000_000.0;
                lastNanoTime = now;

                if (deltaSeconds > 0.1) deltaSeconds = 0.016;

                accumTime += deltaSeconds;
                draw(deltaSeconds);
            }
        };
        timer.start();
    }

    private void draw(double deltaSeconds) {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) return;

        GraphicsContext gc = getGraphicsContext2D();

        // --- СЛОЙ 1: Глубокий Космос ---
        gc.setGlobalBlendMode(BlendMode.SRC_OVER);
        gc.setGlobalAlpha(1.0);

        RadialGradient spaceBg = new RadialGradient(
                0, 0, w * 0.5, h * 0.5, Math.max(w, h), false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.web("#0e1017")),
                new Stop(0.5, Color.web("#07080d")),
                new Stop(1.0, Color.web("#020204"))
        );
        gc.setFill(spaceBg);
        gc.fillRect(0, 0, w, h);

        // --- СЛОЙ 2: Мерцающие Звёзды ---
        for (Star star : stars) {
            double normalizedSin = (Math.sin(accumTime * 2.0 + star.phase) + 1.0) / 2.0;
            double alpha = Math.max(0.05, Math.min(1.0, 0.2 + 0.7 * normalizedSin));

            Color starColor = (star.size > 1.2)
                    ? Color.rgb(200, 220, 255, alpha)
                    : Color.rgb(255, 240, 220, alpha);

            gc.setFill(starColor);
            gc.fillOval(star.x * w, star.y * h, star.size, star.size);
        }

        // --- СЛОЙ 3: Отрисовка только ВЫБРАННОГО объекта ---
        if (CURRENT_MODE == Mode.SUN) {
            sunRenderer.render(gc, w, h, accumTime);
        } else if (CURRENT_MODE == Mode.EARTH) {
            earthRenderer.render(gc, w, h, deltaSeconds);
        } else if (CURRENT_MODE == Mode.BLACK_HOLE) {
           // blackHoleRenderer.render(gc, w, h, deltaSeconds);
        }
    }

    @Override
    public boolean isResizable() {
        return true;
    }

    private static class Star {
        double x, y, size, phase;
        Star(double x, double y, double size, double phase) {
            this.x = x;
            this.y = y;
            this.size = size;
            this.phase = phase;
        }
    }
}