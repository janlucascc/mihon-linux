plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21"
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":core:common"))
    implementation(project(":i18n"))
    implementation(project(":source-api"))
    implementation("org.xerial:sqlite-jdbc:3.45.0.0")
    implementation("org.jetbrains.exposed:exposed-core:0.48.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.48.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.48.0")
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
