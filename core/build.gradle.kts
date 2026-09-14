plugins {
    alias(libs.plugins.kotlin)
}

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    testImplementation(libs.junit)
}
