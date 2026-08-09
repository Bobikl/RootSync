# RootSync

面向已 ROOT Android 手机的局域网 rsync 同步工具，依据
`Android_ROOT_rsync_开发大纲.md` 开发。当前版本为 arm64 技术验证 APK。

## 当前版本（0.2.4）

- Kotlin、Jetpack Compose、Material 3；`compileSdk/targetSdk 36`，适配 Android 15/16；
- APK 内置经上游签名验证的 **rsync 3.4.4 arm64**，无需额外安装 ROOT rsync 模块；
- 内置 arm64 `syncmeta`，快照并恢复目录 mtime；两个原生 ELF 均使用 16 KB LOAD 对齐；
- 独立的“只发送”和“只接收”方向，所有方向均强制执行零删除同步；
- 多设备配置：每台设备分别保存地址、密钥、方向和本机目录；
- Android NSD/mDNS 与 UDP 8874 双通道自动发现，点击设备后由对方确认连接；
- 扫描时申请附近设备权限、绑定 Wi-Fi 网络并启用组播接收，修复部分 Android 16/小米设备扫描不到的问题；
- 默认设备名称读取系统“设备名称”，不再使用厂商与型号拼接；
- 本机密钥支持手动自定义，最少 6 位；
- 已配对设备按稳定设备 ID 长期信任，覆盖安装、密钥轮换和 DHCP 地址变化后自动恢复连接；只有主动删除设备才解除信任；
- 配对设备的方向策略会同步到另一端，并自动换算为另一端的相反本机动作；
- 每次传输前请求远端按正确方向准备 `send` 或 `receive` 模块，使任意一端都能主动发起发送或接收；
- 差异预览提供独立大面板，显示发生变化的文件夹；传输时显示当前上传/接收文件夹、进度和预计完成时间；
- 传输进度同步显示在系统通知栏，前台 `dataSync` 服务配合 CPU/Wi-Fi 锁保持后台传输；
- 支持暂停、连接中断自动暂停、跨进程重启继续传输，并可单独删除传输记录；partial 分片和用户文件不会随记录删除；
- rsync 客户端和服务端密钥文件改为 ROOT 所有、0600 权限，修复 ROOT 模式的密钥文件校验与部分错误 10；
- rsync daemon 提供受限的 `send` 只读模块和 `receive` 只写模块，并拒绝全部删除类参数；
- 客户端命令不生成删除参数，运行时再次拦截删除选项，代码中不执行显式清理命令；
- 目标端独有文件始终保留；被更新文件的旧版本保存到目标目录下的 `.rootsync-history`；
- dry-run、partial 续传、超时和实时日志；
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

已构建版本：`dist/RootSync-v0.2.4-debug-arm64.apk`

## 两机使用

1. 两台手机安装相同 APK，授予 ROOT，并连接同一 Wi-Fi。
2. 两端进入“服务”，确认本机发送源、本机接收目录和监听端口，然后启动服务。
3. 进入“同步”并点击“自动扫描局域网”；点击设备，对方选择“允许连接”。
4. 为该设备选择“只发送”或“只接收”，保存设备策略。
5. 可先运行差异预览，在详情框检查变化文件夹，再执行零删除同步。
6. 传输可切到后台、暂停或在连接中断后继续；“删除传输记录”只删除任务记录。

自动发现和首次连接弹窗要求对方 RootSync 处于打开状态。完成配对并启动 rsync
服务后，rsync daemon 可独立运行，另一台设备可按已保存的 IP 和策略连接。

## 第三方源码与许可证

见 `THIRD_PARTY_NOTICES.md`。rsync 3.4.4 对应源码位于 `native/rsync/src`，
上游原始压缩包、签名与发布密钥位于 `native/rsync/vendor`。APK 的 assets 中也包含
rsync/popt 许可证和第三方声明。
