# Minis for Root

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20Root-lightgrey.svg)](#)

> **基于 [OpenMinis](https://github.com/OpenMinis/OpenMinis) 二次开发的 Android 客户端。**
>
> 上游是一个成熟的跨平台 AI Agent 项目；本仓库只保留 Android 线，并把改造方向收敛到一件事：
> **让 root 机器上的手机，变成一台可以随时自己醒来的私人助手。**

---

## 这是什么

一个装在你手机里的 AI Agent。它能读你的文件、跑你的命令、看你给的网页、记住你说过的偏好，
并且**能在你睡着的时候自己醒来、自己思考、自己把结果记下来。**

给它接上你自己的模型（Claude / GPT / Gemini / 任何 OpenAI 兼容接口），
它就不再是一个需要你盯着打开的聊天 App，而是一个常驻的、属于你自己的后台进程。

## Root 与权限模型

Agent 的 shell **只有一个执行环境**：PRoot + Alpine Linux 沙箱。它完整、能 `apk add`、能编译，
但对宿主 Android 系统**零访问** —— 没有 `am` / `pm` / `settings`，看不到 `/sdcard`，也拿不到任何 capability。

宿主能力全部收敛到 `android` 这一个工具里，逐项经过权限门：

| 动作 | 实现 | 默认权限 |
|---|---|---|
| 设备 / 包 / 设置 / 输入 / 通知 / 日历 / 相册 … | App 自身 API | BYPASS |
| `android` 的 `shizuku` 动作 | Shizuku binder（UID 2000，或 Sui 提供的 UID 0） | **拒绝**，需在设置里开 |
| `android` 的 `root` 动作 | `su`（UID 0） | **拒绝**，需在设置里开 |
| `read` / `write` / `edit` | 宿主文件系统 | BYPASS（可在设置里改为每次询问） |

文件工具现在直接在宿主上解析路径，所以 `/data/data/…` 这类路径才真正可达。代价是要自己划线，
规则写在 `tools/FileToolGuard.kt`：写系统分区和裸块设备**无条件拒绝**（会刷砖，没有值得冒这个险的任务），
读其他应用的私有数据走权限门。

### 为什么不是"容器内 root"

有人会问：能不能给 Agent 一个真正隔离的 root 环境？在这台机器上不行，而且这不是实现问题：

```
$ zcat /proc/config.gz | grep USER_NS
# CONFIG_USER_NS is not set
```

Android GKI 默认不编译 user namespace。没有它就没有 capability drop，`chroot` 又从来不是安全边界
（它只切换路径解析的根）。实测沙箱内 `mount -t tmpfs` + `mknod` 可以直接建出块设备节点读写裸盘，
SELinux 全程零拒绝。所以「容器内 root」和「不破坏设备」在当前内核上互斥。

于是选择很明确：**不给 Agent 裸 root shell，把 root 的能力拆成工具。** 这也是容器运行时的通行做法
（root in a container），只不过 Android 缺了实现它所需的那块内核。

### 需不需要 root

**建议 root，但不是硬性要求。** App 不检测 root，也不因为没 root 而拒绝安装：

- **有 root（Magisk / KernelSU）** → 在设置里打开 `android` 的 `root` 动作，获得完整 UID 0 能力：
  `/data/local/tmp`、其他应用的私有数据、`/system`、设备节点。可玩性最高。
- **无 root** → 用 `android` 的 `shizuku` 动作。装 Shizuku 并用无线 ADB 启动（UID 2000），
  或装 Sui 模块让它以 UID 0 运行。包管理、设置、输入注入这些都在，
  够用但够不到其他应用的私有数据。

## 时刻唤醒

这是本项目最想做的事，目前已经可用：

```
系统闹钟 (AlarmManager, exact + allowWhileIdle)
        ↓
前台服务保活（穿透 Doze）
        ↓
唤起 Agent 循环 → 跑完整个工具链 → 写回长期记忆
        ↓
通知栏推送结果
```

你可以配置：每天早上自动整理日程和未读、每小时抓一次 RSS 并提炼要点、
每个小时提醒你喝水、活动、久坐。定时任务不是简单的"到点发个 prompt"——
它是一个完整的 Agent 回合，可以调用任意工具、可以再派生子 Agent、
结果写进 `memory` 工具能读到的长期存储里。

## 工具面

8 个工具，名字都是单词 —— 工具 schema 每轮都要重发一遍，名字越短越划算。

| 工具 | 作用 |
|---|---|
| `bash` | 在沙箱里执行 shell 命令 |
| `read` | 读文件。文本返回内容，图片直接作为附件返回 |
| `write` | 写文件 |
| `edit` | 精确字符串替换 |
| `browser_use` | 内置浏览器自动化 |
| `android` | **宿主 Android 的唯一入口**：`android --help <action>` 看某项的参数 |
| `memory` | 跨会话的长期记忆，带 `content` 是写，不带是搜 |
| `subagent` | 派生子 Agent 并发跑，支持 `status` / `steer` / `cancel` / `resume` |

完整的参数文档不在 schema 里，而在 `assets/skills/` 下按需读取
（`android-api` / `minis-control` / `skill-creator`），由一个 `minis-docs` 索引 skill 指向。
每条命令也都支持自己的 `--help`。

## 改造方向（进行中 / 计划）

- **Root 能力的深度**：`android` 的 root 动作目前只透传命令，值得像 shizuku 那样拆出结构化子命令
- **常驻与省电**：更可靠的前台保活、更精细的唤醒策略、更长的后台存活
- **远程控制**：从电脑向家里放着的手机下达指令并实时查看过程（现成的 JSON-RPC 调试面已在，只差流式事件与配对）
- **CLI / Agent 兼容**：尝试把 `claude` 这类现成的终端 Agent 直接跑在沙箱里

## 从源码构建

Android 端需要 JDK 17、Android SDK (API 36)、NDK r28+、Go 1.25+ 和 gomobile。
沙箱相关的原生依赖（PRoot、Alpine rootfs、rclone）都需要**从源码构建**，不提交二进制。

**完整指南见 [BUILDING.md](BUILDING.md)**。简版：

```sh
git clone --recurse-submodules https://github.com/bot-naeem/OpenMinis.git
cd OpenMinis

./deps/build_proot.sh && ./scripts/prepare_android_sandbox.sh
./deps/build_rclone_android.sh
mkdir -p src/android/app/libs && cp deps/build/rclone/rclone.aar src/android/app/libs/
cd src/android && ./gradlew :app:assembleDebug
```

产物：`src/android/app/build/outputs/apk/debug/app-debug.apk`

## 目录结构

```
src/android/     Android 端（Kotlin / Compose），本项目唯一活跃的开发线
src/ios/         iOS 端，随上游保留，本项目不做改动
deps/            原生依赖构建脚本与 vendored 源码
docs/specs/      架构与接口规格
scripts/         rootfs 准备与开发工具
```

## 基于 OpenMinis

本项目基于 **[OpenMinis](https://github.com/OpenMinis/OpenMinis)** 开发，
遵循其原仓库的 **[GPLv3](LICENSE)** 协议。

沙箱与媒体相关的上游依赖同样保留：

- **[iSH](https://github.com/ish-app/ish)** (GPLv3) — iOS 端 Linux 用户态模拟（`src/ios/`）
- **[PRoot](https://github.com/termux/proot)** (GPLv2) + [talloc](https://talloc.samba.org) (LGPLv3+) — Android 沙箱
- **[Alpine Linux](https://alpinelinux.org)** — 沙箱启动的 minirootfs
- **[FFmpeg](https://ffmpeg.org)** (LGPL-2.1+)、**[LAME](https://lame.sourceforge.io)** (LGPL)、
  [cppjieba](https://github.com/yanyiwu/cppjieba) (MIT)、[KaTeX](https://katex.org) (MIT)

完整清单与许可条款见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。

上游贡献请前往 OpenMinis 主仓库；本仓库的改动集中在 Android 端的 root 与常驻能力上。
