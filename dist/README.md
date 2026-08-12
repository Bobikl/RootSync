# RootSync APK

- 文件：`RootSync-v0.2.10-debug-arm64.apk`
- applicationId：`com.rootsync.android.debug`
- versionCode / versionName：`12` / `0.2.10-debug`
- minSdk / targetSdk：`26` / `36`
- ABI：`arm64-v8a`
- APK SHA-256：`0D61326D7ED3803C6ECC37A8F1A78E9D61A6FFCD8FFF20460DC3F3949070F906`
- 内置 rsync 3.4.4 SHA-256：`38FF6DE1B36F18CE391DF512713192F3129B733C8F2FE55122DA26B0A600A6E9`
- 签名：Android Debug，APK Signature Scheme v2

这是调试签名技术验证包，可与上一版 debug 包覆盖安装。

本版强制启用零删除保护：目标端独有文件不删除，被更新文件的旧版本保存到
`.rootsync-history/<同步时间>/`；客户端与 rsync daemon 双重拒绝删除类选项。

v0.2.10 根据设备进程和 TCP 状态进一步定位大目录预览无输出问题。
测试目录约 303 GB、12197 个文件；双向模式的 `--checksum` 会在产生第一条差异结果前读取双方文件内容，用户取消后旧代码还会遗留 ROOT rsync 子进程。
本版改用大小和修改时间快速预览，并确保取消时结束经过 PID/命令行校验的 rsync 子进程。

局域网控制协议保持 v5；建议两端都安装 v0.2.10。

