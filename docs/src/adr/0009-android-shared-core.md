---
last-updated: 2026-10-25
title: ADR-0009 Android 共享核心与 Capacitor 壳
order: 10
---

# ADR-0009：Android 共享核心与 Capacitor 壳

## Context

VutronMusic 的桌面端依赖 Electron 主进程、Fastify、本地 SQLite、Worker 插件和 Electron IPC。Android 不能直接运行这套主进程；同时，歌词、音源数据契约、歌曲匹配和封面规则本身不应该因为平台不同而产生两份实现。

Vutron Music - Mobile 1.0.1 使用 Vue 界面与 Android 平台适配层。共享逻辑需要与平台能力分开，既减少桌面端与 Android 端的行为分叉，也保留后台播放、通知、文件授权和悬浮窗所需的原生实现。

## Decision

`packages/core` 只放不依赖 Electron、Android、DOM、Fastify 或 SQLite 的纯 TypeScript 逻辑，当前共享内容包括：

- LRC/YRC 解析；
- 不完整逐字歌词的覆盖判断；
- 原文/译文的同时间戳一一配对；
- 网易云封面字段选择和 512×512 缩略图规则。

`packages/ui` 提供桌面与 Android 实际共用的 `ButtonIcon` 和 `SideNavItem`。按钮外观及交互状态由共享组件维护，导航业务与平台接口由各端上层提供；桌面端原组件入口继续可用。

`apps/android-vutron` 是 Capacitor Android 应用，使用 Vue 3、Pinia、`packages/core` 和 `packages/ui`。Android 插件负责原生网络访问、播放服务、MediaSession、通知、文件夹授权、缓存及悬浮窗；桌面端继续使用 Electron。Electron 主进程、桌面的 Fastify 应用服务和 better-sqlite3 数据层不进入 APK。

`vendor/android-runtime` 提供 Android 构建使用的 Java 运行时辅助类、JavaScript 音源匹配器及其依赖和 ARM64 Nodejs-Mobile 库。本地匹配、音质选择与音源设置仍包含 Android 平台实现，不属于 `packages/core` 的共享 TypeScript 范围。

## Consequences

正面：

- 已抽取的歌词、封面规则及基础按钮组件由两端共用；
- Android UI 可以继续逐步复用桌面 Vue 组件；
- Android 特有能力集中在平台适配器，不污染共享业务层；
- Web 界面和原生服务通过明确的桥接接口协作。

代价：

- Electron IPC 和桌面数据库接口不能直接复用，需要 Android 平台适配器；
- Android 离线资料使用应用私有 JSON 快照和偏好设置，当前没有 Android SQLite 适配器；
- 桌面完整页面、业务组件和 Pinia 状态尚未全部共享，两端仍需分别验证平台行为。

## Current boundary

### 应用与数据

Mobile 已接入二维码/Cookie 登录、歌单读取、网易云与内置其它音源播放、多文件夹与本地歌单导入、歌曲/歌单/封面缓存、推荐歌单离线索引、持久化播放队列、MediaSession 通知控制、播放位置恢复、定时和歌曲/歌单音量设置。歌曲音频缓存可按实际播放触发，歌单及其它离线资料由 Android 平台层保存。

### 界面复用

Android 使用手机布局，并复用桌面的 SVG、主题变量和部分交互动效参数。`packages/ui` 的两个基础按钮组件是实际共享代码；完整的 `ContextMenu`、`TrackListItem`、`LyricPage` 和桌面页面尚未整体成为共享组件。沿用视觉参数或显示语义不代表直接复用了对应完整组件。

设置采用分组折叠，动效提供完整、跟随系统与减少三种策略。即时设置在 UI 预览和原生保存之间合并拖动请求、提交末值，并避免旧回包覆盖当前输入；歌曲相关设置通过歌曲身份持久化。

### 歌词与平台服务

应用支持原文与译文配对、逐字滚动、配色与延迟设置。真实逐字数据优先，估算逐字仅作为可选显示方式。Android 悬浮窗使用原生窗口与显示帧回调，支持锁定触摸穿透、暂停隐藏及续播恢复，并处理系统暂时隐藏悬浮窗后的恢复。MediaPlayer、窗口权限、通知和服务生命周期仍由 Android 平台层管理。

### 能力、会话与缓存

登录、二维码与本地模式状态集中在移动端的 auth/session 模块。loggedIn 仅表示账号状态，hasAccess 决定是否可以进入播放器，onlineAvailable 决定在线首页、搜索和歌单操作；本地功能不以账号状态作入口条件。Android 原生层独立保存本地模式，并在音乐请求边界拦截网络回退，显式登录和用户启用的 GitHub 更新属于独立入口。

缓存 owner 索引与登录会话分别持久化。退出账号保留已缓存歌单及媒体文件，缓存索引不赋予账号权限。本地曲目优先读取已保存索引，首次缺少索引、超过 24 小时、目录授权变化或手动操作才启动扫描；扫描请求合并，前台恢复不反复遍历文件夹。

主界面将设置页、更新设置、歌词和队列拆为独立组件；本地搜索与列表呈现使用独立 composable。列表分批呈现不截断播放队列或缓存数据。应用可见时按 2 秒刷新播放状态，进入后台停止 WebView 轮询；实际音频与悬浮歌词仍由原生服务及其帧时钟负责。

### 规则来源与平台适配

共享 TypeScript core 负责应用内歌词配对、逐字覆盖与封面规则。Android 歌词仓库、时间线、文件缓存、本地匹配与系统浮窗是原生适配层；仍有独立原生解析时，以来源提供的原文／译文及原始时间为数据契约，通过共享 core 用例与 LyricMigration、LocalOnlyMode 等定向用例验证边界，不把某一侧的修改视为另一侧自动完成。

### 本地资料管理与可移植备份

本地管理统一经过 LocalLibraryManager：轻量桥接与 LibraryTaskRunner 的扫描／导入长任务分离，队列变更在媒体主线程串行执行。扫描取消后不提交部分索引；ZIP 在隐藏暂存目录完成校验后才发布为完整歌单。LocalTrackList 与 localTrackOrdering 负责列表呈现、手柄拖动和每个集合的独立顺序；原生队列按相同歌曲身份重排，保留当前歌曲进度和插队列表。

LibraryBackup 只序列化白名单设置与可移植曲目资料。恢复的歌单是引用资料，文件授权和音频必须由用户重新提供，只有文件名及完整元数据唯一匹配时才关联，不用同名搜索猜测录音，也不把缺失本地录音改成在线播放。

HTTP 音频兼容属于原生与嵌入运行时的适配边界。原生只为已解析的 HTTP 媒体注册私有回环地址，Node 代理保留字节与范围请求；HTTPS 及本地／缓存播放不走此代理。Android 网络配置默认禁止明文，仅保留回环和既有网易云封面域。媒体代理的范围、HEAD、跳转及私网拦截有独立契约检查。

### 设备性能验证

分批列表和搜索防抖已有 2,000 首数据的逻辑用例。实际 SAF 存储扫描耗时、30 分钟后台播放稳定性及悬浮歌词帧率仍需在目标设备测量；桌面单元测试不提供这些真机性能数值。Release 构建目前未开启 R8 和资源收缩，后续启用前需验证 Capacitor 插件反射、JNA 和原生运行库的保留规则。

构建、共享逻辑测试和浏览器模拟只能证明各自覆盖的行为，不能代替目标 Android/HyperOS 设备上的网络、通知、悬浮窗与系统生命周期验证。系统或宿主应用可以限制悬浮窗显示及触摸；已覆盖路径的结果不代表任意设备和敏感场景均具有相同行为。
