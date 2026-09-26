# RootSync APK

- 文件：`RootSync-v0.2.57-debug-arm64.apk`
- applicationId：`com.rootsync.android.debug`
- versionCode / versionName：`59` / `0.2.57-debug`
- minSdk / targetSdk：`26` / `36`
- ABI：`arm64-v8a`
- 文件大小：12,706,565 字节
- APK SHA-256：`4AA3C5BC3D0C526493193AB7D7C48D266B0D78AD80F7E7981F438F86385663A7`
- 签名证书 SHA-256：`9ac1989e811dca262387317264b7ecfc72535278c2c6af0cf44b40344811db12`
- 签名与旧版一致，可覆盖安装；签名、APK 16 KB 对齐与 syncmeta ELF LOAD 16 KB 对齐检查通过。

修复重复信任恢复；差异预览新增阶段／耗时／实际扫描项目数、哈希读取量和比较阶段百分比。
原生扫描进度可通过准备心跳回传；总量未知使用动态进度条，不假造整体百分比。
两端建议均升级，控制协议仍为 8。

105 项 JVM 单元测试通过，构建和 Lint 通过（0 错误）。
原生工具 NDK -Werror 编译通过；11 组 SHA-256 向量和 34 组清单解析测试通过。
未安装新版或操作用户手机；双端实机验收待执行，详见 docs/验证-v0.2.57.md。

本次 X: 盘未挂载，无法复制到原来的临时同步目录；请使用本目录 APK。
