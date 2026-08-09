# Android ROOT + rsync + Kotlin/Compose 同步工具开发大纲

> 文档性质：产品需求、技术架构与实施计划大纲  
> 目标场景：两台已 ROOT 的 Android 手机，在局域网内同步指定目录，重点支持 `Android/data/com.danmaku.bili/download`，可靠保留文件及文件夹修改时间。  
> 推荐形态：同一个 APK 同时具备发送端、接收端和任务管理能力。

---

## 1. 项目目标

### 1.1 核心目标

1. 在两台 Android 手机之间直接同步目录，不经过云端或 NAS。
2. 使用 rsync 实现增量扫描、差异传输、断点续传和镜像同步。
3. 通过 ROOT 权限访问其他应用位于 `Android/data` 下的目录。
4. 同步后保留：
   - 普通文件内容；
   - 相对路径和目录结构；
   - 文件修改时间 `mtime`；
   - 文件夹修改时间 `mtime`；
   - 空文件夹；
   - 可选的文件权限、属主、符号链接等高级属性。
5. 提供 Kotlin + Jetpack Compose 图形界面，用于设备配对、目录选择、任务配置、执行进度和日志查看。
6. 支持 300 MB、数 GB 乃至更大文件，不因固定大小、超时或连接复用问题中断。
7. 传输失败后可以重试或续传，而不是重新复制全部数据。

### 1.2 首要业务场景

```text
手机 A：/storage/emulated/0/Android/data/com.danmaku.bili/download
                              ↓ 局域网 rsync
手机 B：/storage/emulated/0/Android/data/com.danmaku.bili/download
```

首版优先支持：

- A → B 单向复制；
- A → B 单向镜像；
- B → A 交换方向；
- 手动立即同步；
- 同步后恢复所有目录的原始 `mtime`；
- 可选在同步前强制停止哔哩哔哩，避免同步期间修改下载目录。

### 1.3 暂不纳入首版

1. 公网穿透、中继服务器和账号云服务。
2. 多人共享和复杂权限管理。
3. 类似网盘的版本历史浏览。
4. 自动解决双向编辑冲突。
5. Google Play 上架适配。
6. 无 ROOT 状态下通用访问其他应用的 `Android/data`。
7. 永久冻结目录时间；首版只保证“同步任务完成时”恢复正确。

---

## 2. 关键技术结论

### 2.1 为什么选择 rsync

1. 原生支持递归目录同步和增量传输。
2. 使用文件大小和修改时间快速判断差异，也可选用校验和进行严格判断。
3. 支持 `--partial`、`--partial-dir`、超时和重试策略。
4. `--times/-t` 可以传递修改时间，默认包括文件和目录。
5. 支持复制、更新、镜像删除、过滤规则和 dry-run。
6. 能将协议引擎与 Android UI 分离，方便独立测试。

### 2.2 为什么必须增加目录时间二次恢复机制

仅依赖 rsync 的 `-t` 仍可能受到以下因素影响：

- 接收端临时文件创建、重命名和删除会更新父目录时间；
- `.rsync-partial` 等临时目录会更新上级目录时间；
- 同步完成前后的清理操作可能再次改变目录时间；
- 哔哩哔哩可能在后台扫描、创建或修改下载任务；
- Android 的 emulated storage 层可能降低时间精度；
- 不同 ROOT 方案、ROM 和 SELinux 配置可能产生不同结果。

因此需要独立的“源端目录时间清单 + 接收端最终恢复”流程，不能只相信协议层。

### 2.3 Android/data 访问策略

首版采用 ROOT 专用模式：

1. App 普通进程负责 UI、任务调度、数据库和网络状态。
2. 需要读写受限目录时，通过 `libsu` 请求 ROOT shell。
3. rsync 服务端和接收端进程在受控的 ROOT 环境中运行。
4. ROOT 进程只暴露同步配置指定的目录，不允许客户端浏览整个根文件系统。
5. 目录时间恢复由 ROOT 原生辅助程序完成。

需要兼容的 ROOT 管理方案：

- Magisk；
- KernelSU；
- APatch；
- 其他提供标准 `su` 接口的方案。

---

## 3. 产品形态与工作模式

### 3.1 单 APK 双角色

每台手机安装相同 APK，可切换：

- **服务端模式**：共享一个或多个经过授权的本地目录；
- **客户端模式**：连接另一台手机并执行同步；
- **混合模式**：后台提供服务，同时允许主动连接其他设备。

### 3.2 同步模式

