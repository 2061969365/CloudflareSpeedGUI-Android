# CloudflareSpeedGUI 安卓版 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: 使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务执行。每步用 `- [ ]` 复选框跟踪。每个任务遵循 `superpowers:test-driven-development`（先写失败测试→跑失败→最小实现→跑通→提交）。

**Goal:** 把 CloudflareSpeedGUI（PySide6+cfst.exe 的 Cloudflare CDN IP 优选桌面工具）做成纯 Kotlin 原生安卓 App，minSdk 26 覆盖 98.3% 设备，稳定、免 root、侧载分发。

**Architecture:** 全新 Kotlin 工程（独立目录 `E:\FIX\CloudflareSpeedGUI_android`，与桌面版互不覆盖）。**两个独立 Gradle 构建**：
- `engine/`：纯 Kotlin JVM 库（零 Android 依赖，本地即可 `gradlew test`），承载全部测速/解析逻辑。源码目录 `engine/src/main/kotlin`，测试 `engine/src/test/kotlin`。
- `android/`：安卓 App，`app` 模块通过 `sourceSets` 直接引用 engine 源码目录（`../../engine/src/main/kotlin`），不发布 jar；仅在 GitHub Actions 上构建（需要 Android SDK）。
引擎核心：`Socket` 并发 TCP 探测延迟、OkHttp 自定义 `Dns` 解析 host→被测 IP 测下载速度、`cf-ray` 头解析地区码；两阶段扫描编排（延迟→按地区择优测速）镜像桌面 `ScanController`。UI 层 Jetpack Compose + Material 3 单 Activity + 底部导航 4 页。长扫描跑 `dataSync` 前台服务抗 Doze。

**Tech Stack:** Kotlin 2.2.x · Jetpack Compose + Material 3 · AGP 9.0.1 · Gradle 9.1 · JDK 17(CI)/21(本地) · OkHttp 4.12 · Room · DataStore · 协程 · JUnit4 + MockWebServer + coroutines-test。**零原生代码、无 Go 二进制、无 ABI 拆分**（通用 APK 全机型可装）。

**分发与构建：** 侧载 APK（酷安 / GitHub Releases / 二维码）。GitHub Actions 远程构建 + 测试 + 签名 + 出包。仓库：GitHub `CloudflareSpeedGUI-Android`。

## 全局约束
- minSdk **26**、targetSdk/compileSdk **36**、AGP 9.0.1、Gradle 9.1。CI 用 JDK 17（temurin），本地 JDK 21 亦可。
- 包名 `com.cfst.android`，App 显示名 "CloudflareSpeedGUI"（中文 UI）。
- **禁止**使用 Go 二进制 / 执行 assets 里的可执行文件 / VpnService / 原生 `.so`。测速逻辑纯 JVM。
- 速度单位**十进制 MB/s**（1MB=10^6 B，与桌面 `format_speed` 一致）；延迟单位 ms。
- `network_security_config.xml` 用 `<base-config cleartextTrafficPermitted="true">`（必须用 base-config，domain-config 匹配不了裸 IP）；Manifest 引用之。
- 权限仅：INTERNET / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE / WAKE_LOCK / FOREGROUND_SERVICE / FOREGROUND_SERVICE_DATA_SYNC。
- engine 运行时依赖仅 kotlinx-coroutines-core + okhttp；不引入 serialization/gson（自写 CsvCodec）。测试依赖：junit / mockwebserver / coroutines-test。
- engine 纯逻辑不依赖 Android API（保证本地 JVM 单测）；仅 android 的 `data/`、`service/`、`ui/` 可依赖 Android。
- UI 文案全中文；每任务提交前跑引擎测试 +（CI 上）`./gradlew :app:testDebugUnitTest`。

