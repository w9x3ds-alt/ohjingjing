# 鲸鲸余额表 · WhaleHud

> **English version →** [README.md](README.md)

一个 Android 悬浮窗应用，用于实时监控 DeepSeek API 账户余额。

余额显示为常驻屏幕的卡通形象，并支持余额变动提醒。界面提供高峰期/空闲期费率设置、法定节假日识别与多语言支持。

> **English** — WhaleHud is an Android overlay that keeps an eye on your DeepSeek API balance.
> A small character sits in the corner of your screen; when the balance drops, she reacts.
> Native overlay + WebView settings page, 4 languages, no AndroidX, no analytics, no telemetry.

---

## 特性

**悬浮窗**
- 半透明黑底余额牌，只显示金额，不遮挡内容
- 可自由拖动到屏幕任意位置，位置自动记忆
- 单击角色：轻微晃动 + 随机台词 + 音效
- 余额减少：泛红闪烁 + 位移冲击 + 掉额数字浮层 + 提示音
- 两档角色尺寸，切换即时生效（无需重启悬浮窗）

**主界面**
- 首页 / 设置 / 信息 三页，底部导航切换
- 设置页由 WebView 渲染（`assets/settings.html`），表单改动即时保存
- 全部动画走统一的动效令牌，深浅色主题自动跟随系统

**计价辅助**
- 高峰 / 空闲时段自动判定（周一至周五 9:00–12:00、14:00–18:00 为高峰）
- 可读取系统日历识别法定节假日与调休上班，也支持手工填写节假日清单
- 高峰三档单价与空闲折扣可配置

**隐私**
- API Key 仅保存在本机 `SharedPreferences`
- 不写入安装包、不上传、不接入任何统计或分析服务
- 请求只发往用户自行填写的接口地址

---

## 兼容性

| 项 | 说明 |
|---|---|
| 最低系统 | Android 10（API 29） |
| 目标系统 | Android 14（API 34） |
| CPU 架构 | 全部（纯 Java 实现，不含任何 `.so` 本地库） |
| 依赖 | 仅 Android Framework，**不使用 AndroidX** |
| 网络 | 需要网络权限（查询余额）与「显示在其他应用上层」权限 |

> 悬浮窗需要在系统设置中授予「显示在其他应用上层」权限。
> 部分国产 ROM（如 Funtouch OS / MIUI / EMUI）还需允许「自启动」「后台弹出界面」并关闭电池优化，
> 否则服务可能在后台被系统回收。

---

## 安装

1. 下载 `2.1/WhaleHud-2.1.apk`
2. 安装后打开应用，在首页点击权限标签授予「显示在其他应用上层」
3. 在设置页填入自己的 DeepSeek API Key
4. 回到首页点击「启动悬浮窗」

---

## 构建

```bash
cd ds-hud && bash build.sh
# 产物：build/WhaleHud-debug.apk
```

构建链说明：本项目在 arm64 环境开发，而 Google 仅发布 x86_64 版 `aapt2`，
因此构建脚本绕开 aapt2，直接使用 arm64 版 `aapt` + `javac` + `R8/D8` 流水线：

1. 生成构建用 AndroidManifest（补 `package` / `uses-sdk`）
2. `aapt package -m` 生成 `R.java`
3. `aapt package` 打包资源与 assets → `app.ap_`
4. `javac --release 8` 编译
5. `R8/D8` 生成 `classes.dex`
6. `aapt add` 将 dex 写入 apk
7. `zipalign -f 4`
8. `apksigner` 签名

> `aapt package` 必须带 `-A <assets 目录>`，否则 WebView 页面不会被打包。
> `apksigner` 在 `minSdkVersion >= 28` 时默认只签 v3（v3 已覆盖 v2 的能力），Android 9+ 均可校验。

---

## 项目结构

