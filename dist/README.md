# RootSync APK

- 文件：`RootSync-v0.2.12-debug-arm64.apk`
- applicationId：`com.rootsync.android.debug`
- versionCode / versionName：`14` / `0.2.12-debug`
- minSdk / targetSdk：`26` / `36`
- ABI：`arm64-v8a`
- APK SHA-256：`494DD031E7EE824946DCC37464D2E211E504DA88C2FF1637C2E4992556CA0F50`
- 内置 rsync 3.4.4 SHA-256：`38FF6DE1B36F18CE391DF512713192F3129B733C8F2FE55122DA26B0A600A6E9`
- 签名：Android Debug，APK Signature Scheme v2

这是调试签名技术验证包，可与上一版 debug 包覆盖安装。

本版强制启用零删除保护：目标端独有文件不删除，被更新文件的旧版本保存到
`.rootsync-history/<同步时间>/`；客户端与 rsync daemon 双重拒绝删除类选项。

v0.2.12 修复 Android 杀死应用后 ROOT `librsync.so` 脱离父进程并持续占用 CPU。
传输和 daemon 启动时建立独立看门狗，应用 PID 消失后自动终止本应用 transfer、daemon 及自身；应用下次启动和前台服务任务移除时也执行受限清理。
清理只匹配记录的 PID、本应用 rsync 配置路径或当前应用安装路径，不影响其他 rsync 程序。

局域网控制协议保持 v6；建议两端都安装 v0.2.12。