## 工程结构
```
CloudflareSpeedGUI_android/
  README.md
  docs/plans/2026-08-09-android-app.md
  .github/workflows/build.yml
  engine/                                  # 独立 Kotlin JVM 构建
    settings.gradle.kts  build.gradle.kts  gradle.properties  gradle/wrapper/  gradlew
    src/main/kotlin/com/cfst/android/engine/
      IpParser.kt  IpGenerator.kt  ColoRegionMapper.kt
      LatencyProbe.kt  SpeedProbe.kt  ScanController.kt
      model/{ScanResult,ScanRequest,ScanEvent,LatencyStats,ResultStats}.kt
      CsvCodec.kt
    src/test/kotlin/com/cfst/android/engine/   # 全部 JVM 单测
  android/                                 # 安卓工程（仅 CI 构建）
    settings.gradle.kts  build.gradle.kts  gradle.properties  gradle/libs.versions.toml
    gradle/wrapper/  gradlew
    app/build.gradle.kts
    app/src/main/AndroidManifest.xml
    app/src/main/res/xml/network_security_config.xml
    app/src/main/assets/ip/official.txt   app/src/main/assets/ip/cmip.txt   # 复制自桌面 resources/
    app/src/main/res/values/{strings,themes}.xml, mipmap/...
    app/src/main/java/com/cfst/android/
      MainActivity.kt
      ui/theme/{Color,Theme,Type}.kt
      ui/nav/AppNavHost.kt
      ui/screens/scan/{ScanScreen,ScanViewModel}.kt
      ui/screens/result/{ResultScreen,ResultViewModel}.kt
      ui/screens/history/{HistoryScreen,HistoryViewModel}.kt
      ui/screens/settings/{SettingsScreen,SettingsViewModel}.kt
      ui/components/{StatCard,SectionDivider}.kt
      data/ConfigRepository.kt  data/HistoryRepository.kt  data/HistoryDb.kt
      service/ScanService.kt  service/ScanNotifier.kt
```
`android/app/build.gradle.kts` 关键点：`sourceSets["main"].java.srcDirs("../../engine/src/main/kotlin")`，使 engine 源码随 App 一起编译；engine 的三个依赖（coroutines-core、okhttp）在 android 模块同样声明（CI 上 engine 测试单独跑）。

## 共享接口（任务间契约）
- `IpParser.parseCidr(s: String): IpNetwork?`；`IpNetwork.prefixLen / version: Int / numAddresses: Long / sampleHosts(n: Int): List<String> / expandAll(): Sequence<String>`
- `IpGenerator.generate(cidrs: List<String>, maxIps: Int, fullScan: Boolean, progress: (Int) -> Unit): List<String>`（语义镜像桌面 `ip_source.py`）
- `LatencyProbe.probe(ip, port, pingCount, timeoutMs): LatencyStats`（suspend；`LatencyStats(sent, received, lossPct, avgMs: Float?, minMs: Float?, maxMs: Float?)`）
- `SpeedProbe.measure(ip, port, url, durationSec, speedLimit): Float?`（suspend；返回十进制 MB/s，失败或低于 speedLimit 返回 null）
- `ColoRegionMapper.map(colo: String): String`
- `ScanController`（构造注入 `latencyProbe: (String, Int) -> LatencyStats`、`speedProbe: (String, Int, String, Int, Float) -> Float?`）；`start(req)` / `cancel()` / `events: MutableSharedFlow<ScanEvent>`；`ScanEvent = PhaseChanged | Progress(pct,text,eta) | Log(line) | ResultReady(List<ScanResult>) | Error(msg) | Done`
- `ScanRequest(source, customLines, maxIps, fullScan, ports, multiPortBest, speedEnabled, region, speedCount, probeCount, fullProbeCount, latencyLimit, downloadTime, downloadCount, speedLimit, downloadUrl, pingCount, pingConcurrency, speedConcurrency)`
- `ScanResult(ip, port, avgMs: Float?, minMs, maxMs, lossPct, speed: Float?, regionCode, regionName, testedAt)`；`ResultStats.compute(records): ResultStats(total, topRegions, fastestMs)`
- `CsvCodec.encode(records): String`（表头 `IP 地址,端口,平均延迟(ms),下载速度(MB/s),地区码,测试时间`）；`CsvCodec.parse(text): List<ScanResult>`
- `ConfigRepository`：键+默认值见 Task 10；`downloadUrl` 默认 `http://speed.cloudflare.com/__down?bytes=50000000`
- `HistoryRepository.add/list/delete/deleteOlderThan(days)`；`HistoryEntry(id, startedAt, ipCount, resultCount, fastestMs, regionsSummary, recordsCsv)`

