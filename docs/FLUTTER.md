# Flutter 前端（可选模块）

App 目前仍是 Compose 前端。`flutter/` 是一个 **add-to-app 模块**，默认不参与构建：
`settings.gradle.kts` 只在传入 `-PflutterUi=true` 时才 `include(":flutter")`，所以普通
`./gradlew assembleDebug` 的行为、产物和耗时与引入 Flutter 之前完全一致，CI 也不需要装 Flutter SDK。

等 Dart 实现追平功能后再把它切成默认前端，届时删掉这个 flag 即可。

## 目录

```
flutter/
├── pubspec.yaml          # package claudroid_ui；module: 段标记 add-to-app
├── .metadata             # project_type: module（pub get 生成 .android/ 的触发条件）
├── lib/main.dart         # 入口，当前是 prism_ui 占位页
├── test/smoke_test.dart
└── .android/             # flutter pub get 生成，已 gitignore
```

`.android/` 是生成物，不进版本库；`pubspec.lock` **要**提交。

## 构建

```bash
# 1. 生成 flutter/.android/（每次改 pubspec 后都要跑）
cd flutter && flutter pub get

# 2. 让 Gradle 找到 SDK：local.properties 里加一行
flutter.sdk=C\:\\dev-toolchain\\flutter
#    或者导出 FLUTTER_ROOT

# 3. 带 flag 构建
./gradlew assembleDebug -PflutterUi=true -Pandroid.newDsl=false
```

产物里会多出 `lib/*/libflutter.so` 与 `assets/flutter_assets/`，其余部分不变。

### 为什么必须 `-Pandroid.newDsl=false`

Flutter 的 Gradle 插件（`FlutterPluginUtils.getLegacyAndroidExtension`）用
`project.extensions.findByType(BaseExtension::class.java)!!` 取 AGP 的旧扩展，
`forceNdkDownload` 会走到那里；AGP 9 的新 DSL 不再注册 `BaseExtension`，`!!` 直接抛
NPE。**这个 flag 只能在命令行传**：AGP 在 settings 脚本执行之前就读它，所以在
`settings.gradle.kts` 里改 `gradle.startParameter` 无效（已实测）。

不要把它写进 `gradle.properties`——那会让 Compose 的默认构建也退回旧 DSL。等 Flutter
插件支持新 DSL 后，删掉这个 flag 即可。

### 为什么必须 apply `module_plugin_loader.gradle`

`settings.gradle.kts` 里那句 `apply(from = "$flutterSdk/.../module_plugin_loader.gradle")`
不是可选的。Flutter 插件在 `:app` 的 `afterEvaluate` 里注册各 variant 的任务；没有这个
loader，`:app` 会在 `:flutter` 配置之前就求值完成，于是抛出
`Cannot run Project.afterEvaluate(Action) when the project is already evaluated`。
loader 会对除 `:flutter` 以外的所有 project 设置 `evaluationDependsOn(':flutter')`，
同时 include 模块的 native 插件。

### 仓库冲突

Flutter 插件在配置期执行 `rootProject.allprojects { repositories.maven { ... } }` 拉引擎
产物（`io.flutter:flutter_embedding_*`、`libflutter.so`），与
`FAIL_ON_PROJECT_REPOS` 冲突。`settings.gradle.kts` 的解法是：flag 打开时在
`dependencyResolutionManagement` 里声明 `https://storage.googleapis.com/download.flutter.io`，
并把 mode 切成 `PREFER_SETTINGS`。构建会打三条 "prefer settings repositories" 警告，属预期。

## 依赖

| 包 | 用途 |
| --- | --- |
| `prism_ui` 0.1.2 | FlClash 的视觉语言：液态玻璃、glyph 图标、`generateSection` 列表分组 |

`prism_ui` 是 GPL v3（chen08209），只在可选前端里用；见 `THIRD_PARTY_NOTICES.md`。

`flutter/lib/main.dart` 只用 `package:flutter/widgets.dart`，**不要 import Material**：
`prism_ui` 自带 `AppBar` / `ThemeMode`，同时引入会产生 `ambiguous_import`。

## 与 Kotlin 侧的契约

桥接协议冻结在 [`BRIDGE_CONTRACT.md`](BRIDGE_CONTRACT.md)。Kotlin 侧的平台通道实现放在
`app/src/main/flutter/java/`（只在 `flutterUi` 打开时进 sourceSet），所以默认构建的
classpath 里不会出现 Flutter embedding。
