# 桌面版歌单导出工具

需要 Windows 上的 Python 3.10 或更新版本。双击 tools/export-desktop-playlist-gui.cmd 打开简易窗口：选择数据库，读取歌单，选中歌单后选择 ZIP 保存位置。窗口显示进度，支持取消；原始音频和已有导出包在取消时保留。

勾选“包含桌面已登录账号的网易云歌单”，并让桌面版保持运行、完成登录，即可同时选择账号创建和收藏的歌单。工具通过桌面版的本机 API 读取列表与曲目，保持歌单顺序，不在电脑批量下载在线音频。已有可访问且身份已确认的本地音频会复制；其余歌曲可保留在线资料，手机联网登录后播放。仅本地模式会显示在线标记并阻止联网补播。

本地导出和 GUI 只使用 Python 标准库。旧版 Electron 将登录态存于 Chromium LevelDB；读取私密歌单可能还需要运行 py -3 -m pip install -r tools/requirements-export.txt。工具只在内存中使用登录态，不写入导出 ZIP、界面状态或结果日志。新版插件数据库的登录态可直接只读获取。

命令行仍可用。先列出桌面版的本地歌单和已保存的在线歌单：

```powershell
.\tools\export-desktop-local-playlist.cmd --list
```

默认读取 `%APPDATA%\VutronMusic\api_cache\vutron_music.sqlite`，也识别旧版 `db.sqlite`。
其它数据目录可传 `--db "D:\MusicData\api_cache\vutron_music.sqlite"`。
数据库以只读模式打开，不修改桌面资料。加 --account 可通过运行中的桌面版读取已登录账号的完整歌单；账号列表中的 ID 形如 netease:12345，导出时加同一 --account 参数。加 --audio-only 可只导出已有音频，跳过未下载曲目。默认桌面 API 地址为本机 127.0.0.1:41830；不会向外部地址发送账号 Cookie。
复制列表中的完整 ID，导出到指定 ZIP：

```powershell
.\tools\export-desktop-local-playlist.cmd --playlist-id "这里替换为歌单ID" --output "D:\MusicExports\我的歌单.zip"
```

也可运行 `py -3 tools/export-desktop-local-playlist.py` 加相同参数。
已有 ZIP 默认不覆盖；确认需要覆盖时加 `--overwrite`。

ZIP 根目录清单标识为 `format: 1`、`generator: "vutron-desktop-export"`，音频按歌单顺序复制。
同目录、同名的 `.lrc` 和 `.eslrc` 随音频复制并统一包内文件名；每份歌词最多 256 KiB，超出的文件跳过并提示，原文件保留。
支持当前本地插件数据库和旧 JSON 数据库；本地歌单缺失文件会跳过，结果的 `missing` 列出没有本地音频的曲目 ID。账号歌单默认保留这些歌曲的在线资料，结果的 audioTracks / onlineTracks 分别说明打包音频和仅在线资料的数量。
包内不含 Cookie、账号配置、原始绝对路径或其它歌单。手机选择“导入桌面版歌单包”。
每包音频合计不超过 2 GiB，较大歌单需拆分。没有根清单的普通音频 ZIP 可在手机选择按本地音频导入；带错误来源清单的 ZIP 会被拒绝。
