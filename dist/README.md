# RootSync APK

- 文件：`RootSync-v0.2.9-debug-arm64.apk`
- applicationId：`com.rootsync.android.debug`
- versionCode / versionName：`11` / `0.2.9-debug`
- minSdk / targetSdk：`26` / `36`
- ABI：`arm64-v8a`
- APK SHA-256：`BF6447E0A1FDACA4826C5A483541201975DCAAD9150159E8F08A1A2C44C6C93D`
- 内置 rsync 3.4.4 SHA-256：`38FF6DE1B36F18CE391DF512713192F3129B733C8F2FE55122DA26B0A600A6E9`
- 签名：Android Debug，APK Signature Scheme v2

这是调试签名技术验证包，可与上一版 debug 包覆盖安装。

本版强制启用零删除保护：目标端独有文件不删除，被更新文件的旧版本保存到
`.rootsync-history/<同步时间>/`；客户端与 rsync daemon 双重拒绝删除类选项。

v0.2.9 根据设备持久日志定位并修复 `@ERROR: max connections (1) reached`。
原因为服务启动和远端就绪检查使用空 TCP 连接，占用了 daemon 唯一连接槽，随后真正的预览与传输被拒绝。
本机服务改为读取内核 TCP 监听表，已配对远端准备成功后不再额外探测端口；控制通道无响应时的后备探测会等待连接槽释放。

局域网控制协议保持 v5；建议两端都安装 v0.2.9。

