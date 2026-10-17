# TorNet

[Русский](README.md) · **English**

**Tor for Android with automatic IP rotation, censorship-circumvention bridges and a VPN mode.**
A mobile take on [tornet](https://github.com/ByteBreach/tornet): Tor runs right inside the app —
no Orbot, no root.

**[⬇ Download the latest release](https://github.com/nekohash9/tornet-vpn/releases/latest)**

> Which file to pick: `…-arm64-v8a.apk` for almost every modern phone,
> `…-universal.apk` if you are not sure. Android 7.0 or newer.

> The app is in English and Russian: it follows the system language, or pick one with the
> **System / English / Русский** switch at the top of the screen.

## Features

- **IP change** — a "New IP" button in the app and in the notification. Optionally on a timer:
  fixed or random interval, a set number of times or forever (off by default: frequent changes
  break sessions and trigger captchas).
- **Priority region** — exit IP only from the countries you pick (e.g. Germany and the Netherlands).
  The current exit country and flag are shown next to the IP.
- **VPN mode** — all phone traffic goes through Tor, DNS included. No per-app proxy setup.
  Android's "Always-on VPN" is supported.
- **Choose apps (split tunneling)** — all apps through Tor, only selected ones, or all except
  selected. Handy for excluding banks and services that block Tor (ChatGPT, Gemini…).
- **Resilience** — reconnects after switching Wi-Fi ↔ mobile data and after sleep, recovers after
  a crash, works with Android "Always-on VPN" and "Block connections without VPN".
- **Bridges against blocking** — if connecting gets stuck at 5–10%:
  - **Auto** tries methods one after another until one works, and remembers the working one;
  - obfs4, Snowflake, dnstt (over DNS-over-HTTPS), meek, or your own bridges;
  - fetch fresh bridges for your country from the Tor Project (Moat) — works even when the Tor
    website is blocked.
- **Telegram in one tap** — opens Telegram with the proxy already filled in.
- **Share the proxy over Wi-Fi and hotspot** — a computer or another phone on the same network can
  use this phone's Tor (a `t.me/socks` link for Telegram Desktop). The proxy opens only on
  Wi-Fi/hotspot and only for local addresses.
- **Local proxies** SOCKS5 `127.0.0.1:9050` and HTTP `127.0.0.1:8118` for any app.
- Event log: connection progress, every IP change, errors.
- English and Russian interface with an in-app language switch (Android 13+ also lets you pick it in
  the system app settings).

## How to use

1. Install the APK (allow installing from unknown sources).
2. Tap **Start**. The first start can take a couple of minutes.
3. For all traffic, turn on **VPN mode** and allow the VPN in the system dialog.
4. Can't connect? Keep **Auto** under "Connecting to Tor", or paste bridges from the Telegram bot
   [@GetBridgesBot](https://t.me/GetBridgesBot).

## Good to know

- **Tor in VPN mode ≠ Tor Browser.** Your IP is hidden, but a regular browser still identifies you by
  cookies, accounts and device fingerprint. Use Tor Browser for anonymous browsing.
- **UDP does not work over Tor:** messenger calls, some games, QUIC. Most apps fall back to TCP
  on their own.
- Some websites and banks block logins from the Tor network.
- An IP change applies to **new** connections: connections already open keep using the old circuit.

## Privacy

No analytics, ads or trackers. The app connects only to:

- the Tor network (directly or through the selected bridges);
- `check.torproject.org` and `api.ipify.org` — **over Tor**, to show your current exit IP;
- Moat (the Tor Project's bridge API) — only when requesting bridges, through a meek/dnstt tunnel.

## Permissions

| Permission | Why |
| --- | --- |
| Internet | connecting to Tor |
| VPN (`BIND_VPN_SERVICE`) | VPN mode — asked separately, only if you turn it on |
| Notifications, foreground service | Tor keeps running when the app is in the background; a notification with the IP and buttons |

## Building from source

Requires JDK 17, Android SDK and NDK.

```sh
git clone --recursive https://github.com/nekohash9/tornet-vpn
cd tornet-vpn
python3 tools/fetch_geoip.py   # GeoIP for exit-country selection (optional)
./gradlew assembleRelease
# app/build/outputs/apk/release/
```

Without the `TORNET_KEYSTORE*` environment variables the APK is signed with the debug key —
fine for personal use.

## Publishing a new version

Releases are built by GitHub Actions and appear on the Releases page by themselves:

```sh
git tag v1.4.0
git push origin v1.4.0
```

Or without the command line: **Actions → Release → Run workflow**, enter the version (`1.4.0`).

Before the first release add repository secrets (Settings → Secrets and variables → Actions):
`TORNET_KEYSTORE_BASE64` (the keystore in base64), `TORNET_KEYSTORE_PASSWORD`, `TORNET_KEY_ALIAS`,
`TORNET_KEY_PASSWORD`. The key must always stay the same, otherwise updates won't install over
the previous version.

## How it works

- `TorNetService` — foreground service: embedded Tor ([tor-android](https://github.com/guardianproject/tor-android)),
  picking a connection method, IP rotation (`SIGNAL NEWNYM`), checking the exit IP over Tor's HTTP tunnel.
- `PluggableTransports`, `Bridges`, `Moat` — bridges via [IPtProxy](https://github.com/tladesignz/IPtProxy)
  (lyrebird, Snowflake, dnstt) and fetching bridges from the Tor Project.
- `TorVpnService` — `VpnService` + [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
  (tun2socks, a git submodule); DNS is resolved on the Tor side via mapdns.
- `MainActivity` — the UI.

## Licenses

App code — [MIT](LICENSE). Components: Tor (BSD-3-Clause), tor-android and jtorctl (BSD-3-Clause),
IPtProxy (MIT) with lyrebird and Snowflake (BSD), hev-socks5-tunnel (MIT).
