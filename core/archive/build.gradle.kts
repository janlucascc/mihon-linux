plugins {
    kotlin("jvm")
}

dependencies {
    implementation("org.jsoup:jsoup:1.18.1")
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

