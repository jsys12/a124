plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.okhttp)
    testImplementation(libs.junit)
}

tasks.test {
    // Real filter lists for the (optional) large-corpus test: -PlistsDir=/path/to/lists
    project.findProperty("listsDir")?.let { systemProperty("listsDir", it.toString()) }
    project.findProperty("skipLists")?.let { systemProperty("skipLists", it.toString()) }
    maxHeapSize = "2g"
    testLogging {
        events("failed", "passed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
