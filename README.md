# tv-adb-keeper

小米电视（MiTV / MITV OS）每次重启后，厂商的 `com.xiaomi.mitv.settings`
会在开机约 20 秒时把 `Settings.Global.ADB_ENABLED` 写回 `0`，导致无线 ADB
（5555 端口）断开，重启后必须有人进「设置 → 账号与安全」手动打开。

这个 App 只做一件事：开机后每 3 秒检查一次 `adb_enabled`，发现被改成 0 就写回 1。
于是 —— 重启后 ADB 会自动恢复，浏览器遥控（ws-scrcpy / scrcpy）不再需要手动开调试。

## 原理

- `WRITE_SECURE_SETTINGS` 这个权限在 Android 里的保护级别是
  `signature|privileged|development`，带 `development` 位意味着 **adb shell 可以
  `pm grant` 给普通第三方 App**。
- 授权一次即持久（跟着包走，重启不丢；只有 force-stop / 卸载重装会清）。
- 写 `ADB_ENABLED=1` 之后，是 `system_server` 里的 `AdbSettingsObserver`
  真正去重启 adbd —— App 不需要 root，也不需要自己启进程。
- 厂商那个 APK 里 `adb_enabled` 字符串只出现 1 次、`accessibility_enabled` 出现 0 次，
  说明它只重置 ADB、不碰无障碍。

## 装机（只做一次）

```bash
# 1. 装 APK
adb install -r -g tv-adb-keeper-debug.apk

# 2. 授予写系统设置的权限（这一步是唯一的手动动作）
adb shell pm grant com.shijie.adbkeeper android.permission.WRITE_SECURE_SETTINGS

# 3. 打开一次 App（或直接起服务），让守护跑起来
adb shell am start -n com.shijie.adbkeeper/.MainActivity
```

之后开机 `BOOT_COMPLETED` 会自动拉起守护服务，不需要再做任何事。

## 验证

```bash
# 故意关掉 ADB，看是否自动恢复（3 秒内）
adb shell settings put global adb_enabled 0
# ...等 5 秒
adb shell settings get global adb_enabled     # 期望 1
```

注意：`adb_enabled=0` 会立刻断掉当前的 adb 会话，所以要另开一个 shell 观察，
或者用电视上的 App 界面看状态（App 首页会实时显示「当前 ADB 开关：开/关」）。

## 界面

App 首页会显示：
- 写入权限是否已授予
- 当前 ADB 开关状态（每 2 秒刷新）
- 「立即检查并开启一次」「启动后台守护」「加入电池白名单」「打开开发者选项页」四个按钮

没授权时首页会直接把 `pm grant` 命令打在屏幕上，方便复制。

## 构建

`.github/workflows/build.yml` 用 GitHub Actions 构建（ubuntu + JDK 17 + Android SDK 33），
产物在 Actions 的 Artifacts 里，名字 `tv-adb-keeper-apk`。不需要本地装 Android SDK。

## 兼容性

- minSdk 21 / targetSdk 28 / compileSdk 33
- 纯 Java，无 AndroidX、无第三方依赖、无 native 库 → 任何 ABI 都能装
- 已在 MiTV-ANSP0（Android 9 / SDK 28 / armeabi-v7a / 2GB RAM）上验证

## 已知限制

- 只能在「厂商写 0 之后」抢回来，不是真正阻止厂商写。极端情况下（厂商改完立刻
  做一次性 adbd 检查）可能有几秒窗口期。
- Android 10+ 的分区存储/后台限制更严，本项目只针对 Android 9 电视验证过。

## License

MIT
