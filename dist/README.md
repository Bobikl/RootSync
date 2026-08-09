# RootSync APK

- 文件：`RootSync-v0.2.5-debug-arm64.apk`
- applicationId：`com.rootsync.android.debug`
- versionCode / versionName：`7` / `0.2.5-debug`
- minSdk / targetSdk：`26` / `36`
- ABI：`arm64-v8a`
- APK SHA-256：`0D48905BD2D6BC7828D4000A4588A9BC2131CC23CD61B9D5E39F9E5659DC9431`
- 内置 rsync 3.4.4 SHA-256：`38FF6DE1B36F18CE391DF512713192F3129B733C8F2FE55122DA26B0A600A6E9`
- 签名：Android Debug，APK Signature Scheme v2

这是调试签名技术验证包，可与上一版 debug 包覆盖安装。

本版强制启用零删除保护：目标端独有文件不删除，被更新文件的旧版本保存到
`.rootsync-history/<同步时间>/`；客户端与 rsync daemon 双重拒绝删除类选项。

v0.2.5 增加只发送、只接收和双向同步的“全部内容 / 指定时间至今”范围选项。
双向模式按较新修改时间收敛并使用校验和确认差异；两端仍强制零删除，覆盖前版本保留。

局域网控制协议已升级到 v5，参与同步的两端都需要安装 v0.2.5。

