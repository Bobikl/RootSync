# RootSync APK

- 文件：`RootSync-v0.2.26-debug-arm64.apk`
- applicationId：`com.rootsync.android.debug`
- versionCode / versionName：`28` / `0.2.26-debug`
- minSdk / targetSdk：`26` / `36`
- ABI：`arm64-v8a`
- APK SHA-256：`6A6D694A943E21CDC3E81B4D946273837288DB31CAEA720C2A4CB7A0B60F2E71`
- 内置 rsync 3.4.4 SHA-256：`38FF6DE1B36F18CE391DF512713192F3129B733C8F2FE55122DA26B0A600A6E9`
- 签名：Android Debug，APK Signature Scheme v2

v0.2.25 将生命周期看门狗改成带唯一脚本路径和 PID 文件的独立 `sh` 进程。重复任务
不再反复创建看门狗；应用异常退出时会清理本应用 rsync、daemon 以及孤立看门狗。

v0.2.26 在正式传输结束后，为本次实际新增或更新的文件建立 NUL 分隔清单，并执行
rsync `--checksum --dry-run` 零写入校验。校验失败时任务保持暂停；只有校验全部通过，
界面、传输记录和系统通知才会显示同步完成。

零删除保护保持不变：目标端独有文件不会删除，被更新文件的旧版本保存在
`.rootsync-history/<同步时间>/`，完整性校验命令同样不包含任何删除或写入选项。

两端必须同时安装 v0.2.26。完整分步版本见 `docs/修复回滚清单.md`。
