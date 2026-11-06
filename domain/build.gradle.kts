plugins {
    kotlin("jvm")
    id("dev.zacsweers.metro")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21"
}

dependencies {
    implementation(project(":source-api"))
    implementation(project(":core:common"))
    implementation(project(":core:metro"))
    implementation(project(":core:archive"))
    implementation(project(":i18n"))
    implementation("dev.zacsweers.metro:runtime:1.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
