# RootSync APK

- 文件：`RootSync-v0.2.3-debug-arm64.apk`
- applicationId：`com.rootsync.android.debug`
- versionCode / versionName：`5` / `0.2.3-debug`
- minSdk / targetSdk：`26` / `36`
- ABI：`arm64-v8a`
- APK SHA-256：`65B224EF39D7853C4940AC6599604E26B13845C8A5D064B6F397B2016630F05C`
- 内置 rsync 3.4.4 SHA-256：`38FF6DE1B36F18CE391DF512713192F3129B733C8F2FE55122DA26B0A600A6E9`
- 签名：Android Debug，APK Signature Scheme v2

这是调试签名技术验证包，可与上一版 debug 包覆盖安装。

本版强制启用零删除保护：目标端独有文件不删除，被更新文件的旧版本保存到
`.rootsync-history/<同步时间>/`；客户端与 rsync daemon 双重拒绝删除类选项。