#### 复制 Copy

- 源端新增或改变的内容复制到目标端；
- 不删除目标端独有内容；
- 适合首次测试和普通备份。

#### 更新 Update

- 仅当源文件比目标文件更新或内容不同才复制；
- 可选择“目标较新时跳过”或“源端绝对覆盖”。

#### 镜像 Mirror

- 目标端最终与源端一致；
- 删除目标端多余文件；
- 默认必须先执行 dry-run；
- 必须设置单次最大删除数量或比例保护。

#### 移动 Move（后续版本）

- 目标端验证成功后删除源文件；
- 需要额外防断电、防误删和校验保护。

#### 双向同步（后续版本）

- 首版不做自动双向同步；
- 后续可基于快照数据库实现三方比较；
- 必须定义冲突副本和删除冲突规则。

---

## 4. 总体架构

```text
┌─────────────────────────────────────────────┐
│                Compose UI                   │
│  首页 / 设备 / 同步任务 / 进度 / 日志 / 设置 │
└──────────────────────┬──────────────────────┘
                       │ ViewModel + Flow
┌──────────────────────▼──────────────────────┐
│               Domain / UseCase              │
│ 配对、扫描、预览差异、执行、取消、恢复、校验 │
└───────────────┬───────────────────┬─────────┘
                │                   │
┌───────────────▼──────────┐  ┌─────▼────────────────┐
│ Task Repository / Room   │  │ Foreground Service   │
│ 配置、设备、历史、日志     │  │ 生命周期/WakeLock/通知 │
└──────────────────────────┘  └─────┬────────────────┘
                                    │
┌───────────────────────────────────▼─────────┐
│                 Sync Engine                 │
│ RsyncCommandBuilder / Planner / Parser      │
│ Retry / Resume / Validation / Time Restore  │
└───────────────┬───────────────────┬─────────┘
                │                   │
┌───────────────▼──────────┐  ┌─────▼────────────────┐
│ Root Bridge (libsu)      │  │ Network/Discovery    │
│ su、进程、文件描述符       │  │ NSD/UDP、连接检测      │
└───────────────┬──────────┘  └──────────────────────┘
                │
┌───────────────▼─────────────────────────────┐
│ Native Layer                                │
│ rsync binary + syncmeta helper + launcher   │
│ stat/lstat/utimensat/进程终止/路径边界检查    │
└─────────────────────────────────────────────┘
```

### 4.1 推荐模块划分

首版可以先单 app module 分包，稳定后再拆 Gradle module。

```text
app/
├─ ui/
│  ├─ home/
│  ├─ devices/
│  ├─ profiles/
│  ├─ transfer/
│  ├─ logs/
│  └─ settings/
├─ domain/
│  ├─ model/
│  ├─ repository/
│  └─ usecase/
├─ data/
│  ├─ db/
│  ├─ datastore/
│  └─ repository/
├─ engine/
│  ├─ rsync/
│  ├─ planning/
│  ├─ validation/
│  └─ timestamp/
├─ root/
│  ├─ RootManager.kt
│  ├─ RootProcess.kt
│  └─ SafeCommand.kt
├─ network/
│  ├─ discovery/
│  ├─ pairing/
│  └─ monitor/
├─ service/
│  ├─ SyncForegroundService.kt
│  └─ RsyncServerService.kt
└─ native/
   ├─ rsync/<abi>/rsync
   └─ syncmeta/<abi>/syncmeta
```

后续可拆分为：

```text
:app
:core:model
:core:data
:core:root
:core:network
:engine:rsync
:feature:devices
:feature:profiles
:feature:transfer
:feature:logs
```

---

## 5. 技术栈

### 5.1 Android 层

- Kotlin；
- Jetpack Compose + Material 3；
- Navigation Compose；
- Coroutines + Flow；
- Room：设备、任务和执行历史；
- DataStore：用户设置；
- WorkManager：定时触发和条件检查；
- Foreground Service：真正执行长时间同步；
- Android Keystore：保护配对密钥和 rsync secret；
- Hilt 或 Koin：依赖注入，可在首版二选一；
- `libsu`：ROOT shell 管理。

### 5.2 Native 层

- rsync：固定版本源码交叉编译；
- Android NDK + CMake；
- `syncmeta` 小型原生工具：
  - `lstat/stat`；
  - 导出目录时间；
  - `utimensat()` 恢复时间；
  - 校验路径是否位于允许根目录；
  - 输出 JSON Lines 或 CBOR；
