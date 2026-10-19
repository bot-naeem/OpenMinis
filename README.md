# Minis for Root (OpenMinis)

<p align="center">
  <b>让一台 Root 过的 Android 手机，成为 7x24h 随时自主唤醒、具备系统级全权掌控力的私人智能体。</b>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License: GPL v3"></a>
  <a href="#"><img src="https://img.shields.io/badge/Platform-Android%2012%2B-green.svg" alt="Platform"></a>
  <a href="#"><img src="https://img.shields.io/badge/Root-Magisk%20%7C%20KernelSU%20%7C%20APatch-orange.svg" alt="Root Supported"></a>
  <a href="#"><img src="https://img.shields.io/badge/Sandbox-PRoot%20%2B%20Alpine-purple.svg" alt="Sandbox"></a>
  <a href="#"><img src="https://img.shields.io/badge/UI-Jetpack%20Compose-navy.svg" alt="UI"></a>
</p>

> **基于 [OpenMinis](https://github.com/OpenMinis/OpenMinis) 二次开发的 Android 独立分支。**  
> 上游是一个成熟的跨平台 AI Agent 项目；本仓库聚焦 Android 深度改造，将手机从单纯的“聊天客户端”彻底重构为**常驻后台、具备独立 Linux 沙箱与宿主 Root 双层掌控力、随时能自我唤醒思考并操作手机的物理助手**。

---

## 💡 为什么选择 Minis for Root？

现有的各类“手机端 AI Agent”大多存在以下硬伤：
- ❌ **后台即死**：锁屏或切换应用就被系统杀后台，无法持续自主执行长链任务。
- ❌ **弱鸡沙箱或裸机风险**：要么没有环境只能做玩具聊天，要么直接暴露裸 root shell 一旦模型幻觉就 `rm -rf /` 或刷砖。
- ❌ **界面遮挡与软键盘打架**：悬浮窗挡住操作区域、弹出软键盘顶起整个页面导致视觉模型（VLM）坐标错位。

**Minis for Root 的核心解法：**

- 🌌 **全屏极光流光 (Aurora Edge Glow)**：告别遮挡屏幕的悬浮胶囊！在思考（Thinking）、执行（Executing）、完成/异常时通过屏幕四周全屏边缘极光呼吸指示状态，截屏瞬间精准快门闪烁（Shutter Blink），全屏零物理遮挡。
- ⌨️ **零 UI 干扰 IME 接管 (MinisKeyboardService)**：内置无界面静默输入法，Agent 输入文字时无需弹出任何软键盘，防止界面重绘错位。
- 🛡️ **双层安全沙箱架构**：计算与环境在 **PRoot + Alpine Linux** 沙箱内安全隔离；宿主特权通过严格的 **Root 权限门 + FileToolGuard 物理熔断**，绝不触碰分区表与裸盘设备。
- 🚀 **原生快速路径 (Fast-Path Intent) 与 视觉差分**：优先尝试 App DeepLink 直达目标页面；配合视觉感知与差分检测，告别盲目瞎划。
- ⏰ **Doze 穿透保活与定时自主唤醒**：结合 `AlarmManager` 极低功耗对齐与前台高优先级保活，即使深夜锁屏也能按时醒来、完成复杂多轮任务并推送到通知栏。

---

## 🏛️ 系统架构 (Architecture)

```mermaid
flowchart TD
    subgraph CloudLocalLLM["🧠 外部 / 本地大模型"]
        LLM["Claude / GPT-4o / DeepSeek / Ollama / OpenAI 兼容接口"]
    end

    subgraph HostAndroid["📱 Android 宿主环境 (Minis App)"]
        UI["Jetpack Compose UI\n(会话 / 历史 / 存储 / 权限管理)"]
        Aurora["🌌 AuroraGlowManager\n(全屏极光呼吸灯 + 截屏快门)"]
        IME["⌨️ MinisKeyboardService\n(后台静默输入法接管)"]
        FGS["⚡ 保活与定时中枢\n(Foreground Service + AlarmManager)"]
        Gate["🛡️ OffloadPermissionManager\n& FileToolGuard (物理安全门)"]
    end

    subgraph ExecSandbox["🐧 Linux 沙箱执行环境"]
        PRoot["PRoot + Alpine Linux rootfs\n(支持 apk add / Python / 独立隔离)"]
    end

    subgraph HostControl["⚡ 宿主操控与特权层"]
        RootBridge["Host Root (`su` UID 0)\nMagisk / KernelSU / APatch"]
        ShizukuBridge["Shizuku / Sui Binder\n(UID 2000 / UID 0)"]
        SystemAPI["Android Accessibility / A11y & MediaProjection"]
    end

    LLM <--> FGS
    FGS <--> UI
    FGS -.-> Aurora
    FGS -.-> IME
    FGS --> Gate

    Gate -->|本地运行命令 / 读写沙箱| PRoot
    Gate -->|高危系统命令 (白名单与询问)| RootBridge
    Gate -->|应用免Root操作 / 注入| ShizukuBridge
    Gate -->|界面点击 / 截图 / OCR| SystemAPI
```

---

## 🛡️ Root 与权限安全模型

Agent 的 shell **只有一个执行环境**：PRoot + Alpine Linux 沙箱。它完整、能 `apk add`、能编译，但对宿主 Android 系统**零访问** —— 没有 `am` / `pm` / `settings`，看不到 `/sdcard`，也拿不到任何 capability。

宿主能力全部收敛到 `android` 与系统工具中，逐项经过权限门过滤：

| 动作分类 | 核心实现 | 默认权限级别 | 适用场景与安全保证 |
|---|---|---|---|
| **设备 / 包 / 设置 / 输入 / 通知** | 宿主原生 API / A11y | `BYPASS` | 读取电量、打开 App、截屏、点击等基础交互 |
| **`android` 的 `shizuku` 动作** | Shizuku Binder (UID 2000 / Sui UID 0) | `BYPASS`* (需服务在线) | 卸载/冻结 App、免 Root 权限管理与输入注入 |
| **`android` 的 `root` 动作 / `bash(as_root=true)`** | `su` (UID 0) | 需在设置中显式开启 | 读取应用私有数据 (`/data/data/`)、修改高级系统配置 |
| **`read` / `write` / `edit` 文件工具** | 宿主文件系统透传 | `BYPASS` (受保护) | 受 `FileToolGuard` 熔断拦截，严禁写系统分区与裸块设备 |

> \*注：运行时真实校验后端服务连通性与用户授权。

### 为什么不是“容器内 Root”？

很多人会问：能不能给 Agent 容器一个真正隔离的 root 账户？
答案是：**在标准 Android 内核上做不到**。
```sh
$ zcat /proc/config.gz | grep USER_NS
# CONFIG_USER_NS is not set
```
Android GKI 内核默认**未编译** `CONFIG_USER_NS`（User Namespaces）。
- 没有 User Namespace 就无法安全降权（Capability Drop），而 `chroot` 从来都不是安全边界；
- 在沙箱内一旦开放挂载特权，大模型在实测中可以通过 `mount -t tmpfs` + `mknod` 直接生成原始磁盘块设备（如 `/dev/block/mmcblk0`），对裸盘数据直接抹写，SELinux 均无法拦截。

因此架构决策极其明确：**不给 Agent 裸 root shell，把 root 能力解构为带有安全拦截门的专有工具**。

### 需不需要 Root？

**建议 Root，但非强制要求**：
- **有 Root (Magisk / KernelSU / APatch)**：直接在「设置 → 权限与扩展」中一键探测并点亮 Root 权限。获得完整 UID 0 能力，可访问任意应用内部数据库、抓包、调试与深度系统定制。
- **无 Root (Shizuku / 无线 ADB)**：通过 Shizuku 激活（UID 2000）或 Sui 模块。应用冻结、后台输入注入、电池优化白名单等全都在线，满足绝大多数日常自动化。

---

## 🧰 核心工具矩阵

工具设计遵循极简单词命名（降低上下文 Token 开销，Schema 保持小巧）：

| 工具名称 | 权限要求 | 作用说明 |
|---|---|---|
| `bash` | 沙箱内自由 | 在 PRoot Alpine 沙箱内执行命令（可自由 `apk add`、跑 Python 脚本、并发多任务）；当传入 `as_root=true` 时按规则由宿主 Root 代理执行 |
| `android` | 宿主分级控制 | **宿主操作统一网关**。运行 `android --help <action>` 查看子命令：包名直达、系统广播、通知发送等 |
| `read` | 宿主安全守卫 | 读取文件。代码文本直接返回内容，图片以视觉附件形式交付模型 |
| `write` / `edit` | 宿主安全守卫 | 文件新建与精准跨行替换（受 `FileToolGuard` 保护，拒绝篡改系统关键分区） |
| `browser_use` | 原生沙箱 | 内置无头浏览器引擎，自主浏览网页、抓取动态数据与表单填写 |
| `memory` | 本地存储 | 跨会话的持久记忆库。传入 `content` 为存入记忆，省略为检索与联想 |
| `subagent` | 并发调度 | 派生异步子智能体协同作业，支持 `status` / `steer` / `cancel` / `resume` 拓扑控制 |

完整的参数文档不在 schema 里，而在内置 Prompt 与按需索引中按需读取。

---

## ⏰ 时刻唤醒与长期自主性

这是 Minis 最强大的能力 —— 它不是简单的定时提醒，而是一个完整的 Agent 回合：

```
系统闹钟 (AlarmManager.setExactAndAllowWhileIdle)
        ↓
前台服务高优先级保活（穿透 Android Doze 低功耗休眠）
        ↓
唤醒 Agent 思考回路 → 执行多步工具链与屏幕自动化 → 写回持久化长期记忆
        ↓
系统通知栏呈现实时摘要报告
```

**应用场景举例：**
- **早间简报**：每天早晨 7:30 自动查阅未读邮件、天气与当天日程，提炼生成晨间摘要。
- **自动化巡检**：定时采集指定 RSS、论坛监控、服务器监控状态，异常时直接通过手机推送警报。
- **深层任务**：夜间无人使用手机时，自动清洗临时文件、同步日志与备份个人数据。

---

## 🚀 快速上手

### 1. 下载与安装

- 编译生成的 Debug 安装包位于：`src/android/app/build/outputs/apk/debug/app-debug.apk`。
- 通过 ADB 直接安装并赋予核心权限：
  ```sh
  adb install -r app-debug.apk
  ```

### 2. 核心权限一键配置

为了获得极致的自动化体验，安装后进入 App 设置：
1. **全屏极光流光 (悬浮窗权限)**：
   - 若手机已 Root，在「设置 → 权限」中可一键通过 Root 自动授权（免手动跳转）；
   - 或点击进入系统“显示在其他应用上层”手动授权。
2. **Minis 静默输入法**：
   - 在系统「语言与输入法」中勾选启用 **Minis Keyboard**，获得无软键盘干扰的文字注入体验。
3. **Root 授权状态检测**：
   - 在设置中点击“Root 权限状态”，将主动触发 Magisk / KernelSU / APatch 授权弹窗确认。

### 3. 配置大模型

支持各种主流云端 LLM 与本地部署模型：
- **Anthropic Claude**（推荐 `claude-3-7-sonnet`，多步 Tool Use 最稳健）
- **OpenAI / DeepSeek**（支持全系兼容格式接口）
- **Google Gemini**（支持多模态实时视觉感知）
- **本地 Ollama / vLLM**（在局域网或沙箱内填入端点即可离线运行）

---

## 🛠️ 从源码构建 (WSL2 / Linux 推荐)

> [!TIP]
> **强烈建议在 WSL2 (Ubuntu 22.04+) 或原生 Linux 环境下编译**。  
> 可以完全规避 Windows 环境下的软链接断裂、Cygwin 脚本换行符与 260 字符 MAX_PATH 路径限制。

### 依赖环境
- JDK 17
- Android SDK (API 36) & Android NDK (r28+)
- Go 1.25+ & `gomobile`
- CMake, Make, Patch

### 构建步骤

```bash
# 1. 克隆代码仓库及子模块
git clone --recurse-submodules https://github.com/bot-naeem/OpenMinis.git
cd OpenMinis

# 2. 编译 PRoot 原生沙箱与 Alpine rootfs
./deps/build_proot.sh && ./scripts/prepare_android_sandbox.sh

# 3. 编译 rclone 原生库并同步到 libs
./deps/build_rclone_android.sh
mkdir -p src/android/app/libs && cp deps/build/rclone/rclone.aar src/android/app/libs/

# 4. 执行 Gradle 构建生成 APK
cd src/android
./gradlew :app:assembleDebug
```

产物：`src/android/app/build/outputs/apk/debug/app-debug.apk`

更详尽的编译配置与环境变量设置见 [BUILDING.md](BUILDING.md)。

---

## 📂 目录结构

```
OpenMinis/
├── src/android/       # Android 端源码 (Kotlin / Compose)，活跃开发主线
│   ├── app/           # 主应用、UI、ViewModel、前台保活服务、极光流光管理器
│   └── ...
├── deps/              # 原生依赖 (PRoot, talloc, rclone) 构建脚本与源码
├── scripts/           # 沙箱 rootfs 准备、签名与打包辅助工具
├── docs/specs/        # 架构规格文档与接口设计
└── assets/skills/     # 智能体扩展技能与指令提示词
```

---

## 📜 开源协议与致谢

本项目基于 **[OpenMinis](https://github.com/OpenMinis/OpenMinis)** 深度二次开发，遵循 **[GPLv3](LICENSE)** 协议开源。

沙箱与媒体相关的上游依赖同样完整保留：
- **[iSH](https://github.com/ish-app/ish)** (GPLv3) — iOS 端 Linux 用户态模拟支持（随上游保留）
- **[PRoot](https://github.com/termux/proot)** (GPLv2) + **[talloc](https://talloc.samba.org)** (LGPLv3+) — Android 端沙箱隔离核心
- **[Alpine Linux](https://alpinelinux.org)** — 超轻量 Linux minirootfs
- **[FFmpeg](https://ffmpeg.org)** (LGPL-2.1+)、**[LAME](https://lame.sourceforge.io)** (LGPL)、**[KaTeX](https://katex.org)** (MIT)、**[cppjieba](https://github.com/yanyiwu/cppjieba)** (MIT)

完整第三方开源许可条款请参阅 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。
