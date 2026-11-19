import java.util.Properties

// The Flutter UI is the app's only frontend, so the add-to-app module is always
// included. Gradle therefore needs the Flutter SDK: `flutter.sdk` in local.properties,
// or FLUTTER_ROOT in the environment.
// (pluginManagement is compiled apart from the script body, so each block below
// reads local.properties itself.)

fun flutterSdkPath(): String? = runCatching {
    val properties = Properties()
    val file = file("local.properties")
    if (file.isFile) file.inputStream().use { properties.load(it) }
    properties.getProperty("flutter.sdk")
}.getOrNull() ?: System.getenv("FLUTTER_ROOT")

pluginManagement {
    // pluginManagement is compiled apart from the script body, so it reads
    // local.properties itself instead of calling flutterSdkPath().
    val flutterSdk = runCatching {
        val properties = java.util.Properties()
        val file = file("local.properties")
        if (file.isFile) file.inputStream().use { properties.load(it) }
        properties.getProperty("flutter.sdk")
    }.getOrNull() ?: System.getenv("FLUTTER_ROOT")

    // dev.flutter.flutter-gradle-plugin is not published anywhere; it ships inside
    // the SDK and is consumed as a composite build.
    if (flutterSdk != null) {
        includeBuild("$flutterSdk/packages/flutter_tools/gradle")
    }

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Engine artifacts (io.flutter:flutter_embedding_*, libflutter.so).
        // Flutter's plugin adds this to every project at configuration time,
        // which FAIL_ON_PROJECT_REPOS rejects; declaring it here and relaxing
        // the mode is the supported route.
        maven { url = uri("https://storage.googleapis.com/download.flutter.io") }
    }
    // Build prints three "prefer settings repositories" warnings; expected.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
}

rootProject.name = "Claudroid"
include(":app")

// ---------------------------------------------------------------------------
// Flutter UI module
// ---------------------------------------------------------------------------
val flutterSdk = flutterSdkPath()
check(flutterSdk != null) {
    "Flutter SDK required: set flutter.sdk in local.properties or FLUTTER_ROOT."
}

// `flutter pub get` generates flutter/.android/; it is gitignored build output,
// so its presence is the signal that the module is ready to be included.
val flutterModuleDir = file("flutter/.android/Flutter")
check(flutterModuleDir.isDirectory) {
    "Flutter module is not generated yet: run `flutter pub get` in flutter/ " +
        "(expected ${flutterModuleDir.path})."
}
include(":flutter")
project(":flutter").projectDir = flutterModuleDir

// Flutter's own settings hook: includes the module's native plugins and forces
// every other project to evaluate after :flutter (the Flutter plugin registers
// its per-variant tasks from inside :app's afterEvaluate, so :app must not
// already be evaluated when :flutter is configured).
apply(from = "$flutterSdk/packages/flutter_tools/gradle/module_plugin_loader.gradle")