- 首版 ABI：`arm64-v8a`；
- 后续增加：`armeabi-v7a`、`x86_64` 测试构建。

### 5.3 最低系统建议

- `minSdk`：Android 8.0 / API 26；
- `targetSdk`：使用开发时最新稳定 SDK；
- 重点测试 Android 11 及以上版本。

---

## 6. ROOT 权限与进程模型

### 6.1 ROOT 首次授权流程

1. App 启动时只检查是否存在可用的 `su`。
2. 用户首次启用 ROOT 功能时说明用途。
3. 调用 `libsu` 请求 ROOT。
4. 执行能力探测：
   - 当前 UID 是否为 0；
   - 是否能读取目标目录；
   - 是否能创建、删除临时文件；
   - 是否能调用 `utimensat()` 修改目录时间；
   - 是否能启动并终止 rsync；
   - SELinux 是否阻止相关操作。
5. 生成探测结果页面，失败项可单独重试。

### 6.2 ROOT 辅助进程原则

- 不让 Compose/UI 进程本身长期以 ROOT 运行；
- 每次任务生成最小权限配置；
- ROOT 进程只允许访问同步根目录；
- 不拼接未经验证的路径到 shell；
- 所有参数必须经过统一 argv 转义或通过配置文件/管道传递；
- 保存子进程 PID，支持可靠取消；
- App 异常退出后，下次启动清理孤儿进程和过期临时文件。

### 6.3 哔哩哔哩进程控制

任务可配置：

- 不处理应用进程；
- 同步前执行 `am force-stop com.danmaku.bili`；
- 仅在检测到目标目录活跃写入时提示；
- 同步结束后不自动启动应用；
- 记录同步后目录再次被应用修改的情况。

---

## 7. rsync 传输架构

### 7.1 MVP 网络模式

首版采用 rsync daemon 模式：

```text
手机 A：ROOT 启动 rsync --daemon
手机 B：ROOT 启动 rsync client，连接 rsync://A_IP:PORT/module/
```

优点：

- 实现简单；
- 不依赖完整 SSH 服务；
- 易于捕获 rsync 标准输出和进度；
- 手机之间可以互换服务端/客户端角色。

限制：

- rsync daemon 流量默认不加密；
- MVP 只允许可信局域网使用；
- 后续应增加 SSH 隧道、TLS 包装或应用层加密通道。

### 7.2 服务端配置生成

每次启动动态生成配置，禁止使用用户直接编辑的任意配置。

示例结构：

```ini
pid file = /data/user/0/<package>/files/runtime/rsync.pid
log file = /data/user/0/<package>/files/runtime/rsync-server.log
use chroot = false
read only = false
list = false
hosts allow = <paired-client-ip>
max connections = 1
timeout = 300

[sync]
path = /storage/emulated/0/Android/data/com.danmaku.bili/download
auth users = sync-user
secrets file = /data/user/0/<package>/files/runtime/rsync.secret
```

安全要求：

- 模块名随机化或使用任务 ID；
- secret 每次配对生成高熵值；
- secret 文件仅 ROOT/应用可读；
- 绑定当前 Wi-Fi 地址，而不是所有公网接口；
- 任务结束后可以自动停止服务端；
- 严格校验模块路径的 canonical path。

### 7.3 客户端推荐参数基线

共享存储不适合完整保留 Linux owner/group/device，因此不要盲目使用全部 `-a` 属性。

推荐从以下参数组合开始验证：

```text
rsync
  -rlt
  --human-readable
  --info=progress2,stats2,name1
  --partial
  --partial-dir=.rsync-partial
  --delay-updates
  --timeout=300
  --contimeout=30
  --no-perms
  --no-owner
  --no-group
```

镜像模式追加：

```text
--delete-delay
--max-delete=<用户配置值>
```

严格校验模式可选：

```text
--checksum
```

注意事项：

- 首次开发必须验证 Android 版 rsync 对 `--info`、`--partial-dir` 等参数的支持；
- 不支持的参数应通过 capability probe 动态禁用；
- 中文、空格、换行符和特殊字符路径不能依赖文本切割解析；
- 密码通过受保护的 password file 或环境传入，不显示在进程参数和日志中。

### 7.4 传输方向

#### Pull 模式（首版优先）

- 接收端主动从发送端拉取；
- 目录时间最终恢复在接收端本机完成；
- 接收端更容易控制临时文件、校验和恢复顺序。

#### Push 模式

