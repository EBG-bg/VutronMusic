# Vutron Music - Mobile Android

Vutron Music - Mobile 1.0.1 的 Android 开发与构建说明。移动端使用 Vue 3、TypeScript、Pinia 和 Capacitor，提供在线与本地音乐播放、离线缓存、播放队列和歌词功能。下载与功能介绍见 [仓库首页](../../README.md)。

## 环境与开发

- Node.js 22.16 或更新的受支持版本。
- JDK 21；设置 JAVA_HOME。
- Android SDK Platform 35 和 Build Tools 35+；设置 ANDROID_HOME / ANDROID_SDK_ROOT。

```sh
cd apps/android-vutron
npm ci --ignore-scripts
npm run dev
```

仓库根目录的 `scripts/build-mobile.ps1` / `scripts/build-mobile.sh` 依次执行类型检查、Vite 构建、Capacitor 同步和 Gradle。只克隆 apps 子目录不能构建：packages、原图标/主题、android/shared 和 vendor/android-runtime 都是输入。

## Release 签名

Gradle 从以下环境变量读取签名配置；值不得写入源代码或提交：

- VUTRON_RELEASE_KEYSTORE：现有签名库的绝对路径。
- VUTRON_RELEASE_STORE_PASSWORD：签名库密码。
- VUTRON_RELEASE_KEY_ALIAS：别名。
- VUTRON_RELEASE_KEY_PASSWORD：可选，缺省同签名库密码。

```powershell
./scripts/build-mobile.ps1 -Configuration Release
```

未提供签名配置时生成 unsigned release APK，需自行签名后安装。项目不附带签名私钥。覆盖升级要求 applicationId 和签名证书一致；当前 applicationId 为 `io.github.vutronmusic.personal.webshell`。

Release APK 不开启 WebView 调试。CI 的 Debug artifact 只用于开发检查，其签名可能随 runner 改变，不作为已安装正式版的升级来源。

已发布的 1.0.0 和 1.0.1 安装包使用同一签名证书，主题为 `C=US, O=Android, CN=Android Debug`，证书 SHA-256 为 `d879659d80b689de9be73f419d3ccd01f4bee96f9725a08896b420037688d6d8`。构建类型为 Release，应用调试关闭。证书主题和有效期不能证明密钥的来源或保管方式；更新仍须同时满足构建通道、包名、签名和递增版本码。1.0.x 保持现有签名；若以后更换，将另行说明签名轮换或重新安装及数据迁移方式，不静默替换。

## 验证与限制

认证与本地模式位于 src/stores/domains/authSession.ts；设置界面、更新控制与列表搜索／分批呈现分别为独立模块。UI 使用 hasAccess、loginVisible 与 onlineAvailable 选择对应能力；原生音乐网络策略不依赖前端隐藏按钮。

检查更新使用 GitHub Releases 的稳定 Mobile ARM64 资产，默认不自动请求。下载完成后验证大小、SHA-256、包名、版本和签名，再打开 Android 系统安装器。构建不附带 GitHub token，也不向 GitHub 发送音乐账号 Cookie。

仅 Mobile 通道允许通过应用内入口下载和安装公开包，其他构建只查看公开版本信息；前端及原生入口均限制跨通道安装。本地模式暂停启动、返回前台及开启开关触发的自动检查；只有主动点击“检查更新”才联网 GitHub。切入本地模式会中断进行中的自动检查。

歌曲、歌单卡片和播放队列采用每批 80 项的“显示更多”，分页不截断实际播放队列。本地及缓存歌曲可按标题、歌手或专辑排序，记住选择，也可恢复原有顺序。目录权限失效时，侧栏提示重新授权。

系统备份采用指定设置白名单，仅包括歌词、音量、缓存容量、本地目录信息和本地模式偏好；不包含登录会话、账号资料、WebView 数据、音乐文件、缓存、扫描索引或更新安装包。恢复目录信息后仍需重新授予文件夹访问权限。系统备份不等同于本地歌单及音乐的手动导出。

界面国际化沿用原版 vue-i18n Composition API 与 `src/renderer/locales` 三语词库；移动端扩展位于 `src/locales`，设置中的“界面语言”可立即切换并保存简体中文、繁体中文、英文。导航、登录、首页主要标题、队列、排序和更新已接入，部分高级设置说明、运行中提示及原生错误文案仍保留中文。缺失译文回退到中文；歌曲／歌单名称、歌词与构建品牌不作界面翻译。新增移动端语言键需同步三个词库并通过键集合检查。