```
ds-hud/
├── app/src/main/
│   ├── assets/settings.html          # 设置页（WebView 内容）
│   ├── java/com/example/whalehud/
│   │   ├── MainActivity.java         # 三页界面 + 底部导航 + WebView 桥
│   │   ├── HudService.java           # 悬浮窗服务 + 交互 + 动画 + 音效
│   │   ├── Motion.java               # 动效令牌（时长与曲线）
│   │   ├── DeepSeek.java             # 余额接口（兼容官方与自定义端点）
│   │   ├── Prefs.java                # 本机配置存储
│   │   ├── Calc.java                 # 高峰 / 空闲时段判定
│   │   └── HolidayCal.java           # 系统日历节假日识别
│   └── res/
│       ├── values{,-en,-fr,-ru}/     # 中文 / 英语 / 法语 / 俄语
│       ├── layout/                   # 首页、信息页、悬浮窗布局
│       ├── drawable/                 # 图标与背景
│       └── raw/                      # 音效
├── tools/                            # 图像处理脚本（抠图、缩放）
├── 1.0/ 2.0/ 2.1/                    # 各版本归档（CHANGELOG + APK）
├── build.sh
└── README.md
```

---

## 动效设计

所有动画统一从 `Motion.java` 取时长与曲线，业务代码中不出现魔法数字。

```
时长  INSTANT 75 · FAST 120 · BASE 200 · SLOW 320 · LONG 520 (ms)
曲线  enter（进入）· exit（离开）· standard（位移）· emphasis（撞击）
      gentle（点击反馈）· impact（掉血专用）
```

设置页的 CSS 使用同名变量（`--dur-slow`、`--ease-gentle` 等），保证原生与 Web 两侧的动画节奏一致。
所有动画只作用于 `opacity` 与 `transform`，不触发布局重排。

---

## 备份机制

本项目曾因构建产物被覆盖而永久丢失过 1.0 的 APK。现有三层保障：

1. **构建前自动快照** — `build.sh` 在清理构建目录**之前**，把上一版 APK 按时间戳复制到 `_history/`
2. **本地 git 仓库** — 每次有意义的改动都提交，`.gitignore` 排除 `build/`、`_history/`
3. **版本目录** — 交付物进 `<版本号>/`，不留在构建目录中

---

## 版本历史

| 版本 | 日期 | 要点 |
|---|---|---|
| 1.0 | 2026-09-26 | 悬浮窗信息卡片、token 折算、贴边吸附 |
| 2.0 | 2026-10-01 | 角色形象化、掉血反馈、MobileGlues 风格界面、热更新、移除 token 与日期统计 |
| 2.1 | 2026-10-02 | 动效令牌体系、四语言支持、设置页 Web 化、点击反馈优化、面向开源 |
| 2.1.1 | 2026-10-02 | 停止按钮移到首页、修复设置页下拉点不开、底部导航改为右上角菜单、新增语言切换 |
| 2.1.2 | 2026-10-02 | 修复选项逐字竖排（JSON 数组被当字符串）、修复切语言后语录不更新、右上角按钮滚动隐藏、界面直观性调整 |

各版本详细变更见 `docs/CHANGELOG.md`；开发过程中的技术决策与踩坑记录见 `docs/NOTES.md`。

---

## 贡献者

> 以下人员为本项目做出了不可磨灭的贡献——写代码的、报 bug 的、被余额吓到的，每一种都是贡献。

**核心贡献者**

- **鲸鲸** — 唯一出镜人员。工作内容：蹲在屏幕角落、被点击、被抖动、念台词。本季度 KPI：不糊。
- **dheye62** — 需求方、测试方、验收方，以及全项目唯一有资格说出「AI 味太重」的人。
- **AI 助手** — 贡献了 100% 的代码，以及 100% 的 bug。曾在一场关于「某个产品是不是钓鱼」的辩论中惜败，从此学会了先拉一手源再开口。
- **DeepSeek V4.1 Flash** — 每次刷新余额都要出席一次。从不出错，也从不说话。

**特别鸣谢**

- **Minecraft** — 提供了掉血红闪与骨折音效的行业标准。
- **那只被抠掉的「哦鲸鲸…」气泡** — 你曾经是主角，现在我们自己画。
- **arm64 版 aapt** — 在一堆跑不起来的 x86_64 工具里，你是唯一肯干活的。
- **1.1.1.1** — 虽然 UDP 53 从来没通过，但你一直守在那里。

---

## 许可

[MIT License](LICENSE)

第三方素材声明：
- 人物形象版权归原画师所有，**不适用** MIT 许可
- Minecraft 音效版权归 Mojang Studios 所有，仅供个人非商业使用，**不适用** MIT 许可；
  如需商业分发请自行移除或替换