- 后续加入；
- 适合由源手机主动推送；
- 仍需目标端服务协助执行最后的时间恢复。

---

## 8. 文件夹时间保留方案

### 8.1 时间定义

首版明确保留：

- 文件 `mtime`；
- 目录 `mtime`。

不承诺：

- `ctime`：Unix/Android 上不能任意设置；
- 创建时间/birth time：文件系统和 Android 接口并不统一；
- `atime`：可能被挂载参数禁用或在读取时改变。

### 8.2 源端目录时间清单

同步开始后，源端通过 `syncmeta snapshot` 生成清单：

```json
{"version":1,"root":"/storage/emulated/0/Android/data/com.danmaku.bili/download","createdAt":1780000000}
{"type":"dir","path":".","mtimeSec":1750000000,"mtimeNsec":123456789}
{"type":"dir","path":"12345","mtimeSec":1740000000,"mtimeNsec":0}
{"type":"dir","path":"12345/80","mtimeSec":1740001000,"mtimeNsec":0}
```

规则：

- 清单只保存相对路径；
- 禁止 `..`、绝对路径和越界符号链接；
- 使用 epoch seconds + nanoseconds，不保存本地时区字符串；
- 清单自身放在 App 私有目录，不放进待同步目录；
- 记录源端扫描开始和结束时间；
- 可选记录目录数量、文件数量和摘要。

### 8.3 一致性策略

源端扫描和传输期间，文件仍可能改变。首版提供两种模式：

1. **快速模式**：记录一次快照后直接传输，结束时检查是否有变化；
2. **安静模式**：先强制停止哔哩哔哩，再生成快照并同步。

若同步过程中源端发生变化：

- 任务标记为“完成但源端有变化”；
- 自动执行第二轮快速同步；
- 最多重试指定轮数，避免应用持续写入时无限循环。

### 8.4 接收端恢复顺序

必须在以下操作全部完成后恢复目录时间：

1. 文件传输完成；
2. 临时文件重命名完成；
3. 镜像删除完成；
4. `.rsync-partial` 等临时目录完成清理；
5. 文件内容和关键文件时间校验完成。

然后按照目录深度从深到浅恢复：

```text
download/12345/80
download/12345
download
```

原因：修改子目录内容会改变父目录时间；父目录必须最后恢复。

### 8.5 时间恢复验证

恢复后重新 `lstat`：

- 比较秒和纳秒；
- 根据文件系统能力设置允许误差，例如 0、1 或 2 秒；
- 失败目录单独重试一次；
- 最终日志列出无法恢复的相对路径、期望值、实际值和 errno；
- UI 显示“目录时间全部匹配”或“部分目录不匹配”。

### 8.6 防止同步后立即被应用改回当前时间

可选策略：

- 同步完成后保持哔哩哔哩停止状态；
- 延迟 2～5 秒再次检查和恢复一次；
- 监控短时间内的目录变化并提示“目标应用已再次修改目录”；
- 不提供长期循环强制改时间的默认功能。

---

## 9. 差异判断与校验策略

### 9.1 快速判断

默认使用：

- 相对路径；
- 类型；
- 文件大小；
- `mtime`。

适合大部分视频下载目录，扫描速度快。

### 9.2 严格判断

用户可启用校验和：

- 小文件全量哈希；
- 大文件按需哈希；
- 传输后的 rsync 整文件校验仍保留；
- 可选生成 BLAKE3/SHA-256 结果用于任务报告。

### 9.3 时间精度

- 内部统一使用 Unix epoch；
- 不使用格式化日期作为比较依据；
- 自动探测源端和目标端时间精度；
- 支持设置 `modify-window` 类似容差；
- 检测两台手机系统时钟偏差并提示，但 `mtime` 传输本身以 epoch 为准。

---

## 10. 设备发现与配对

### 10.1 MVP

- 手动输入 IP 和端口；
- 服务端显示本机 Wi-Fi IP；
- 显示临时配对码或二维码；
- 客户端扫码导入：设备 ID、IP、端口、临时 secret 和指纹。

### 10.2 自动发现

后续增加：

- Android NSD/mDNS；
- UDP 局域网广播作为兼容后备；
- 只显示同一网段设备；
- 设备使用长期随机 ID，不以 IP 作为唯一标识；
- IP 变化后可以重新发现。

### 10.3 网络变化处理

- 使用 `ConnectivityManager.NetworkCallback`；
- 同步过程中 Wi-Fi 断开立即暂停/失败重试；
- IP 改变后重新发现服务端；
- 可配置是否允许热点网络；
- 默认禁止移动数据网络执行。

