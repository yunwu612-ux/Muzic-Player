# 本地音乐 V2.1.4

本版本修复 V2.1.3 中剩余的 Kotlin 类型错误：进度状态和目标时间均使用 `Long` 毫秒，Material3 Slider 使用 `Float` 比例值。拖动时按歌曲时长换算，松开后再请求播放器跳转。

保留音乐库、自定义播放列表、公告栏、封面静止显示及原有动效。

将完整项目上传到原来的 `Muzic-Player` 仓库，保留已有签名 Secrets，运行 GitHub Actions。成功后下载 `LocalMusicPlayer-V2.1.4-signed`。

说明：请以 GitHub Actions 构建结果为准；本 ZIP 尚未在本地完成 Gradle 编译或真机测试。