## 任务分解

### Task 1: engine 工程骨架 + 本地构建
**Files:** `engine/settings.gradle.kts`、`engine/build.gradle.kts`、`engine/gradle.properties`、`engine/gradle/wrapper/*`、占位 `IpParser.kt`
- [ ] 写 `engine/settings.gradle.kts`（rootProject.name="cfst-engine"）+ `engine/build.gradle.kts`：`kotlin("jvm") version "2.2.10"`、`kotlin { jvmToolchain(17) }`、`repositories { mavenCentral() }`、deps：coroutines-core 1.10.x、okhttp 4.12.0；test：junit 4.13.2、mockwebserver 4.12.0、coroutines-test
- [ ] `engine/gradle.properties`：`org.gradle.jvmargs=-Xmx1g`、`org.gradle.caching=true`
- [ ] 用 gradle-9.1 二进制生成 wrapper：`gradle wrapper --gradle-version 9.1`（GRADLE_USER_HOME 指向非 C 盘）
- [ ] 跑 `engine/gradlew.bat build` 验证 BUILD SUCCESSFUL
- [ ] 提交

### Task 2: GitHub 仓库 + CI workflow
**Files:** `.github/workflows/build.yml`、`README.md`
- [ ] `gh repo create CloudflareSpeedGUI-Android --public --source . --push`（或先提交后推送）
- [ ] 写 workflow：triggers `[push, workflow_dispatch, tags v*]`；jobs.build on ubuntu-latest：`actions/checkout@v4` → `actions/setup-java@v4`(temurin 17) → `gradle/actions/setup-gradle@v4` → `android-actions/setup-android@v3`(platform 36, build-tools 36.0.0) → Step1 引擎测试 `./gradlew -p engine test` → Step2 安卓构建 `./gradlew -p android :app:assembleRelease`（签名密钥从 secrets 读取，存在才签）→ `actions/upload-artifact@v4` 上传 APK
- [ ] 签名说明 `android/signing.properties.example`；keystore 用 secret `KEYSTORE_BASE64/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD`，CI 里 base64 -d 还原后由 AGP signingConfig 从 env 读取
- [ ] 触发一次构建，确认 workflow 绿
- [ ] 提交

### Task 3: IpParser（TDD）
**Files:** `engine/src/main/kotlin/com/cfst/android/engine/IpParser.kt`；测试 `engine/src/test/kotlin/com/cfst/android/engine/IpParserTest.kt`
- [ ] Step1 写失败测试：
```kotlin
class IpParserTest {
  @Test fun parse_v4_cidr() {
    val n = IpParser.parseCidr("173.245.48.0/20")!!
    assertEquals(20, n.prefixLen); assertEquals(4, n.version); assertEquals(4096L, n.numAddresses)
    val s = n.sampleHosts(1).single(); assertTrue(s.startsWith("173.245."))
  }
  @Test fun bad_cidr_returns_null() { assertNull(IpParser.parseCidr("not-a-cidr")) }
  @Test fun ipv6_parse() { assertNotNull(IpParser.parseCidr("2606:4700::/128")) }
  @Test fun expandAll_covers_full_range() { assertEquals(2, IpParser.parseCidr("10.0.0.0/30")!!.expandAll().toList().size) }
}
```
- [ ] Step2 跑→FAIL（类不存在）
- [ ] Step3 最小实现：手写 IPv4/IPv6 CIDR 解析（IPv4 用 Long 位运算：网络地址 `addr & mask`，host 数 `(1L shl (32-prefix)) - 2` 下限 0；IPv6 用 BigInteger）。`sampleHosts(n)` 随机采样（排除网络/广播地址）；`expandAll()` 顺序展开 IPv4（去网络/广播），IPv6 返回 CIDR 串本身（镜像桌面 full_scan 行为）
- [ ] Step4 跑→PASS → [ ] Step5 提交