---

## 11. 数据模型

### 11.1 Device

```text
id
displayName
publicIdentity/fingerprint
lastKnownHost
lastKnownPort
lastSeenAt
trusted
capabilities
```

### 11.2 SyncProfile

```text
id
name
sourceDeviceId
sourcePath
destinationDeviceId
destinationPath
direction
mode: COPY / UPDATE / MIRROR
preserveFileTime
preserveDirectoryTime
verificationMode
deletePolicy
filters
stopPackagesBeforeSync
schedule
networkConstraint
enabled
```

### 11.3 SyncRun

```text
id
profileId
startedAt
finishedAt
status
phase
bytesTotal
bytesTransferred
filesScanned
filesTransferred
filesDeleted
dirsRestored
warningCount
errorCode
rsyncExitCode
logPath
```

### 11.4 FilterRule

```text
id
profileId
order
type: INCLUDE / EXCLUDE
pattern
enabled
```

### 11.5 DeletePlan

```text
runId
relativePath
type
size
reason
approved
```

---

## 12. 任务状态机

```text
IDLE
  ↓
CHECKING_REQUIREMENTS
  ↓
ACQUIRING_ROOT
  ↓
STOPPING_TARGET_APPS（可选）
  ↓
DISCOVERING_OR_CONNECTING
  ↓
STARTING_SERVER
  ↓
SNAPSHOTTING_SOURCE
  ↓
DRY_RUNNING（镜像首次必需）
  ↓
WAITING_DELETE_CONFIRMATION（按策略）
  ↓
TRANSFERRING
  ↓
VERIFYING_FILES
  ↓
CLEANING_TEMP_FILES
  ↓
RESTORING_DIRECTORY_TIMES
  ↓
VERIFYING_DIRECTORY_TIMES
  ↓
STOPPING_SERVER
  ↓
COMPLETED / COMPLETED_WITH_WARNINGS / FAILED / CANCELLED
```

要求：

- 状态写入 Room，进程重启后可识别未完成任务；
- 每个阶段可报告进度和人类可读说明；
- 取消任务时先发送温和终止，再超时强制结束；
- 任何失败都要保存 rsync exit code、阶段和最后错误输出。

---

## 13. UI 页面大纲

### 13.1 首页

- ROOT 状态；
- 服务端开关；
- 最近发现设备；
- 常用同步任务；
- 最近一次同步结果；
- “立即同步”主按钮。

### 13.2 ROOT 检测页

- ROOT 管理器类型；
- `su` 授权状态；
- 目标目录读写测试；
- 时间修改测试；
- rsync 可执行测试；
- 一键导出诊断报告。

### 13.3 设备页

- 扫描局域网设备；
- 手动添加 IP；
- 扫二维码配对；
- 信任/取消信任；
- 延迟、协议版本、rsync 版本和能力信息。

### 13.4 同步任务编辑页

- 任务名称；
- 源设备/目录；
- 目标设备/目录；
- 方向交换；
- 复制/更新/镜像；
- 是否保留文件时间；
- 是否保留文件夹时间；
- 是否强制停止指定应用；
- 排除规则；
- 严格校验；
- 删除保护；
- 网络和电量约束；
- 定时计划。

### 13.5 Dry-run 预览页

- 将新增的文件；
- 将更新的文件；
- 将删除的文件；
- 总传输大小；
- 删除数量及比例；
- 异常路径和权限问题；
- 确认执行按钮。

### 13.6 传输进度页

- 当前阶段；
- 总进度和当前文件进度；
- 实时速度；
- 已传输/总字节；
- 当前文件相对路径；
- 剩余时间估算；
- 暂停、取消；
- 展开实时日志。

### 13.7 任务结果页

- 成功/警告/失败；
- 扫描、复制、跳过、删除统计；
- 文件校验结果；
- 文件夹时间恢复结果；
- 平均速度和耗时；
- 错误路径列表；
- 导出日志。

### 13.8 设置页

- 默认端口；
- 并发/带宽限制；
- 超时和重试次数；
- 日志级别；
- 日志保留天数；
- 默认删除保护；
- Wi-Fi Lock/WakeLock；
- rsync 版本和许可证；
- 开源许可证列表。

---

## 14. 后台任务与系统限制

### 14.1 Foreground Service

- 长时间同步必须运行前台服务；
- 通知显示进度、速度、暂停和取消操作；
- Activity 销毁不影响任务；
- 任务结束后及时释放服务。

