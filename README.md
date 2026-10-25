# Vutron Music - Mobile

Vutron Music - Mobile 是 [VutronMusic](https://github.com/stark81/VutronMusic) 的**非官方** Android 移植版，支持在线歌单、本地音乐、离线缓存与桌面歌词。当前代码版本：**1.0.1**，主要适配 Android 13 / HyperOS 的 ARM64 设备。

移动端由 EBG-bg 独立维护，与上游作者及音乐平台不存在官方隶属或背书关系。项目基于 Vue 3、TypeScript、Pinia 和 Capacitor，复用上游图标、主题和共享组件。原桌面项目介绍见 [README.upstream.md](README.upstream.md)，原许可证见 [LICENSE](LICENSE)。

## 用前须知

本移植为纯vibe-coding项目，移植代码by 6.1sol 检查与建议by deepseek v4.1 1017 ＆ 6.1sol ＆ 我自己
因本人为学生，所以大部分优化/issue/pr/.....会在周末/假期处理和更新

# 用前须知
本移植为纯vibe-coding项目，移植代码by 6.1sol 检查与建议by deepseek v4.1 1017 ＆ 6.1sol ＆ 我自己
因本人为学生，所以大部分优化/issue/pr/.....会在周末/假期处理和更新

## 功能

| 功能 | 说明 |
| --- | --- |
| 在线音乐 | 网易云音乐二维码/Cookie 登录、首页推荐、搜索和歌单播放。 |
| 网易云歌单管理 | 喜欢／取消喜欢、加入歌单、新建及编辑自有歌单、删除自有歌单和移除其中的歌曲；状态以服务端重新读取的结果为准。 |
| 本地音乐 | 选择或取消读取单个文件夹，识别本地音频；可从应用移除曲目并恢复扫描排除项，原文件保留。 |
| 仅本地模式 | 无需账号或网络即可进入本地／离线音乐库；设置中可随时登录网易云。 |
| 离线缓存 | 缓存歌曲、歌单信息和封面，可配置缓存占用；单曲删除只移除缓存音频，保留歌单、封面与歌词。 |
| 播放队列 | 查看队列、追加歌曲、将歌曲插入下一首播放。 |
| 播放设置 | 保存歌曲与歌单音量、重启恢复播放进度、睡眠定时。 |
| 应用内歌词 | 原文与译文同步滚动、逐曲延迟微调；支持 YRC、增强 LRC／WRC 的真实词时间，以及默认关闭的估算逐字。 |
| 桌面歌词 | 悬浮歌词、颜色自定义、透明度和阴影设置。 |
| 歌单布局 | 支持列表与卡片布局，可调整每排卡片数量。 |
| 本地排序 | 本地及导入歌单支持独立的自定义拖动顺序、恢复原顺序，并按该顺序播放。 |
| 桌面歌单导出 | 配套 Windows 简易 GUI / CLI，可导出本地及已登录网易云账号的歌单。见 [工具说明](tools/README.md)。 |
| JSON 备份 | 导出设置与本地歌单资料；音频、账号和目录授权不包含在内，恢复后重新关联原文件。 |
| 检查更新 | 设置底部从 GitHub 检查、下载并安装新版；自动检查默认关闭。 |
| 关于 | 从“检查更新”栏进入，查看版本、原项目信息，以及离线可读的隐私、使用说明和完整许可。 |

估算逐字显示由程序根据歌词时间计算，不等同于来源提供的精确逐字时间。

歌曲菜单提供“喜欢／取消喜欢”和“添加到歌单”；在线歌单页及侧栏可新建歌单，自有歌单的“更多”提供编辑和删除。仅本地模式隐藏这些在线管理入口。删除歌单或移除其中歌曲不删除手机原音频，也不会自动中断当前播放。

本地音频可搭配同名 `.lrc` 或 `.eslrc`；来源带有真实逐字时间时优先使用，应用内与悬浮歌词共用解析结果。桌面导出工具和纯音频 ZIP 导入会保留同名歌词。

各来源播放能力取决于该来源的可用性及用户拥有的权限。本项目不附带账号、Cookie、音乐文件或平台授权，也不承诺提供受限内容。

| 计划功能 |
| --- |
| 倍速 |
| CUE |
| 预取 |

## 下载与安装

已发布安装包在 [GitHub Releases](https://github.com/EBG-bg/VutronMusic/releases)，请选择对应版本的 ARM64 APK；尚未发布的代码版本可以按后文在本机生成安装包。`.sha256` 文件用于核对安装包完整性，`Source code` 为开发源码，不是安装包。

安装后可使用二维码或 Cookie 登录网易云音乐，也可选择本地音乐文件夹。使用桌面歌词时，需要在系统中授予应用悬浮窗权限。

不登录时，点击“仅本地模式”即可播放已授权的本地文件和已有音频缓存；首页推荐、在线搜索与网易云歌单入口在此模式下隐藏。已有缓存歌单可在“离线”中查看，缺少音频缓存的歌曲不会联网补播。退出网易云账号会保留本地音乐与缓存。

“设置 → 检查更新”提供手动检查与自动检查开关。自动检查默认关闭，开启后最多每 6 小时检查一次 GitHub；下载和系统安装均需确认，首次安装更新可能需要授予本应用“安装未知应用”权限。

仅本地模式会暂停自动检查；主动点击“检查更新”仍会连接 GitHub。公开安装包只能由允许的发行通道下载安装，应用会同时核验包名、版本码和签名。

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

## 上游同步与发布

- 仓库默认分支应为 `mobile`；桌面端页面不应成为访客默认看到的入口。
- 移动端 release 只使用 `mobile-v*` tag，例如 `mobile-v1.0.1`；不要复用上游 `v*` tag。
- 移动端只依赖 `Mobile Android` workflow；桌面的 `Release`、`app-test`、`documents` 只在上游 `master`/`v*` tag 触发，在 fork 中可禁用。
- 上游变更只 cherry-pick 或合并需要的提交，不把上游 `main` 整体合入 `mobile`；同步后重新跑移动端构建和真机检查。

## 项目结构

- `apps/android-vutron`：Vue/Pinia Android UI 与 Capacitor 原生适配。
- `packages/core`：桌面/Android 共享的歌词与封面规则。
- `packages/ui`：从上游抽取并被桌面/Android 实际共用的控件。
- `android/shared`：网络与本机会话适配。
- `vendor/android-runtime`：经过验证的音源、匹配与音质运行时。

## 隐私、许可与使用

请阅读 [隐私说明](legal/PRIVACY.md)、[使用说明](legal/USE_POLICY.md)、[第三方声明](THIRD_PARTY_NOTICES.md)、[再分发说明](legal/REDISTRIBUTION.md) 和 [NOTICE](NOTICE)。根 MIT 文本保留原作者版权；第三方库分别适用其许可证，尤其内置 LGPL 组件不应被表述为全部 MIT。

问题与反馈请使用本 fork 的 Issues，提交日志前自行删除账号、Cookie 和本地文件路径等个人信息。
