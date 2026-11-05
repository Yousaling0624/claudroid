import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// CI passes these in. Local builds derive the version from the latest release tag so
// "关于" never shows a checked-in number that does not exist anywhere on GitHub.
fun gitOutput(vararg args: String): String? = runCatching {
    val process = ProcessBuilder(listOf("git", *args))
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    if (process.waitFor() == 0) output.ifBlank { null } else null
}.getOrNull()

fun gitDir(): File? {
    val dotGit = File(rootProject.projectDir, ".git")
    if (dotGit.isDirectory) return dotGit
    if (!dotGit.isFile) return null
    // Worktrees/submodules point at the real git dir from a `.git` file.
    val line = runCatching {
        dotGit.readLines().firstOrNull { it.startsWith("gitdir:") }
    }.getOrNull() ?: return null
    val path = line.removePrefix("gitdir:").trim()
    val dir = File(path)
    return if (dir.isAbsolute) dir else File(rootProject.projectDir, path)
}

fun packedRefs(gitDir: File): Map<String, String> = runCatching {
    val file = File(gitDir, "packed-refs")
    if (!file.isFile) return emptyMap()
    val refs = mutableMapOf<String, String>()
    var previous: String? = null
    file.readLines().forEach { line ->
        when {
            line.isBlank() || line.startsWith("#") -> Unit
            line.startsWith("^") -> {
                previous?.let { refs[it] = line.substring(1).trim() }
                previous = null
            }
            else -> {
                val parts = line.split(' ', limit = 2)
                if (parts.size == 2) {
                    refs[parts[1]] = parts[0]
                    previous = parts[1]
                } else {
                    previous = null
                }
            }
        }
    }
    refs
}.getOrDefault(emptyMap())

/**
 * This project is sometimes built by Windows Gradle against a WSL checkout, where the
 * git CLI exists only on the Linux side. Reading refs directly still names the newest
 * release; without the history we cannot count commits, so call it "-dev".
 */
fun refFallbackVersion(): String? {
    val dir = gitDir() ?: return null
    val tags = mutableSetOf<String>()
    File(dir, "refs/tags").walkTopDown()
        .filter { it.isFile }
        .forEach { file ->
            tags += file.relativeTo(File(dir, "refs/tags")).invariantSeparatorsPath
        }
    packedRefs(dir).keys.mapTo(tags) { it.removePrefix("refs/tags/") }
    return tags
        .filter { it.matches(Regex("v0\\.1\\.\\d+")) }
        .maxByOrNull { it.substringAfterLast('.').toIntOrNull() ?: 0 }
        ?.let { "${it.removePrefix("v")}-dev" }
}

fun localVersionName(): String {
    // Tags are not fetched in every checkout; "-dev" keeps the About screen honest.
    val tag = gitOutput("describe", "--tags", "--abbrev=0", "--match", "v0.1.*")
        ?.removePrefix("v")
        ?: return refFallbackVersion() ?: "0.1.0-dev"
    val ahead = gitOutput("rev-list", "--count", "$tag..HEAD")?.toIntOrNull() ?: 0
    val base = if (ahead > 0) "$tag-dev.$ahead" else tag
    // An uncommitted tree is not any published release; "-dirty" says so in 关于.
    return if (gitOutput("status", "--porcelain") != null) "$base-dirty" else base
}

val appVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 5
val appVersionName = (project.findProperty("versionName") as String?) ?: localVersionName()

// A release keystore is optional. Without one we sign the release APK with the debug
// key so `assembleRelease` still produces something installable (sideload project,
// not a Play submission).
val envKeystore = System.getenv("CLAUDROID_KEYSTORE") ?: (project.findProperty("keystorePath") as String?)
val hasEnvKeystore = envKeystore != null && File(envKeystore).isFile

android {
    namespace = "app.claudroid"
    // AGP 9 splits the platform minor version out of compileSdk; the installed
    // package is "platforms;android-37.0", so the default (android-37) would miss.
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "app.claudroid"
        minSdk = 26
        // Deliberate: Android 10+ forbids exec() of files in an app's data directory
        // for targetSdk >= 29. Proot and the embedded CLI both need executable files
        // in filesDir, exactly like Termux (targetSdk 28). This is not a Play build.
        targetSdk = 28
        versionCode = appVersionCode
        versionName = appVersionName
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    androidResources {
        // AGP 9 replaces defaultConfig.resourceConfigurations with locale filters.
        localeFilters.addAll(listOf("zh", "en"))
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

// AGP 9's built-in Kotlin exposes the standard `kotlin` extension; the old
// android.kotlinOptions block no longer exists.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// ---------------------------------------------------------------------------
// Proot runtime (Termux proot + loader + libtalloc + libandroid-shmem)
//
// Fetched at build time rather than committed: the files are third-party GPL
// binaries, and .gitignore keeps them out of the tree. The script is idempotent;
// pass -PskipProot to build a root/chroot-only APK offline.
// ---------------------------------------------------------------------------
val fetchProot = tasks.register<Exec>("fetchProot") {
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

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Async image decoding with a memory/disk cache: thumbnails and the zoomable
    // preview would otherwise each decode a full bitmap on the main-thread budget.
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Frosted top bars / sheets: a real blur pass instead of a flat translucent fill.
    // 2.1.0 glass is the reason this project runs AGP 9 / compileSdk 37 / Compose 1.12;
    // do not downgrade without also downgrading the UI to the Haze 1.x API.
    implementation("dev.chrisbanes.haze:haze:2.1.0")
    implementation("dev.chrisbanes.haze:haze-blur:2.1.0")
    implementation("dev.chrisbanes.haze:haze-glass:2.1.0")

    // Organic corner morphs for the loading/brand moment; 1.1.0 is built on Kotlin 2.0.21.
    implementation("androidx.graphics:graphics-shapes:1.1.0")

    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
