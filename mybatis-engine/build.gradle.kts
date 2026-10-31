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
    implementation(project(":core"))
    implementation("org.mybatis:mybatis:3.6.0")
    implementation(kotlin("stdlib"))

    testImplementation(libs.junit)
}
