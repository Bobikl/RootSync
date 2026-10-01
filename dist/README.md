# RootSync APK

- 文件：`RootSync-v0.2.58-debug-arm64.apk`
- applicationId：`com.rootsync.android.debug`
- versionCode / versionName：`60` / `0.2.58-debug`
- minSdk / targetSdk：`26` / `36`
- ABI：`arm64-v8a`
- 文件大小：12,398,479 字节
- APK SHA-256：`04BB82204C4FB048413AFBD49865F84CA6C923A3985D28C245EB7DE4C4303D3B`
- 签名证书 SHA-256：`9ac1989e811dca262387317264b7ecfc72535278c2c6af0cf44b40344811db12`
- 签名与旧版一致，可覆盖安装；签名、版本、APK 16 KB 对齐检查通过。

修复 Android/data 剩余空间查询：普通身份失败时使用 ROOT 查询实际目录。
同步范围、起始时间、快速／严格比较模式随设备策略自动同步与确认。
ROOT 文件夹列表改为整页编码，并增加 15 秒短时显示缓存。
**控制协议为 9，双方都必须升级到 v0.2.58。**

121 项 JVM 单元测试全部通过，构建成功；Lint 0 错误、10 警告、2 提示。
B 设备只读验证：原空间查询被拒绝，新 ROOT 查询成功；100 项目录列表从 5389ms 降到 1938ms，结果一致。
没有安装新版、重启应用或执行真实文件同步，完整双端验收仍待用户安装验证。
详见 docs/验证-v0.2.58.md。

APK 已复制到 X:\临时同步，副本 SHA-256 相同。
