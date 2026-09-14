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
    implementation("org.mybatis:mybatis:3.5.19")
    implementation(kotlin("stdlib"))

    testImplementation(libs.junit)
}
