plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21"
}

dependencies {
    implementation(project(":core:common"))
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.reactivex.rxjava3:rxjava:3.1.8")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

kotlin {
}
