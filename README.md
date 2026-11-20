# TG Flow19

**TG Flow19** is a fork of [crim50n/tg-ws-proxy-android](https://github.com/crim50n/tg-ws-proxy-android)
(TG WS Proxy, MIT), which is based on [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) (MIT).
It is not affiliated with crim50n, Flowseal or Telegram.

## Download

- **[Latest release](https://github.com/megaflow19/tg-flow19/releases/latest)**: one universal APK
  for Android 8.0+ (arm64-v8a, armeabi-v7a, x86, x86_64), with a `.sha256` checksum.
- **[Obtainium](https://github.com/ImranR98/Obtainium):** add `https://github.com/megaflow19/tg-flow19`
  to get updates automatically.
- Release APKs are signed with this certificate (SHA-256):
  `59:ED:87:B6:4A:D0:C7:DE:03:D3:82:B0:D9:59:4D:52:E5:15:BD:AB:67:7B:59:A7:F6:35:90:CF:8E:30:D3:F3`

## First start

1. Open the app and follow the **first-time setup**: notifications, battery optimization, the phone
   maker's app launch settings and connecting Telegram (also in Settings → Support).
2. Delete old entries of this proxy in Telegram, then fully close Telegram (swipe it away in your
   recent apps) and open it again.
3. On networks that block the usual Cloudflare addresses, the first connection can take up to about
   30 seconds while the app finds another route; then it is remembered for that network.

## Changes from TG WS Proxy

- **Cloudflare TLS mode Auto** (Settings → TLS for Cloudflare): when a network drops the TLS
  handshake by domain name, the app retries with the TLS ClientHello split into two records and two
  TCP segments; when the stream then freezes, it falls back to port 80. The working step is
  remembered per network and plain TLS is rechecked hourly. On and Off modes are still available.
- **Alternate Cloudflare addresses:** when the usual Cloudflare address for the proxy domains does
  not respond, the app finds another Cloudflare address that works and remembers it per network.
- **Cloudflare proxy route:** TLS SNI is the bare base domain, and `kwsN.<domain>` goes only in the
  HTTP `Host` header. Fixes networks whose DPI drops handshakes with `kws*` SNI.
- **First-time setup** wizard and a status line message while another route is searched.
- **Crash fix:** no more `ConcurrentModificationException` when the network changes.
- **Frozen direct media:** when direct WebSocket media freezes on a network, the app remembers it for
  that network and sends media through Cloudflare instead of hanging again after every restart.
- **Background reliability:** guards against vendor power managers (Honor, Huawei, Xiaomi and others)
  that freeze the proxy in the background: a battery-optimization prompt, a shortcut to the phone
  maker's app launch settings, and freeze detection in diagnostics.
- **Own app ID** `io.github.megaflow19.tgflow19`, so it installs alongside the original.

Release notes: [`release-notes/`](release-notes/) (crim50n's original notes are in
[`release-notes/upstream/`](release-notes/upstream/)).

> [!NOTE]
> Everything below this line is the original TG WS Proxy README by crim50n, kept for its
> description of the app. The cover illustration and screenshots are crim50n's (TG WS Proxy v2.0.0,
> MIT) and show the original app. Download and issue links have been changed to point to TG Flow19.

---

<p align="center">
  <img src="screenshots/2.0.0/cover.jpg" width="100%" alt="An illustrated terrace with a visual connection from Android through WebSocket to Telegram">
</p>

<h1 align="center">TG WS Proxy</h1>

<p align="center">
  <strong>A local bridge to Telegram, right on your Android.</strong><br>
  MTProto over WebSocket, with Cloudflare and direct TCP fallback.<br>
  No root. No separate server to deploy. No ads or analytics.
</p>

## Simple to Use. Flexible When Needed.

- **Adaptive routing.** Automatic route selection, with manual WebSocket, Cloudflare,
  and TCP controls when you need them.
- **Additional transports.** HTTP/2 for Cloudflare media connections, custom
  Cloudflare Workers, and optional Fake TLS for the local MTProto listener.
- **Quiet defaults.** Extra route checks, prepared connections, and CPU wake lock
  are off by default. Background controls remain available.
- **Clear status.** Local service and Telegram connectivity are shown separately,
  alongside optional traffic statistics and diagnostic logs.
- **Native Android interface.** Notification controls, a Quick Settings tile,
  light and dark themes, Material You, and English / Russian localization.

## Screenshots

<table>
  <tr>
    <th width="33%">Connect</th>
    <th width="33%">Monitor</th>
    <th width="33%">Configure</th>
  </tr>
  <tr>
    <td valign="top"><a href="screenshots/2.0.0/main-dark.jpg"><img src="screenshots/2.0.0/main-dark.jpg" width="100%" alt="Dark theme with the local proxy running and Telegram connected"></a></td>
    <td valign="top"><a href="screenshots/2.0.0/statistics.jpg"><img src="screenshots/2.0.0/statistics.jpg" width="100%" alt="Connection status, transport counters, traffic, and errors"></a></td>
    <td valign="top"><a href="screenshots/2.0.0/settings.jpg"><img src="screenshots/2.0.0/settings.jpg" width="100%" alt="Local proxy, routing, and power settings"></a></td>
  </tr>
</table>

<details>
  <summary>Light theme, Help, and About</summary>
  <br>
  <p align="center">
    <a href="screenshots/2.0.0/main.jpg"><img src="screenshots/2.0.0/main.jpg" width="31%" alt="Light theme with local proxy status and Telegram connection controls"></a>
    &nbsp;
    <a href="screenshots/2.0.0/help.jpg"><img src="screenshots/2.0.0/help.jpg" width="31%" alt="Expandable help with setup instructions and troubleshooting"></a>
    &nbsp;
    <a href="screenshots/2.0.0/about.jpg"><img src="screenshots/2.0.0/about.jpg" width="31%" alt="About screen with update checks and transport limitations"></a>
  </p>
</details>

## Quick Start

1. [Install the latest APK](https://github.com/megaflow19/tg-flow19/releases/latest)
   on Android 8.0 or newer.
2. Open the app and tap **Connect**.
3. Tap **Open in Telegram**, then confirm and select the proxy.
4. Open a chat to check the connection.

The default listener is **`127.0.0.1:1443`**, accessible only on this device.
Settings save automatically; changed runtime settings take effect when you leave
Settings, restarting an active proxy if needed.

Advanced connection settings include multiple Cloudflare domains, Worker domains,
and an optional Fake TLS domain. After changing Fake TLS, open the updated proxy
link in Telegram. PROXY protocol and forced test-DC routing are intended for
specialized setups; leave them off for normal on-device use.

**Your last choice is remembered.** An enabled proxy attempts to resume after
device boot. Stopping it from the app, notification, or Quick Settings keeps it
off. Android force-stop and battery restrictions can prevent background startup.

## Updates and Support

Check for updates in **About**. TG Flow19 only checks for new releases; it never
downloads or installs APKs itself. Install updates with Obtainium or from the
release page.

For connection problems, start with the built-in **Help**. If the problem persists,
[open an issue](https://github.com/megaflow19/tg-flow19/issues) with your
Android version, network type, and steps to reproduce. Diagnostic reports are
available under **Settings → Support and diagnostics**; review them before sharing,
even though recognized sensitive data is redacted.

## Security

This is a Telegram proxy, **not a device-wide VPN or an anonymity guarantee**.
It runs locally but connects to remote endpoints. Telegram message encryption is
separate from the proxy transport.

Transport TLS certificate verification is intentionally disabled; do not treat
it as an authenticated TLS channel. Direct TCP fallback and optional port-80
Cloudflare connections add no TLS layer. Fake TLS disguises the local proxy
handshake; it is not an additional encryption layer.
Keep the default local address unless you intend to share access: `0.0.0.0`
listens on all IPv4 interfaces. Keep your proxy link and secret private.

## Development

<details>
<summary>Build from source</summary>

Use JDK 17 and the checked-in Gradle wrapper:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

APKs are written to `app/build/outputs/apk/debug/` and
`app/build/outputs/apk/release/`. Debug builds install separately as
`dev.minios.tgwsproxy.debug`.

Release signing requires the ignored `app/keystore/signing.properties` and its
keystore. Never commit signing files.

</details>

## Credits

Maintained by [crim50n](https://github.com/crim50n). Includes protocol adaptations
from [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy)
([MIT](app/src/main/assets/licenses/flowseal.txt)), developed independently.
Not an official Telegram product.

Icons by [Tabler](https://github.com/tabler/tabler-icons), Paweł Kuna and
contributors ([MIT](app/src/main/assets/licenses/tabler-icons.txt)).
Project licensed under [MIT](LICENSE).
