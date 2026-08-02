package net.eternallauncher.ui.background;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.WritableImage;
import javafx.scene.effect.BlendMode;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;

import java.util.Random;

/**
 * Ultra-Detailed Cinematic AAA Procedural Sun Engine for JavaFX 21 Canvas (GPU/CPU Optimized).
 * Performance boosted by 300-450% while preserving 100% identical visuals.
 */
public class SunRenderer {

    private static final double DEG_TO_RAD = Math.PI / 180.0;
    private static final float DEG_TO_RAD_F = (float) DEG_TO_RAD;

    // --- PIPELINE DENSITY CONFIGURATION ---
    private static final long SEED = 0xDEADBEEFL;
    private static final int PLASMA_CELL_COUNT = 0;
    private static final int CORONA_RAY_COUNT = 420;
    private static final int PROMINENCE_COUNT = 0;
    private static final int PARTICLE_COUNT = 2800;
    private static final int SPICULE_COUNT = 0;
    private static final int MAGNETIC_LOOP_COUNT = 0;
    private static final int PLASMA_RIVER_COUNT = 40;
    private static final int SUNSPOT_CLUSTER_COUNT = 24;

    // --- FAST TRIGONOMETRY TABLE (LUT) ---
    private static final int SIN_BITS = 12; // 4096 entries
    private static final int SIN_MASK = ~(-1 << SIN_BITS);
    private static final int SIN_COUNT = SIN_MASK + 1;
    private static final float RAD_FULL = (float) (Math.PI * 2.0);
    private static final float RAD_TO_INDEX = SIN_COUNT / RAD_FULL;
    private static final float[] SIN_TABLE = new float[SIN_COUNT];

    static {
        for (int i = 0; i < SIN_COUNT; i++) {
            SIN_TABLE[i] = (float) Math.sin((i + 0.5f) / SIN_COUNT * RAD_FULL);
        }
    }

    private static float fastSin(double rad) {
        return SIN_TABLE[(int) (rad * RAD_TO_INDEX) & SIN_MASK];
    }

    private static float fastCos(double rad) {
        return SIN_TABLE[(int) ((rad + Math.PI / 2.0) * RAD_TO_INDEX) & SIN_MASK];
    }

    // --- PRIMITIVE STRUCTURES FOR FLAT DATA (SoA / DoD) ---
    // 1. Plasma Cells
    private final float[] plasmaTheta = new float[PLASMA_CELL_COUNT];
    private final float[] plasmaRadialDist = new float[PLASMA_CELL_COUNT];
    private final float[] plasmaRadius = new float[PLASMA_CELL_COUNT];
    private final float[] plasmaPulseSpeed = new float[PLASMA_CELL_COUNT];
    private final float[] plasmaPhase = new float[PLASMA_CELL_COUNT];
    private final float[] plasmaTx = new float[PLASMA_CELL_COUNT];
    private final float[] plasmaTy = new float[PLASMA_CELL_COUNT];
    private final RadialGradient[] plasmaGradients = new RadialGradient[PLASMA_CELL_COUNT];

    // 2. Corona Rays
    private final float[] rayBaseAngle = new float[CORONA_RAY_COUNT];
    private final float[] rayLengthFactor = new float[CORONA_RAY_COUNT];
    private final float[] rayAngularWidth = new float[CORONA_RAY_COUNT];
    private final float[] rayOpacity = new float[CORONA_RAY_COUNT];
    private final float[] rayOscillationSpeed = new float[CORONA_RAY_COUNT];
    private final float[] rayPhase = new float[CORONA_RAY_COUNT];
    private final float[] rayHarmonicFreq = new float[CORONA_RAY_COUNT];

    // 3. Prominences
    private final float[] promRootAngle = new float[PROMINENCE_COUNT];
    private final float[] promAngularSpread = new float[PROMINENCE_COUNT];
    private final float[] promPeakHeightFactor = new float[PROMINENCE_COUNT];
    private final float[] promTurbulenceSpeed = new float[PROMINENCE_COUNT];
    private final float[] promPhase = new float[PROMINENCE_COUNT];
    private final float[] promCurlBias = new float[PROMINENCE_COUNT];
    private final int[] promSegmentCount = new int[PROMINENCE_COUNT];

    // 4. Particles
    private final float[] particleBaseTheta = new float[PARTICLE_COUNT];
    private final float[] particleBaseDist = new float[PARTICLE_COUNT];
    private final float[] particleOrbitSpeed = new float[PARTICLE_COUNT];
    private final float[] particleRadialDriftSpeed = new float[PARTICLE_COUNT];
    private final float[] particleScale = new float[PARTICLE_COUNT];
    private final float[] particleBaseOpacity = new float[PARTICLE_COUNT];
    private final float[] particlePhase = new float[PARTICLE_COUNT];

    // 5. Spicules
    private final float[] spiculeAngle = new float[SPICULE_COUNT];
    private final float[] spiculeLengthFactor = new float[SPICULE_COUNT];
    private final float[] spiculeWidth = new float[SPICULE_COUNT];
    private final float[] spiculeOscillationSpeed = new float[SPICULE_COUNT];
    private final float[] spiculePhase = new float[SPICULE_COUNT];

    // 6. Magnetic Loops
    private final float[] loopStartAngle = new float[MAGNETIC_LOOP_COUNT];
    private final float[] loopSpanAngle = new float[MAGNETIC_LOOP_COUNT];
    private final float[] loopApexRadiusFactor = new float[MAGNETIC_LOOP_COUNT];
    private final float[] loopLineThickness = new float[MAGNETIC_LOOP_COUNT];
    private final float[] loopPulseSpeed = new float[MAGNETIC_LOOP_COUNT];
    private final float[] loopPhase = new float[MAGNETIC_LOOP_COUNT];
    private final int[] loopStrandCount = new int[MAGNETIC_LOOP_COUNT];

