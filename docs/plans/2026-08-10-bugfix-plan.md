# CloudflareSpeedGUI-Android 全量 Bug 修复计划

> 触发：6 个并行审查子代理彻查引擎/编排/cfst 集成/数据层/扫描页/结果历史设置页，
> 发现高 20 项、中约 30 项、低约 20 项 bug。用户决策：**全修（A+B+C）+ 保留双引擎修复集成层 + 直接开修**。
> 执行方式：8 个工作包并行派出子代理，文件所有权严格互斥，零合并冲突。

## 全局契约（集成前必须一致）

1. `ScanEngine.latencyScan` 新增参数 **`pingTimeoutMs: Int`**（Kotlin 引擎透传到 LatencyProbe）。
2. `CfstEngine` 在 **exit!=0 时抛 `IllegalStateException`**（而非返回空表）；`FallbackEngine` 捕获后自动降级 Kotlin 引擎重试同一请求。
3. **历史写入唯一归属 AppContainer**（WP-A1），`ScanViewModel` 不再写历史（WP-A3 删除 persistHistoryIfNeeded）。
4. **速度单位统一为十进制 MB/s**：`CfstCsvParser` 把 cfst 的 MiB/s ÷1.048576 归一化（WP-E3）；`ResultFormatter` 维持 ×8 → mbps 展示（WP-E4 不动）。
5. `IpGenerator.generate(cidrs, maxIps, fullScan, progress)` 签名不变（WP-E1 内部改语义）。
6. `ScanEvent` 新增 `Cancelled`（或 Done 带原因），取消/空结果不触发自动跳转结果页。
7. `downloadUrl` 三处（CfstBinary.DEFAULT_SPEED_URL / ConfigRepository / ScanViewModel buildScanRequest）统一为空串回退默认 URL。

## 验证命令

- 引擎（纯 JVM，本地可跑）：`cd engine && ./gradlew test`；单类：`./gradlew test --tests "类名"`
- Android：`cd android && ./gradlew :app:assembleDebug` + `:app:testDebugUnitTest`（需 SDK，尽力而为）

---

## WP-E1 · IP 解析与生成

**独占文件**：`engine/src/main/kotlin/com/cfst/android/engine/IpParser.kt`、`IpGenerator.kt`
**测试文件**：`engine/src/test/kotlin/.../IpParserTest.kt`、`IpGeneratorTest.kt`

修复：
1. IPv6 全量扫描把 CIDR 字符串（含 `/`）当 IP 用（IpGenerator.kt:39-41）：fullScan 遇 v6 大前缀 → `net.sampleHosts(1)` 取真实 host；`expandV6All()`（IpParser.kt:30-34）对不可展开前缀返回 `emptySequence()`，绝不返回带 `/` 的字符串。
2. v6 /127、/128 采样返回 `original`（带 /prefix）（IpParser.kt:67）：/128→`listOf(hostAt(0))`、/127→`listOf(hostAt(0), hostAt(1))`。
3. /31、/127 usable 语义错误（IpParser.kt:118-121, 147-150）：`prefix==31/127` 时 usable=2；sampleHosts 在 offset 0..1 采样。
4. `oneHostPer24` 一次性展开 OOM（IpGenerator.kt:70-73）：改惰性 Sequence + 上限截断；`maxIps==0` + `/0` 不 OOM；每 /24 随机取 1 host（对齐桌面语义）。
5. 裸 IP 绕过 maxIps 配额（IpGenerator.kt:26, 56-64）：总量截到 maxIps；配额不足二轮回填。
6. IPv4 前导零过宽（IpParser.kt:110-112）：拒绝 `o.length>1 && o.startsWith("0")` 的八位组。
7. `isValidIp` 对 IPv6 只做字符校验（IpGenerator.kt:79-90）：与 `parseCidr` 口径统一。
8. v6 采样偏移越界到保留地址（IpParser.kt:72）：偏移限制 1..usable。

**测试要求**：断言内容而非数量——`v6_slash128_returns_plain_ip`、`v6_slash127_returns_two_plain_ips`、`/31 采样含第 2 地址`、`0.0.0.0/0 + maxIps=0 不 OOM`、`bareIps 受 maxIps 截断`、`IPv6 fullScan 不产生含 / 的 IP`。

**验证**：`cd engine && ./gradlew test --tests "IpGeneratorTest" --tests "IpParserTest"`

---

## WP-E2 · 编排 + 引擎接口 + Kotlin/回退引擎

