plugins {
    kotlin("jvm")
    id("application")
}

application {
    mainClass.set("mihon.desktop.MainKt")
    applicationDefaultJvmArgs = listOf(
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")
    implementation("org.jetbrains.exposed:exposed-core:0.48.0")
    implementation("com.formdev:flatlaf:3.5.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.12.0")
    implementation("com.twelvemonkeys.imageio:imageio-jpeg:3.12.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
