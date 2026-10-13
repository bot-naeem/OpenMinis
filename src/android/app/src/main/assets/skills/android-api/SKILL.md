---
name: android-api
description: How to drive the Android device through the `android` tool: packages, settings, notifications, alarms, input, media, and the Shizuku/root privileged backends. Read this when a request needs something the Linux sandbox cannot reach.
---

## The `android` tool

The shell sandbox is an isolated Alpine container with no view of Android. Anything that
touches the device goes through the `android` tool instead:

```
android(action="<action>", args="<arguments>")
android --help <action>   # arguments for one action
android --help           # list every action
```

Arguments are a single space-separated string. Each action below keeps the argument
syntax of the command it replaced, so the examples read exactly as they always did.

## Actions

- android-alarm — schedule alarms/timers in the system Clock app (`schedule <HH:MM> --label <L> [--repeat ONCE|DAILY|WEEKDAYS]`, `timer <seconds> --label <L>`, `open`). Alarms/timers are saved into the user's Android Clock — list/cancel are not supported (no system query API); tell the user to manage them from the Clock app's Alarms/Timers tabs (or `android-alarm open` / minis://views/alarm).
- android-calendar — read/write the device calendar (`list --start YYYY-MM-DD [--end ...] [--max N]`; `create --title <T> --start <ISO> [--end <ISO>] [--description <D>] [--location <L>] [--all-day]`).
- android-clipboard — `get | set <text> [--label L] | clear`.
- android-contacts — `list [--max N] | search <query> [--max N] | get <id> | delete <id>`. Requires READ_CONTACTS (delete also needs WRITE_CONTACTS).
- android-device — `[all|info|battery|storage]` — model, OS version, battery, storage (JSON).
- android-location — `current` for device location with reverse-geocoded address; `geocode <lat> <lon>` for reverse, `forward --address "<addr>"` for forward geocoding.
- android-notification — `send --title <T> [--body <B>] | clear | list [--max N]`. `send` triggers the system permission prompt on Android 13+ if POST_NOTIFICATIONS isn't granted. `list` reads active status-bar notifications and requires Notification Access (one-time setup; the first `list` call opens that page automatically).
- android-open <url> — open a URL via the system handler (http/https, tel:, mailto:, geo:, market:, intent:, etc.). Use shell_execute to run `android-open <url>` when you want to open something immediately. To offer a tappable link instead, write a standard Markdown link with the URL directly — the app handles system URL schemes natively.
- android-photos — `list [--max N] | stats | near <lat> <lon> [--radius KM] [--max N]` — query the device photo library via MediaStore.
- android-player — audio playback sessions (`play <session> <path>`, `pause/resume/seek/stop/status <session>`, `list`).
- android-speak — device TTS (`<text> [--rate F] [--pitch F] [--volume F]`; `--stop | --status`).
- android-speech — microphone transcription (`listen [--language BCP47] [--max N] [--timeout SEC]`; `status`). Requires RECORD_AUDIO.
- android-weather <latitude> <longitude> — Open-Meteo forecast (current + hourly + daily). No API key needed.
- android-shizuku-cli — invoke privileged Android system APIs (package management, settings, system commands) via Shizuku when granted. Curated subcommands return structured JSON; for anything not covered, fall back to `android-shizuku-cli exec <any shell command>` which runs the command via `sh -c` with Shizuku privilege (same surface as `adb shell`). Run with no args (or --help) for the subcommand list.
- android-root-cli — run privileged commands with a superuser grant (UID 0) via `android(action="root", args="<command>")`. Reaches full `/data`, other apps' private databases, system properties, and deep diagnostics (`logcat`, `dumpsys`, `getprop`). Both stdout and stderr are returned in structured JSON. **Authorization:** In chat sessions, root commands trigger an interactive confirmation card showing the exact command with options to `Allow once`, `Allow in this session`, or `Deny`. If the session is pre-authorized (via `/root` slash command or the chat menu), commands execute directly. **Safety:** RootCommandGuard unconditionally blocks raw block device writes (`/dev/block`), mass partition wipes (`rm -rf /data`), and destructive system partition remounts.
- android-a11y-cli — drive system UI (read screen, tap, type, swipe, scroll) via the Android AccessibilityService when enabled. Run with no args (or --help) for the subcommand list.