### 14.2 WorkManager

只负责：

- 按计划唤醒；
- 检查 Wi-Fi、电量和充电条件；
- 启动前台服务。

不直接承载长时间 rsync 子进程。

### 14.3 电量与网络锁

- 同步期间申请 `PARTIAL_WAKE_LOCK`；
- 必要时申请高性能 Wi-Fi Lock；
- 必须使用 `try/finally` 释放；
- 电量低于阈值时可暂停新任务；
- 避免多个同步任务同时争用同一目录。

---

## 15. 安全设计

### 15.1 路径安全

- 所有路径 canonicalize 后必须位于任务允许根目录；
- 拒绝 `..`、NUL、绝对相对路径混淆；
- 对符号链接单独定义策略；
- 不跟随指向允许根目录外的符号链接；
- rsync module 不允许列出其他目录。

### 15.2 删除保护

- 镜像任务首次执行强制 dry-run；
- 默认最大删除数量，例如 100；
- 默认最大删除比例，例如目标项目的 10%；
- 超过阈值必须人工确认；
- 根目录为空或异常扫描结果时禁止执行 `--delete`；
- 源目录读取失败时绝不能把“空目录”当作真实源状态；
- 任务保存后路径发生变化时重新确认。

### 15.3 命令注入防护

- 不直接执行字符串拼接的任意 shell 命令；
- 使用固定命令模板和白名单参数；
- 路径优先通过配置文件、stdin 或 NUL 分隔形式传递；
- 日志中隐藏密码、secret 和完整令牌；
- 任何用户输入都不能成为 rsync 配置键名。

### 15.4 配对和网络

- 每台设备生成唯一身份密钥；
- 配对必须在两端确认或扫码；
- rsync secret 使用 Android Keystore 包装存储；
- 只允许已配对 IP/设备连接；
- 服务端超时自动停止；
- 后续版本增加加密传输，公网环境未加密时禁止启用。

---

## 16. 日志、诊断与错误处理

### 16.1 日志分层

- UI 操作日志；
- 任务状态日志；
- rsync stdout/stderr；
- ROOT shell 日志；
- 服务端连接日志；
- 时间恢复与验证日志。

### 16.2 日志格式

推荐结构化 JSON Lines，至少包含：

```text
timestamp
runId
level
component
phase
event
relativePath（可选）
message
errorCode（可选）
```

### 16.3 常见错误码

```text
ROOT_DENIED
ROOT_UNAVAILABLE
SOURCE_NOT_READABLE
DESTINATION_NOT_WRITABLE
PATH_OUTSIDE_ALLOWED_ROOT
NETWORK_UNAVAILABLE
PAIRING_FAILED
SERVER_START_FAILED
RSYNC_CONNECT_FAILED
RSYNC_TIMEOUT
RSYNC_EXIT_NONZERO
SOURCE_CHANGED_DURING_SYNC
DELETE_LIMIT_EXCEEDED
VERIFY_FAILED
DIR_MTIME_RESTORE_FAILED
CANCELLED_BY_USER
```

### 16.4 诊断包

导出时包含：

- App/系统/设备/ROOT 版本；
- rsync 版本和 capability；
- 已脱敏任务配置；
- 最近任务日志；
- 目录权限和挂载信息；
- SELinux 状态；
- 不包含密码和文件内容。

---

## 17. 许可证与第三方组件

1. rsync 本身采用 GPL；打包和分发前必须确认整体开源和源码提供义务。
2. 若参考或派生 GPLv3 Android 项目，应保持许可证兼容。
3. `libsu`、Compose、Room 等分别记录许可证和版本。
4. App 内提供“开源许可证”页面。
5. 保存 rsync 对应版本源码、构建脚本、补丁和校验值，保证 APK 可复现构建。
6. 若只供个人使用，也建议从第一天保留完整许可证文件，避免后续发布时返工。

---

## 18. 构建与工程化

### 18.1 仓库建议结构

```text
rsync同步/
├─ README.md
├─ docs/
│  ├─ architecture.md
│  ├─ protocol.md
│  ├─ timestamp-preservation.md
│  ├─ security.md
│  └─ test-plan.md
├─ app/
├─ native/
│  ├─ rsync/
│  ├─ syncmeta/
│  ├─ patches/
│  └─ build-android.ps1
├─ gradle/
├─ scripts/
│  ├─ install-debug.ps1
│  ├─ collect-diagnostics.ps1
│  └─ integration-test.ps1
├─ LICENSE
├─ THIRD_PARTY_NOTICES.md
└─ Android_ROOT_rsync_开发大纲.md
```