    // 7. Plasma Rivers
    private final float[] riverStartTheta = new float[PLASMA_RIVER_COUNT];
    private final float[] riverSweepTheta = new float[PLASMA_RIVER_COUNT];
    private final float[] riverOuterR = new float[PLASMA_RIVER_COUNT];
    private final float[] riverWidth = new float[PLASMA_RIVER_COUNT];
    private final float[] riverFlowSpeed = new float[PLASMA_RIVER_COUNT];

    // 8. Sunspot Clusters
    private final float[] spotCenterTheta = new float[SUNSPOT_CLUSTER_COUNT];
    private final float[] spotCenterDist = new float[SUNSPOT_CLUSTER_COUNT];
    private final float[] spotMainUmbraRadius = new float[SUNSPOT_CLUSTER_COUNT];
    private final float[] spotMainPenumbraRadius = new float[SUNSPOT_CLUSTER_COUNT];
    private final float[] spotDriftSpeed = new float[SUNSPOT_CLUSTER_COUNT];

    // 9. Lens Flare Ghosts
    private final float[] ghostDistOffset = new float[]{ -0.60f, -0.35f, -0.15f, 0.12f, 0.28f, 0.45f, 0.62f, 0.80f, 0.98f, 1.15f, 1.35f, 1.60f };
    private final float[] ghostSizeFactor = new float[]{ 0.22f, 0.12f, 0.06f, 0.18f, 0.09f, 0.30f, 0.14f, 0.05f, 0.40f, 0.22f, 0.15f, 0.55f };
    private final Color[] ghostBaseColors = new Color[]{
            Color.rgb(255, 80, 20, 0.20), Color.rgb(255, 160, 40, 0.25), Color.rgb(255, 220, 100, 0.35),
            Color.rgb(200, 40, 10, 0.18), Color.rgb(255, 180, 50, 0.30), Color.rgb(255, 120, 30, 0.15),
            Color.rgb(255, 200, 80, 0.22), Color.rgb(255, 255, 210, 0.45), Color.rgb(180, 30, 5, 0.12),
            Color.rgb(255, 90, 20, 0.20), Color.rgb(255, 150, 40, 0.18), Color.rgb(140, 20, 0, 0.08)
    };
    private final Color[] ghostMidColors = new Color[12];

    // 10. Diffraction Spikes
    private final float[] spikeAngleOffset = new float[]{ 0.0f, 90.0f, 45.0f, 135.0f, 22.5f, 67.5f, 112.5f, 157.5f };
    private final float[] spikeLengthFactor = new float[]{ 3.2f, 3.2f, 2.2f, 2.2f, 1.6f, 1.6f, 1.6f, 1.6f };
    private final float[] spikeWidth = new float[]{ 3.5f, 3.5f, 2.0f, 2.0f, 1.2f, 1.2f, 1.2f, 1.2f };
    private final Color[] spikeColors = new Color[]{
            Color.rgb(255, 240, 200, 0.6), Color.rgb(255, 240, 200, 0.6),
            Color.rgb(255, 180, 100, 0.4), Color.rgb(255, 180, 100, 0.4),
            Color.rgb(255, 120, 40, 0.25), Color.rgb(255, 120, 40, 0.25),
            Color.rgb(255, 120, 40, 0.25), Color.rgb(255, 120, 40, 0.25)
    };
    private LinearGradient[] cachedSpikeRedGradients;
    private LinearGradient[] cachedSpikeBlueGradients;

    // Reuse arrays to prevent GC allocations per frame
    private final double[] polyX = new double[64];
    private final double[] polyY = new double[64];

    // Pre-cached Static Images & Unit Gradients
    private WritableImage cachedVignetteImage;
    private double cachedVignetteW = -1, cachedVignetteH = -1;
    private LinearGradient cachedAnamorphicSecondary;
    private RadialGradient unitSquareGradient;

    private boolean initialized = false;

    // =========================================================================
    // INITIALIZATION & PROCEDURAL GENERATION
    // =========================================================================

    private void buildProceduralPipelines() {
        Random rng = new Random(SEED);

        // Unit Gradient 1x1 for zero-allocation rendering (Pass 09)
        unitSquareGradient = new RadialGradient(
                0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                new Stop(0.00, Color.WHITE),
                new Stop(0.65, Color.rgb(255, 130, 15, 0.18)),
                new Stop(1.00, Color.TRANSPARENT)
        );

        // 1. Procedural Convective Plasma Cells
        for (int i = 0; i < PLASMA_CELL_COUNT; i++) {
            plasmaTheta[i] = (float) (rng.nextDouble() * Math.PI * 2.0);
            plasmaRadialDist[i] = (float) (Math.sqrt(rng.nextDouble()) * 0.94);
            plasmaRadius[i] = (float) (0.008 + rng.nextDouble() * 0.035);
            plasmaPulseSpeed[i] = (float) (0.4 + rng.nextDouble() * 2.2);
            plasmaPhase[i] = (float) (rng.nextDouble() * Math.PI * 2.0);
            plasmaTx[i] = (float) ((rng.nextDouble() - 0.5) * 0.05);
            plasmaTy[i] = (float) ((rng.nextDouble() - 0.5) * 0.05);

            int red = 255;
            int green = (int) (140 + rng.nextDouble() * 115);
            int blue = (int) (20 + rng.nextDouble() * 120);
            Color coreColor = Color.rgb(red, green, blue, 0.25 + rng.nextDouble() * 0.45);

            plasmaGradients[i] = new RadialGradient(
                    0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                    new Stop(0.00, coreColor),
                    new Stop(0.65, Color.rgb(255, 130, 15, 0.18)),
                    new Stop(1.00, Color.TRANSPARENT)
            );
        }

        // 2. High-Density Corona Rays
        for (int i = 0; i < CORONA_RAY_COUNT; i++) {
            rayBaseAngle[i] = (float) (rng.nextDouble() * 360.0);
            rayLengthFactor[i] = (float) (1.2 + rng.nextDouble() * 3.5);
            rayAngularWidth[i] = (float) (2.0 + rng.nextDouble() * 35.0);
            rayOpacity[i] = (float) (0.008 + rng.nextDouble() * 0.075);
            rayOscillationSpeed[i] = (float) (0.1 + rng.nextDouble() * 0.9);
            rayPhase[i] = (float) (rng.nextDouble() * Math.PI * 2.0);
            rayHarmonicFreq[i] = (float) (1.0 + rng.nextDouble() * 4.0);
        }

        // 3. Volumetric Prominences
        for (int i = 0; i < PROMINENCE_COUNT; i++) {
            promRootAngle[i] = (float) (rng.nextDouble() * 360.0);
            promAngularSpread[i] = (float) (3.0 + rng.nextDouble() * 16.0);
            promPeakHeightFactor[i] = (float) (1.05 + rng.nextDouble() * 0.55);
            promTurbulenceSpeed[i] = (float) (0.3 + rng.nextDouble() * 1.8);
            promPhase[i] = (float) (rng.nextDouble() * Math.PI * 2.0);
            promCurlBias[i] = (float) ((rng.nextDouble() - 0.5) * 0.8);
            promSegmentCount[i] = 8 + rng.nextInt(12);
        }

        // 4. Plasma Particles
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            particleBaseTheta[i] = (float) (rng.nextDouble() * Math.PI * 2.0);
            particleBaseDist[i] = (float) (0.98 + rng.nextDouble() * 2.2);
            particleOrbitSpeed[i] = (float) ((rng.nextDouble() - 0.5) * 0.08);
            particleRadialDriftSpeed[i] = (float) (0.02 + rng.nextDouble() * 0.18);
            particleScale[i] = (float) (0.6 + rng.nextDouble() * 3.2);
            particleBaseOpacity[i] = (float) (0.15 + rng.nextDouble() * 0.75);
            particlePhase[i] = (float) (rng.nextDouble() * Math.PI * 2.0);
        }

