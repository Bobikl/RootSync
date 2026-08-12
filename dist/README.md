# RootSync APK

- 文件：`RootSync-v0.2.11-debug-arm64.apk`
- applicationId：`com.rootsync.android.debug`
- versionCode / versionName：`13` / `0.2.11-debug`
- minSdk / targetSdk：`26` / `36`
- ABI：`arm64-v8a`
- APK SHA-256：`BA1B0E45FA10D6D3217176161023329D6F0D51774F5A1B7A1BF87BD04AFF11AC`
- 内置 rsync 3.4.4 SHA-256：`38FF6DE1B36F18CE391DF512713192F3129B733C8F2FE55122DA26B0A600A6E9`
- 签名：Android Debug，APK Signature Scheme v2

这是调试签名技术验证包，可与上一版 debug 包覆盖安装。

本版强制启用零删除保护：目标端独有文件不删除，被更新文件的旧版本保存到
`.rootsync-history/<同步时间>/`；客户端与 rsync daemon 双重拒绝删除类选项。

v0.2.11 根据 B 端详细日志修复任务状态快速消失：服务准备阶段不再提前创建活动状态，活动消息携带 taskId 并对重复 UDP 包去重。
发起端会把当前差异/传输文件路径同步给对端，对端详情面板可显示对方正在处理的文件与目录。
设备策略标签新增在线状态圆点，使用轻量 UDP 单播探测判断在线状态。

局域网控制协议升级为 v6；两端必须都安装 v0.2.11。

