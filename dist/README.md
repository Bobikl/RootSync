# RootSync APK

- 文件：`RootSync-v0.2.6-debug-arm64.apk`
- applicationId：`com.rootsync.android.debug`
- versionCode / versionName：`8` / `0.2.6-debug`
- minSdk / targetSdk：`26` / `36`
- ABI：`arm64-v8a`
- APK SHA-256：`3B7D2834B2CFACBA3F292C9E169D0F621034AD1989CF4687B6844CBFD50B2861`
- 内置 rsync 3.4.4 SHA-256：`38FF6DE1B36F18CE391DF512713192F3129B733C8F2FE55122DA26B0A600A6E9`
- 签名：Android Debug，APK Signature Scheme v2

这是调试签名技术验证包，可与上一版 debug 包覆盖安装。

本版强制启用零删除保护：目标端独有文件不删除，被更新文件的旧版本保存到
`.rootsync-history/<同步时间>/`；客户端与 rsync daemon 双重拒绝删除类选项。

v0.2.6 修复设备策略卡片长文字挤压、差异预览操作反馈不明显以及无差异时提示缺失。
预览按钮现在位于结果框上方，准备、扫描、完成和失败状态都会直接显示在框内。
对端会同步显示“对方正在扫描差异文件夹”，结束后自动清除。

局域网控制协议保持 v5；双端扫描状态提示需要两端都安装 v0.2.6。

