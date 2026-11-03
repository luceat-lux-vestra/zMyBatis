import org.gradle.api.tasks.testing.Test

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


tasks.withType<Test>().configureEach {
    addTestOutputListener { _, event ->
        event.message
            .lineSequence()
            .filter { it.startsWith("ZMYBATIS_PREPARATION_SCALE ") }
            .forEach { logger.lifecycle(it) }
    }
}
