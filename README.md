# CarHotspotRouter

An **LSPosed (Xposed) module** that solves a specific pain: when your phone connects
to the **car head-unit Wi-Fi hotspot** for screen-mirroring / CarLife / Android Auto,
the hotspot has no internet, so Android makes it the default network and **every other
app loses connectivity** even though mobile data is available.

This module keeps **cellular data as the default network** for the whole system while
the car hotspot is connected, and binds **only the car-link app** back to that Wi-Fi so
it can still reach the head-unit LAN.

```
                 +---------------------------+
  apps --------->|  default = CELLULAR       |----> internet (4G/5G)
                 +---------------------------+
  car-link app ->|  bindProcessToNetwork     |----> car Wi-Fi LAN (no internet)
                 +---------------------------+
```

## How it works

1. **In `system_server`** it hooks `ConnectivityService`. When the currently connected
   Wi-Fi SSID equals the configured car hotspot SSID, it strips
   `NET_CAPABILITY_INTERNET` (and `VALIDATED` / `CAPTIVE_PORTAL`) from that Wi-Fi
   network's `NetworkCapabilities` and triggers a network re-match. Because the Wi-Fi
   no longer claims internet, Android keeps the cellular network as the default and
   normal apps keep working. Leaving the hotspot restores the capability automatically.
2. **In the car-link app process** it hooks `Application.onCreate` and calls
   `ConnectivityManager.bindProcessToNetwork()` on the internet-less Wi-Fi network, so
   that app alone still talks to the head unit over the local LAN.

SSID matching is exact, so home/office Wi-Fi is unaffected.

## Build

Requirements: JDK 11+, Android SDK (compileSdk 33), Gradle wrapper included, and
LSPosed on the device. The Xposed API jar is vendored in `app/libs` (compile-only,
never bundled).

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Or just push this repo to GitHub — the included GitHub Action builds the APK
automatically on every push and uploads it as an artifact.

## Install

1. Install the APK and enable the module in **LSPosed Manager**.
2. Scope must include **System Framework (`android`)** and your car-link app package.
   The default scope list is in `app/src/main/res/values/arrays.xml`.
3. Reboot (at least soft-reboot system).

## Configure

Set the target SSID and car-link packages. `persist.*` props survive reboot and are
readable from `system_server` and app processes:

```bash
su -c 'setprop persist.carrouter.ssid "MyCar_AP"'
su -c 'setprop persist.carrouter.apps "com.baidu.carlife,com.xxx.carlink"'
```

Wrap the whole value in quotes when the SSID contains spaces. On ROMs where hidden-API
restrictions block `SystemProperties.get`, edit `DEFAULT_SSID` / `DEFAULT_APPS` in
`MainHook.java` and rebuild.

If the car-link app is not one of the defaults, also add it to `arrays.xml` scope and
to `persist.carrouter.apps`.

## Verify

```bash
dumpsys connectivity | grep -iE "Active default|Default network|NET_CAPABILITY_INTERNET" | head -40
dumpsys wifi | grep -i "SSID"
```

After connecting the car hotspot, the active default network should be MOBILE, and
the car Wi-Fi capabilities should no longer contain `NET_CAPABILITY_INTERNET`.
Browser / WeChat etc. still reach the internet via mobile data, while the car-link
app still connects to the head unit.

## Optional system-level fallback

These two settings often fix the same symptom without any module (try first):

```
su -c 'settings put global network_avoid_bad_wifi 1'
su -c 'settings put global mobile_data_always_on 1'
```

## Limitations

- The car-link app is bound to Wi-Fi for its **whole process**, so that app itself
  cannot use the public internet (only the car LAN). If its login / online navigation
  also needs internet, you need per-app split routing (VpnService per-app or
  `ip rule uidrange ... lookup <wifi_table>` to point only that UID's public traffic
  back to the cellular table).
- Reflective access to `ConnectivityService` internals is version-sensitive; the
  helpers locate `NetworkAgentInfo` by field type rather than hard-coded names to
  survive AOSP changes, but major Android releases may need re-testing.
- This is a local hobby module for your own device. Test on your ROM before relying
  on it for daily driving.

## License

MIT
