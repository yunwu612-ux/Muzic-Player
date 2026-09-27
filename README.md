# 本地音乐 V1.0

独立 Android 本地音乐播放器，目标是简单、稳定、低打扰。

## V1.0
- 扫描本机音乐库
- 搜索歌曲 / 歌手 / 专辑
- 播放、暂停、上一首、下一首
- 后台播放
- 锁屏 / 系统媒体控制
- 耳机媒体按键控制
- Media3 MediaSession
- HyperOS 媒体通知 / 超级岛适配路径

## HyperOS 3
本项目使用 Android 标准 Media3 MediaSessionService 与系统媒体通知。根据小米澎湃OS开发者平台的媒体通知适配说明，音乐类 App 接入系统媒体通知后可自动进入超级岛展示。

## 构建
GitHub Actions 使用 Gradle 8.11.1 + JDK 17 构建 Debug APK。
