# MiAuto

**小米 CarWith / OPPO Car+ 车联 / vivo Jovi InCar 等 ICCOA CarLink 手机，直接上车机大屏。无线连接。**

**MiAuto 装在车机上**，把自己广播成一台支持 ICCOA CarLink 的车机，与已经开着 CarWith、
Car+ 车联或 Jovi InCar 的手机配对 —— 小米 / OPPO / vivo 等皆可。手机继续用它自己的地图、
音乐与电话应用：手机侧不装任何东西，车机侧也不需要配套账号、转接盒、数据线、root 或改固件。

> **本仓库以 [DiAuto Pro](https://github.com/zutaozhong-hash/DiAuto-Pro) 为基座**，
> 而 DiAuto Pro 是 [DiAuto](https://github.com/shihabal3amri/DiAuto) 的独立分支，
> DiAuto 又是 [Open Headunit](https://github.com/andreknieriem/open-headunit) 的独立分支。
> 车机外壳、设置体系与 BYD 仪表 / HUD 输出都来自这条脉络；手机侧协议已换成 **ICCOA CarLink**
> —— 小米 CarWith、OPPO Car+ 车联、vivo Jovi InCar 走的都是这一套。
> 详见下方[致谢与许可](#致谢与许可)。

[下载与安装](https://piggybackbayonet.github.io/MiAuto/zh-Hans/) ·
[最新版本](https://github.com/PiggyBackBayonet/MiAuto/releases/latest)

[English](README.md) · [简体中文](README.zh-CN.md)

![车机待连时显示 6 位配对码](site/assets/connect.png)

## 0.1.3 更新内容

两项修复取自 [guo-zhc/MiAuto](https://gitee.com/guo-zhc/MiAuto)（本仓库 0.1.1 的独立二开分支）：

- **画布改为实测，不再假设。** 以前无条件「长边在前」并写死 1920x720 兜底，在竖屏车机上就是错的：
  画面拉伸、触控点位整体偏移。现在从系统读真实显示尺寸，按屏幕方向设置归序，画布变化时重 init SDK。
- **重连指数退避。** 连续无线建链失败（`10003`/`10006`/`10008`）改为 1.2 → 2.4 → 4.8 秒（封顶 8 秒），
  不再在手机 P2P 组还在回收时每 1.2 秒硬撞一次。

细节与验证边界见 [CHANGELOG-zh.md](CHANGELOG-zh.md)。

## 0.1.2 更新内容

**手机连上了，车机就真的会到投屏画面。** 0.1.1 把 SDK 的 `parameter` 参数当成连接状态，
于是每一次连接成功都被当作「还在广播」。

- 状态码改为按 SDK 自身语义分发；连上就打开投屏页，30 秒重播看门狗不再打断进行中的会话。
- 不再宣称侧载 APK 永远拿不到的「车机热点」能力 —— 那正是手机卡在协商 10% 的原因。
- 退出再进投屏不再黑屏（换 Surface 前先让 SDK 停手，再绑定新 Surface）。
- 车机屏幕上的触控会回传给手机（ICCOA UIBC 通道）。
- 32 位 / x86 车机不再启动即崩，而是明确说明本机架构跑不了协议栈。

哪些验证过、哪些没有，见 [CHANGELOG-zh.md](CHANGELOG-zh.md)。

## 0.1.1 更新内容

仅文案修正，行为与 0.1.0 完全一致。

- ICCOA CarLink 是小米、OPPO、vivo 联合发起的标准，因此应用的首页、「关于」「支持」页面与法务声明
  统一改为并列书写「小米 CarWith / OPPO Car+ 车联 / vivo Jovi InCar」，不再只提小米。
- 法务声明补充了 ICCOA CarLink 的说明，并把 OPPO、vivo 与小米、ICCOA、Google、比亚迪并列列出。
- 新增繁体中文首页文案；此前 zh-TW 会回退到英文。

## 0.1.0 更新内容

首个公开版本。

- 车机端无线接收 ICCOA CarLink 手机端（小米 CarWith / OPPO Car+ 车联 / vivo Jovi InCar 等）：
  6 位配对码显示在车机屏幕上，在手机上确认即可。
- **30 秒重播看门狗**：车机在未连接状态下会重新发起广播，从而把手机上误关的配对弹窗叫回来，
  且无需重启应用。
- **完整继承基座的设置体系** —— 连接、显示、视频、输入、音频、备份、重置与诊断。
- **继承的仪表盘与 HUD 投屏链路**，供开放了这些区域的车机使用，默认关闭。
- 独立包名（`com.miauto.app`）与独立发布签名密钥，可与同车机上的其他接收端应用并存。

## 特色功能

以下每项都附带**实际状态**，而不只是功能承诺。

### 1. 让车机接住手机互联

应用把自己广播成一台 ICCOA CarLink 可以投到的车机 —— 小米 CarWith、OPPO Car+ 车联、
vivo Jovi InCar 都连它；手机在附近设备列表里发现它，在手机上确认 6 位配对码，
手机画面即出现在车机上。首次正常配对时，车机侧**无需按任何按钮**。

车机端代码里仍保留了基座的 Android Auto 那一半。它现在只作为前台保活存在，其 AA 专属副作用
已通过构建开关刻意关闭 —— 因为其中真正有害的那几项，会与 CarWith 会话争抢它投屏数据通道
所需的 WiFi P2P 组与本地热点。

### 2. 会自己回来的配对弹窗

真正值得工程化应对的失败模式，是用户在手机上把配对弹窗关掉了：车机仍在广播，
但手机不会自己再把弹窗弹出来，而此前的行为只能靠重启应用解决。

MiAuto 在未连接时每 30 秒重新广播一次：

| 机制 | 行为 |
| --- | --- |
| 看门狗 | `30 秒` 定时；到期若仍未连接 → 重播 → 再调度下一轮 |
| 重播 = 停-启 | 广播帧不变时重复广播会被忽略，因此改成「停掉再起」 |
| 绝不打断会话 | 会话建立中或画面已投出时跳过 |
| 新码重置计时 | 用户拿到完整的 30 秒去确认 |
| 手动入口 | 首页刷新按钮做即时重试；仅未连接时可见 |

### 3. 仪表盘与 HUD 链路

基座面向比亚迪已向第三方应用开放的仪表区域，以及挡风玻璃导航输出（箭头、距离、路名）。
只要车机开放了这些区域，MiAuto 就保留了这两条链路。

仪表投屏是**实验性**功能且**默认关闭**。若车机未向应用开放仪表区域，设置项会报告「功能不可用」
而不是报错。仪表投屏按车机按需开启；其下方的投屏时机控件**始终显示** ——
一个会隐藏自己的控件，与一个从未实现的功能无法区分。

**这两条链路是继承来的，尚未与 MiAuto 的协议栈一起验证。**

## 功能一览

- 无线接收 ICCOA CarLink 手机端（小米 / OPPO / vivo 等）；手机可以放在兜里或无线充电板上。
- 配对码自动出现，并带 30 秒重播看门狗（见上）。
- 完整设置体系：连接、显示、视频、输入、音频、备份、重置与诊断。
- 仪表盘镜像与挡风玻璃 HUD 导航，面向开放了这些区域的车机。
- 车机友好的首页：大号配对码、刷新按钮、一个入口进设置。
- 独立包名与签名密钥，可与其他接收端应用并存。
- 下载页提供英文与简体中文；应用本身沿用基座带来的社区翻译。

## 兼容性与安装

**已验证：** 在 Newland NLS-MT9055-GL（Android 11 / arm64）上，应用可安装、可启动、可广播、
可显示配对码并完成一次 CarWith 会话，且配对码在冷启动后依然有效。

**尚未验证：** 在比亚迪或其他量产车机上的安装与配对，以及仪表盘 / HUD 投屏链路。
若你正好在用这类设备，请把自己当作早期测试者，并把现象反馈给我们。

需要一台支持 ICCOA CarLink 的手机：小米 CarWith、OPPO Car+ 车联、vivo Jovi InCar，
或设置里对应的投屏入口。这不是 Android Auto，也不是 Apple CarPlay。

请参阅[安装文档](docs/INSTALL.md)，其中包含权限与安装途径说明。安装 APK 需要你的车机允许。
**请在停车状态下完成设置。**

## 构建

需要 JDK 17、Android SDK 36、NDK `29.0.14206865`、CMake `3.22.1`。
设置 `ANDROID_HOME`，或提供一份不入库的 `local.properties`（`sdk.dir=...`）。

```sh
./gradlew :app:testGithubDebugUnitTest :app:assembleGithubDebug :app:assembleGithubRelease
```

产物位于 `app/build/outputs/apk/github/` 下。

### ICCOA CarLink 协议层不在本仓库内

ICCOA CarLink（CarWith / Car+ 车联 / Jovi InCar 走的手机侧协议）以预编译 SDK 的形式引入，
**该 SDK 不入 git**（见 [app/libs/README.md](app/libs/README.md)）。它在编译期的一半是一份纯签名存根
`app/stubs/icar-sdk-stubs.jar`，这份**是入库的** —— 只用于类型检查，绝不会进 APK。

缺少 SDK dex 时构建仍会成功，但产出的 APK 不具备 CarWith 能力。当 SDK dex 存在时，
`injectIccoaDex` 会把它作为下一个空闲的 `classesN.dex` 追加进成品 APK，再 zipalign 并重签 ——
因为改动 zip 会作废 AGP 的签名。该任务是 finalizer，因此做了幂等处理：追加前先剔除任何与
SDK dex **逐字节相同**的历史注入副本，避免反复构建把 APK 撑大。

### 签名

当存在一份不入库的 `key.properties` 时，Release 构建会自动签名：

```properties
storeFile=/absolute/path/to/your-release-keystore.jks
storePassword=YOUR_PRIVATE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_PRIVATE_PASSWORD
```

`storeFile` 可以相对于仓库根目录，也可以相对于 `app/` 模块，两种路径都会被尝试。
切勿提交签名密钥与口令。自行构建的 APK 若与公开发布的版本签名不同，将无法覆盖安装。

### 包名

| 构建类型 | applicationId | 应用名 |
| --- | --- | --- |
| Release | `com.miauto.app` | MiAuto |
| Debug（可共存） | `com.miauto.app.debug` | MiAuto |

debug 包名让测试版可以与正式版装在同一台车机上。本仓库的 CI 只覆盖 `github` flavor。

## 网页

静态下载页位于 `site/`，由 `scripts/build_site.py` 生成。编辑 `site/content.json` 与该脚本顶部的
`BASE` / `REPO` / `VERSION` 常量，然后运行：

```sh
python3 scripts/build_site.py
```

**不要手工编辑 `site/*/index.html`** —— 下一次构建会覆盖它们。
GitHub Actions 只把 `site/` 部署到 GitHub Pages。

## 致谢与许可

MiAuto 以 [DiAuto Pro](https://github.com/zutaozhong-hash/DiAuto-Pro) 为基座，
后者是 [DiAuto](https://github.com/shihabal3amri/DiAuto) 的独立分支，
再往前是 [Open Headunit](https://github.com/andreknieriem/open-headunit)，
基于其贡献者与 [Michael Reid](https://github.com/mikereidis/headunit) 的工作。

同时感谢 [guo-zhc/MiAuto](https://gitee.com/guo-zhc/MiAuto) —— 本仓库 0.1.1 的独立二开分支，
它把这条路走得远得多。本版采用的「运行时读取真实画布 + 按屏幕方向归序」与「重播指数退避」
两项都出自它的实现，也都补上了本仓库此前判断错的地方。它同样以 AGPL-3.0 发布。

MiAuto 采用 **AGPL-3.0** 许可。见 [LICENSE](LICENSE) 与
`COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt` 中的原始版权声明。本仓库包含已发布应用的源码。

本项目是独立的社区项目，与小米、OPPO、vivo、ICCOA、Google、比亚迪均**无隶属、授权或合作关系**。
文中提及的 Android Auto、CarWith、Car+、Jovi InCar 及所有产品与公司名称，归各自所有者所有。