**独占文件**：`engine/.../ScanEngine.kt`、`ScanController.kt`、`KotlinEngine.kt`、`FallbackEngine.kt`、`model/ScanEvent.kt`、`model/ScanRequest.kt`、`model/ScanResult.kt`
**测试文件**：`ScanControllerTest.kt`、`KotlinEngineTest.kt`

修复：
1. 回退分支静默跳过测速阶段（ScanController.kt:74-76）：`targets.isEmpty()` 时改为「取最快 N 个」后仍执行 `PhaseChanged("下载测速") + runSpeedPhase(req, targets)`。
2. 看门狗公式误杀正常组（ScanController.kt:206）：改 `maxOf(60_000L, ceil(组大小/concurrency)×downloadTime×1000×1.5 + 30_000)`。
3. maxIps/fullProbeCount 失效（ScanController.kt:50）：非全量用 `req.maxIps`、全量用 `req.fullProbeCount` 传入生成器；进度按实际展开数回调。
4. region 匹配大小写/空白（ScanController.kt:177, 190, 250-251）：trim+uppercase 标准化；"全部"用标准化值判断。
5. `runCatching` 吞 CancellationException（ScanController.kt:189）：`catch(CancellationException){throw e} catch(Throwable){null}`。
6. ResultReady 前过滤 speed==null（ScanController.kt:238-241）：与 cfst 路径行为一致。
7. 多端口+地区按 IP 去重竞态（ScanController.kt:182, 191-195）：写 `resolved` 前显式比较 avgMs 取最优端口 + synchronized。
8. 地区解析并发过大（ScanController.kt:183）：专用并发 `min(pingConcurrency, 16)`；已有 regionCode 不重复解析；同 IP 去重。
9. 事件流 replay=0 + tryEmit 丢事件（ScanController.kt:28, 89-92）：`replay=1`；Done/Error/ResultReady 用 suspend `emit`；进度事件维持 tryEmit。
10. 取消后旧 job 事件干扰新扫描（ScanController.kt:32-40）：join 加 `withTimeoutOrNull(2s)`；cancel 后旧协程 catch 不发事件（代次标记）。
11. 进度除零/ETA 冗余（ScanController.kt:155-174）：`total<=0` 直接 return；测速阶段 pct 用全局累计 done/total。
12. latencyLimit=0 语义（KotlinEngine.kt:50）：`latencyLimit<=0` 视为不限（与 cfst `-tl 0` 语义统一）。
13. pingTimeoutMs 死配置：`ScanEngine.latencyScan` 加 `pingTimeoutMs: Int` 参数（契约1）→ KotlinEngine 透传；ScanRequest 已有字段。
14. 回退引擎运行期降级（FallbackEngine.kt:16-21, 33-48）：`active()` 用 Mutex 单飞；latencyScan/speedScan 包 runCatching，primary 抛异常 → fallback 重试同请求；暴露 `suspend fun resolvedEngineName(): String`（供 WP-A1 打日志）。
15. `ScanEvent` 新增 `Cancelled`（契约6）：取消路径 emit `Cancelled` 而非 `Error("已取消")`。

**测试**：回退分支补测速、看门狗不误杀（大组小 dn）、latencyLimit=0 不限、region 大小写、取消后无事件、pingTimeoutMs 透传、primary 抛异常自动降级、Cancelled 事件。

**验证**：`cd engine && ./gradlew test --tests "ScanControllerTest" --tests "KotlinEngineTest"`

---

## WP-E3 · cfst 原生集成

**独占文件**：`engine/.../cfst/CfstBinary.kt`、`CfstEngine.kt`、`CfstProcessRunner.kt`、`CfstProgressParser.kt`、`CfstCsvParser.kt`
**测试文件**：`CfstBinaryTest.kt`、`CfstEngineTest.kt`、`CfstCsvParserTest.kt`、`CfstProgressParserTest.kt` + **新增 `CfstProcessRunnerTest.kt`**

