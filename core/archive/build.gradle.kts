plugins {
    kotlin("jvm")
}

dependencies {
    implementation("org.jsoup:jsoup:1.18.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

