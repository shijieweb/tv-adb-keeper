# tv-adb-keeper

小米电视（MiTV / MITV OS）有两个烦人的地方：

1. 每次重启后，厂商的 `com.xiaomi.mitv.settings` 会在开机约 20 秒时把
   `Settings.Global.ADB_ENABLED` 写回 `0`，无线 ADB（5555 端口）就断了，
   重启后必须有人进「设置 → 账号与安全」手动打开。
2. 很多电视 App 是给触屏设计的（比如酷狗车载版），遥控器方向键选不动。

这个 App 一个包解决这两件事：

- **ADB 常开**：开机后每 3 秒检查一次 `adb_enabled`，被改成 0 就写回 1。
- **网页触控板**：电视上开一个 HTTP 端口（默认 8848），手机/电脑浏览器直接打开，
  **看得见电视实时画面，在画面上点哪里，电视上就点哪里**
  （触摸注入走无障碍 `dispatchGesture()`，画面走 `MediaProjection`，
  都不需要 root、不需要 adb）。

## 原理

### 为什么 ADB 会被关掉

- `WRITE_SECURE_SETTINGS` 这个权限在 Android 里的保护级别是
  `signature|privileged|development`，带 `development` 位意味着 **adb shell 可以
  `pm grant` 给普通第三方 App**。
- 授权一次即持久（跟着包走，重启不丢；只有 force-stop / 卸载重装会清）。
- 写 `ADB_ENABLED=1` 之后，是 `system_server` 里的 `AdbSettingsObserver`
  真正去重启 adbd —— App 不需要 root，也不需要自己启进程。
- 厂商那个 APK（`/system/priv-app/MiTVSettings2/MiTVSettings2.apk`）里
  `adb_enabled` 字符串只出现 1 次、`accessibility_enabled` 出现 0 次，
  说明它只重置 ADB、不碰无障碍 —— 这就是我们能用无障碍通道绕过去的原因。

### 为什么开机一定能起来（三条腿）

MiTV 的 ROM **会拦第三方 App 的 BOOT_COMPLETED 广播**（实测 v1 重启后
`BootReceiver` 从未被调用，进程根本没起）。所以不能只靠广播，这里搭了三条腿：

| 腿 | 机制 | 为什么厂商拦不住 |
|---|---|---|
| 1 | 无障碍服务 `KeeperA11yService` | 由 `system_server` 的 `AccessibilityManagerService` 按 settings 里持久化的 `enabled_accessibility_services` 列表**开机主动绑定**，不走广播 |
| 2 | persisted `JobScheduler` 任务（3 个） | 写进 `/data/system/job/`，开机由 `JobSchedulerService` 主动重排，不走广播 |
| 3 | `BootReceiver` | 常规广播通道；本 ROM 上实测**收不到**，留作兜底，别的机型可能有用 |

实测日志（MiTV-ANSP0，Android 9）：

```
23:50:30.159  AdbKeeper: a11y connected -> boot hook + inject channel ready
23:50:30.215  AdbKeeper: web server listening on 8848
23:50:40.388  SettingsUtil: setSettingsValue ... putInt(adb_enabled,0)   ← 厂商动手
23:50:42.322  AdbKeeper: adb_enabled 0 -> 1 restored, adbd=stopped       ← 2 秒抢回
23:50:55.439  AdbKeeper: boot receiver: android.intent.action.BOOT_COMPLETED
```

## 装机（只做一次）

```bash
# 1. 装 APK
adb install -r -g tv-adb-keeper-debug.apk

# 2. 授予写系统设置的权限（这一步是唯一的手动动作）
adb shell pm grant com.shijie.adbkeeper android.permission.WRITE_SECURE_SETTINGS

# 3. 打开一次 App，它会把所有自愈入口都登记好
adb shell am start -n com.shijie.adbkeeper/.MainActivity

# 4. 可选：加电池白名单，降低被省电策略杀掉的概率
adb shell cmd deviceidle whitelist +com.shijie.adbkeeper
```

之后重启不需要再做任何事。

## 网页触控板怎么用

1. 电视和手机/电脑连**同一个 WiFi**。
2. 浏览器打开 `http://<电视IP>:8848/`（电视上 App 首页会直接显示这个地址）。
3. 画布上会实时显示电视画面（每 1.4 秒刷一帧）。点 = 点电视；按住拖动 = 滑动；按住不动 550ms 以上 = 长按。
4. 右上「画面：开/关」可以暂停同步（省流量、省电视 CPU）；「拍一张」立刻抓一帧。
5. 下面有 主页 / 返回 / 最近 / 通知 四个键，以及电视上所有可启动应用的按钮。

## 网页端口挂了怎么自救（不需要 adb）

电视桌面（`com.gathin.tvhome`）第一行第三个卡片就是 **「ADB 常开」**（绿色安卓图标），用遥控器按方向键选到它、
按确认键打开即可。打开后 App 会自动把网页端口和点击注入通道都拉起来，屏幕上会显示地址。