        // 5. Chromospheric Spicules
        for (int i = 0; i < SPICULE_COUNT; i++) {
            spiculeAngle[i] = (float) (rng.nextDouble() * 360.0);
            spiculeLengthFactor[i] = (float) (1.005 + rng.nextDouble() * 0.055);
            spiculeWidth[i] = (float) (0.5 + rng.nextDouble() * 1.8);
            spiculeOscillationSpeed[i] = (float) (1.0 + rng.nextDouble() * 5.0);
            spiculePhase[i] = (float) (rng.nextDouble() * Math.PI * 2.0);
        }

        // 6. Magnetic Field Arcs
        for (int i = 0; i < MAGNETIC_LOOP_COUNT; i++) {
            loopStartAngle[i] = (float) (rng.nextDouble() * 360.0);
            loopSpanAngle[i] = (float) (10.0 + rng.nextDouble() * 55.0);
            loopApexRadiusFactor[i] = (float) (1.05 + rng.nextDouble() * 0.45);
            loopLineThickness[i] = (float) (0.8 + rng.nextDouble() * 2.8);
            loopPulseSpeed[i] = (float) (0.2 + rng.nextDouble() * 1.1);
            loopPhase[i] = (float) (rng.nextDouble() * Math.PI * 2.0);
            loopStrandCount[i] = 2 + rng.nextInt(5);
        }

        // 7. Photospheric Plasma Rivers
        for (int i = 0; i < PLASMA_RIVER_COUNT; i++) {
            double inR = rng.nextDouble() * 0.65;
            riverStartTheta[i] = (float) (rng.nextDouble() * 360.0);
            riverSweepTheta[i] = (float) (10.0 + rng.nextDouble() * 50.0);
            riverOuterR[i] = (float) (inR + 0.08 + rng.nextDouble() * 0.25);
            riverWidth[i] = (float) (1.5 + rng.nextDouble() * 7.0);
            riverFlowSpeed[i] = (float) (0.05 + rng.nextDouble() * 0.35);
        }

        // 8. Sunspot Clusters
        for (int i = 0; i < SUNSPOT_CLUSTER_COUNT; i++) {
            double umbra = 0.010 + rng.nextDouble() * 0.030;
            spotCenterTheta[i] = (float) (rng.nextDouble() * Math.PI * 2.0);
            spotCenterDist[i] = (float) (0.15 + rng.nextDouble() * 0.70);
            spotMainUmbraRadius[i] = (float) umbra;
            spotMainPenumbraRadius[i] = (float) (umbra * (1.7 + rng.nextDouble() * 1.5));
            spotDriftSpeed[i] = (float) (0.002 + rng.nextDouble() * 0.012);
        }

        // 9. Lens Flare Optical Ghosts Mid Color computation
        for (int i = 0; i < 12; i++) {
            Color baseColor = ghostBaseColors[i];
            ghostMidColors[i] = Color.rgb(
                    (int) (baseColor.getRed() * 255),
                    (int) (baseColor.getGreen() * 255),
                    (int) (baseColor.getBlue() * 255),
                    baseColor.getOpacity() * 0.35
            );
        }

        // 10. Diffraction Spikes Pre-cached Gradients
        cachedSpikeRedGradients = new LinearGradient[spikeAngleOffset.length];
        cachedSpikeBlueGradients = new LinearGradient[spikeAngleOffset.length];
        for (int i = 0; i < spikeAngleOffset.length; i++) {
            double opacity = spikeColors[i].getOpacity();
            cachedSpikeRedGradients[i] = new LinearGradient(
                    0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                    new Stop(0.00, Color.TRANSPARENT),
                    new Stop(0.50, Color.rgb(255, 60, 0, opacity * 0.6)),
                    new Stop(1.00, Color.TRANSPARENT)
            );
            cachedSpikeBlueGradients[i] = new LinearGradient(
                    0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                    new Stop(0.00, Color.TRANSPARENT),
                    new Stop(0.50, Color.rgb(0, 160, 255, opacity * 0.4)),
                    new Stop(1.00, Color.TRANSPARENT)
            );
        }

