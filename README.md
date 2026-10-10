# 本地音乐 V2.1

## 更新内容
- 修复播放进度条拖动时被播放进度刷新覆盖的问题，松手后调用 Media3 seekTo。
- 播放页封面保持静止，不再倾斜旋转。
- 新增底部 Dock：音乐、播放列表、公告栏。
- 支持创建、删除播放列表，向列表添加本地歌曲，并播放列表中的歌曲。
- 播放列表保存于本地应用偏好设置。
- 保留现有播放页转场、歌曲脉冲、按钮状态动画和歌词自动滚动。

## 构建
上传项目到 GitHub 后运行 Actions。需要仓库中配置 KEYSTORE_BASE64、KEYSTORE_PASSWORD、KEY_ALIAS、KEY_PASSWORD 四个 Actions Secrets。
