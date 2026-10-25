# @vutronmusic/core

跨桌面端和 Android 端共享的无平台业务逻辑。

当前包含：

- LRC/YRC 解析；
- 完整歌词与不完整逐字歌词的覆盖判断；
- 原文/译文同时间戳一一配对；
- 网易云封面字段选择和缩略图 URL 规则。

这里不能依赖 Electron、Android、Fastify、SQLite 或 DOM。平台能力通过上层适配器提供。