### 18.2 rsync 可执行文件构建

- 固定源码版本和下载校验值；
- 使用 Android NDK 独立工具链交叉编译；
- 首先只输出 `arm64-v8a`；
- 关闭不需要的 daemon 外围能力；
- 检查动态库依赖，优先生成适合 APK 分发的构建；
- APK 首次运行将二进制释放到 app 私有可执行目录；
- 校验二进制 SHA-256 后才允许运行；
- 记录编译参数和补丁。

### 18.3 版本管理

- 使用 Gradle Version Catalog；
- 主分支必须可构建；
- 每个版本附带数据库 migration；
- Native 和 Android App 版本分别记录；
- release APK 使用专用签名，debug 签名单独使用。

### 18.4 CI 建议

- Kotlin 编译；
- Android Lint；
- 单元测试；
- Native 构建和 hash 校验；
- arm64 APK 构建；
- 许可证清单生成；
- 可选使用 ROOT 模拟器/实体机运行集成测试。

---

## 19. 测试计划

### 19.1 单元测试

- 命令参数生成；
- shell/argv 转义；
- 路径规范化和越界判断；
- 过滤规则；
- 状态机；
- rsync 输出解析；
- 删除保护；
- 目录按深度逆序排序；
- 时间精度比较；
- 数据库 migration。

### 19.2 Native 测试

- 普通文件、目录和符号链接 `stat`；
- 纳秒时间导出和恢复；
- 中文和特殊字符路径；
- 越界路径拒绝；
- 不存在/权限不足/只读文件系统；
- 10 万目录时间恢复性能；
- 32 位/64 位 `time_t` 范围。

### 19.3 集成测试数据集

至少包含：

- 空目录；
- 0 字节文件；
- 1 KB、1 MB、300 MB、1 GB、4 GB+ 文件；
- 大量小文件；
- 多层嵌套目录；
- 中文、空格、Emoji、引号、换行等名称；
- 相同大小不同内容；
- 相同内容不同时间；
- 目标较新；
- 源端删除；
- 传输期间源文件变化；
- 文件夹原始时间早于内部文件时间的情况。

### 19.4 故障注入

- 传输到 10%、50%、99% 时断 Wi-Fi；
- 杀死 UI 进程；
- 杀死前台服务；
- ROOT 被临时撤销；
- 服务端 IP 改变；
- 存储空间不足；
- 目标目录变成只读；
- 哔哩哔哩在传输中重新启动并写入；
- 设备休眠和锁屏；
- 任务取消后立即重新执行。

### 19.5 Android/设备矩阵

- Android 11、12、13、14、15、16；
- 至少两种 ROOT 管理方案；
- F2FS 和 ext4 数据分区；
- 不同厂商 ROM；
- Wi-Fi 5/6、手机热点；
- 两台手机时间不一致场景。

---

## 20. 性能目标

1. 千兆局域网条件下不人为限制 rsync 单流性能。
2. 大文件传输速度主要受 Wi-Fi 和闪存限制。
3. 300 MB 文件连续重复测试至少 20 次无固定大小断线。
4. 断线恢复后只继续未完成内容，不重新复制已确认文件。
5. 10 万文件扫描时 UI 不阻塞，进度持续更新。
6. 日志写入采用缓冲，避免每行同步刷盘。
7. 目录时间恢复采用批处理，减少每个目录单独启动 `su` 的开销。

可配置项：

- rsync I/O timeout；
- 连接超时；
- 带宽上限；
- 日志详细程度；
- 哈希策略；
- 重试次数和退避时间。

---

## 21. 验收标准

### 21.1 MVP 功能验收

- 两台 ROOT 手机可以完成配对；
- A 可以开启受限 rsync 服务；
- B 可以把指定目录拉取到本地；
- 支持复制和镜像模式；
- 300 MB、1 GB 和 4 GB+ 文件传输成功；
- 传输中断后能够重试/续传；
- 空文件夹能够复制；
- 中文和常见特殊字符文件名保持不变；
- 同步完成后文件 `mtime` 匹配；
- 同步完成后所有目录 `mtime` 在允许误差内匹配；
- 未授权客户端无法访问服务端模块；
- 镜像删除超过阈值时被阻止；
- 任务历史和完整日志可查看。

### 21.2 时间保留专项验收

