# RootSync APK

- 文件：`RootSync-v0.2.4-debug-arm64.apk`
- applicationId：`com.rootsync.android.debug`
- versionCode / versionName：`6` / `0.2.4-debug`
- minSdk / targetSdk：`26` / `36`
- ABI：`arm64-v8a`
- APK SHA-256：`6B61E0F1D38406DC28F5A9F8AAB3A28524FF427300A55BD608A2E817D6E217C3`
- 内置 rsync 3.4.4 SHA-256：`38FF6DE1B36F18CE391DF512713192F3129B733C8F2FE55122DA26B0A600A6E9`
- 签名：Android Debug，APK Signature Scheme v2

这是调试签名技术验证包，可与上一版 debug 包覆盖安装。

本版强制启用零删除保护：目标端独有文件不删除，被更新文件的旧版本保存到
`.rootsync-history/<同步时间>/`；客户端与 rsync daemon 双重拒绝删除类选项。

v0.2.4 增加持久可信设备自动重连、双端方向自动准备、差异/当前文件夹面板、
预计完成时间、通知栏进度、后台前台服务、暂停续传、异常自动暂停和传输记录管理。

