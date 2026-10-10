# 本地音乐 V2.1.1

- 修复播放进度条：拖动时立即更新位置并向 MediaController 发起定位，松手后再次确认目标位置。
- 增加进度条拖动时的轻微弹性动效。
- 保留 V2.1 的音乐库、自定义播放列表、公告栏、固定封面和原有动效。
- 版本号：2.1.1（versionCode 6）。

## GitHub Actions 构建

将整个项目上传到原来的 `Muzic-Player` 仓库，确保原有签名 Secrets `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` 仍然存在，然后在 Actions 运行 `Build Signed APK`。下载 `LocalMusicPlayer-V2.1.1-signed` artifact。

请继续使用同一份签名密钥，避免覆盖安装时出现签名不一致。