1. 构造至少三级目录，每级使用不同历史时间。
2. 目录内包含需要创建、覆盖、删除的文件。
3. 同步完成并清理临时目录。
4. 接收端逆序恢复目录时间。
5. 再次扫描全部目录并逐项比较。
6. 在不开启哔哩哔哩的情况下保持稳定。
7. 打开哔哩哔哩后若时间改变，日志能够明确识别为同步后的外部写入。

---

## 22. 分阶段实施计划

### 阶段 0：技术验证 Spike

目标：先证明三个最关键问题可行。

- ROOT 读取两台手机的目标目录；
- Android arm64 rsync daemon/client 可以互传 1 GB 文件；
- `syncmeta` 可以恢复多级文件夹 `mtime`。

交付物：

- 最小命令行构建；
- rsync 和 syncmeta 二进制；
- 两台真机测试记录；
- 时间精度和 SELinux 兼容报告。

### 阶段 1：最小可用 APK

- Compose 单页 UI；
- ROOT 检测；
- 服务端启动/停止；
- 手动 IP 连接；
- 固定测试目录单向 Pull；
- 实时日志；
- 基础目录时间恢复。

### 阶段 2：同步任务系统

- Room 数据库；
- 多任务配置；
- 目录选择；
- Copy/Update/Mirror；
- dry-run 和删除保护；
- 历史记录；
- 错误码体系。

### 阶段 3：稳定性

- Foreground Service；
- WakeLock/Wi-Fi Lock；
- 重试和断点续传；
- 进程重启恢复；
- 完整时间清单和复核；
- 大文件、断网和持续写入测试。

### 阶段 4：配对和自动发现

- 二维码配对；
- NSD/UDP 自动发现；
- 设备信任管理；
- IP 变化处理；
- secret 轮换。

### 阶段 5：安全传输

- 评估 rsync over SSH；
- 或在 rsync daemon 外增加 TLS 通道；
- 身份指纹校验；
- 加密协议升级和兼容协商。

### 阶段 6：自动化和发布

- 定时任务；
- 充电/Wi-Fi 条件；
- 完整诊断包；
- 多 ABI；
- CI 构建；
- 开源许可证和源码发布准备。

---

## 23. 首个开发迭代建议

不要一开始就做完整 UI，按以下顺序推进：

1. 建立空 Compose 工程。
2. 集成 `libsu` 并显示 ROOT 探测结果。
3. 编译 arm64 rsync，释放到 App 私有目录并执行 `--version`。
4. 在手机 A 上以 ROOT 启动只读 rsync module。
5. 在手机 B 上手工输入 IP，执行到普通测试目录的 Pull。
6. 改成目标 `Android/data/com.danmaku.bili/download`。
7. 验证 300 MB、1 GB 和断线续传。
8. 编写 `syncmeta`，先只支持 snapshot/restore/verify。
9. 验证多级目录时间逆序恢复。
10. 最后再加入 Room、任务编辑和自动发现。

阶段 0 未通过前，不投入大量时间制作视觉界面。

---

## 24. 开工前需要确认的设备信息

1. 手机 A 品牌、型号和 Android 版本。
2. 手机 B 品牌、型号和 Android 版本。
3. 两台手机分别使用 Magisk、KernelSU 还是 APatch。
4. 两台手机 CPU ABI，通常为 `arm64-v8a`。
5. 两台手机是否都能通过 ROOT shell 直接执行：

```text
ls -la /storage/emulated/0/Android/data/com.danmaku.bili/download
```

6. 是否需要同步前自动强制停止哔哩哔哩。
7. 主要方向是 A → B，还是两边经常交换。
8. 镜像模式是否允许删除目标端多余文件。
9. 是否只在家庭局域网使用，还是以后需要异地/公网同步。

---

## 25. 最终建议

第一版路线固定为：

```text
ROOT 访问受限目录
    +
rsync daemon/client 完成增量传输
    +
自定义目录时间清单
    +
syncmeta 使用 utimensat() 逆序恢复目录时间
    +
Kotlin/Compose 提供任务与日志 UI
```

其中最关键、必须最先验证的是：

1. 两台真机 ROOT 对目标目录的稳定读写；
2. Android arm64 rsync 在大文件和断线环境下的行为；
3. 哔哩哔哩停止状态下目录时间是否能被可靠恢复；
4. 恢复后重新打开哔哩哔哩会执行哪些目录写入。

只要这四项验证通过，剩余工作主要是 Android 工程化、UI、任务管理和稳定性完善。

