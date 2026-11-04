import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// CI passes these in; local builds keep the checked-in baseline.
val appVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 5
val appVersionName = (project.findProperty("versionName") as String?) ?: "1.1.0"

// A release keystore is optional. Without one we sign the release APK with the debug
// key so `assembleRelease` still produces something installable (sideload project,
// not a Play submission).
val envKeystore = System.getenv("CLAUDROID_KEYSTORE") ?: (project.findProperty("keystorePath") as String?)
val hasEnvKeystore = envKeystore != null && File(envKeystore).isFile

android {
    namespace = "app.claudroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.claudroid"
        minSdk = 26
        // Deliberate: Android 10+ forbids exec() of files in an app's data directory
        // for targetSdk >= 29. Proot and the embedded CLI both need executable files
        // in filesDir, exactly like Termux (targetSdk 28). This is not a Play build.
        targetSdk = 28
        versionCode = appVersionCode
        versionName = appVersionName
        resourceConfigurations += listOf("zh", "en")
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (hasEnvKeystore) {
            create("env") {
                storeFile = File(envKeystore!!)
                storePassword = System.getenv("CLAUDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CLAUDROID_KEY_ALIAS")
                keyPassword = System.getenv("CLAUDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("env") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        // Proot is shipped as libproot.so etc. and must exist as a real file in
        // nativeLibraryDir; uncompressed-in-APK packaging would hide it.
        jniLibs.useLegacyPackaging = true
    }

    lint {
        // Intentional, see targetSdk above (same suppression Termux/DSH-style apps use).
        disable += "ExpiredTargetSdkVersion"
        abortOnError = false
        // lintVital pulls another lint-gradle artifact at release time; it adds nothing
        // here because the targetSdk warning is deliberate. Run `lintDebug` explicitly.
        checkReleaseBuilds = false
    }
}

// ---------------------------------------------------------------------------
// Proot runtime (Termux proot + loader + libtalloc + libandroid-shmem)
//
// Fetched at build time rather than committed: the files are third-party GPL
// binaries, and .gitignore keeps them out of the tree. The script is idempotent;
// pass -PskipProot to build a root/chroot-only APK offline.
// ---------------------------------------------------------------------------
val fetchProot by tasks.registering(Exec::class) {
    group = "build"
    description = "Fetch Termux proot runtime binaries into app/src/main/jniLibs"
    onlyIf { !project.hasProperty("skipProot") }
    workingDir = rootProject.projectDir
    val python = if (System.getProperty("os.name").lowercase().contains("win")) "python" else "python3"
    commandLine(python, rootProject.file("tools/fetch-proot.py").absolutePath, "--all")
}

tasks.named("preBuild").configure {
    dependsOn(fetchProot)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