> 这个入口是 **v5.0 才有的**。v4 及更早只声明了 `CATEGORY_LAUNCHER`，而 MiTV 桌面的卡片区和「常用」列表
> 只列带 `CATEGORY_LEANBACK_LAUNCHER` 的应用 —— 结果是 App 装上了但电视上完全找不到入口，进程被系统停掉后
> 只能靠 adb 拉起来，而 adb 又被厂商关，形成死锁。**装 v5.0 之前请确认这一条**：
>
> ```
> adb shell "cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER --brief"
> ```
>
> 输出里没有 `com.shijie.adbkeeper` 就说明是旧版。

接口（也可以自己写脚本调）：

| 方法 | 路径 | 参数 | 作用 |
|---|---|---|---|
| GET | `/` | — | 触控板页面 |
| GET | `/status` | — | 在线状态、端口、注入通道、ADB 开关 |
| GET | `/apps` | — | JSON 应用列表 `[{label,pkg,act}]` |
| GET | `/frame.jpg` | — | 当前电视画面（JPEG 1280x720），未就绪回 503 |
| GET | `/screen` | — | 画面通道探测文本 |
| POST | `/tap` | `x`,`y` | 点击（电视像素坐标 1920x1080） |
| POST | `/long` | `x`,`y`,`ms` | 长按（400~6000ms） |
| POST | `/swipe` | `x1`,`y1`,`x2`,`y2`,`ms` | 滑动（80~4000ms） |
| POST | `/key` | `k`=home\|back\|recents\|notifications\|power | 全局按键 |
| POST | `/open` | `pkg`,`act`（可选） | 启动应用 |

```bash
curl -X POST -d "x=936&y=441" http://192.168.5.101:8848/tap
```

端口从 8848 起试，被占用就自动往后挪到 8849 / 8850 / 8851。

## 界面

App 首页实时显示 5 项状态（每 2 秒刷新）：

- ADB 写入权限：已授予 / 未授予
- 当前 ADB 开关：开 / 关
- 开机自愈入口：已注册 / 未注册
- 点击注入通道：已连通 / 未连通
- 网页遥控：地址 + 端口

按钮：立即检查并开启一次 / 启动后台守护 / 加入电池白名单 /
注册开机自愈入口 / 启动网页遥控端口 / 打开开发者选项页。
没授权时首页会直接把 `pm grant` 命令打在屏幕上。

## 构建

`.github/workflows/build.yml` 用 GitHub Actions 构建（ubuntu + JDK 17 + Android SDK 33），
产物在 Actions 的 Artifacts 里，名字 `tv-adb-keeper-apk`。不需要本地装 Android SDK。

签名用的是仓库里固定的 `keystore/debug.jks`（口令 `android`，别名 `androiddebugkey`）。
**不要删它** —— CI 默认生成的 debug keystore 每次都不一样，换了签名就只能卸载重装，
而卸载会丢掉 `pm grant` 给的权限，等于自愈功能失效。

## 兼容性

- minSdk 21 / targetSdk 28 / compileSdk 33
- 纯 Java，无 AndroidX、无第三方依赖、无 native 库 → 任何 ABI 都能装
- 已在 MiTV-ANSP0（Android 9 / SDK 28 / armeabi-v7a / 2GB RAM）上验证：
  连续 3 次重启，每次都在厂商写 0 后 2 秒内抢回，网页端口 8848 全程可用
- 点击注入要求 Android 7.0+（`dispatchGesture` 是 API 24）
- v5.0 起同时声明 `CATEGORY_LAUNCHER` 与 `CATEGORY_LEANBACK_LAUNCHER`，所以在电视桌面和手机上都看得到入口

## 已知限制

- **只能在「厂商写 0 之后」抢回来，不是真正阻止厂商写。** 实测窗口期约 2 秒，
  这 2 秒内 adb 连不上。
- **进程被系统停掉后不会自己回来**：三条腿只能保证开机时被拉起。运行中被省电策略、
  厂商清理或 `am force-stop` 杀掉后，要人去电视桌面点一次「ADB 常开」（v5.0 提供了这个入口）。
  加固办法：`adb shell cmd deviceidle whitelist +com.shijie.adbkeeper`（重启后需重加）。
- 无障碍服务是唯一的注入通道。如果有人在设置里把它关掉、或者把 App
  force-stop / 卸载重装，注入会失效，得重新 `pm grant` + 打开一次 App。
- Android 10+ 的后台启动 Activity 限制更严，`/open` 在新系统上可能被拦；
  本项目只针对 Android 9 电视验证过。
- **有些 App 截不出来，会显示黑屏**：凡是自己设了 FLAG_SECURE 的窗口（部分正版视频 App、银行类），系统层面就禁止录屏，MediaProjection 拿到的就是黑帧，这是系统保护，App 无法绕过。实测本机酷狗/桌面/设置/B站/腾讯视频都能正常出画面。
- 没有登录鉴权：同局域网内谁都能访问 8848。家用它够了，公共 WiFi 下别开。

## License

MIT
