# 本地音乐 V2.1.3

本版本修复 V2.1.2 进度条代码中的 Kotlin 类型不匹配问题。拖动目标时间统一使用 `Long` 毫秒，Material3 Slider 的值仍使用 `Float` 比例，松手后再转换为毫秒调用播放器定位。

保留音乐库、自定义播放列表、公告栏、静止封面和已有动效。

## 构建

将完整项目上传到原来的 `Muzic-Player` 仓库，确认现有 GitHub Actions 签名 Secrets 仍在，然后运行 Actions。成功后下载 `LocalMusicPlayer-V2.1.3-signed`。
