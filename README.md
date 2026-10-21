# 本地音乐 V3.0.1

本版本修复 V3.0 的 Kotlin 编译错误。

- 修正 `animateColorAsState` 的 Compose 包导入。
- 使用局部不可变变量读取协程委托状态中的封面 Bitmap，避免 smart cast 编译失败。
- 应用包名保持 `com.yunwu.localmusic`。
- 版本号为 3.0.1，versionCode 为 10。
- GitHub Actions 使用原有 `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` Secrets 签名 Release APK。

构建产物：`LocalMusicPlayer-V3.0.1-signed`。