### Task 4: IpGenerator（TDD）
**Files:** `engine/.../IpGenerator.kt`；测试 `IpGeneratorTest.kt`
**语义（镜像桌面 ip_source.py）：** `maxIps==0`（采样）→ IPv4 每 `/24` 子网取 1 随机 host（`prefixLen>=24` 直接在该网络取 1，否则按 `/24` 拆子网各取 1）、IPv6 取 1 随机 host、裸 IP 行原样加入；`maxIps>0` → 按 CIDR 均分配额随机采样（`base=maxIps/len(valid)`，前 `remainder` 个 +1，每 CIDR `sampleHosts(quota)`）；`fullScan` → IPv4 全部展开去重、IPv6/裸行保留原行；最终 `distinct()`；非法行丢弃。
- [ ] 失败测试：采样 `["173.245.48.0/20"]` maxIps=0 → 非空且全在网段；配额 `maxIps=10` 去重后 ≤10；全量 `["10.0.0.0/30"]` → 2 个 host；裸 IP 行保留；progress 回调被调用
- [ ] FAIL → 实现 → PASS → 提交

### Task 5: ColoRegionMapper（TDD）
**Files:** `engine/.../ColoRegionMapper.kt`；测试 `ColoRegionMapperTest.kt`
- [ ] 失败测试：`map("HKG")=="中国香港"`、`map("LAX")=="美国洛杉矶"`、`map("hkg")=="中国香港"`、`map("ZZZ")=="ZZZ"`、`map("")=="未知"`
- [ ] 实现：`uppercase(Locale.US)` 规范化；映射表含桌面全部地区 `HKG/LAX/SJC/SEA/NRT/FRA/LHR/AMS/SIN` + 常见 CF colo（KHH,SGN,BKK,KUL,KIX,HND,ICN,CDG,WAW,MAD,BCN,ARN,OSL,GIG,GRU,EZE,SCL,JNB,MIA,ATL,IAD,ORD,DFW,PHX,DEN,EWR,YYZ,YUL 等）→中文
- [ ] FAIL → 实现 → PASS → 提交

### Task 6: LatencyProbe（TDD）
**Files:** `engine/.../LatencyProbe.kt`；测试 `LatencyProbeTest.kt`
- [ ] 失败测试（回环 ServerSocket）：开放端口 pingCount=3 → sent=3 received=3 lossPct=0 avg/min/max 非空；封闭端口 → lossPct=100 avgMs=null；`timeoutMs` 生效
- [ ] 实现：`Socket().connect(InetSocketAddress(ip, port), timeoutMs)`，`System.nanoTime()` 计时，connect 失败/超时算丢包；avg/min/max 仅统计成功
- [ ] FAIL → 实现 → PASS → 提交

### Task 7: SpeedProbe（TDD）
**Files:** `engine/.../SpeedProbe.kt`；测试 `SpeedProbeTest.kt`
- [ ] 失败测试（MockWebServer 大 Body）：1s 窗口测速返回 >0 MB/s；低于 speedLimit 返回 null；读 Body 用 128KB 缓冲
- [ ] 实现：`OkHttpClient.Builder().dns { host -> listOf(InetAddress.getByName(ip)) }` 把 URL host 解析到被测 IP；按 `durationSec` 秒窗口循环读 Body 累计 bytes；`MB/s = bytes/1e6/elapsedSec`；失败/超时/低于 speedLimit 返回 null
- [ ] FAIL → 实现 → PASS → 提交

