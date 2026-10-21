# 本地音乐 V3.1

## 本次更新
- 重绘音乐首页为 iPod Classic 风格：银色机身、LCD 歌曲菜单、蓝色选中条和可操作点击轮（MENU、上一首、下一首、播放/暂停、SELECT）。
- 全屏歌词滚动改为仅在当前行接近可视区域边缘时才滚动，避免每切换一句歌词就重启滚动动画。
- 全屏歌词移除逐行缩放动画，保留轻量颜色过渡与透明度层次；歌词列表增加稳定 key/contentType，降低重组与布局工作。
- 同步优化播放页小歌词面板的滚动策略。
- 保留本地音乐扫描、播放列表、公告栏、导入歌词/封面及原有播放控制。

## 版本与签名
- applicationId：`com.yunwu.localmusic`（保持不变）
- versionName：`3.1`
- versionCode：`11`
- GitHub Actions 使用原有 `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` Secrets。

构建产物：`LocalMusicPlayer-V3.1-signed`。

> 本项目 ZIP 的完整性会在打包后检查；请在 GitHub Actions 中实际构建，并在设备上检查点击轮与全屏歌词性能。