本地索引、扫描与导入位于 `src/stores/domains/localLibrary.ts`，登录由 `domains/authSession.ts` 管理，页面导航保留在 shell。队列面板打开时，播放状态轮询只在队列来源、索引、数量、插队数量、模式或歌曲身份变化后重读完整队列；主动播放新来源会强制同步。当前列表仍是渐进分页，持续“显示更多”会增加 DOM；扫描和导入已有进度／取消，大型库的固定窗口虚拟化和其余领域拆分尚未完成。

在线歌单关系与写操作位于 `domains/neteaseLibrary.ts`，通过原生 `OnlineLibraryManager` / `NeteaseApi` 调用网易云协议，不启动 Node 插件运行时。喜欢状态单独读取 likelist；原生重新校验歌单所有权。提交期间禁止重复操作，账号切换和新快照会使旧读取失效；结果未确认或刷新失败时保留旧界面资料，要求先重新同步。写入与读取协议由原生契约测试覆盖，组件交互另有隔离后端的浏览器检查。

共享核心 `packages/core/src/lyrics.ts` 和原生 `LyricTimeline` 均支持增强 LRC、WRC 和既有 YRC。所有格式转换为现有 words 数据；同名 `.eslrc` / `.lrc` 保留原编码与 256 KiB 上限，真实逐字优先。混合逐字／普通行、翻译配对、offset 与 seek 由定向测试覆盖，估算逐字默认关闭。

设置的“检查更新”栏提供“关于”页面，版本与名称读取同一构建身份；隐私、使用说明、项目及第三方许可按需读取 APK 内置文本，离线可用。项目与反馈链接只在点击时打开 GitHub。返回沿用页面历史及滚动位置恢复。

最低系统声明仍为 API 23。标准库降级采用 `desugar_jdk_libs_nio` 2.1.5，通知渠道及触摸遮挡透明度接口保留系统版本判断；这不等于所有旧设备已经真机验收。

Android 网络策略默认禁止明文连接，只为本机桥接和网易云封面 CDN 保留例外。已确认的 HTTP 音频地址注册到应用私有的回环流式代理，保留原字节、Range、HEAD 和媒体来源头；HTTPS 音频直接播放。代理不接收账号 Cookie，拒绝私网目标，并在连接断开时取消上游请求。外部音源链路仍可能包含 HTTP；这项兼容不表示所有来源都支持端到端 HTTPS。本地文件或已完成缓存播放不会为此启动 Node 运行时。

本地曲目菜单的“从应用移除”保留原文件。文件夹扫描曲目会写入排除项，可在设置中恢复；删除导入歌单只移除应用私有副本。移除目录会释放 SAF 授权；重新选择可再次扫描。扫描和导入提供进度及取消，取消的扫描不保存半截索引。

桌面导出工具在 tools/，支持简易 GUI 和 CLI，也可读取已登录账号的网易云歌单。新歌单包校验导出来源，已导入的旧包仍可读取。普通音频 ZIP 可选择仅本地导入，或在登录后补全在线元数据；本地模式禁止后者，补全失败不会切换音频来源。

设置中的 JSON 备份保存界面、歌词、音量及本地歌单顺序；播放队列作为独立资料歌单保存。账号、缓存音频、封面、绝对路径和目录权限均不导出。恢复创建资料歌单并保留现有库；重新授权原目录或重新导入原歌单包后，按文件名与完整元数据唯一匹配音频。不能确认的曲目保持“待关联”，不会自动替换为在线歌曲。

当前 Release 保持 R8／资源收缩关闭；涉及 Capacitor 插件反射与 JNA 的裁剪需另行建立保留规则及真机回归。构建类型与证书名称是不同信息。

单曲设置按完整歌曲身份保存。法律文件位于 APK 的 `assets/legal`，运行库原许可证随 `assets/sources-runtime` 提供。

主要目标设备为 Android 13 / HyperOS ARM64；其他 Android 版本、全部第三方音源、长时间后台及设备厂商权限策略尚未完整验收。

见仓库根 README、NOTICE、THIRD_PARTY_NOTICES.md 和 legal/。
