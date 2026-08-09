# CloudflareSpeedGUI-Android

基于 CloudflareSpeedTest（cfst）的安卓端 Cloudflare CDN IP 优选工具。扫描并测速 Cloudflare 节点 IP，找出当前网络下延迟最低、速度最快的 IP，用于优化访问 Cloudflare CDN 资源的体验。

## 功能
- 一键极速 / 单端口快速 / 自定义扫描三种模式
- 官方库（约 150 万 IP）与 CMIP（约 6.3 万 IP）两种内置 IP 来源，也可导入自定义 IP 文件
- 多端口择优、全量扫描、下载速度测试（可按地区筛选、限制数量）
- 结果表格展示（IP / 端口 / 延迟 / 速度 / 地区），支持复制与导出 CSV
- 扫描历史记录与设置项本地保存

## 截图
（预留，发布时补充）

## 最低要求
- Android 8.0（API 26）及以上
- 需要网络权限；测速会消耗流量，单次完整扫描可能下载数百 MB 到 1 GB，请在 WiFi 下使用

## 构建
```bash
# 引擎（纯 JVM）单元测试
cd engine && ./gradlew test

# 安卓模块：编译并生成 release APK
cd android && ./gradlew :app:assembleRelease
```
APK 输出路径：`android/app/build/outputs/apk/release/app-release.apk`

## 构建说明 / Building

`libcfst.so`（CloudflareSpeedTest 的 Android 原生库）已编译好并提交在仓库中，本地构建无需 Go 工具链。如需重新编译，需先安装 Go 1.21+：

```bash
bash scripts/build-cfst-android.sh          # 默认编译 v2.3.5
bash scripts/build-cfst-android.sh v2.3.5   # 指定 tag
```

脚本会把产物分别写到：
- `android/app/src/main/jniLibs/arm64-v8a/libcfst.so`
- `android/app/src/main/jniLibs/armeabi-v7a/libcfst.so`

CI（`.github/workflows/build.yml` 的 `build-cfst` job）会在每次 push 到 main 时自动编译并将生成的 `.so` 提交回仓库；首次运行后仓库即自带这两个 ABI 的二进制，后续构建 APK 时直接复用。

仓库自带 GitHub Actions 工作流（`.github/workflows/build.yml`），push 到 main 自动跑引擎测试 + 构建 APK，并以 artifact 形式提供下载。若要签名发布：
1. 生成签名用的 keystore（如 `keytool -genkeypair -v -keystore cfst-release.jks -alias cfst -keyalg RSA -keysize 2048 -validity 10000`）
2. 复制 `android/signing.properties.example` 为 `android/signing.properties` 并填入 keystore 路径与密码
3. 重新执行 `./gradlew :app:assembleRelease`，产物即为签名 APK

## 安装（侧载）
1. 从 GitHub Releases / CI artifact 下载 `app-release.apk`
2. 手机开启「允许安装未知来源应用」（不同厂商设置位置略有不同：设置 → 安全 → 未知来源；小米/OPPO/vivo 需在「手机管家/安全中心」中允许）
3. 打开 APK 按提示安装；若 Play Protect 拦截，选择「仍然安装」

## 注意事项
- **测速前关闭 VPN / 代理 / 科学上网工具**，否则结果不准甚至测速失败
- 完整测速（尤其全量扫描）会消耗大量流量，注意流量套餐
- 本工具仅供学习与个人网络优化使用
