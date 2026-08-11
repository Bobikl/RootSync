# RootSync APK

- 文件：`RootSync-v0.2.8-debug-arm64.apk`
- applicationId：`com.rootsync.android.debug`
- versionCode / versionName：`10` / `0.2.8-debug`
- minSdk / targetSdk：`26` / `36`
- ABI：`arm64-v8a`
- APK SHA-256：`010BC37D1C728A5BB57BEECA756439C5763D1281FC6459D360D285FE0DDB777E`
- 内置 rsync 3.4.4 SHA-256：`38FF6DE1B36F18CE391DF512713192F3129B733C8F2FE55122DA26B0A600A6E9`
- 签名：Android Debug，APK Signature Scheme v2

这是调试签名技术验证包，可与上一版 debug 包覆盖安装。

本版强制启用零删除保护：目标端独有文件不删除，被更新文件的旧版本保存到
`.rootsync-history/<同步时间>/`；客户端与 rsync daemon 双重拒绝删除类选项。

v0.2.8 修复 rsync itemize 输出带诊断前缀时差异文件无法显示的问题，结果面板现在逐项展示实际文件路径。
本版加入跨重启持久详细日志、系统文件导出和 ADB 读取命令，同时记录任务命令、原始输出、解析计数、退出码以及 daemon PID/端口诊断。
对端扫描结束后还会保留 8 秒完成状态，避免提示直接闪退消失。

局域网控制协议保持 v5；建议两端都安装 v0.2.8。

