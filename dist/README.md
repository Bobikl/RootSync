# RootSync APK

- 文件：`RootSync-v0.2.59-debug-arm64.apk`
- applicationId：`com.rootsync.android.debug`
- versionCode / versionName：`61` / `0.2.59-debug`
- minSdk / targetSdk：`26` / `36`
- ABI：`arm64-v8a`
- 文件大小：12,399,519 字节
- APK SHA-256：`7BA9BD42E117FD2042B7C85ABDE3AA3A0256E2E35629614461093F91D4E61F23`
- 签名证书 SHA-256：`9ac1989e811dca262387317264b7ecfc72535278c2c6af0cf44b40344811db12`
- 签名与旧版一致，可覆盖安装；签名、版本、APK 16 KB 对齐检查通过。

目录列表整页只执行一次 printf，消除逐目录启动外部输出程序的开销；200ms 内完成不闪烁加载提示。
B 设备同一 100 项目录页、不使用应用目录缓存的命令实测：1797ms → 中位 125ms（约 14 倍），名称、排序和分页完全一致。
协议仍为 9，可与 v0.2.58 互通，无需为协议兼容强制另一端同时升到 v0.2.59。

122 项 JVM 单元测试全部通过；构建成功，Lint 0 错误、10 警告、2 提示。
仅在 B 设备执行只读命令，没有安装、重启应用或写入手机文件；新版完整 UI 验收待用户安装。
详细记录见 docs/验证-v0.2.59.md。

APK 已复制到 X:\临时同步，副本 SHA-256 相同。
