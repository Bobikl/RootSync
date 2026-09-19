# RootSync APK

- 文件：`RootSync-v0.2.56-debug-arm64.apk`
- applicationId：`com.rootsync.android.debug`
- versionCode / versionName：`58` / `0.2.56-debug`
- minSdk / targetSdk：`26` / `36`
- ABI：`arm64-v8a`
- 文件大小：12,369,147 字节
- APK SHA-256：`C5BD3B6C2600CEF24E3A37E19919A528ADBE830A3AD709BEA449446D34BCF97B`
- 签名证书 SHA-256：`9ac1989e811dca262387317264b7ecfc72535278c2c6af0cf44b40344811db12`
- 签名与 v0.2.55 相同，签名验证与 APK 16 KB 对齐检查通过，可覆盖安装。

本次修复 ROOT 授权后的启动等待：移除启动时全机进程扫描，清理限时 2 秒；
版本诊断限时并优先直接执行；引擎结果逐项显示，日志记录阶段耗时。
控制协议仍为 8。

94 项 JVM 单元测试通过；构建成功，Lint 0 错误、10 警告、2 提示。
已读取旧版实机日志并测量只读命令；按用户要求未安装新版，新版启动实测待用户安装。
详细记录见 docs/验证-v0.2.56.md。
APK 另存于 X:\临时同步，副本 SHA-256 一致。
