# RootSync APK

- 文件：`RootSync-v0.2.55-debug-arm64.apk`
- applicationId：`com.rootsync.android.debug`
- versionCode / versionName：`57` / `0.2.55-debug`
- minSdk / targetSdk：`26` / `36`
- ABI：`arm64-v8a`
- 文件大小：12,674,450 字节
- APK SHA-256：`1572B1793C06B3AF5E2443775BF6B5093F97D9F5863BCD85F9BD1070B16D7758`
- 签名证书 SHA-256：`9ac1989e811dca262387317264b7ecfc72535278c2c6af0cf44b40344811db12`
- 签名与 v0.2.54 相同；APK Signature Scheme v2 验证通过，可覆盖安装。
- APK 16 KB 对齐检查与 syncmeta ELF 16 KB LOAD 对齐检查通过。

本次更新：ROOT 直接授权与管理器异步识别、只读清单差异计划、
Android/data ROOT 文件夹选择器、双端互补策略确认。

控制协议升级到 8，两端应同时安装 v0.2.55。
已通过 87 项 JVM 单元测试、11 组 SHA-256 向量及 34 组清单解析测试。
构建与 Lint 通过；当前未连接 ROOT 实机，双端验收清单见 docs/验证-v0.2.55.md。

APK 另存于 X:\临时同步，副本 SHA-256 一致。
