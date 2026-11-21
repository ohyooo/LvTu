plugins {
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.agp) apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
