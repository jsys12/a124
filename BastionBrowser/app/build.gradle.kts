import java.net.HttpURLConnection
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Downloads the filter lists marked `"bundle": true` in filter_catalog.json and stores them as assets, so protection works on first launch without network. A failed download only logs a
 * warning: the app still builds and fetches the list on first update.
 */
abstract class DownloadFilterListsTask : DefaultTask() {
    @get:InputFile
    abstract val catalog: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        @Suppress("UNCHECKED_CAST")
        val lists = groovy.json.JsonSlurper().parse(catalog.get().asFile) as List<Map<String, Any>>
        val dir = outputDir.get().asFile.resolve("filters")
        dir.mkdirs()
        for (entry in lists) {
            if (entry["bundle"] != true) continue
            val id = entry["id"] as String
            val url = entry["url"] as String
            // Stored uncompressed: the APK zip compresses assets (and AAPT would unpack .gz anyway).
            val target = dir.resolve("$id.txt")
            var lastError: Exception? = null
            for (attempt in 1..3) {
                try {
                    val conn = URI(url).toURL().openConnection() as HttpURLConnection
                    conn.connectTimeout = 30_000
                    conn.readTimeout = 60_000
                    conn.setRequestProperty("User-Agent", "BastionBrowser-build")
                    val bytes = conn.inputStream.use { it.readBytes() }
                    if (conn.responseCode != 200 || bytes.size < 512) error("HTTP ${conn.responseCode}, ${bytes.size} bytes")
                    target.writeBytes(bytes)
                    logger.lifecycle("Bundled filter list $id: ${bytes.size / 1024} KB")
                    lastError = null
                    break
                } catch (e: Exception) {
                    lastError = e
                    Thread.sleep(2000L * attempt)
                }
            }
            if (lastError != null) logger.warn("Could not bundle filter list $id ($url): ${lastError.message}")
        }
    }
}

val downloadFilterLists = tasks.register<DownloadFilterListsTask>("downloadFilterLists") {
    catalog.set(layout.projectDirectory.file("src/main/assets/filter_catalog.json"))
    outputDir.set(layout.buildDirectory.dir("generated/filterAssets"))
    onlyIf { !project.hasProperty("skipFilterDownload") }
}

android {
    namespace = "io.github.jsys12.bastion"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.jsys12.bastion"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        // Release signing from the environment (CI secrets); falls back to the debug key.
        val storeFilePath = System.getenv("BASTION_KEYSTORE")
        if (storeFilePath != null && file(storeFilePath).exists()) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("BASTION_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BASTION_KEY_ALIAS")
                keyPassword = System.getenv("BASTION_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/previous-compilation-data.bin")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.maxHeapSize = "3g"
            // Optional Maven mirror for Robolectric's android-all jars: -ProbolectricRepo=https://...
            project.findProperty("robolectricRepo")?.let { test.systemProperty("robolectric.dependency.repo.url", it.toString()) }
            project.findProperty("screenshotDir")?.let { test.systemProperty("screenshotDir", it.toString()) }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(downloadFilterLists, DownloadFilterListsTask::outputDir)
    }
}

dependencies {
    implementation(project(":adblock"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
