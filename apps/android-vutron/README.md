# Vutron Music - Mobile Android

公开移动端版本 1.0.0，Vue 3 + TypeScript + Pinia + Capacitor；功能与 0.3.10 本地测试版一致，公开显示名称统一为 Vutron Music - Mobile。

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

未提供签名配置时生成 unsigned release APK，由维护者在本机签名。项目不附带发布密钥；本地个人测试包也不进入仓库或 Release。升级要求包名和签名证书一致。

Release APK 不开启 WebView 调试。CI 的 Debug artifact 只用于开发检查，其签名可能随 runner 改变，不作为已安装正式版的升级来源。

本仓库 1.0.0 Release 附件为关闭调试的 Release 构建，但为保留已有测试安装的数据与覆盖升级，沿用原有 Android Debug 证书，并非新建的生产签名。证书 SHA-256 为 `d879659d80b689de9be73f419d3ccd01f4bee96f9725a08896b420037688d6d8`；私钥不随源码或安装包提供。后续若更换签名，将另行说明升级方式。

## 验证与限制

公开版本继承测试版登录、歌曲/歌单、本地音乐、缓存、通知、歌词与悬浮歌词能力，没有因改名删除运行库。单曲设置按完整歌曲身份保存。法律文件在 APK 的 assets/legal 中，运行库原许可证仍随 assets/sources-runtime 提供。

主要目标设备为 Android 13 / HyperOS ARM64；其他 Android 版本、全部第三方音源、长时间后台及设备厂商权限策略尚未完整验收。对照包和原生回退包由维护者本地保留，不公开上传。

见仓库根 README、NOTICE、THIRD_PARTY_NOTICES.md 和 legal/。
