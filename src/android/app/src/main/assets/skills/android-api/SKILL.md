---
name: android-api
description: How to control and automate the Android device via Root bash and Doubao/UI-TARS Visual Grounding (screen_view, screen_touch). Read this when a task needs to interact with the Android host system, apps, or screen.
---

## Android Architecture Overview

Minis provides an atomic, high-performance tool surface tailored for Android geeks and power users:

1. **Host Privilege & System Operations**: `bash(command="...", as_root=true)`
2. **Visual Grounding & Screen Automation**: `screen_view` and `screen_touch` (aligned with Doubao Phone Assistant / UI-TARS)
3. **Sandbox Browser**: `browser_use` for isolated web tasks
4. **File & Container Operations**: `bash(command="...", as_root=false)`, `read`, `write`, `edit`

---

## 1. Privileged Host Operations (`bash as_root`)

To interact with the Android OS, system settings, package manager, activity manager, or filesystem outside the Linux container, use `bash` with `as_root: true`:

```json
{
  "tool_title": "Inspect installed packages",
  "command": "pm list packages -3",
  "as_root": true
}
```

### Common Root Commands:
- **Package Manager (`pm`)**:
  - List third-party apps: `pm list packages -3`
  - Get APK path: `pm path com.example.app`
  - Force stop app: `am force-stop com.example.app`
  - Clear app data: `pm clear com.example.app`
  - Launch app: `monkey -p com.example.app -c android.intent.category.LAUNCHER 1` or `am start -n <package>/<activity>`
- **System Properties & Diagnostics**:
  - Properties: `getprop ro.build.version.release`, `getprop ro.product.model`
  - Battery stats: `dumpsys battery`
  - Diagnostics: `logcat -d -t 100`, `dumpsys meminfo <package>`
- **Host Filesystem**:
  - Direct access to `/data/data/<package>/` (e.g. app databases, preferences)
  - `/sdcard/` / `/storage/emulated/0/` access

> [!NOTE]
> Root execution is protected by `RootCommandGuard` (blocking brick commands like `/dev/block` wipes) and session-level user confirmation cards.

---

## 2. Visual Grounding & Screen Automation (`screen_view` & `screen_touch`)

Aligned with Doubao Phone Assistant (豆包手机助手) and UI-TARS.

### Screen Perception (`screen_view`)
Captures the current screen. Automatically selects the best backend (Root `screencap`, Shizuku, or AccessibilityService).

```json
{
  "tool_title": "Perceive current screen",
  "som": true
}
```

- **Set-of-Marks (`som: true`, default)**:
  - Scans interactive UI elements using Accessibility hierarchy.
  - Draws high-contrast numbered tags (`[1]`, `[2]`, ...) on the screenshot bitmap.
  - Returns the tagged image together with a structured list of detected elements (`id`, `text`, `desc`, `center`, `bounds`).
  - Perfect for general multimodal models (GPT-4o, Claude 3.5, Gemini 1.5): the model can simply target elements by ID (e.g. `element: 3`).
- **Clean Screenshot (`som: false`)**:
  - Returns raw, untagged screenshot.
  - Ideal for native GUI grounding models (e.g. UI-TARS, Qwen2-VL) that output coordinates directly.

### Precision Interaction (`screen_touch`)
Executes physical touch gestures, text entry, navigation, or pauses for user assistance.

Targeting options:
1. **By Element ID**: `element: 3` (from previous `screen_view` SoM)
2. **By UI-TARS Point**: `point: "[523, 814]"` (normalized 0~1000 scale)
3. **By Coordinates**: `x: 523, y: 814` (normalized 0~1000 scale)

Supported actions:
- `click` / `tap`: Tap an element or coordinate.
- `double_click` / `double_tap`: Quick double tap.
- `long_press`: Long press (specify `duration_ms`, default 800ms).
- `swipe`: Drag from `(x, y)` to `(to_x, to_y)`.
- `scroll`: Scroll in container (`direction: "up"|"down"|"left"|"right"`).
- `type`: Type text into the active field (`text: "..."`).
- `press_key`: System keys (`key: "back"|"home"|"recents"|"enter"|"delete"|"volume_up"|"volume_down"`).
- `wait`: Pause for UI rendering or animation (`duration_ms: 1000`).
- `takeover`: Human-in-the-loop handoff. Pauses execution when encountering CAPTCHAs, SMS OTPs, or biometric auth, requesting the user to handle it before resuming.

### Instant Visual Verification (`capture_after: true`)
Set `capture_after: true` on any `screen_touch` action to immediately capture and return the updated screen in the same turn, reducing roundtrips.
