plugins {
    java
    application
}

group = "dev.leo"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "dev.leo.vaultpredictor.OminousVaultPredictor"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.register<JavaExec>("runGui") {
    group = "application"
    description = "Launches the Swing GUI."
    mainClass.set("dev.leo.vaultpredictor.VaultPredictorGui")
    classpath = sourceSets["main"].runtimeClasspath
}


// Double-clickable jar: launch the Swing GUI (CLI stays available via gradlew run).
tasks.jar {
    manifest {
        attributes("Main-Class" to "dev.leo.vaultpredictor.VaultPredictorGui")
    }
}
