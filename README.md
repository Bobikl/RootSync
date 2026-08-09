# RootSync

面向已 ROOT Android 手机的局域网 rsync 同步工具，依据
`Android_ROOT_rsync_开发大纲.md` 开发。当前版本为 arm64 技术验证 APK。

## 当前版本（0.2.1）

- Kotlin、Jetpack Compose、Material 3；`compileSdk/targetSdk 36`，适配 Android 15/16；
- APK 内置经上游签名验证的 **rsync 3.4.4 arm64**，无需额外安装 ROOT rsync 模块；
- 内置 arm64 `syncmeta`，快照并恢复目录 mtime；两个原生 ELF 均使用 16 KB LOAD 对齐；
- 独立的“只发送”和“只接收”方向，可选择镜像或仅更新；
- 多设备配置：每台设备分别保存地址、密钥、方向、镜像开关和本机目录；
- Android NSD/mDNS 与 UDP 8874 双通道自动发现，点击设备后由对方确认连接；
- 扫描时申请附近设备权限、绑定 Wi-Fi 网络并启用组播接收，修复部分 Android 16/小米设备扫描不到的问题；
- 默认设备名称读取系统“设备名称”，不再使用厂商与型号拼接；
- 本机密钥支持手动自定义，最少 6 位；
- rsync daemon 提供受限的 `send` 只读模块和 `receive` 只写模块；
- dry-run、最多 100 项删除保护、partial 续传、超时和实时日志；
- 修正默认哔哩哔哩包名为 `tv.danmaku.bili`；本机接收目录不存在时由 ROOT 自动创建；
- 默认仅构建 `arm64-v8a`。

## 构建

依赖 Android SDK 36、NDK 26.3、JDK 17、Gradle 9.2 和 MSYS2（构建 rsync 时使用）。

```powershell
.\native\build-syncmeta.ps1
.\native\rsync\build-android.ps1
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

已构建版本：`dist/RootSync-v0.2.1-debug-arm64.apk`

## 两机使用

1. 两台手机安装相同 APK，授予 ROOT，并连接同一 Wi-Fi。
2. 两端进入“服务”，确认本机发送源、本机接收目录和监听端口，然后启动服务。
3. 进入“同步”并点击“自动扫描局域网”；点击设备，对方选择“允许连接”。
4. 为该设备选择“只发送”或“只接收”，按需打开“镜像目标端”，保存设备策略。
5. 先运行差异预览；镜像策略预览成功后才能执行。

自动发现和首次连接弹窗要求对方 RootSync 处于打开状态。完成配对并启动 rsync
服务后，rsync daemon 可独立运行，另一台设备可按已保存的 IP 和策略连接。

## 第三方源码与许可证

见 `THIRD_PARTY_NOTICES.md`。rsync 3.4.4 对应源码位于 `native/rsync/src`，
上游原始压缩包、签名与发布密钥位于 `native/rsync/vendor`。APK 的 assets 中也包含
rsync/popt 许可证和第三方声明。