### Task 8: 模型 + ResultStats + CsvCodec（TDD）
**Files:** `engine/.../model/{ScanResult,LatencyStats,ResultStats}.kt`、`engine/.../CsvCodec.kt`；测试 `ResultStatsTest.kt`、`CsvCodecTest.kt`
- [ ] 失败测试：top3 地区计数排序；fastestMs 取最小非零；encode→parse 往返一致；空列表安全；null 速度过滤
- [ ] 实现 → PASS → 提交

### Task 9: ScanController（TDD）
**Files:** `engine/.../ScanController.kt`、`engine/.../model/{ScanRequest,ScanEvent}.kt`；测试 `ScanControllerTest.kt`（注入 Fake probes）
**编排（镜像桌面）：**
1. `IpGenerator.generate` → `Log`+`Progress`
2. 延迟阶段：单端口直接扫；多端口+`multiPortBest`→串行逐端口（结果合并，同 IP 跨端口取最佳）；多端口非择优→并行。并发 `limitedParallelism(pingConcurrency)`；每 IP `pingCount` 次；`avgMs<=latencyLimit` 才保留；进度=已测/总数；ETA=`剩余*平均单IP耗时`；取消=scope.cancel()（内部 ensureActive）
3. 择优：`region!="全部"` 按 regionName 过滤；按 avgMs 升序取前 `speedCount`；按端口分组
4. 测速：逐组，组内 `limitedParallelism(speedConcurrency=5)`，每 IP 测 `downloadTime` 秒；低于 `speedLimit`(>0) 置 null；看门狗：每组最坏 `downloadCount*downloadTime+30s` 超时跳过该组（镜像桌面 `_arm_speed_guard`）
5. 完成 → `ResultReady(sorted)` → `Done`；异常 → `Error(msg)`+`Done`（不崩溃）
- [ ] 失败测试：单端口正常流（Phase 顺序、ResultReady 数量、latencyLimit 过滤）；多端口择优串行；地区筛选；cancel() 后不再 emit；看门狗跳组
- [ ] FAIL → 实现 → PASS → 提交

### Task 10: ConfigRepository（DataStore，android 模块）
**Files:** `android/app/src/main/java/com/cfst/android/data/ConfigRepository.kt`；测试 `data/ConfigRepositoryTest.kt`
- 键+默认值（镜像桌面 DEFAULTS）：`lastSource="official" lastScene="quick" lastPorts=[443] multiPortEnabled=false fullScanEnabled=false pingCount=2 downloadCount=50 downloadTime=10 speedLimit=0 latencyLimit=200 probeCount=500 fullScanProbeCount=5000 downloadUrl="http://speed.cloudflare.com/__down?bytes=50000000" historyRetentionDays=30 speedRegion="全部" speedCount=50 pingConcurrency=8 speedConcurrency=5`
- API：`val flow: Flow<Map<String,Any>>`、`suspend fun get(key): Any?`、`suspend fun set(key, value)`、`suspend fun resetDefaults()`（保留 lastSource/lastScene/lastPorts/multiPortEnabled/fullScanEnabled）
- [ ] TDD（PreferencesDataStore 临时文件）→ 提交

### Task 11: 历史 Room 库（android 模块）
**Files:** `android/app/src/main/java/com/cfst/android/data/{HistoryDb,HistoryRepository}.kt`；测试 `HistoryRepositoryTest.kt`（Room in-memory）
- DAO：insert / getAll: Flow / deleteById / deleteOlderThan(ts) / clearAll；`HistoryEntry` 存 `recordsCsv`（CsvCodec）+ `regionsSummary`（`region:count;...` 自拼，避免 JSON 依赖）
- [ ] TDD：增删查、deleteOlderThan 按 startedAt、clearAll → 提交

