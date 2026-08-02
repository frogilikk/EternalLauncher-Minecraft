package net.eternallauncher.ui.background;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.BlendMode;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;

import java.util.Random;

/**
 * AAA Cinematic Earth & Re-entry Bolide Renderer for JavaFX Canvas.
 * Generates an ultra-realistic, quiet planetary hero object with multi-layer
 * Rayleigh/Mie atmospheric scattering, high-resolution procedural terrain,
 * ocean bathymetry, complex cloud formations, and an immense, majestic deep-space meteor entry.
 */
public final class EarthRenderer {

    // --- MAP RESOLUTION & BUFFERS ---
    private static final int MAP_WIDTH = 1024;
    private static final int MAP_HEIGHT = 512;
    private static final int MAP_SIZE = MAP_WIDTH * MAP_HEIGHT;

    private final int radius;
    private final int diameter;
    private final int bufferSize;

    // --- SPHERICAL LOOKUP TABLES & SPHERE BUFFER ---
    private final double[] sphereNormalX;
    private final double[] sphereNormalY;
    private final double[] sphereNormalZ;
    private final int[] sphereMask;
    private final int[] sphereU;
    private final int[] sphereV;

    private final WritableImage sphereImage;
    private final PixelWriter pixelWriter;
    private final int[] pixelBuffer;

    // --- PROCEDURAL PLANETARY MAPS ---
    private final int[] landMap = new int[MAP_SIZE];
    private final int[] nightMap = new int[MAP_SIZE];
    private final double[] heightMap = new double[MAP_SIZE];
    private final int[] cloudMap = new int[MAP_SIZE];
    private final int[] cloudShadowMap = new int[MAP_SIZE];

    // --- ANIMATION TIMERS & ROTATION ---
    private double earthRotation = 0.0;
    private double cloudRotation = 0.0;
    private double timeAccumulator = 0.0;

    // --- CINEMATIC METEOR SYSTEM ---
    private final CinematicMeteor meteor = new CinematicMeteor();
    private static final int SPARK_COUNT = 90;
    private static final int FRAGMENT_COUNT = 14;

    // --- CACHED GRADIENTS & RENDER STATES ---
    private RadialGradient cachedVolumetricGlow;
    private RadialGradient cachedOuterAtmosphere;
    private RadialGradient cachedInnerAtmosphere;
    private RadialGradient cachedCinematicVignette;
    private double lastCenterX = Double.NaN;
    private double lastCenterY = Double.NaN;
    private double lastWidth = Double.NaN;
    private double lastHeight = Double.NaN;

    // --- PERMUTATION TABLE FOR SIMPLEX NOISE ---
    private static final int PERM_SIZE = 512;
    private static final int[] PERM = new int[PERM_SIZE];

