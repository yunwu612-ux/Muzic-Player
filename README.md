# 本地音乐 V3.0

## V3.0 更新
- 全新 iPod Classic 风格界面：金属灰外框、LCD 屏幕质感、蓝色选中态与经典音乐菜单视觉。
- 歌词高亮增加平滑缩放与颜色过渡。
- 新增全屏歌词模式，自动跟随当前播放行，可随时退出。
- 保留本地音乐扫描、播放控制、导入 LRC/TXT 歌词、导入封面、自定义播放列表与公告栏。
- 优化动效节奏，避免用持续旋转封面等高频动画。
- 固定 applicationId 为 `com.yunwu.localmusic`，versionCode 增至 9。

## 构建与升级
GitHub Actions 会使用仓库 Secrets `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` 签署 release APK，产物名为 `LocalMusicPlayer-V3.0-signed`。

**升级要求：**Android 更新必须同时满足 applicationId 相同、签名证书相同、versionCode 不低于已安装版本。请安装 `LocalMusicPlayer-V3.0-signed`，不要把 `assembleDebug` 生成的调试 APK 当作正式更新包。如果旧安装包是用不同密钥签名的，Android 无法无损覆盖升级；这种情况下首次迁移可能需要卸载旧包，卸载前请先备份。之后持续使用同一 release 密钥即可正常覆盖更新。
