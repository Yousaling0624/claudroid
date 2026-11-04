# Claudroid

完整开发规范在仓库根目录 [`AGENTS.md`](../AGENTS.md)，动手前必须先阅读。

最低限度必须遵守：

- 主要改 `app/`；不要提交 `app/src/main/jniLibs/`、APK、keystore。
- 改完跑 `./gradlew testDebugUnitTest assembleDebug`。
- `targetSdk = 28` 是 PRoot 能执行的前提，不要随手升。
- 不要手动升版本号、打 tag、发 Release；CI 自动生成 `v0.1.<run_number>`。
- 不要提交或打印 API Key、签名密码。
- commit 标题使用 `Android：一句话总结`，正文用列表写具体改动。
