plugins {
    kotlin("jvm") version "2.0.21"
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
}

tasks.test {
    useJUnitPlatform()
    // The env-gated real-bracket DoF regression holds 10 full-res frames + their Laplacian
    // pyramids + weight masks at once (the design doc pins DoF as the peak-memory mode);
    // Gradle's default 512 MB test-worker heap OOMs there. 4 GB matches a dev machine/CI box.
    maxHeapSize = "4g"
}
