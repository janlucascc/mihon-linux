plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("application")
}

application {
    mainClass.set("mihon.desktop.MainKt")
    applicationDefaultJvmArgs = listOf(
        "-Xms128m",
        "-Xmx768m",
        "-XX:+UseG1GC",
        "-XX:+UseStringDeduplication",
        "-XX:G1PeriodicGCInterval=3000",
        "-XX:+ExplicitGCInvokesConcurrent",
        "-XX:MaxGCPauseMillis=20",
        "-Dsun.java2d.opengl=true",
        "-Dawt.useSystemAAFontSettings=on",
        "-Dswing.aatext=true",
    )
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":source-api"))
    implementation(project(":data"))
    implementation(project(":core:common"))
    implementation(project(":core:archive"))
    implementation(project(":i18n"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")
    implementation("org.jetbrains.exposed:exposed-core:0.48.0")
    implementation("com.formdev:flatlaf:3.5.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.12.0")
    implementation("com.twelvemonkeys.imageio:imageio-jpeg:3.12.0")
    implementation("net.java.dev.jna:jna:5.14.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
