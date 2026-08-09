# RootSync APK

- 文件：`RootSync-v0.2.7-debug-arm64.apk`
- applicationId：`com.rootsync.android.debug`
- versionCode / versionName：`9` / `0.2.7-debug`
- minSdk / targetSdk：`26` / `36`
- ABI：`arm64-v8a`
- APK SHA-256：`1B322F83CEE70F06509B8B4DEFF5484FB7E47FECDE087472F4C406B1CE8E67DB`
- 内置 rsync 3.4.4 SHA-256：`38FF6DE1B36F18CE391DF512713192F3129B733C8F2FE55122DA26B0A600A6E9`
- 签名：Android Debug，APK Signature Scheme v2

这是调试签名技术验证包，可与上一版 debug 包覆盖安装。

本版强制启用零删除保护：目标端独有文件不删除，被更新文件的旧版本保存到
`.rootsync-history/<同步时间>/`；客户端与 rsync daemon 双重拒绝删除类选项。

v0.2.7 修复自动准备双向模块后 rsync daemon 启动检查过早，以及旧服务端口未释放的竞态。
启动时现在等待 PID、验证监听端口，并在失败时把当前 `rsyncd.log` 的详细原因回传到双方。

局域网控制协议保持 v5；建议两端都安装 v0.2.7。

