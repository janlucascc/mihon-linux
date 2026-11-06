plugins {
    kotlin("jvm")
    id("dev.zacsweers.metro")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21"
}

dependencies {
    implementation("dev.zacsweers.metro:runtime:1.5.2")
    implementation("dev.icerock.moko:resources:0.26.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("io.reactivex.rxjava3:rxjava:3.1.8")
    implementation(project(":i18n"))
    implementation(project(":core:metro"))
}

kotlin {
    jvmToolchain(21)
}
