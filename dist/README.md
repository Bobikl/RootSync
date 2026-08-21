# RootSync APK

- 文件：`RootSync-v0.2.30-debug-arm64.apk`
- applicationId：`com.rootsync.android.debug`
- versionCode / versionName：`32` / `0.2.30-debug`
- minSdk / targetSdk：`26` / `36`
- ABI：`arm64-v8a`
- APK SHA-256：`EC864F8D20D44BE4E131BB557806F9216FB13920C8B9059F67642576A36EAE8F`
- 内置 rsync 3.4.4 SHA-256：`38FF6DE1B36F18CE391DF512713192F3129B733C8F2FE55122DA26B0A600A6E9`
- 签名：Android Debug，APK Signature Scheme v2

v0.2.27 将 rsync 人类可读单位和界面容量统一为十进制 SI，修复大型目录同步总量与
实际传输量相差约 7.37% 的问题。v0.2.28 在开始任务前主动探测所选配对设备，离线时
禁止预览、传输和继续。v0.2.29 在任务期间锁定同步页全部配置控件，仅保留暂停或取消。

v0.2.30 在接收目录不存在时由接收设备弹窗确认是否创建，等待期间发送设备显示
“对方正在选择操作”。拒绝或 110 秒无响应时不会创建目录，也不会启动 rsync 写入；
同意后才通过 ROOT 创建并继续。后台收到请求时同时显示前台服务通知，便于用户返回确认。

零删除保护保持不变：目标端独有文件不会删除，被更新文件的旧版本保存在
`.rootsync-history/<同步时间>/`，完整性校验命令同样不包含任何删除或写入选项。

建议两端同时安装 v0.2.30。完整分步版本见 `docs/修复回滚清单.md`。
