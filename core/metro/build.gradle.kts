plugins {
    kotlin("jvm")
    id("dev.zacsweers.metro")
}

dependencies {
    implementation("dev.zacsweers.metro:runtime:1.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

kotlin {
    jvmToolchain(21)
}