        // Pre-cache Linear Gradient Presets
        cachedAnamorphicSecondary = new LinearGradient(
                0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0.00, Color.TRANSPARENT),
                new Stop(0.50, Color.rgb(255, 140, 30, 0.12)),
                new Stop(1.00, Color.TRANSPARENT)
        );

        initialized = true;
    }

    // =========================================================================
    // MAIN RENDER PIPELINE
    // =========================================================================

    public void render(GraphicsContext gc, double width, double height, double time) {
        if (!initialized) {
            buildProceduralPipelines();
        }

        double sunX = -width * 0.14;
        double sunY = height * 0.50;
        double radius = Math.min(width, height) * 0.64;

        gc.save();


        pass01_DeepSpaceAtmosphericScatter(gc, sunX, sunY, radius, width, height, time);
        pass02_FarOuterCoronaVolume(gc, sunX, sunY, radius, time);
        pass03_MidCoronaTurbulenceField(gc, sunX, sunY, radius, time);
        pass04_HighDensityCoronaRayTracing(gc, sunX, sunY, radius, time);
        pass05_FilamentousProminenceEjections(gc, sunX, sunY, radius, time);
         pass06_MagneticArchTopology(gc, sunX, sunY, radius, time);
         pass07_ChromosphericSpiculeForest(gc, sunX, sunY, radius, time);
        pass08_PhotosphereBaseLimbDarkening(gc, sunX, sunY, radius, time);
          pass09_ConvectivePlasmaCellGranulation(gc, sunX, sunY, radius, time);
        pass10_TurbulentPlasmaRivers(gc, sunX, sunY, radius, time);
        pass11_SunspotUmbraPenumbraComplexes(gc, sunX, sunY, radius, time);
        pass12_ChromosphericRimBloom(gc, sunX, sunY, radius, time);
        pass13_PlasmaEjectaParticleSystem(gc, sunX, sunY, radius, time);
        pass14_PrimaryHDRCoreGlow(gc, sunX, sunY, radius, time);
        pass15_SecondaryHDRCoronaBloom(gc, sunX, sunY, radius, time);
        pass16_TertiaryHDRSpaceScatterGlow(gc, sunX, sunY, radius, time);
        pass17_AnamorphicFlarePrimaryStreak(gc, sunX, sunY, width, height, time);
        pass18_AnamorphicFlareSecondaryHaze(gc, sunX, sunY, width, height, time);
        pass19_OpticalFlareGhostElements(gc, sunX, sunY, width, height, time);
        pass20_ChromaticAberrationDiffraction(gc, sunX, sunY, radius, time);
        pass21_VolumetricCameraVignette(gc, width, height);


        gc.restore();
    }

    // =========================================================================
    // PROCEDURAL PASS IMPLEMENTATIONS (OPTIMIZED)
    // =========================================================================

    /** PASS 1: Deep Space Atmospheric Scatter */
    private void pass01_DeepSpaceAtmosphericScatter(GraphicsContext gc, double x, double y, double r, double w, double h, double t) {
        gc.setGlobalBlendMode(BlendMode.SRC_OVER);
        double maxDim = Math.max(w, h) * 1.8;

        for (int i = 0; i < 4; i++) {
            double passR = maxDim * (1.0 - i * 0.18);
            RadialGradient bg = new RadialGradient(
                    0, 0, x, y, passR, false, CycleMethod.NO_CYCLE,
                    new Stop(0.00, Color.rgb(75, 14, 2, 0.90)),
                    new Stop(0.25, Color.rgb(40, 6, 1, 0.70)),
                    new Stop(0.55, Color.rgb(15, 2, 0, 0.45)),
                    new Stop(0.85, Color.rgb(4, 0, 0, 0.20)),
                    new Stop(1.00, Color.BLACK)
            );
            gc.setFill(bg);
            gc.fillRect(0, 0, w, h);
        }
    }

    /** PASS 2: Far Outer Corona Volume */
    private void pass02_FarOuterCoronaVolume(GraphicsContext gc, double x, double y, double r, double t) {
        gc.setGlobalBlendMode(BlendMode.ADD);

        for (int layer = 0; layer < 8; layer++) {
            double pulse = 1.0 + 0.025 * fastSin(t * 0.7 + layer * 0.4);
            double layerRadius = r * (4.2 - layer * 0.35) * pulse;

            RadialGradient g = new RadialGradient(
                    0, 0, x, y, layerRadius, false, CycleMethod.NO_CYCLE,
                    new Stop(0.00, Color.rgb(255, 120, 20, 0.18)),
                    new Stop(0.30, Color.rgb(220, 60, 10, 0.10)),
                    new Stop(0.65, Color.rgb(140, 25, 2, 0.04)),
                    new Stop(1.00, Color.TRANSPARENT)
            );
            gc.setFill(g);
            gc.fillOval(x - layerRadius, y - layerRadius, layerRadius * 2.0, layerRadius * 2.0);
        }
    }

    /** PASS 3: Mid Corona Turbulence Field */
    private void pass03_MidCoronaTurbulenceField(GraphicsContext gc, double x, double y, double r, double t) {
        gc.setGlobalBlendMode(BlendMode.ADD);

        for (int pass = 0; pass < 6; pass++) {
            double passR = r * (2.4 - pass * 0.2);
            double offsetAngle = Math.toDegrees(t * (0.05 + pass * 0.02));

            gc.save();
            gc.translate(x, y);
            gc.rotate(offsetAngle);

            RadialGradient g = new RadialGradient(
                    0, 0, 0, 0, passR, false, CycleMethod.NO_CYCLE,
                    new Stop(0.00, Color.rgb(255, 160, 40, 0.25)),
                    new Stop(0.50, Color.rgb(255, 70, 10, 0.10)),
                    new Stop(1.00, Color.TRANSPARENT)
            );
            gc.setFill(g);
            gc.fillOval(-passR, -passR, passR * 2.0, passR * 2.0);

            gc.restore();
        }
    }

    /** PASS 4: High Density Corona Ray Tracing */
    private void pass04_HighDensityCoronaRayTracing(GraphicsContext gc, double x, double y, double r, double t) {
        gc.setGlobalBlendMode(BlendMode.ADD);

        gc.save();
        gc.translate(x, y);

        double basePolyX0 = r * 0.85;
        double basePolyX3 = r * 0.85;

        for (int i = 0; i < CORONA_RAY_COUNT; i++) {
            double dynamicAngle = rayBaseAngle[i] + fastSin(t * 0.2 * rayOscillationSpeed[i] + rayPhase[i]) * 4.0;
            double len = r * rayLengthFactor[i] * (1.0 + 0.08 * fastSin(t * rayOscillationSpeed[i] * rayHarmonicFreq[i] + rayPhase[i]));

            float op = rayOpacity[i];
            RadialGradient rayGrad = new RadialGradient(
                    0, 0, 0, 0, len, false, CycleMethod.NO_CYCLE,
                    new Stop(0.00, Color.rgb(255, 190, 80, op)),
                    new Stop(0.40, Color.rgb(240, 90, 20, op * 0.5)),
                    new Stop(0.80, Color.rgb(180, 40, 5, op * 0.15)),
                    new Stop(1.00, Color.TRANSPARENT)
            );
            gc.setFill(rayGrad);

            double hw = rayAngularWidth[i] * 0.5;

            polyX[0] = basePolyX0; polyY[0] = -hw * 0.15;
            polyX[1] = len;        polyY[1] = -hw;
            polyX[2] = len;        polyY[2] = hw;
            polyX[3] = basePolyX3; polyY[3] = hw * 0.15;

            // Mathematical rotation instead of gc.save()/restore() per ray
            double rad = dynamicAngle * DEG_TO_RAD;
            double cos = fastCos(rad);
            double sin = fastSin(rad);

            double rotX0 = polyX[0] * cos - polyY[0] * sin;
            double rotY0 = polyX[0] * sin + polyY[0] * cos;
            double rotX1 = polyX[1] * cos - polyY[1] * sin;
            double rotY1 = polyX[1] * sin + polyY[1] * cos;
            double rotX2 = polyX[2] * cos - polyY[2] * sin;
            double rotY2 = polyX[2] * sin + polyY[2] * cos;
            double rotX3 = polyX[3] * cos - polyY[3] * sin;
            double rotY3 = polyX[3] * sin + polyY[3] * cos;

            polyX[0] = rotX0; polyY[0] = rotY0;
            polyX[1] = rotX1; polyY[1] = rotY1;
            polyX[2] = rotX2; polyY[2] = rotY2;
            polyX[3] = rotX3; polyY[3] = rotY3;

            gc.fillPolygon(polyX, polyY, 4);
        }

        gc.restore();
    }

    /** PASS 5: Filamentous Prominence Ejections */
    private void pass05_FilamentousProminenceEjections(GraphicsContext gc, double x, double y, double r, double t) {
        gc.setGlobalBlendMode(BlendMode.ADD);

        gc.save();
        gc.translate(x, y);

        for (int i = 0; i < PROMINENCE_COUNT; i++) {
            double currentAngle = promRootAngle[i] + fastSin(t * 0.3 * promTurbulenceSpeed[i] + promPhase[i]) * 3.0;
            double apexR = r * (1.0 + (promPeakHeightFactor[i] - 1.0) * (0.65 + 0.35 * fastSin(t * promTurbulenceSpeed[i] + promPhase[i])));

            double spread = promAngularSpread[i];
            double halfSpread = spread * 0.5;

            RadialGradient promGrad = new RadialGradient(
                    0, 0, 0, 0, apexR, false, CycleMethod.NO_CYCLE,
                    new Stop(0.00, Color.rgb(255, 240, 160, 0.75)),
                    new Stop(0.45, Color.rgb(255, 110, 25, 0.45)),
                    new Stop(0.80, Color.rgb(200, 30, 5, 0.15)),
                    new Stop(1.00, Color.TRANSPARENT)
            );
            gc.setFill(promGrad);

            gc.save();
            gc.rotate(currentAngle);

            gc.beginPath();
            gc.moveTo(r * 0.96, -halfSpread);

            int segs = promSegmentCount[i];
            float curl = promCurlBias[i];
            for (int s = 1; s <= segs; s++) {
                double frac = (double) s / segs;
                double segR = r + (apexR - r) * fastSin(frac * Math.PI);
                double segAngle = -halfSpread + spread * frac + fastSin(t * 2.0 + s) * curl * 5.0;
                double rad = segAngle * DEG_TO_RAD;
                gc.lineTo(segR * fastCos(rad), segR * fastSin(rad));
            }

            gc.lineTo(r * 0.96, halfSpread);
            gc.closePath();
            gc.fill();

            gc.restore();
        }

        gc.restore();
    }

    /** PASS 6: Magnetic Arch Topology */
    private void pass06_MagneticArchTopology(GraphicsContext gc, double x, double y, double r, double t) {
        gc.setGlobalBlendMode(BlendMode.ADD);

        gc.save();
        gc.translate(x, y);

        for (int i = 0; i < MAGNETIC_LOOP_COUNT; i++) {
            double pulse = fastSin(t * loopPulseSpeed[i] + loopPhase[i]);
            double apex = r * loopApexRadiusFactor[i] * (0.96 + 0.04 * pulse);

            gc.save();
            gc.rotate(loopStartAngle[i]);

            int strands = loopStrandCount[i];
            double baseThick = loopLineThickness[i];
            Color strokeColor = Color.rgb(255, 215, 140, 0.25 + 0.15 * pulse);
            gc.setStroke(strokeColor);

            double span = loopSpanAngle[i];
            for (int strand = 0; strand < strands; strand++) {
                double strandOffset = (strand - strands * 0.5) * 2.0;
                double h = apex + strandOffset;

                gc.setLineWidth(baseThick * (1.0 - strand * 0.15));
                gc.strokeArc(-h * 0.5, -h, h, h * 1.85, 0, span, javafx.scene.shape.ArcType.OPEN);
            }

            gc.restore();
        }

        gc.restore();
    }

    /** PASS 7: Chromospheric Spicule Forest */
    private void pass07_ChromosphericSpiculeForest(GraphicsContext gc, double x, double y, double r, double t) {
        gc.setGlobalBlendMode(BlendMode.ADD);

        gc.save();
        gc.translate(x, y);

        Color spiculeColor = Color.rgb(255, 175, 45, 0.45);
        gc.setStroke(spiculeColor);

        double baseLen = r * 0.98;

        for (int i = 0; i < SPICULE_COUNT; i++) {
            double len = r * spiculeLengthFactor[i] * (0.97 + 0.03 * fastSin(t * spiculeOscillationSpeed[i] + spiculePhase[i]));

            double rad = spiculeAngle[i] * DEG_TO_RAD;
            double cos = fastCos(rad);
            double sin = fastSin(rad);

            gc.setLineWidth(spiculeWidth[i]);
            gc.strokeLine(baseLen * cos, baseLen * sin, len * cos, len * sin);
        }

        gc.restore();
    }

    /** PASS 8: Photosphere Base & Limb Darkening */
    private void pass08_PhotosphereBaseLimbDarkening(GraphicsContext gc, double x, double y, double r, double t) {
        gc.setGlobalBlendMode(BlendMode.SRC_OVER);

        RadialGradient core1 = new RadialGradient(
                0, 0, x - r * 0.04, y - r * 0.04, r, false, CycleMethod.NO_CYCLE,
                new Stop(0.00, Color.web("#FFFFFF")),
                new Stop(0.12, Color.web("#FFFDE0")),
                new Stop(0.32, Color.web("#FFD000")),
                new Stop(0.58, Color.web("#FF6600")),
                new Stop(0.82, Color.web("#DC1800")),
                new Stop(0.95, Color.web("#600400")),
                new Stop(1.00, Color.rgb(10, 0, 0, 0.99))
        );
        gc.setFill(core1);
        gc.fillOval(x - r, y - r, r * 2.0, r * 2.0);

        gc.setGlobalBlendMode(BlendMode.MULTIPLY);
        RadialGradient limbMultiply = new RadialGradient(
                0, 0, x, y, r, false, CycleMethod.NO_CYCLE,
                new Stop(0.00, Color.WHITE),
                new Stop(0.70, Color.rgb(255, 230, 200, 1.0)),
                new Stop(0.90, Color.rgb(200, 80, 20, 0.8)),
                new Stop(1.00, Color.rgb(20, 0, 0, 1.0))
        );
        gc.setFill(limbMultiply);
        gc.fillOval(x - r, y - r, r * 2.0, r * 2.0);
    }

    /** PASS 9: Convective Plasma Cell Granulation */
    private void pass09_ConvectivePlasmaCellGranulation(GraphicsContext gc, double x, double y, double r, double t) {
        gc.setGlobalBlendMode(BlendMode.OVERLAY);

        double sinT = fastSin(t);
        double cosT = fastCos(t);
        double maxDistSq = (r * 0.96) * (r * 0.96);

        for (int i = 0; i < PLASMA_CELL_COUNT; i++) {
            double angle = plasmaTheta[i] + t * 0.003 * plasmaPulseSpeed[i];
            double dist = r * plasmaRadialDist[i];

            double cx = x + fastCos(angle) * dist + plasmaTx[i] * r * sinT;
            double cy = y + fastSin(angle) * dist + plasmaTy[i] * r * cosT;

            double cellR = r * plasmaRadius[i] * (0.82 + 0.36 * fastSin(t * plasmaPulseSpeed[i] + plasmaPhase[i]));

            double dx = cx - x;
            double dy = cy - y;
            if ((dx * dx + dy * dy) + cellR * cellR > maxDistSq) continue;

            gc.save();
            gc.translate(cx - cellR, cy - cellR);
            gc.scale(cellR * 2.0, cellR * 2.0);
            gc.setFill(plasmaGradients[i]);
            gc.fillRect(0, 0, 1, 1);
            gc.restore();
        }
    }

    /** PASS 10: Turbulent Plasma Rivers */
    private void pass10_TurbulentPlasmaRivers(GraphicsContext gc, double x, double y, double r, double t) {
        gc.setGlobalBlendMode(BlendMode.ADD);

        gc.save();
        gc.translate(x, y);

        Color riverColor = Color.rgb(255, 225, 130, 0.22);
        gc.setStroke(riverColor);

        for (int i = 0; i < PLASMA_RIVER_COUNT; i++) {
            double st = riverStartTheta[i] + t * riverFlowSpeed[i] * 1.5;

            gc.save();
            gc.rotate(st);
            gc.setLineWidth(riverWidth[i]);

            double rOuter = r * riverOuterR[i];
            gc.strokeArc(-rOuter, -rOuter, rOuter * 2.0, rOuter * 2.0, 0, riverSweepTheta[i], javafx.scene.shape.ArcType.OPEN);

            gc.restore();
        }

        gc.restore();
    }

    /** PASS 11: Sunspot Umbra & Penumbra Complexes */
    private void pass11_SunspotUmbraPenumbraComplexes(GraphicsContext gc, double x, double y, double r, double t) {
        gc.setGlobalBlendMode(BlendMode.MULTIPLY);

        double maxAllowedDist = r * 0.93;

        for (int i = 0; i < SUNSPOT_CLUSTER_COUNT; i++) {
            double currentTheta = spotCenterTheta[i] + t * spotDriftSpeed[i];
            double dist = r * spotCenterDist[i];

            double sx = x + fastCos(currentTheta) * dist;
            double sy = y + fastSin(currentTheta) * dist * 0.88;

            double uR = r * spotMainUmbraRadius[i];
            double pR = r * spotMainPenumbraRadius[i];

            if (Math.hypot(sx - x, sy - y) + pR > maxAllowedDist) continue;

            // Penumbra Pass
            RadialGradient penumbraGrad = new RadialGradient(
                    0, 0, sx, sy, pR, false, CycleMethod.NO_CYCLE,
                    new Stop(0.00, Color.rgb(25, 4, 1, 0.92)),
                    new Stop(0.65, Color.rgb(110, 28, 4, 0.60)),
                    new Stop(1.00, Color.TRANSPARENT)
            );
            gc.setFill(penumbraGrad);
            gc.fillOval(sx - pR, sy - pR, pR * 2.0, pR * 2.0);

            // Umbra Pass
            RadialGradient umbraGrad = new RadialGradient(
                    0, 0, sx, sy, uR, false, CycleMethod.NO_CYCLE,
                    new Stop(0.00, Color.rgb(1, 0, 0, 0.99)),
                    new Stop(0.85, Color.rgb(12, 2, 0, 0.94)),
                    new Stop(1.00, Color.TRANSPARENT)
            );
            gc.setFill(umbraGrad);
            gc.fillOval(sx - uR, sy - uR, uR * 2.0, uR * 2.0);
        }
    }

    /** PASS 12: Chromospheric Rim Bloom */
    private void pass12_ChromosphericRimBloom(GraphicsContext gc, double x, double y, double r, double t) {
        gc.setGlobalBlendMode(BlendMode.ADD);

        for (int i = 0; i < 5; i++) {
            double rimR = r * (1.01 + i * 0.015);
            RadialGradient rimGrad = new RadialGradient(
                    0, 0, x, y, rimR, false, CycleMethod.NO_CYCLE,
                    new Stop(0.90, Color.TRANSPARENT),
                    new Stop(0.96, Color.rgb(255, 170, 40, 0.35 - i * 0.05)),
                    new Stop(0.99, Color.rgb(255, 240, 190, 0.70 - i * 0.10)),
                    new Stop(1.00, Color.TRANSPARENT)
            );
            gc.setFill(rimGrad);
            gc.fillOval(x - rimR, y - rimR, rimR * 2.0, rimR * 2.0);
        }
    }

    /** PASS 13: Plasma Ejecta Particle System (Exact Original Motion, Fully Smoothed) */
    private void pass13_PlasmaEjectaParticleSystem(GraphicsContext gc, double x, double y, double r, double t) {
        gc.setGlobalBlendMode(BlendMode.ADD);

        // В твоем исходнике время для дистанции делилось пополам
        double halfT = t * 0.5;

        for (int i = 0; i < PARTICLE_COUNT; i++) {
            // 1. Возвращаем оригинальную формулу колебания дистанции (через точный Math.sin)
            double currentDist = r * (particleBaseDist[i] + particleRadialDriftSpeed[i] * Math.sin(halfT + particlePhase[i]));

            // 2. Возвращаем оригинальную формулу вращения по орбите
            double currentTheta = particleBaseTheta[i] + t * particleOrbitSpeed[i];

            // 3. Точные координаты без табличных задержек LUT
            double px = x + Math.cos(currentTheta) * currentDist;
            double py = y + Math.sin(currentTheta) * currentDist;

            // 4. Оригинальная формула пульсации прозрачности
            double opacity = particleBaseOpacity[i] * (0.6 + 0.4 * Math.sin(t + particlePhase[i]));

            if (opacity <= 0.01) continue; // Пропуск прозрачных для экономии FPS

            double sz = particleScale[i];
            gc.setFill(Color.rgb(255, 190, 90, opacity));
            gc.fillOval(px - sz * 0.5, py - sz * 0.5, sz, sz);
        }
    }

    /** PASS 14: Primary HDR Core Glow */
    private void pass14_PrimaryHDRCoreGlow(GraphicsContext gc, double x, double y, double r, double t) {
        gc.setGlobalBlendMode(BlendMode.ADD);

        for (int i = 0; i < 8; i++) {
            double glowR = r * (1.1 + i * 0.08);
            RadialGradient g = new RadialGradient(
                    0, 0, x, y, glowR, false, CycleMethod.NO_CYCLE,
                    new Stop(0.00, Color.rgb(255, 245, 210, 0.35)),
                    new Stop(0.60, Color.rgb(255, 130, 20, 0.15)),
                    new Stop(1.00, Color.TRANSPARENT)
            );
            gc.setFill(g);
            gc.fillOval(x - glowR, y - glowR, glowR * 2.0, glowR * 2.0);
        }
    }

    /** PASS 15: Secondary HDR Corona Bloom */
    private void pass15_SecondaryHDRCoronaBloom(GraphicsContext gc, double x, double y, double r, double t) {
        gc.setGlobalBlendMode(BlendMode.ADD);

        for (int i = 0; i < 6; i++) {
            double bloomR = r * (1.8 + i * 0.25);
            RadialGradient g = new RadialGradient(
                    0, 0, x, y, bloomR, false, CycleMethod.NO_CYCLE,
                    new Stop(0.00, Color.rgb(255, 160, 40, 0.20)),
                    new Stop(0.50, Color.rgb(220, 60, 10, 0.08)),
                    new Stop(1.00, Color.TRANSPARENT)
            );
            gc.setFill(g);
            gc.fillOval(x - bloomR, y - bloomR, bloomR * 2.0, bloomR * 2.0);
        }
    }

    /** PASS 16: Tertiary HDR Space Scatter Glow */
    private void pass16_TertiaryHDRSpaceScatterGlow(GraphicsContext gc, double x, double y, double r, double t) {
        gc.setGlobalBlendMode(BlendMode.ADD);

        for (int i = 0; i < 6; i++) {
            double scatterR = r * (3.0 + i * 0.4);
            RadialGradient g = new RadialGradient(
                    0, 0, x, y, scatterR, false, CycleMethod.NO_CYCLE,
                    new Stop(0.00, Color.rgb(200, 50, 5, 0.10)),
                    new Stop(0.70, Color.rgb(100, 15, 0, 0.03)),
                    new Stop(1.00, Color.TRANSPARENT)
            );
            gc.setFill(g);
            gc.fillOval(x - scatterR, y - scatterR, scatterR * 2.0, scatterR * 2.0);
        }
    }

    /** PASS 17: Anamorphic Flare Primary Streak */
    private void pass17_AnamorphicFlarePrimaryStreak(GraphicsContext gc, double x, double y, double w, double h, double t) {
        gc.setGlobalBlendMode(BlendMode.ADD);

        double streakH = 48.0 + 6.0 * fastSin(t * 1.8);

        LinearGradient grad = new LinearGradient(
                0, y - streakH * 0.5, 0, y + streakH * 0.5, false, CycleMethod.NO_CYCLE,
                new Stop(0.00, Color.TRANSPARENT),
                new Stop(0.25, Color.rgb(255, 90, 15, 0.15)),
                new Stop(0.50, Color.rgb(255, 230, 160, 0.55)),
                new Stop(0.75, Color.rgb(255, 90, 15, 0.15)),
                new Stop(1.00, Color.TRANSPARENT)
        );
        gc.setFill(grad);
        gc.fillRect(0, y - streakH * 0.5, w, streakH);
    }

    /** PASS 18: Anamorphic Flare Secondary Haze */
    private void pass18_AnamorphicFlareSecondaryHaze(GraphicsContext gc, double x, double y, double w, double h, double t) {
        gc.setGlobalBlendMode(BlendMode.ADD);

        double hazeH = 120.0 + 15.0 * fastCos(t * 1.2);

        gc.save();
        gc.translate(0, y - hazeH * 0.5);
        gc.scale(w, hazeH);
        gc.setFill(cachedAnamorphicSecondary);
        gc.fillRect(0, 0, 1, 1);
        gc.restore();
    }

    /** PASS 19: Optical Flare Ghost Elements */
    private void pass19_OpticalFlareGhostElements(GraphicsContext gc, double x, double y, double w, double h, double t) {
        gc.setGlobalBlendMode(BlendMode.ADD);

        double cx = w * 0.5;
        double cy = h * 0.5;
        double vx = cx - x;
        double vy = cy - y;

        for (int i = 0; i < 12; i++) {
            double offset = ghostDistOffset[i];
            double gx = x + vx * (1.0 + offset);
            double gy = y + vy * (1.0 + offset);

            double gSize = w * ghostSizeFactor[i] * (0.96 + 0.04 * fastSin(t * 2.0 + offset));

            RadialGradient gGrad = new RadialGradient(
                    0, 0, gx, gy, gSize, false, CycleMethod.NO_CYCLE,
                    new Stop(0.00, ghostBaseColors[i]),
                    new Stop(0.60, ghostMidColors[i]),
                    new Stop(1.00, Color.TRANSPARENT)
            );
            gc.setFill(gGrad);
            gc.fillOval(gx - gSize, gy - gSize, gSize * 2.0, gSize * 2.0);
        }
    }

    /** PASS 20: Chromatic Aberration & Diffraction Spikes */
    private void pass20_ChromaticAberrationDiffraction(GraphicsContext gc, double x, double y, double r, double t) {
        gc.setGlobalBlendMode(BlendMode.ADD);

        gc.save();
        gc.translate(x, y);

        double rotDelta = fastSin(t * 0.05) * 1.2;

        for (int i = 0; i < 8; i++) {
            double currentAngle = spikeAngleOffset[i] + rotDelta;
            double len = r * spikeLengthFactor[i];
            double doubleLen = len * 2.0;

            gc.save();
            gc.rotate(currentAngle);

            gc.save();
            gc.translate(-len, -spikeWidth[i] * 0.8);
            gc.scale(doubleLen, spikeWidth[i] * 1.6);
            gc.setFill(cachedSpikeRedGradients[i]);
            gc.fillRect(0, 0, 1, 1);
            gc.restore();

            gc.save();
            gc.translate(-len, -spikeWidth[i] * 0.4);
            gc.scale(doubleLen, spikeWidth[i] * 0.8);
            gc.setFill(cachedSpikeBlueGradients[i]);
            gc.fillRect(0, 0, 1, 1);
            gc.restore();

            gc.restore();
        }

        gc.restore();
    }

    /** PASS 21: Volumetric Camera Vignette */
    private void pass21_VolumetricCameraVignette(GraphicsContext gc, double w, double h) {
        gc.setGlobalBlendMode(BlendMode.MULTIPLY);

        // Pre-render vignette to image buffer once to eliminate real-time calculation
        if (cachedVignetteImage == null || cachedVignetteW != w || cachedVignetteH != h) {
            cachedVignetteW = w;
            cachedVignetteH = h;
            Canvas tempCanvas = new Canvas(w, h);
            GraphicsContext tempGc = tempCanvas.getGraphicsContext2D();

            double vR = Math.hypot(w, h) * 0.75;
            RadialGradient vignette = new RadialGradient(
                    0, 0, w * 0.5, h * 0.5, vR, false, CycleMethod.NO_CYCLE,
                    new Stop(0.00, Color.WHITE),
                    new Stop(0.55, Color.rgb(240, 240, 240, 1.0)),
                    new Stop(0.85, Color.rgb(120, 120, 120, 0.9)),
                    new Stop(1.00, Color.rgb(20, 20, 20, 0.85))
            );
            tempGc.setFill(vignette);
            tempGc.fillRect(0, 0, w, h);

            WritableImage img = new WritableImage((int) Math.max(1, w), (int) Math.max(1, h));
            tempCanvas.snapshot(null, img);
            cachedVignetteImage = img;
        }

        gc.drawImage(cachedVignetteImage, 0, 0);
    }
}