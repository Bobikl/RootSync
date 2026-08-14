# RootSync APK

- 文件：`RootSync-v0.2.13-debug-arm64.apk`
- applicationId：`com.rootsync.android.debug`
- versionCode / versionName：`15` / `0.2.13-debug`
- minSdk / targetSdk：`26` / `36`
- ABI：`arm64-v8a`
- APK SHA-256：`F9198581D00A4855485F89DA6A557756D85C8078680E654897EDD43570C1D980`
- 内置 rsync 3.4.4 SHA-256：`38FF6DE1B36F18CE391DF512713192F3129B733C8F2FE55122DA26B0A600A6E9`
- 签名：Android Debug，APK Signature Scheme v2

这是调试签名技术验证包，可与上一版 debug 包覆盖安装。

本版强制启用零删除保护：目标端独有文件不删除，被更新文件的旧版本保存到
`.rootsync-history/<同步时间>/`；客户端与 rsync daemon 双重拒绝删除类选项。

v0.2.13 修复大量差异项目通过 UDP 逐条通知时丢包、导致发起端与接收端计数相差一个数量级的问题。
每项消息携带累计序号并限流，任务结束消息携带权威总数并重复发送校准。

传输前通过 dry-run 统计同步总量，界面分别显示同步总量、已上传和已下载字节。
预计剩余时间在实际传输 10 秒后开始显示，按近 10 秒平均速度计算，并每 10 秒更新一次。

局域网控制协议保持 v6 并增加兼容扩展字段；建议两端都安装 v0.2.13。

