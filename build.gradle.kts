plugins {
    java
    application
    // Добавляем официальный плагин JavaFX
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "net.eternallauncher"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// Настраиваем модули JavaFX
javafx {
    version = "21.0.2"
    modules("javafx.controls", "javafx.fxml")
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    // Точка входа для запуска GUI
    mainClass.set("net.eternallauncher.LauncherApp")
}