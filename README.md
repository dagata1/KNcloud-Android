# KNcloud for Android

KNcloud is a modern and powerful proxy client for Android, supporting [Xray core](https://github.com/XTLS/Xray-core) and [v2fly core](https://github.com/v2fly/v2ray-core).

[![API](https://img.shields.io/badge/API-21%2B-yellow.svg?style=flat)](https://developer.android.com/about/versions/lollipop)
[![Kotlin Version](https://img.shields.io/badge/Kotlin-2.3.0-blue.svg)](https://kotlinlang.org)
[![GitHub Releases](https://img.shields.io/github/downloads/dagata1/KNcloud-Android/latest/total?logo=github)](https://github.com/dagata1/KNcloud-Android/releases)

---

## Features

- **Modern Dual-Mode UI**: Seamlessly switch between Simple (Dashboard) Mode and Classic (List) Mode.
- **Account & Subscription Management**: Direct integration with KNcloud web accounts, subscription sync, traffic usage tracking, and one-click authorization.
- **Core Support**: Built on high-performance Xray/v2ray cores with full protocol support (VLESS, VMess, Trojan, Shadowsocks, Hysteria2, WireGuard, TUIC, etc.).
- **Smart Routing & Bypass**: Custom routing rules, GeoIP/GeoSite rule sets, per-app proxying, and fragment packet fragmentation.
- **Speed & Latency Testing**: One-click real ping delay test and speed testing.

---

## Usage

### GeoIP and GeoSite
- `geoip.dat` and `geosite.dat` rule files are stored in `Android/data/top.kncloud.com/files/assets` (or application assets path).
- Enhanced geo rules can be automatically updated or manually imported from custom URLs.

---

## Development Guide

The Android project under `KNcloud` directory can be compiled directly in Android Studio or using Gradle wrapper:

```bash
cd KNcloud
./gradlew assembleDebug
```

KNcloud can run on Android Emulators or WSA (Windows Subsystem for Android). For WSA, grant VPN permissions via:
```bash
appops set [package name] ACTIVATE_VPN allow
```
