# RootSync APK

- 文件：`RootSync-v0.2.24-debug-arm64.apk`
- applicationId：`com.rootsync.android.debug`
- versionCode / versionName：`26` / `0.2.24-debug`
- minSdk / targetSdk：`26` / `36`
- ABI：`arm64-v8a`
- APK SHA-256：`64B31D088624205B8D0CEAEF42EBDDA4AA57CD4C7856D51709481E046C5D85FB`
- 内置 rsync 3.4.4 SHA-256：`38FF6DE1B36F18CE391DF512713192F3129B733C8F2FE55122DA26B0A600A6E9`
- 签名：Android Debug，APK Signature Scheme v2

这是调试签名技术验证包，可覆盖安装此前的 RootSync debug 包。v0.2.15 至
v0.2.24 的每个修复版本均保存在本目录，并对应同名 Git 标签；完整说明见
`docs/修复回滚清单.md`。

本版继续强制零删除保护：目标端独有文件不删除，被更新文件的旧版本保存到
`.rootsync-history/<同步时间>/`；客户端与 rsync daemon 双重拒绝删除类选项。

本轮重点改进后台接收保活、任务互斥、远端心跳、按设备隔离的续传记录、有界日志、
界面刷新节流、局域网请求重试和短时预览统计复用。局域网控制协议升级到 v7，策略、
服务准备、进度和密钥轮换消息均验证设备控制令牌。

两端必须同时安装 v0.2.24；已有配对配置会在覆盖安装后自动迁移，无需重新配对。
