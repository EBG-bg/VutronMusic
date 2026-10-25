---
last-updated: 2026-10-25
title: ADR-0009 Android 共享核心与 Capacitor 壳
order: 10
---

# ADR-0009：Android 共享核心与 Capacitor 壳

## Context

VutronMusic 的桌面端依赖 Electron 主进程、Fastify、本地 SQLite、Worker 插件和 Electron IPC。Android 不能直接运行这套主进程；同时，歌词、音源数据契约、歌曲匹配和封面规则本身不应该因为平台不同而产生两份实现。

现有 Android 个人版已经验证了 Android 播放服务、通知、缓存、文件夹扫描和桌面歌词，但其界面和部分业务逻辑是 Java 重写。长期维护需要减少桌面端与 Android 端的行为分叉。

## Decision

增加 `packages/core`，只放不依赖 Electron、Android、DOM、Fastify 或 SQLite 的纯 TypeScript 逻辑。第一批共享内容包括：

- LRC/YRC 解析；
- 不完整逐字歌词的覆盖判断；
- 原文/译文的同时间戳一一配对；
- 网易云封面字段选择和 512×512 缩略图规则。

新增 `apps/android-vutron` 作为 Capacitor Android Web 壳，复用 Vue 3、Pinia 和 `packages/core`。播放、MediaSession、通知、文件夹授权、SQLite 和悬浮窗通过 Android 插件提供；桌面端继续使用 Electron，不改变现有发布链。

原生 Android 个人版保留为当前可安装回退实现。Web 壳继续复用个人版的音源运行时、本地匹配、音质选择和音量身份规则；Electron 主进程本身不进入 APK。

## Consequences

正面：

- 业务规则和歌词解析只维护一份；
- Android UI 可以逐步复用桌面 Vue 组件和 Pinia 状态；
- Android 特有能力集中在平台适配器，不污染共享业务层；
- 可以先迁移页面和数据流，再迁移播放及悬浮窗，降低一次性重写风险。

代价：

- Electron IPC 不能直接复用，需要定义 Android 平台适配器；
- Fastify 和 better-sqlite3 不进入 Android Web 壳，需使用原生网络和 SQLite 实现；
- Capacitor 壳与原生个人版会在迁移期间并存，需要分别构建和验证。

## Current boundary

目前 Capacitor 壳已接入二维码/Cookie 登录、歌单读取、网易云与内置其它音源播放、本地文件夹匹配、歌曲/歌单/封面缓存、MediaSession 通知控制、播放位置恢复、歌词与桌面歌词、定时和音量设置。桌面端完整页面组件仍不直接复制进 Android；Android 继续使用 Capacitor UI 与原生适配器，原生个人版作为可安装回退路径。HyperOS 真机行为仍需在目标设备上确认。

0.3.6 已构建并校验，覆盖多目录与本地歌单导入、按实际播放触发的静默缓存、推荐歌单离线索引、持久化插队队列、逐字原译滚动、取色与延迟设置和暂停浮窗恢复边界。桌面 SVG、主题变量和按钮按压参数直接复用原项目；Android 页面保留手机布局。离线资料使用应用私有原子 JSON 快照，当前没有 Android SQLite 适配器。新增定向测试与浏览器模拟只证明对应逻辑，不能代替目标手机上的网络、通知、悬浮窗与系统生命周期验证；ADB 尚无目标设备，迁移的整体验收仍未完成。

0.3.7 补入设置分组折叠、调色独立默认收起、原版底部导航与播放按钮状态过渡，以及锁定歌词的默认触摸穿透模式。触屏输入只提供按住状态，不叠加自定义释放关键帧；已有浮窗模式与颜色设置保留。交付包已构建并校验，目标手机的系统触摸穿透仍待 ADB 真机确认。

2026-10-25 已在用户 Mi 10 / Android 13 / HyperOS 的 0.3.7 上完成 26 项新增路径真机检查：设置与独立调色折叠、WebView 触屏动效、跨 UID 普通触摸穿透、严格触摸拒绝后的临时隐藏、安全页面退出恢复、暂停隐藏和续播恢复。临时宿主已卸载；日志位于 android/_analysis/device-0.3.7。已验证具体宿主上的触摸行为，不推广为任意敏感应用均能穿透；本轮没有重新验证扫码和本地导入等未修改路径。

0.3.8/0.3.9 继续按桌面组件复用方向修正：歌词配色与桌面参数一致，真实逐字优先，估算仅为可选显示投影；歌曲菜单使用原 more SVG，追加/插队保留持久化队列。按钮过渡参数与禁止选字规则来自 ButtonIcon、SideNav 和 global.scss，菜单/列表过渡来自 ContextMenu，歌词可见区对齐复用 LyricPage 的整行居中语义。平台原生悬浮窗按 LyricLine 的低频状态/连续动画分离方式改为显示帧驱动；MediaPlayer、窗口权限与帧回调仍属于 Android 平台适配。0.3.9 的新路径定向检查、构建与 APK 核验通过，真实目标设备当前未连接，尚未据此宣称整项迁移完成。

0.3.10 新增 `packages/ui`，把原 ButtonIcon 和 SideNav 的无业务按钮部分抽成桌面/Android 实际共用组件，桌面原路径继续可用，导航业务及平台接口仍由上层提供。Android 独立按钮状态样式对应去重；这是真实代码复用的第一批，而非把所有独立移动页面描述成原组件迁移。完整 ContextMenu、TrackListItem 与 LyricPage 等仍有后续抽取工作。

本轮即时设置路径将 UI 乐观状态与原生保存分离：每字段/歌曲身份保存最新输入，合并拖动请求，末值显式 flush，旧回包与滞后轮询不覆盖预览。桥接携带完整歌曲信息，平台层沿原有稳定身份保存；歌词设置由平台在下一帧应用。动效策略改为可见的完整/系统/减少三档，避免应用自身 CSS/WAAPI 对系统偏好处理不一致。0.3.10 为可覆盖升级的 debug 测试包；原生回退版和 0.3.9 对照包保留，未建立已验收 release 基线。