    static {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;

        long seed = 987654321098L;
        for (int i = 255; i > 0; i--) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L);
            int j = (int) Math.abs(seed % (i + 1));
            int temp = p[i];
            p[i] = p[j];
            p[j] = temp;
        }

        for (int i = 0; i < 256; i++) {
            PERM[i] = p[i];
            PERM[256 + i] = p[i];
        }
    }

    public EarthRenderer(int diameter) {
        this.diameter = diameter;
        this.radius = diameter / 2;
        this.bufferSize = diameter * diameter;

        this.sphereImage = new WritableImage(diameter, diameter);
        this.pixelWriter = sphereImage.getPixelWriter();
        this.pixelBuffer = new int[bufferSize];

        this.sphereNormalX = new double[bufferSize];
        this.sphereNormalY = new double[bufferSize];
        this.sphereNormalZ = new double[bufferSize];
        this.sphereMask = new int[bufferSize];
        this.sphereU = new int[bufferSize];
        this.sphereV = new int[bufferSize];

        precomputeSphericalGeometry();
        generateProceduralEarthMaps();
    }

    // =========================================================================
    // 1. SPHERICAL GEOMETRY PRECOMPUTATION
    // =========================================================================

    private void precomputeSphericalGeometry() {
        for (int y = 0; y < diameter; y++) {
            double ny = (radius - y) / (double) radius;
            for (int x = 0; x < diameter; x++) {
                double nx = (x - radius) / (double) radius;
                int idx = y * diameter + x;

                double distSq = nx * nx + ny * ny;
                if (distSq <= 1.0) {
                    double nz = Math.sqrt(Math.max(0.0, 1.0 - distSq));
                    sphereNormalX[idx] = nx;
                    sphereNormalY[idx] = ny;
                    sphereNormalZ[idx] = nz;
                    sphereMask[idx] = 1;

                    double longitude = Math.atan2(nx, nz);
                    double latitude = Math.asin(Math.max(-1.0, Math.min(1.0, ny)));

                    double u = (longitude + Math.PI) / (2.0 * Math.PI);
                    double v = (Math.PI / 2.0 - latitude) / Math.PI;

                    sphereU[idx] = (int) (u * (MAP_WIDTH - 1)) & (MAP_WIDTH - 1);
                    sphereV[idx] = (int) (v * (MAP_HEIGHT - 1)) & (MAP_HEIGHT - 1);
                } else {
                    sphereMask[idx] = 0;
                }
            }
        }
    }

    // =========================================================================
    // 2. HIGH-REALISM PROCEDURAL TERRAIN & MAP GENERATION
    // =========================================================================

    private void generateProceduralEarthMaps() {
        Random rnd = new Random(314159265L);

        for (int y = 0; y < MAP_HEIGHT; y++) {
            double lat = (y / (double) MAP_HEIGHT) * Math.PI - Math.PI / 2.0;
            double absLat = Math.abs(lat) / (Math.PI / 2.0);

            for (int x = 0; x < MAP_WIDTH; x++) {
                int idx = y * MAP_WIDTH + x;
                double lon = (x / (double) MAP_WIDTH) * 2.0 * Math.PI - Math.PI;

                double nx = Math.cos(lat) * Math.cos(lon);
                double ny = Math.sin(lat);
                double nz = Math.cos(lat) * Math.sin(lon);

                // Multi-Scale Fractional Brownian Motion
                double nCont1 = fbm(nx * 1.15, ny * 1.15, nz * 1.15, 7);
                double nCont2 = fbm(nx * 3.20, ny * 3.20, nz * 3.20, 5);
                double nDetail = fbm(nx * 11.0, ny * 11.0, nz * 11.0, 3);
                double nRidge = Math.abs(fbm(nx * 7.50, ny * 7.50, nz * 7.50, 4) - 0.5) * 2.0;

                double elevation = nCont1 * 0.62 + nCont2 * 0.26 + nDetail * 0.12;
                heightMap[idx] = elevation;

                boolean isLand = elevation > 0.075;

                int r, g, b;
                if (!isLand) {
                    // Deep Ocean Bathymetry & Coastal Shelf Grading
                    double depth = Math.min(1.0, Math.max(0.0, (0.075 - elevation) * 4.2));
                    if (elevation > 0.035) {
                        // Turquoise Lagoons & Coastal Waters
                        r = (int) (10 + 15 * (1.0 - depth));
                        g = (int) (110 - 45 * depth);
                        b = (int) (155 - 40 * depth);
                    } else if (elevation > -0.16) {
                        // Continental Shelves
                        r = (int) (4 + 6 * (1.0 - depth));
                        g = (int) (30 + 35 * (1.0 - depth));
                        b = (int) (95 + 40 * (1.0 - depth));
                    } else {
                        // Abyssal Ocean Basin
                        r = (int) (2 + 3 * (1.0 - depth));
                        g = (int) (5 + 8 * (1.0 - depth));
                        b = (int) (18 + 32 * (1.0 - depth));
                    }
                } else {
                    // Realistic Biomes & Elevation Maps
                    if (absLat > 0.74 + rnd.nextDouble() * 0.03) {
                        // Polar Glacial Ice Caps
                        r = 232; g = 242; b = 252;
                    } else if (elevation > 0.44) {
                        // Alpine Peaks & Scree
                        r = (int) (205 + 35 * nRidge);
                        g = (int) (210 + 30 * nRidge);
                        b = (int) (220 + 20 * nRidge);
                    } else if (elevation > 0.29) {
                        // Mountainous Highlands
                        r = (int) (85 + 35 * nRidge);
                        g = (int) (75 + 30 * nRidge);
                        b = (int) (65 + 25 * nRidge);
                    } else if (absLat < 0.20 && elevation < 0.18) {
                        // Equatorial Rainforests
                        r = 14; g = 72; b = 26;
                    } else if (absLat > 0.26 && absLat < 0.48 && elevation < 0.20 && nCont2 > 0.38) {
                        // Arid Deserts & Plateaus
                        r = (int) (175 + 25 * nDetail);
                        g = (int) (138 + 20 * nDetail);
                        b = (int) (85 + 15 * nDetail);
                    } else if (absLat > 0.54) {
                        // Subarctic Taiga
                        r = 44; g = 74; b = 40;
                    } else {
                        // Temperate Vegetation
                        r = (int) (36 + 24 * nDetail);
                        g = (int) (100 + 32 * nDetail);
                        b = (int) (38 + 16 * nDetail);
                    }
                }

                landMap[idx] = (255 << 24) | (r << 16) | (g << 8) | b;

                // Nighttime City Networks
                if (isLand && absLat < 0.66 && elevation < 0.36) {
                    double cityNoise = fbm(nx * 16.0, ny * 16.0, nz * 16.0, 4);
                    double networkNoise = fbm(nx * 42.0, ny * 42.0, nz * 42.0, 2);

                    if (cityNoise > 0.23 || (networkNoise > 0.37 && cityNoise > 0.14)) {
                        double intensity = Math.min(1.0, (cityNoise - 0.19) * 4.8);
                        int lr = (int) (255 * intensity);
                        int lg = (int) (175 * intensity * 0.82);
                        int lb = (int) (65 * intensity * 0.45);
                        nightMap[idx] = (255 << 24) | (lr << 16) | (lg << 8) | lb;
                    } else {
                        nightMap[idx] = 0xFF000000;
                    }
                } else {
                    nightMap[idx] = 0xFF000000;
                }

                // Complex Atmospheric Cloud Systems
                double cBase = fbm(nx * 2.10 + 4.2, ny * 2.10, nz * 2.10, 6);
                double cDetail = fbm(nx * 8.50 + 10.0, ny * 8.50, nz * 8.50, 4);
                double cSwirl = Math.sin(Math.atan2(ny, nx) * 3.5 + fbm(nx * 2.8, ny * 2.8, nz * 2.8, 3) * 5.5);

                double cloudDensity = cBase * 0.64 + cDetail * 0.26 + cSwirl * 0.10;

                if (cloudDensity > 0.135) {
                    int cVal = (int) Math.min(255.0, (cloudDensity - 0.135) * 395.0);
                    cloudMap[idx] = cVal;
                    cloudShadowMap[idx] = (int) (cVal * 0.48);
                } else {
                    cloudMap[idx] = 0;
                    cloudShadowMap[idx] = 0;
                }
            }
        }
    }

    // =========================================================================
    // 3. MAIN MULTI-PASS RENDER PIPELINE
    // =========================================================================

    public void render(GraphicsContext gc, double width, double height, double deltaSeconds) {
        timeAccumulator += deltaSeconds;
        earthRotation += deltaSeconds * 0.005;
        cloudRotation += deltaSeconds * 0.009;

        // Position Earth on the left hemisphere of the frame
        double centerX = -radius * 0.15;
        double centerY = height * 0.5;

        updateGradientCaches(centerX, centerY, width, height);
        meteor.update(deltaSeconds, width, height, centerX, centerY, radius);

        // --- RENDER PASS 1: Volumetric Atmosphere & Rayleigh Scattering ---
        renderAtmosphericHalo(gc, centerX, centerY);

        // --- RENDER PASS 2: Earth Sphere Surface, Ocean Specular & Clouds ---
        renderEarthSphere(centerX, centerY);
        gc.drawImage(sphereImage, centerX - radius, centerY - radius, diameter, diameter);

        // --- RENDER PASS 3: Polar Auroras ---
        renderPolarAuroras(gc, centerX, centerY);

        // --- RENDER PASS 4: Massive Re-Entry Bolide & Volumetric Trail ---
        renderCinematicMeteor(gc, centerX, centerY);

        // --- RENDER PASS 5: Cinematic Lens Frame & Vignette ---
        renderCinematicVignette(gc, width, height);
    }

    private void updateGradientCaches(double cx, double cy, double w, double h) {
        if (cx != lastCenterX || cy != lastCenterY || w != lastWidth || h != lastHeight) {
            lastCenterX = cx;
            lastCenterY = cy;
            lastWidth = w;
            lastHeight = h;

            double innerRadius = radius * 1.042;
            cachedInnerAtmosphere = new RadialGradient(
                    0, 0, cx, cy, innerRadius, false, CycleMethod.NO_CYCLE,
                    new Stop(0.88, Color.rgb(0, 0, 0, 0.0)),
                    new Stop(0.95, Color.rgb(25, 120, 255, 0.42)),
                    new Stop(0.99, Color.rgb(115, 200, 255, 0.75)),
                    new Stop(1.00, Color.rgb(0, 60, 255, 0.0))
            );

            double outerRadius = radius * 1.18;
            cachedOuterAtmosphere = new RadialGradient(
                    0, 0, cx, cy, outerRadius, false, CycleMethod.NO_CYCLE,
                    new Stop(0.79, Color.rgb(0, 0, 0, 0.0)),
                    new Stop(0.89, Color.rgb(16, 70, 190, 0.22)),
                    new Stop(0.97, Color.rgb(30, 130, 255, 0.10)),
                    new Stop(1.00, Color.rgb(0, 0, 0, 0.0))
            );

            double volumetricRadius = radius * 1.36;
            cachedVolumetricGlow = new RadialGradient(
                    0, 0, cx, cy, volumetricRadius, false, CycleMethod.NO_CYCLE,
                    new Stop(0.68, Color.rgb(0, 0, 0, 0.0)),
                    new Stop(0.89, Color.rgb(8, 30, 105, 0.10)),
                    new Stop(1.00, Color.rgb(0, 0, 0, 0.0))
            );

            double vignetteRadius = Math.max(w, h) * 0.75;
            cachedCinematicVignette = new RadialGradient(
                    0, 0, w * 0.5, h * 0.5, vignetteRadius, false, CycleMethod.NO_CYCLE,
                    new Stop(0.0, Color.rgb(0, 0, 0, 0.0)),
                    new Stop(0.7, Color.rgb(0, 0, 0, 0.0)),
                    new Stop(1.0, Color.rgb(0, 0, 0, 0.45))
            );
        }
    }

    // =========================================================================
    // 4. ATMOSPHERE & RAYLEIGH SCATTERING RENDER PASS
    // =========================================================================

    private void renderAtmosphericHalo(GraphicsContext gc, double cx, double cy) {
        gc.save();
        gc.setGlobalBlendMode(BlendMode.ADD);

        // Volumetric Atmosphere Dispersion
        double vRad = radius * 1.36;
        gc.setFill(cachedVolumetricGlow);
        gc.fillOval(cx - vRad, cy - vRad, vRad * 2, vRad * 2);

        // Rayleigh Outer Halo
        double oRad = radius * 1.18;
        gc.setFill(cachedOuterAtmosphere);
        gc.fillOval(cx - oRad, cy - oRad, oRad * 2, oRad * 2);

        // Crisp Limb Rayleigh Boundary Line
        double iRad = radius * 1.042;
        gc.setFill(cachedInnerAtmosphere);
        gc.fillOval(cx - iRad, cy - iRad, iRad * 2, iRad * 2);

        // Meteor Atmospheric Glow Interaction (Secondary Warm Light Source)
        if (meteor.active) {
            double distToPlanet = Math.hypot(meteor.headX - cx, meteor.headY - cy);
            if (distToPlanet < radius * 1.45) {
                double intensity = (1.0 - Math.min(1.0, distToPlanet / (radius * 1.45))) * 0.45;
                RadialGradient meteorAtmoGlow = new RadialGradient(
                        0, 0, meteor.headX, meteor.headY, radius * 0.65, false, CycleMethod.NO_CYCLE,
                        new Stop(0.0, Color.rgb(255, 140, 40, intensity)),
                        new Stop(0.5, Color.rgb(255, 70, 10, intensity * 0.5)),
                        new Stop(1.0, Color.rgb(0, 0, 0, 0.0))
                );
                gc.setFill(meteorAtmoGlow);
                gc.fillOval(meteor.headX - radius * 0.65, meteor.headY - radius * 0.65, radius * 1.3, radius * 1.3);
            }
        }

        gc.restore();
    }

    // =========================================================================
    // 5. PLANETARY SHADER PASS (Zero-Allocation Render Loop)
    // =========================================================================

    private void renderEarthSphere(double cx, double cy) {
        int uOffsetEarth = (int) (earthRotation * MAP_WIDTH) & (MAP_WIDTH - 1);
        int uOffsetClouds = (int) (cloudRotation * MAP_WIDTH) & (MAP_WIDTH - 1);
        int uOffsetShadows = (uOffsetClouds - 3 + MAP_WIDTH) & (MAP_WIDTH - 1);

        // Primary Sun Lighting Direction (Sun on the right side of space)
        double sunX = 0.66;
        double sunY = -0.34;
        double sunZ = 0.67;

        for (int i = 0; i < bufferSize; i++) {
            if (sphereMask[i] == 0) {
                pixelBuffer[i] = 0x00000000;
                continue;
            }

            double nx = sphereNormalX[i];
            double ny = sphereNormalY[i];
            double nz = sphereNormalZ[i];

            double NdotL = nx * sunX + ny * sunY + nz * sunZ;

            int uE = (sphereU[i] + uOffsetEarth) & (MAP_WIDTH - 1);
            int uC = (sphereU[i] + uOffsetClouds) & (MAP_WIDTH - 1);
            int uS = (sphereU[i] + uOffsetShadows) & (MAP_WIDTH - 1);
            int v = sphereV[i];

            int mapIdxE = v * MAP_WIDTH + uE;
            int mapIdxC = v * MAP_WIDTH + uC;
            int mapIdxS = v * MAP_WIDTH + uS;

            int landColor = landMap[mapIdxE];
            int nightColor = nightMap[mapIdxE];
            int cloudVal = cloudMap[mapIdxC];
            int shadowVal = cloudShadowMap[mapIdxS];

            int rL = (landColor >> 16) & 0xFF;
            int gL = (landColor >> 8) & 0xFF;
            int bL = landColor & 0xFF;

            // Apply Cloud Shadows onto Terrain/Oceans
            if (shadowVal > 0 && cloudVal == 0) {
                double shadowFac = 1.0 - (shadowVal / 255.0) * 0.46;
                rL = (int) (rL * shadowFac);
                gL = (int) (gL * shadowFac);
                bL = (int) (bL * shadowFac);
            }

            // Ocean Specular Reflections (Sun Glint)
            if (bL > gL && NdotL > 0.0) {
                double rz = 2 * NdotL * nz - sunZ;
                if (rz > 0.0) {
                    double spec = Math.pow(rz, 36);
                    rL = (int) Math.min(255, rL + spec * 235);
                    gL = (int) Math.min(255, gL + spec * 242);
                    bL = (int) Math.min(255, bL + spec * 255);
                }
            }

            // Cloud Layer Blending
            if (cloudVal > 0) {
                double cFrac = cloudVal / 255.0;
                rL = (int) (rL * (1.0 - cFrac) + 242 * cFrac);
                gL = (int) (gL * (1.0 - cFrac) + 246 * cFrac);
                bL = (int) (bL * (1.0 - cFrac) + 255 * cFrac);
            }

            // Calculate Meteor Illumination on Atmosphere & Cloud Surfaces
            double meteorLightingR = 0;
            double meteorLightingG = 0;
            double meteorLightingB = 0;

            if (meteor.active) {
                int px = i % diameter;
                int py = i / diameter;
                double worldX = (cx - radius) + px;
                double worldY = (cy - radius) + py;

                double dx = worldX - meteor.headX;
                double dy = worldY - meteor.headY;
                double distToMeteor = Math.hypot(dx, dy);

                if (distToMeteor < radius * 0.75) {
                    double mFac = Math.pow(1.0 - (distToMeteor / (radius * 0.75)), 2.2) * 0.65;
                    meteorLightingR = 255 * mFac;
                    meteorLightingG = 120 * mFac;
                    meteorLightingB = 25 * mFac;
                }
            }

            int finalR, finalG, finalB;

            if (NdotL > 0.0) {
                // DAY SIDE: Diffuse & Rim Scattering
                double dayFactor = Math.min(1.0, NdotL * 1.36);

                double rim = 1.0 - nz;
                double rimPow = Math.pow(rim, 3.7);

                finalR = (int) Math.min(255.0, rL * dayFactor + rimPow * 28 + meteorLightingR);
                finalG = (int) Math.min(255.0, gL * dayFactor + rimPow * 118 + meteorLightingG);
                finalB = (int) Math.min(255.0, bL * dayFactor + rimPow * 255 + meteorLightingB);
            } else {
                // NIGHT SIDE: City Lights, Sunset Rim & Meteor Illumination
                double nightFactor = Math.min(1.0, -NdotL * 3.6);
                double sunsetFactor = Math.max(0.0, 1.0 - Math.abs(NdotL) * 3.7);

                int rN = (nightColor >> 16) & 0xFF;
                int gN = (nightColor >> 8) & 0xFF;
                int bN = nightColor & 0xFF;

                if (rN > 0) {
                    double flicker = 0.93 + 0.07 * Math.sin(timeAccumulator * 8.5 + uE);
                    rN = (int) (rN * flicker * nightFactor);
                    gN = (int) (gN * flicker * nightFactor);
                    bN = (int) (bN * flicker * nightFactor);
                }

                int sunsetR = (int) (sunsetFactor * 235);
                int sunsetG = (int) (sunsetFactor * 92);
                int sunsetB = (int) (sunsetFactor * 22);

                finalR = (int) Math.min(255.0, rN + sunsetR + meteorLightingR);
                finalG = (int) Math.min(255.0, gN + sunsetG + meteorLightingG);
                finalB = (int) Math.min(255.0, bN + sunsetB + meteorLightingB);
            }

            pixelBuffer[i] = (255 << 24) | (finalR << 16) | (finalG << 8) | finalB;
        }

        pixelWriter.setPixels(0, 0, diameter, diameter, PixelFormat.getIntArgbInstance(), pixelBuffer, 0, diameter);
    }

    // =========================================================================
    // 6. POLAR AURORA PASS
    // =========================================================================

    private void renderPolarAuroras(GraphicsContext gc, double cx, double cy) {
        gc.save();
        gc.setGlobalBlendMode(BlendMode.ADD);

        for (int side = -1; side <= 1; side += 2) {
            double poleY = cy + side * radius * 0.83;

            for (int i = 0; i < 70; i++) {
                double angle = (i / 70.0) * Math.PI - Math.PI / 2.0;
                double wave = Math.sin(timeAccumulator * 2.2 + i * 0.20) * 7.0;

                double poleDist = radius * 0.87 + wave;
                double ax = cx + Math.cos(angle) * poleDist * 0.44;
                double ay = poleY + Math.sin(angle) * 15.0 * side;

                double alpha = (0.16 + 0.09 * Math.sin(timeAccumulator * 3.0 + i * 0.40)) * 0.45;

                gc.setFill(Color.rgb(30, 255, 170, alpha));
                gc.fillOval(ax, ay, 12, 5);
            }
        }

        gc.restore();
    }

    // =========================================================================
    // 7. MASSIVE CINEMATIC METEOR & PLASMA TRAIL PASS
    // =========================================================================

    private void renderCinematicMeteor(GraphicsContext gc, double cx, double cy) {
        if (!meteor.active) return;

        double hX = meteor.headX;
        double hY = meteor.headY;
        double tX = meteor.tailX;
        double tY = meteor.tailY;

        gc.save();

        // 1. Ionized Outer Blue Plasma Trail Envelope
        gc.setGlobalBlendMode(BlendMode.ADD);
        LinearGradient plasmaGrad = new LinearGradient(
                hX, hY, tX, tY, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.rgb(80, 185, 255, 0.75)),
                new Stop(0.25, Color.rgb(40, 110, 255, 0.40)),
                new Stop(0.70, Color.rgb(15, 45, 180, 0.15)),
                new Stop(1.0, Color.rgb(0, 0, 0, 0.0))
        );
        gc.setStroke(plasmaGrad);
        gc.setLineWidth(32.0);
        gc.strokeLine(hX, hY, tX, tY);

        // 2. Main Incandescent Fire & Smoke Core Trail
        LinearGradient fireGrad = new LinearGradient(
                hX, hY, tX, tY, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.rgb(255, 250, 230, 0.98)),
                new Stop(0.08, Color.rgb(255, 190, 50, 0.90)),
                new Stop(0.35, Color.rgb(240, 85, 15, 0.65)),
                new Stop(0.75, Color.rgb(120, 25, 5, 0.30)),
                new Stop(1.0, Color.rgb(20, 5, 2, 0.0))
        );
        gc.setStroke(fireGrad);
        gc.setLineWidth(14.0);
        gc.strokeLine(hX, hY, tX, tY);

        // 3. Volumetric Core Plasma Bloom Glow
        RadialGradient coreGlow = new RadialGradient(
                0, 0, hX, hY, 75.0, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.rgb(255, 255, 240, 1.0)),
                new Stop(0.18, Color.rgb(255, 200, 80, 0.85)),
                new Stop(0.48, Color.rgb(255, 90, 20, 0.42)),
                new Stop(1.0, Color.rgb(0, 0, 0, 0.0))
        );
        gc.setFill(coreGlow);
        gc.fillOval(hX - 75.0, hY - 75.0, 150.0, 150.0);

        // 4. Rocky Bolide Surface Nucleus with Molten Glowing Cracks
        gc.setGlobalBlendMode(BlendMode.SRC_OVER);
        gc.setFill(Color.rgb(28, 22, 18));
        gc.fillOval(hX - 9.0, hY - 9.0, 18.0, 18.0);

        gc.setGlobalBlendMode(BlendMode.ADD);
        gc.setFill(Color.rgb(255, 215, 120, 0.92));
        gc.fillOval(hX - 5.0, hY - 5.0, 10.0, 10.0);

        // 5. Dynamic Ablation Sparks & Breakaway Fragments
        for (Spark s : meteor.sparks) {
            gc.setFill(Color.rgb(255, (int) (140 + s.life * 115), (int) (40 + s.life * 60), s.alpha));
            gc.fillOval(s.x, s.y, s.size, s.size);
        }

        for (Fragment f : meteor.fragments) {
            gc.setFill(Color.rgb(255, 220, 150, f.alpha));
            gc.fillOval(f.x, f.y, f.size, f.size);

            gc.setStroke(Color.rgb(255, 100, 20, f.alpha * 0.5));
            gc.setLineWidth(1.5);
            gc.strokeLine(f.x, f.y, f.x - meteor.dirX * 18.0, f.y - meteor.dirY * 18.0);
        }

        gc.restore();
    }

    // =========================================================================
    // 8. CINEMATIC VIGNETTE PASS
    // =========================================================================

    private void renderCinematicVignette(GraphicsContext gc, double w, double h) {
        if (cachedCinematicVignette != null) {
            gc.save();
            gc.setFill(cachedCinematicVignette);
            gc.fillRect(0, 0, w, h);
            gc.restore();
        }
    }

    // =========================================================================
    // 9. NOISE MATHEMATICS (FBM / Simplex Permutations)
    // =========================================================================

    private static double fbm(double x, double y, double z, int octaves) {
        double total = 0.0;
        double frequency = 1.0;
        double amplitude = 1.0;
        double maxValue = 0.0;

        for (int i = 0; i < octaves; i++) {
            total += noise(x * frequency, y * frequency, z * frequency) * amplitude;
            maxValue += amplitude;
            amplitude *= 0.5;
            frequency *= 2.0;
        }
        return total / maxValue;
    }

    private static double noise(double x, double y, double z) {
        int X = (int) Math.floor(x) & 255;
        int Y = (int) Math.floor(y) & 255;
        int Z = (int) Math.floor(z) & 255;

        x -= Math.floor(x);
        y -= Math.floor(y);
        z -= Math.floor(z);

        double u = fade(x);
        double v = fade(y);
        double w = fade(z);

        int A = PERM[X] + Y, AA = PERM[A] + Z, AB = PERM[A + 1] + Z;
        int B = PERM[X + 1] + Y, BA = PERM[B] + Z, BB = PERM[B + 1] + Z;

        return lerp(w, lerp(v, lerp(u, grad(PERM[AA], x, y, z),
                                grad(PERM[BA], x - 1, y, z)),
                        lerp(u, grad(PERM[AB], x, y - 1, z),
                                grad(PERM[BB], x - 1, y - 1, z))),
                lerp(v, lerp(u, grad(PERM[AA + 1], x, y, z - 1),
                                grad(PERM[BA + 1], x - 1, y, z - 1)),
                        lerp(u, grad(PERM[AB + 1], x, y - 1, z - 1),
                                grad(PERM[BB + 1], x - 1, y - 1, z - 1))));
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static double grad(int hash, double x, double y, double z) {
        int h = hash & 15;
        double u = h < 8 ? x : y;
        double v = h < 4 ? y : h == 12 || h == 14 ? x : z;
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    // =========================================================================
    // 10. INTERNAL DATA STRUCTURES (Cinematic Meteor & Particles)
    // =========================================================================

    private static final class Spark {
        double x, y, vx, vy, size, alpha, life;
    }

    private static final class Fragment {
        double x, y, vx, vy, size, alpha, life;
    }

    private static final class CinematicMeteor {
        double headX, headY;
        double tailX, tailY;
        double dirX, dirY;
        double speed = 24.0;
        double trailLength = 480.0;
        boolean active = true;

        final Spark[] sparks = new Spark[SPARK_COUNT];
        final Fragment[] fragments = new Fragment[FRAGMENT_COUNT];
        final Random rnd = new Random(42L);

        CinematicMeteor() {
            for (int i = 0; i < SPARK_COUNT; i++) sparks[i] = new Spark();
            for (int i = 0; i < FRAGMENT_COUNT; i++) fragments[i] = new Fragment();
        }

        void update(double dt, double w, double h, double planetCX, double planetCY, double planetR) {
            if (!active) return;

            // Slow, majestic cinematic trajectory towards Earth's upper atmosphere
            if (headX == 0 && headY == 0) {
                headX = w * 0.95;
                headY = h * 0.08;
            }

            double targetX = planetCX + planetR * 0.48;
            double targetY = planetCY - planetR * 0.22;

            double dx = targetX - headX;
            double dy = targetY - headY;
            double dist = Math.hypot(dx, dy);

            dirX = dx / dist;
            dirY = dy / dist;

            headX += dirX * speed * dt;
            headY += dirY * speed * dt;

            tailX = headX - dirX * trailLength;
            tailY = headY - dirY * trailLength;

            // Update Ablation Sparks
            for (Spark s : sparks) {
                if (s.life <= 0) {
                    double spread = (rnd.nextDouble() - 0.5) * 16.0;
                    double back = rnd.nextDouble() * 90.0;
                    s.x = headX - dirX * back + dirY * spread;
                    s.y = headY - dirY * back - dirX * spread;
                    s.vx = -dirX * (60 + rnd.nextDouble() * 120) + (rnd.nextDouble() - 0.5) * 30;
                    s.vy = -dirY * (60 + rnd.nextDouble() * 120) + (rnd.nextDouble() - 0.5) * 30;
                    s.size = 1.2 + rnd.nextDouble() * 2.6;
                    s.life = 0.2 + rnd.nextDouble() * 0.5;
                    s.alpha = 0.8 + rnd.nextDouble() * 0.2;
                } else {
                    s.x += s.vx * dt;
                    s.y += s.vy * dt;
                    s.life -= dt;
                    s.alpha = Math.max(0.0, s.life * 2.0);
                }
            }

            // Update Breakaway Bolide Fragments
            for (Fragment f : fragments) {
                if (f.life <= 0) {
                    double back = 10.0 + rnd.nextDouble() * 60.0;
                    f.x = headX - dirX * back;
                    f.y = headY - dirY * back;
                    f.vx = -dirX * (30 + rnd.nextDouble() * 70) + (rnd.nextDouble() - 0.5) * 15;
                    f.vy = -dirY * (30 + rnd.nextDouble() * 70) + (rnd.nextDouble() - 0.5) * 15;
                    f.size = 2.4 + rnd.nextDouble() * 3.2;
                    f.life = 0.4 + rnd.nextDouble() * 0.8;
                    f.alpha = 0.9;
                } else {
                    f.x += f.vx * dt;
                    f.y += f.vy * dt;
                    f.life -= dt;
                    f.alpha = Math.max(0.0, f.life * 1.2);
                }
            }
        }
    }
}