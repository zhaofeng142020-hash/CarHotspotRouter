# CarHotspotRouter

一个 **LSPosed (Xposed) 模块**：解决「手机连车机热点做互联后，因为没有网络，所有 App 都断网」的问题。

v2.0 起提供**设置界面**，可以自己勾选哪个 WiFi 是车机互联热点、勾选哪些 App 是车机互联 App，并能**从桌面隐藏图标**。

```
                 +---------------------------+
  其它 App ----->|  默认网络 = 手机流量       |----> 互联网 (4G/5G)
                 +---------------------------+
  车机 App ----->|  bindProcessToNetwork     |----> 车机 WiFi 局域网
                 +---------------------------+
```

## 功能

- **选择车机热点**：设置里列出已保存/周围的 WiFi，勾选哪个 WiFi 只做车机互联；也可「把当前连接的 WiFi 一键设为车机热点」或手动输入 SSID。支持多选。
- **应用依然走流量**：被选中的 WiFi 连上后，模块在 `system_server` 里剥掉它的 `NET_CAPABILITY_INTERNET`，系统继续把蜂窝当默认网络，微信/浏览器等照常上网。
- **车机 App 仍连车机**：被勾选的车机互联 App 单独 `bindProcessToNetwork` 回该 WiFi 访问车机局域网。
- **总开关**：一键启用/停用分流。
- **隐藏桌面图标**：`PackageManager.setComponentEnabledSetting` 禁用 launcher 入口，模块照常生效；仍可从 LSPosed 管理器或 `am start -n com.eta.carrouter/.SettingsActivity` 打开设置。
- **动态生效**：配置存 `Settings.Global`，hook 每次网络事件都重读，改完选择无需重启（新增车机 App 首次需在 LSPosed 作用域内，界面会尝试用 root 自动加）。

## 原理

1. `system_server` 里 hook `ConnectivityService`：当当前 WiFi SSID 命中用户选中的车机热点集合，就把该 WiFi 的 `NetworkCapabilities` 的 `NET_CAPABILITY_INTERNET` / `VALIDATED` / `CAPTIVE_PORTAL` 移除并触发网络重算。WiFi 不再宣称有网，Android 保持蜂窝为默认网络。离开热点自动恢复。
2. 车机 App 进程 hook `Application.onCreate`，注册「只接受无 INTERNET 的 WiFi」的 NetworkCallback 并 bind，让它仍能访问车机 LAN。
3. 配置通过 `Settings.Global` 在设置界面（app 进程，需 `WRITE_SECURE_SETTINGS`）与 hook（system_server，无需权限）间共享。

## 编译

需要 JDK 11+、Android SDK (compileSdk 33)。Xposed API jar 已放在 `app/libs`（compileOnly，不打包）。

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

推送到 GitHub 后，`.github/workflows/build.yml` 会用 Actions 自动编译并上传 APK artifact。

## 安装与授权

1. 安装 APK。
2. **授予写入权限**（让设置界面能保存配置），root 执行一次：
   ```
   su -c 'pm grant com.eta.carrouter android.permission.WRITE_SECURE_SETTINGS'
   ```
   模块运行时也会在设置里提示；没有这个权限仍能显示界面、但保存可能失败。
3. LSPosed 管理器里启用模块，作用域至少含 **System Framework (`android`)**；车机互联 App 也勾选（界面保存时会尝试用 root 自动写入 LSPosed 作用域数据库）。
4. 重启（至少软重启 system）让 hook 注入。

## 使用

打开「CarHotspotRouter」设置：
- 勾选（或「把当前连接的 WiFi 设为车机热点」）→ 选中的 WiFi 即刻生效
- 勾选车机互联 App（如 CarLife+ `com.baidu.carlife.xiaomi`、`com.mcarplay.app`）
- 打开总开关，点「保存并应用」
- 需要时打开「从桌面隐藏图标」

## 验证

```
dumpsys connectivity | grep -iE "Active default|NET_CAPABILITY_INTERNET" | head -40
```
连上车机热点后，默认网络应是 MOBILE，该 WiFi 不再有 `NET_CAPABILITY_INTERNET`；其它 App 能上网，车机 App 能连车机。

## 隐藏图标后如何再打开设置

- LSPosed 管理器 → 模块 → CarHotspotRouter（模块设置入口）
- 终端：`am start -n com.eta.carrouter/.SettingsActivity`
- 若需恢复图标：`su -c 'pm enable com.eta.carrouter/.Launcher'` 或在设置里关闭开关

## 兼容性与边界

- 车机 App 被整进程绑定到 WiFi，因此该 App 自身只能访问车机局域网、不能上公网；若其登录/在线导航也要公网，需要更细的分流（VpnService per-app 或 `ip rule uidrange`）。
- `ConnectivityService` 内部是反射访问，按字段类型定位 `NetworkAgentInfo` 以适配不同 AOSP 版本，大版本升级后建议重测。
- 系统层面还可叠加：`settings put global network_avoid_bad_wifi 1` 与 `settings put global mobile_data_always_on 1` 作为兜底。

## License

MIT
