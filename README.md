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

## 为什么是 Root

普通的沙箱跑在一个受限的 PRoot 环境里，安全但也就没有权限。
这台项目要做的，是把 Agent 真正接到手机本身上去：

| 执行环境 | 实现 | 能做到什么 |
|---|---|---|
| `SANDBOX` | PRoot + Alpine Linux 用户空间 | 完整 Linux（`apk`、编译、脚本），但被隔离 |
| `HOST_SHELL` | `/system/bin/sh -c` | 直接在 Android 上跑，访问 `am` / `pm` / `/sdcard` |
| `SHIZUKU` | Shizuku ADB shell (UID 2000) | 系统级 API 调用、输入注入、包管理 |
| `ROOT` | `su -c` | **完整 root**。`/data/local/tmp`、`/data/data/*`、设备节点、修改系统 |

Root 环境的意义不是"能跑更多命令"，而是 **Agent 的文件系统边界从沙箱扩大到了整台设备**。
工作区、记忆、技能、配置，可以直接落在真实路径上，重启不丢，换 App 也能访问。

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
结果写进 `memory_get` 能读到的长期存储里。

## 改造方向（进行中 / 计划）

- **Root 环境的可玩性**：让 Agent 在 root 下能真正"当家"，而不是换个环境跑同样的脚本
- **常驻与省电**：更可靠的前台保活、更精细的唤醒策略、更长的后台存活
- **Root 专属能力**：设备状态的深度读取、系统设置、后台任务管理、包管理
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
