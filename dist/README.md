# RootSync APK

- 文件：`RootSync-v0.2.14-debug-arm64.apk`
- applicationId：`com.rootsync.android.debug`
- versionCode / versionName：`16` / `0.2.14-debug`
- minSdk / targetSdk：`26` / `36`
- ABI：`arm64-v8a`
- APK SHA-256：`D10BDFDD018EC5752D4566B97B05D43AF7F673C307354B9681959ACC2B17E6DF`
- 内置 rsync 3.4.4 SHA-256：`38FF6DE1B36F18CE391DF512713192F3129B733C8F2FE55122DA26B0A600A6E9`
- 签名：Android Debug，APK Signature Scheme v2

这是调试签名技术验证包，可与上一版 debug 包覆盖安装。

本版强制启用零删除保护：目标端独有文件不删除，被更新文件的旧版本保存到
`.rootsync-history/<同步时间>/`；客户端与 rsync daemon 双重拒绝删除类选项。

v0.2.14 将正在传输的文件与目录改为每 3 秒刷新一次，避免高速传输时逐文件刷新界面。
运行中的“传输记录”卡片不再显示，只在暂停、中断或完成后展示。

主进度条直接引用既有的同步总量、已上传和已下载字节进度，不使用 rsync 百分比覆盖。
应用在前台正式传输期间保持屏幕常亮，任务暂停、结束或 Activity 销毁后自动解除。

局域网控制协议保持 v6 并增加兼容扩展字段；建议两端都安装 v0.2.14。

