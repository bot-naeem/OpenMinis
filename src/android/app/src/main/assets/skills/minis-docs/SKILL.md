---
name: minis-docs
description: Index of Minis' own reference documentation. Read this when you need the exact arguments of the android tool or of the minis-config / minis-model-use / minis-sessions-cli / minis-scheduled commands, rather than guessing them.
---

# Minis reference documentation

Each file below is a `SKILL.md`. Read the one you need with the `read` tool before
using the commands it documents — the tool schemas deliberately stay short, so the
argument detail lives here instead of being re-sent on every request.

| File | Covers |
|---|---|
| `/var/minis/skills/android-api/SKILL.md` | The `android` tool: device, packages, settings, activities, notifications, alarms, calendar, clipboard, contacts, photos, location, speech, media, the Shizuku backend, the root backend |
| `/var/minis/skills/minis-control/SKILL.md` | `minis-config` (read and change Minis settings), `minis-model-use` (call another configured model), `minis-sessions-cli` (read chat history), `minis-scheduled` (create and manage timed prompts), `minis-open` |
| `/var/minis/skills/skill-creator/SKILL.md` | How to write a skill: frontmatter, progressive disclosure, when to split into `references/` |

Every command also answers `--help` on its own, so `android --help settings` is
always a valid fallback when this index is not enough.
