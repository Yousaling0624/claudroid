import java.util.Properties

// Flutter is an optional add-to-app module, enabled with -PflutterUi=true. A plain
// build therefore stays exactly the Compose app and needs no Flutter SDK installed.
// (pluginManagement is compiled apart from the script body, so each block below
// reads the flag and local.properties itself.)

pluginManagement {
    val flutterUi = gradle.startParameter.projectProperties["flutterUi"] == "true"
    val flutterSdk = runCatching {
        val properties = java.util.Properties()
        val file = file("local.properties")
        if (file.isFile) file.inputStream().use { properties.load(it) }
        properties.getProperty("flutter.sdk")
    }.getOrNull() ?: System.getenv("FLUTTER_ROOT")

    // dev.flutter.flutter-gradle-plugin is not published anywhere; it ships inside
    // the SDK and is consumed as a composite build.
    if (flutterUi && flutterSdk != null) {
        includeBuild("$flutterSdk/packages/flutter_tools/gradle")
    }

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    val flutterUi = gradle.startParameter.projectProperties["flutterUi"] == "true"

    repositories {
        google()
        mavenCentral()
        if (flutterUi) {
            // Engine artifacts (io.flutter:flutter_embedding_*, libflutter.so).
            // Flutter's plugin adds this to every project at configuration time,
            // which FAIL_ON_PROJECT_REPOS rejects; declaring it here and relaxing
            // the mode for this build is the supported route.
            maven { url = uri("https://storage.googleapis.com/download.flutter.io") }
        }
    }
    repositoriesMode.set(
        if (flutterUi) RepositoriesMode.PREFER_SETTINGS else RepositoriesMode.FAIL_ON_PROJECT_REPOS
    )
}

rootProject.name = "Claudroid"
include(":app")

// ---------------------------------------------------------------------------
// Optional Flutter module
// ---------------------------------------------------------------------------
val flutterUi = gradle.startParameter.projectProperties["flutterUi"] == "true"

if (flutterUi) {
    val flutterSdk = runCatching {
        val properties = Properties()
        val file = file("local.properties")
        if (file.isFile) file.inputStream().use { properties.load(it) }
        properties.getProperty("flutter.sdk")
    }.getOrNull() ?: System.getenv("FLUTTER_ROOT")

    check(flutterSdk != null) {
        "-PflutterUi=true needs flutter.sdk in local.properties or FLUTTER_ROOT set."
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
}