修复：
1. 取消不杀子进程 → 双实例 + CSV 竞争（CfstProcessRunner.kt:24-34）：`run()` 对协程挂 `invokeOnCancellation { process.destroyForcibly() }`；try/finally 统一销毁；`destroyForcibly` 后带超时轮询确认退出。
2. `thread.join()` 无超时（CfstProcessRunner.kt:56-57）：`join(5_000)` 后放弃（daemon 线程）。
3. 进度条全程冻结（CfstProcessRunner.kt:41-53 + CfstProgressParser.kt）：按块读取（`InputStream.read` 累积、按 `\r`/`\n` 切行增量解析）；`PROGRESS_RE` 匹配真实 pb 输出（`12 / 456 ... 可用: 8`）；CfstEngine 另加 2s 心跳兜底进度。
4. `-v` 探针联网 checkUpdate + 10s 竞争 → 误判回退（CfstEngine.kt:19-31）：探针改 `-h`（无网络）或 3s 超时。
5. 陈旧 CSV 当本次结果（CfstEngine.kt:64-65, 101-102）：扫描前 `outCsv.delete()`；文件 mtime 校验。
6. exit!=0 语义（CfstEngine.kt:64, 101）：改抛 `IllegalStateException`（契约2）。
7. CSV/文件永不清理（CfstEngine.kt:44-45, 80-81）：扫描开始即删旧文件。
8. `latencyCmd` 空 URL（CfstBinary.kt:23-32）：`url.isBlank()` 用 DEFAULT_SPEED_URL。
9. 并发无上限（CfstBinary.kt:27）：`minOf(1000, concurrency)`。
10. 超时公式低估（CfstBinary.kt:9-13）：latency 加入 `pingCount×2000/并发` 因子；speed 加入存活数×downloadTime 因子（CfstEngine 把 survivors 数量传入或内部重算 dynamicTimeout）。
11. 速度单位归一化（CfstCsvParser）：MiB/s ÷1.048576 → 十进制 MB/s（契约4）。
12. `-dd` 版本语义加注释锁定 v2.3.5。

**测试**：真实进程启动假命令（阻塞/超时/取消/destroy 确认）、真实 pb 进度格式增量解析、扫描前删旧文件、exit!=0 抛异常。

**验证**：`cd engine && ./gradlew test --tests "*Cfst*"`

---

## WP-E4 · 探测 / 编解码 / 统计 / 格式化

**独占文件**：`engine/.../LatencyProbe.kt`、`SpeedProbe.kt`、`CsvCodec.kt`、`ColoRegionMapper.kt`、`model/LatencyStats.kt`、`model/ResultFormatter.kt`、`model/ResultStats.kt`
**测试文件**：`SpeedProbeTest.kt`、`LatencyProbeTest.kt`、`CsvCodecTest.kt`、`ResultStatsTest.kt`、`ResultFormatterTest.kt`

修复：
1. CsvCodec 畸形输入崩溃（CsvCodec.kt:85-86, 59）：`parseTime` 用 runCatching 多格式兜底（含 `yyyy-MM-dd HH:mm:ss`）；`parse` 单行失败降级默认值不整体抛；首行非 HEADER 不 drop。
2. formatNumber 与 KDoc 不一致 / NaN（CsvCodec.kt:76-77 + ResultFormatter.kt:14, 18-22）：统一 2 位小数或改注释；`formatNumber` 对非有限值（NaN/Infinity）返回 "0"。
3. ResultStats 地区大小写分组（ResultStats.kt:19）：`uppercase(Locale.US)` 后 groupBy。
4. SpeedProbe 超时不可中断 + 无显式超时（SpeedProbe.kt:14-19, 38-39）：设 `connectTimeout/readTimeout = max(downloadTime, 10s)`；`durationSec=0` 直接 null；短响应体/极小 elapsed 防虚高（低于阈值返回 null）。
5. LatencyProbe 关闭端口复测稳定性（LatencyProbeTest）：测试端口错开/延迟。
6. ResultFormatter 复制格式：`ms`/`mbps` 间加空格；null 值输出 `-` 而非 "0"。

**测试**：畸形 CSV（缺列/不同时间格式/含引号换行）不崩；NaN 不产出；durationSec=0；地区大小写合并。

**验证**：`cd engine && ./gradlew test --tests "*Codec*" --tests "*Probe*" --tests "*Stats*"`

---

## WP-A1 · App 入口与全局

**独占文件**：`android/app/src/main/java/com/cfst/android/CfApp.kt`、`MainActivity.kt`、`ui/theme/Theme.kt`、`res/xml/network_security_config.xml`

修复：
1. 历史写入下沉 AppContainer（CfApp.kt）：`init`/`onCreate` 用 `appScope` 订阅 `scanController.events`；`ResultReady` 更新 `lastResults`；`Done` 写历史（复用原 persistHistoryIfNeeded 逻辑：ResultStats + HistoryEntry + CsvCodec.encode）→ 修复切 tab/划掉 App 丢历史。
2. 保留天数启动即清理（CfApp.kt init）：读 `historyRetentionDays` 后 `deleteOlderThan(now - days×86400000)`。
3. 引擎选定打日志：appScope 调 `FallbackEngine.resolvedEngineName()`（WP-E2 提供）→ 存 `MutableStateFlow<String?>` 供设置页展示。
4. regionResolver 优化（CfApp.kt:117-139）：共享单 OkHttpClient（不再每 IP newBuilder）；ip→colo 内存缓存；解析失败回退「全部」。
5. network_security_config cleartext（:3-6）：改 `<base-config cleartextTrafficPermitted="true" />`（对齐设计文档，修复自定义 http 测速地址静默失败）。
6. 动态取色恢复（Theme.kt:36）：`useDynamicColor = dynamicColor && SDK>=S`（移除 `darkTheme==null` 前置）。
7. 权限弹窗 rememberSaveable（MainActivity.kt:43）：`rememberSaveable { mutableStateOf(false) }`。

