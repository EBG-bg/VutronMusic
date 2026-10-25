---
last-updated: 2026-10-25
title: ADR-0009 Android 共享核心与 Capacitor 壳
order: 10
---

# ADR-0009：Android 共享核心与 Capacitor 壳

## Context

VutronMusic 的桌面端依赖 Electron 主进程、Fastify、本地 SQLite、Worker 插件和 Electron IPC。Android 不能直接运行这套主进程；同时，歌词、音源数据契约、歌曲匹配和封面规则本身不应该因为平台不同而产生两份实现。

Vutron Music - Mobile 1.0.0 使用 Vue 界面与 Android 平台适配层。共享逻辑需要与平台能力分开，既减少桌面端与 Android 端的行为分叉，也保留后台播放、通知、文件授权和悬浮窗所需的原生实现。

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

### 验证边界

构建、共享逻辑测试和浏览器模拟只能证明各自覆盖的行为，不能代替目标 Android/HyperOS 设备上的网络、通知、悬浮窗与系统生命周期验证。系统或宿主应用可以限制悬浮窗显示及触摸；已覆盖路径的结果不代表任意设备和敏感场景均具有相同行为。
