# 本地音乐 V2.0

## 更新内容
- 全新紫灰色音乐库 UI 与更清晰的歌曲卡片。
- 保留播放页转场、封面动画、播放按钮动画、歌曲播放脉冲与歌词自动滚动。
- 修复歌词行宽度随文本变化导致视觉上横向漂移的问题：歌词行统一占满容器宽度并居中显示。
- LazyColumn 使用稳定 key/contentType，动画属性尽量走 graphicsLayer，减少滚动和动画期间的额外布局工作。
- 保留 V1.2 的 LRC/TXT 歌词导入与封面导入。
- versionCode=4，versionName=2.0。

## 构建
通过 GitHub Actions 构建。Release 签名继续使用仓库已配置的签名 Secrets；不要将 keystore 文件提交到仓库。