**验证**：`cd android && ./gradlew :app:assembleDebug`；`ConfigRepositoryTest` 仍绿。

---

## WP-A2 · 结果页 + 历史页

**独占文件**：`ResultScreen.kt`、`ResultViewModel.kt`、`HistoryScreen.kt`、`HistoryViewModel.kt`

修复：
1. copyAll 用 CSV、其余用 `IP#格式`（ResultViewModel.kt:78）：`copyAll` 改 `ResultFormatter.formatCopyLines(filteredResults)`，与多选/单行统一。
2. 历史明细非惰性渲染（HistoryScreen.kt:346-408）：`records.forEach` 改嵌套 `LazyColumn(heightIn(max=...))` 或整页明细；展开只加载当前项。
3. `_detailMap` 只增不清（HistoryViewModel.kt:68-93, 83-85）：切换展开项先 `collapseDetail(上一个)`；`collapseDetail` 同时清该 id 选择态。
4. loadDetail/delete 竞态孤儿数据（HistoryViewModel.kt:68-93）：写入前校验 id 仍在 summaries 中。
5. 选中集跨筛选/排序残留（ResultViewModel.kt:113-152）：筛选/排序/新结果到达时清空不可见选择；编辑态隐藏顶部「复制全部」。
6. 假刷新（ResultViewModel.kt:54-61、HistoryViewModel.kt:47-54）：下拉刷新改真正重查或明确"来自最近扫描"。
7. exportCsv 全量 load 内存峰值（HistoryViewModel.kt:163-199）：逐条流式写输出流 + 记录数上限。
8. items 无 key（ResultScreen.kt:198）：`items(..., key = { ip:port })`；`combinedClickable` 编辑态 onClick=toggleSelect。
9. HistoryScreen TIME_FORMAT（HistoryScreen.kt:58）：SimpleDateFormat 改线程安全/局部。
10. result region 回退静默（ResultViewModel.kt:167-172）：回退「全部」时 Toast 提示。

**验证**：`cd android && ./gradlew :app:assembleDebug` + `:app:testDebugUnitTest`。

---

## WP-A3 · 扫描页 + 前台服务

**独占文件**：`ScanViewModel.kt`、`ScanScreen.kt`、`ScanService.kt`、`ScanNotifier.kt`、`AppNavHost.kt`、`Destination.kt`

修复：
1. 取消/空结果强制跳空结果页（ScanViewModel.kt:310-314 + ScanScreen.kt:105-110）：`ScanEvent.Cancelled`/空结果不触发自动跳转；仅"正常完成且结果非空"才 `_scanFinished=true`；取消/空留在扫描页高亮原因（联动 WP-E2 事件定义）。
2. ScanViewModel 不再写历史（ScanViewModel.kt:336-358）：删除 `persistHistoryIfNeeded` 及 Done 调用（契约3：WP-A1 唯一写者），保留 lastResults 展示更新。
3. 「测速数量」chips 死控件（ScanViewModel.kt:211-213, 255）：`setSpeedCount` 持久化 `configRepository.set("speedCount", value)`；`buildScanRequest` 改读 `state.speedCount`。
4. 并发数双数据源（ScanViewModel.kt:38-39, 266-267）：`init` 从 configRepository 加载 ping/speedConcurrency 到 state；`buildScanRequest` 直接用 state 值。
5. 扫描中参数可改但不生效（ScanScreen.kt:122-289）：`running` 时禁用整个参数面板 `enabled=!state.running` + 提示条。
6. 扫描状态跨进程/重建不同步（ScanViewModel.kt:32-55）：AppContainer 维护 `scanStatus: MutableStateFlow<ScanStatus>`（running/phase/progress），VM init 恢复；防二次 start 静默取消后台扫描。
7. customLines 导入取消后必失败 + 主线程读大文件（ScanScreen.kt:144-149, 421-429）：取消 SAF 后回退原 source；`readUriLines` 移 `Dispatchers.IO`；导入后 Toast「已导入 N 条」；customLines 空时禁止 start。
8. scanFinished 残留强制跳走（ScanScreen.kt:105-110 + AppNavHost.kt:69-77）：改一次性事件通道（SharedFlow replay=0）仅扫描页组合期间 collect；完成跳转上移 NavHost 监听全局状态。
9. scanStartedAt 竞态（ScanViewModel.kt:78-107）：`start()` 进入即同步置 running（IO 后置），防双击双服务。
10. ScanService 重复收集器/watchdog（ScanService.kt:48-66）：`startScan()` 先 cancel 旧 Job，保证单一 collector；通知更新节流 500ms。
11. 「已取消」被当 Error（ScanService.kt:77-81 + ScanViewModel.kt:307-309）：`ScanEvent.Cancelled` 独立处理，通知文案「已取消」。
12. 日志无界 + 无 key（ScanViewModel.kt:288-291 + ScanScreen.kt:362-368）：截断最近 200 条；`items(key=...)`；贴底才自动滚动。
13. 场景切换残留（ScanViewModel.kt:158-178）：QUICK/SINGLE 模板重置含 region/maxIps/source；CUSTOM 独立参数集。
14. 测速地址空串（ScanViewModel.kt:262-263）：`downloadUrl.isBlank()` → 默认 URL（契约7）。
15. 数值输入钳制体验（ScanScreen.kt:171-205, 386-395）：maxIps 上界（如 500_000）；单IP测量前置校验 IP 合法 + 端口 1..65535。
16. 下载测速 Switch 与 Row clickable 冲突（ScanScreen.kt:222-233）：Switch 移出 clickable 区域。

