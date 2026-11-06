plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    id("dev.zacsweers.metro") version "1.5.2" apply false
    id("org.jetbrains.compose") version "1.7.0" apply false
}

allprojects {
    group = "com.mihon"
    version = "0.1.0"
}
