# Vutron Music - Mobile

VutronMusic 的 Android 移植 fork，基于 Vue 3、TypeScript、Pinia 和 Capacitor，复用上游图标、主题、共享歌词/封面逻辑以及共享按钮组件。当前版本：**1.0.0**。

上游：[stark81/VutronMusic](https://github.com/stark81/VutronMusic)。Mobile fork 由 EBG-bg 维护，与上游作者及音乐平台不存在官方隶属或背书关系。原桌面介绍保存在 [README.upstream.md](README.upstream.md)，原许可证保存在 [LICENSE](LICENSE)。

## 功能

- 网易云音乐二维码/Cookie 登录、首页推荐、搜索和歌单播放。
- 本地文件夹、电脑本地歌单导入，以及歌曲/歌单/封面离线缓存。
- 播放队列、下一首插队、歌曲与歌单独立音量、重启恢复进度及睡眠定时。
- 原文/译文同步歌词、可选估算逐字、逐曲延迟微调、桌面悬浮歌词与调色。
- 直接复用原版 SVG、主题，以及桌面/手机共用的 ButtonIcon、SideNavItem。

各来源播放能力取决于该来源的可用性及用户拥有的权限。本项目不附带账号、Cookie、音乐文件或平台授权，也不承诺提供受限内容。

## 安装与范围

从本 fork 的 Releases 获取 `Vutron-Music-Mobile-1.0.0-arm64.apk`。当前只提供 ARM64；开发验收设备为 Mi 10 / Android 13 / HyperOS，其他设备仍需验证。1.0.0 源于已由维护者试用的 0.3.10，主要变化为公开名称/版本与发布资料。个人测试包、签名私钥、账号状态和测试记录不上传。

为保持本地测试版覆盖升级与数据兼容，内部 applicationId 仍为 `io.github.vutronmusic.personal.webshell`；它不会在应用内显示个人版名称。运行中的媒体服务、缓存和桌面歌词行为沿用测试版。

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

完整桌面页面尚未全部迁成共享组件。现有构建与定向测试不代表所有手机、平台接口或后台情形都已验证。

## 隐私、许可与使用

请阅读 [隐私说明](legal/PRIVACY.md)、[使用说明](legal/USE_POLICY.md)、[第三方声明](THIRD_PARTY_NOTICES.md)、[再分发说明](legal/REDISTRIBUTION.md) 和 [NOTICE](NOTICE)。根 MIT 文本保留原作者版权；第三方库分别适用其许可证，尤其内置 LGPL 组件不应被表述为全部 MIT。

问题与反馈请使用本 fork 的 Issues，提交日志前自行删除账号、Cookie 和本地文件路径等个人信息。