**验证**：`cd android && ./gradlew :app:assembleDebug` + `:app:testDebugUnitTest`。

---

## WP-A4 · 设置页 + 数据层

**独占文件**：`SettingsViewModel.kt`、`SettingsScreen.kt`、`ConfigRepository.kt`、`HistoryDb.kt`、`HistoryRepository.kt`
**测试文件**：`ConfigRepositoryTest.kt`、`HistoryRepositoryTest.kt`

修复：
1. DataStore flow.first() 无容错 → 首启崩溃（SettingsViewModel init :35-39）：init 与 resetDefaults 加 runCatching 兜底默认值。
2. resetDefaults 非原子（ConfigRepository.kt:73-86）：把「读受保护键」移入 `edit {}` 块内。
3. lastPorts 元素类型不校验（ConfigRepository.kt:102-111）：校验 `value.all{it is Int}`；`toPortsList` 全非法回默认 [443]。
4. 键默认值漂移（ConfigRepository.kt:34-46 vs 文档:155）：按设计文档对齐 pingConcurrency（8）/downloadUrl（与 WP-E3 DEFAULT_SPEED_URL 三处统一）；加注释说明偏离。
5. Room 无 startedAt 索引 + 破坏性迁移（HistoryDb.kt:44-47, 26-32）：`@ColumnInfo(index=true)`；`fallbackToDestructiveMigration` 改正式 Migration + `exportSchema=true`。
6. getAll() 全字段大 blob 隐患（HistoryRepository.kt:7 + HistoryDb.kt:29）：移除或私有化，统一走 `getAllSummaries`/`recordsCsv(id)`。
7. 设置页数值输入边输边钳制（SettingsViewModel.kt:101-113 + SettingsScreen.kt:230-240）：本地 String buffer + 失焦提交 + `supportingText` 越界报错。
8. 测速地址留空写空串（SettingsViewModel.kt:65-70 + SettingsScreen.kt:85-93）：留空写入默认 URL 或提示；与 WP-A3 空串回退双保险（契约7）。
9. 每键即时写放大（SettingsViewModel.kt:41-113）：失焦/防抖后提交。
10. resetDefaults 后 `container.setDarkTheme(rebuilt.darkTheme)` 一致（与 WP-A1 动态取色配合）。

**验证**：`cd android && ./gradlew :app:testDebugUnitTest --tests "*Config*" --tests "*History*"`。

---

## 派工与验收流程

1. 8 个 `task` 子代理同消息并行派出（general 类型），每包 prompt 含：独占文件清单、逐条修复指令（file:line）、契约、测试要求、验证命令。
2. 契约核验点（集成前人工检查）：`ScanEngine.latencyScan` 签名 4 处一致；`CfstEngine` 抛异常契约；历史唯一写者；速度单位归一；`ScanEvent.Cancelled`。
3. 收尾（协调者执行）：
   - `cd engine && ./gradlew test` 全量回归
   - `cd android && ./gradlew :app:assembleDebug` + `:app:testDebugUnitTest`
   - 汇总修复清单与测试结果，标记剩余低危项。