### Task 12: 主题 + 导航骨架（android 模块）
**Files:** `android/app/src/main/java/com/cfst/android/{MainActivity,ui/theme/*,ui/nav/AppNavHost}.kt`、`res/values/*`
- Material3 动态取色（Android 12+），低版本回退品牌色 CF 橙 `#F38020`；深色跟随系统；底部导航 4 页（测速/结果/历史/设置），中文 label
- [ ] `assembleDebug` 通过（CI 验证）→ 提交

### Task 13: 测速页 + ScanViewModel（android 模块）
**Files:** `ui/screens/scan/{ScanScreen,ScanViewModel}.kt`、`ui/components/*`
- ViewModel：持 `ScanController`+`ConfigRepository`；`start(customLines)` 组装 `ScanRequest`；`events`→`StateFlow<ScanUiState(running,phase,progress,eta,stats,log)>`；`cancel()`；完成写历史；启动/停止 `ScanService`
- 界面：场景 SegmentedButton（一键极速/单端口快速/自定义，切换逻辑镜像桌面）；IP 来源 FilterChip（官方库(150万)/CMIP(6.3万)/自定义文件 + SAF 选择）；端口 443/8443/2083/2087/2053/2096；抽取IP数步进器（0=采样）；开关：多端口择优/全量扫描；折叠"下载测速参数"（开关+地区下拉+数量）；主按钮"开始扫描"→"取消"；进度条+阶段+ETA；StatCards（已扫IP/最佳地区/最快延迟）；实时日志 LazyColumn
- [ ] CI assembleDebug 通过 → 提交

### Task 14: 结果页 + ResultViewModel（android 模块）
**Files:** `ui/screens/result/*.kt`
- 结果表（IP/端口/延迟/速度/地区），地区 FilterChip，排序切换；复制全部（ClipboardManager）；导出 CSV（SAF `ACTION_CREATE_DOCUMENT` + `CsvCodec.encode`）；速度格式 `x.x MB/s`、延迟 `xx ms`、缺失 `-`
- [ ] CI assembleDebug 通过 → 提交

### Task 15: 历史页 + 设置页（android 模块）
**Files:** `ui/screens/history/*.kt`、`ui/screens/settings/*.kt`
- 历史列表（时间/IP数/最快延迟/Top地区），展开看明细，删除单条/清空；启动+完成自动 `deleteOlderThan(retentionDays)`
- 设置：测速参数全量（-dn 5-200 / -dt 5-30s / -sl 0-100 / -tl 0-10000 / 普通探测数 / 全量探测数 / 测速地址 / 并发数 / 保留天数 1-365）+ 恢复默认（确认）+ 关于
- [ ] CI assembleDebug 通过 → 提交

### Task 16: ScanService（android 模块）
**Files:** `service/ScanService.kt`、`service/ScanNotifier.kt`
- `startForegroundService` + 5s 内 `startForeground`；`foregroundServiceType="dataSync"`；通知含进度 + 取消按钮；`PARTIAL_WAKE_LOCK` 持锁；结束/取消 `stopForeground(STOP_FOREGROUND_REMOVE)`+`stopSelf()`
- [ ] CI assembleDebug 通过 → 提交

### Task 17: 收尾
**Files:** `README.md`（功能/截图占位/构建/侧载安装说明/注意事项：关 VPN、流量提醒、最低 Android 8.0）、`android/signing.properties.example`
- [ ] 全部勾选提交；CI 全绿；生成签名 release APK 产物

## 执行方式
- 每个任务派新子代理执行（`superpowers:subagent-driven-development`），两阶段评审。
- 任务内 TDD（红-绿-重构），提交前缀 `feat:`/`test:`/`chore:`。
- 本地只跑 `engine`（纯 JVM，无需 Android SDK）；安卓构建全部由 GitHub Actions 完成。
- Gradle 用户目录/依赖缓存放非 C 盘（如 `F:\gradle-home`），避免污染系统盘。
