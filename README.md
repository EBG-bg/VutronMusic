# Vutron Music - Mobile

Vutron Music - Mobile 是 [VutronMusic](https://github.com/stark81/VutronMusic) 的 Android 移植版，支持在线歌单、本地音乐、离线缓存与桌面歌词。当前版本：**1.0.0**，主要适配 Android 13 / HyperOS 的 ARM64 设备。

移动端由 EBG-bg 维护，基于 Vue 3、TypeScript、Pinia 和 Capacitor，复用上游图标、主题和共享组件。原桌面项目介绍见 [README.upstream.md](README.upstream.md)，原许可证见 [LICENSE](LICENSE)。

## 功能

| 功能 | 说明 |
| --- | --- |
| 在线音乐 | 网易云音乐二维码/Cookie 登录、首页推荐、搜索和歌单播放。 |
| 本地音乐 | 选择音乐文件夹，识别本地音频，并支持导入电脑本地歌单。 |
| 离线缓存 | 缓存歌曲、歌单信息和封面，可配置缓存占用。 |
| 播放队列 | 查看队列、追加歌曲、将歌曲插入下一首播放。 |
| 播放设置 | 保存歌曲与歌单音量、重启恢复播放进度、睡眠定时。 |
| 应用内歌词 | 原文与译文同步滚动、逐曲延迟微调，以及可选的估算逐字显示。 |
| 桌面歌词 | 悬浮歌词、颜色自定义、透明度和阴影设置。 |
| 歌单布局 | 支持列表与卡片布局，可调整每排卡片数量。 |

估算逐字显示由程序根据歌词时间计算，不等同于来源提供的精确逐字时间。

各来源播放能力取决于该来源的可用性及用户拥有的权限。本项目不附带账号、Cookie、音乐文件或平台授权，也不承诺提供受限内容。

## 下载与安装

打开 [1.0.0 下载页](https://github.com/EBG-bg/VutronMusic/releases/tag/mobile-v1.0.0)，下载并安装 `Vutron-Music-Mobile-1.0.0-arm64.apk`。同页的 `.sha256` 文件用于核对安装包完整性，`Source code` 为开发源码，不是安装包。

安装后可使用二维码或 Cookie 登录网易云音乐，也可选择本地音乐文件夹。使用桌面歌词时，需要在系统中授予应用悬浮窗权限。

当前仅提供 ARM64 安装包，主要适配设备为 Mi 10 / Android 13 / HyperOS。其他 Android 版本和设备的兼容情况仍需确认。

## 构建

需要 Node.js 22.16+、JDK 21、Android SDK Platform 35 / Build Tools 35+。在仓库根目录运行：

```powershell
./scripts/build-mobile.ps1 -Configuration Debug
```

Linux/macOS：

```sh
sh scripts/build-mobile.sh Debug
```

产物位于 `apps/android-vutron/android/app/build/outputs/apk/`。开发构建只需安装 `apps/android-vutron` 的 npm 依赖，不必安装整套 Electron 开发环境。详细说明见 [Android 构建说明](apps/android-vutron/README.md)。

`vendor/android-runtime` 中的生产 JavaScript `node_modules` 与 ARM64 库是实际运行输入，必须保留。修改 LGPL 运行库后可按相同流程重新构建；对应源码、许可证和来源说明随仓库提供。

## 项目结构

- `apps/android-vutron`：Vue/Pinia Android UI 与 Capacitor 原生适配。
- `packages/core`：桌面/Android 共享的歌词与封面规则。
- `packages/ui`：从上游抽取并被桌面/Android 实际共用的控件。
- `android/shared`：网络与本机会话适配。
- `vendor/android-runtime`：经过验证的音源、匹配与音质运行时。

## 隐私、许可与使用

请阅读 [隐私说明](legal/PRIVACY.md)、[使用说明](legal/USE_POLICY.md)、[第三方声明](THIRD_PARTY_NOTICES.md)、[再分发说明](legal/REDISTRIBUTION.md) 和 [NOTICE](NOTICE)。根 MIT 文本保留原作者版权；第三方库分别适用其许可证，尤其内置 LGPL 组件不应被表述为全部 MIT。

问题与反馈请使用本 fork 的 Issues，提交日志前自行删除账号、Cookie 和本地文件路径等个人信息。
