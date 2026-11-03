# MiAuto 0.1.3 — 2026-11-03

## The negotiated canvas was wrong on any panel that is not 1920x720

`MainActivity` reported the panel as *long side first, always*, and `IccoaManager` fell back to a
hardcoded `1920x720`. The canvas is fixed at `init()` time and no API changes it afterwards, so a
portrait head unit or a tablet negotiated a landscape canvas: the picture is stretched, and because
UIBC touches are mapped into the *actual* Surface bounds, every tap lands somewhere else.

- The canvas now comes from the real display at runtime (`DeviceDisplay`: window bounds on API 30+,
  `getRealMetrics()` below that, `Resources` as a last resort) and is never invented. When it cannot
  be measured the app waits 400 ms and retries up to six times, because a guessed canvas is silently
  baked into the negotiation and nothing corrects it afterwards.
- Its ordering follows the screen-orientation setting (`ScreenSizePolicy`): portrait reports short
  side first, landscape long side first, SYSTEM/AUTO pass the window through as-is.
- If the measured canvas no longer matches the one baked into `UCarConfig` — rotation, a display
  change, a different panel — the SDK is now torn down and re-initialised before reconnecting.
  Previously the stale config was re-advertised forever.

## Re-advertising no longer hammers a radio that is still tearing down

After an abnormal disconnect the phone's Wi-Fi P2P group is still being recycled, but the car
re-advertised on a fixed 1.2 s and collected `10003` / `10006` / `10008` back to back — each attempt
pushing the shared radio further out of shape. Consecutive wireless bring-up failures now back off
exponentially (`1.2 s → 2.4 s → 4.8 s`, capped at 8 s). The streak resets on connect, on WFD ready,
and on any non-wireless error (`ReconnectBackoff`).

## Credit

Both changes are adapted from [guo-zhc/MiAuto](https://gitee.com/guo-zhc/MiAuto) (AGPL-3.0), an
independent fork of this project's 0.1.1 that had already solved them and wrote down the reasoning.

## Verified / not verified

Verified: **723 unit tests pass, 15 of them new** (`ScreenSizePolicyTest`, `ReconnectBackoffTest`)
pinning the ordering, the re-negotiation decision and every backoff step. The release APK builds and
keeps the signing key of 0.1.0–0.1.2, so it upgrades in place.

Not verified: a real head unit. The test device's wireless ADB was closed while this was built, so
none of it was exercised on hardware — including the `deInit()` + re-init path, which is the one
change here with a failure mode worth watching. If reconnects start failing after a rotation, that
is the first place to look.

# MiAuto 0.1.2 — 2026-11-03


This release is about one thing: **a phone that connects now actually reaches the projection
screen.** Everything below was traced to code, not guessed from symptoms.

- **Fixed: the car threw away every successful connection.** `onConnectStateChanged` carries
  `(deviceID, state, parameter)`. 0.1.1 read the third argument as the state, so every callback —
  including "connected" — mapped to *advertising*. The projection screen therefore never opened,
  and the car kept re-advertising under a session that was already up. The state codes are now
  taken from the SDK itself (`1` disconnected, `2` device connected, `3` failed, `4` projection
  channel ready, `5` channel stopped, `6`/`7` phone-side foreground switches).
- **Fixed: the car claimed a hotspot capability it can never have.** A sideloaded APK cannot get
  `TETHER_PRIVILEGED`, so SoftAP can never start — but advertising it made phones pick that path,
  and negotiation died at 10%. The car now advertises Wi-Fi Direct only.
- **Fixed: the pairing-code watchdog interrupted live sessions.** It judged "session active" from
  the wrong argument, so after 30 seconds it re-advertised mid-pairing and replaced the code the
  driver was typing. States 2/4/5 now count as an active session.
- **Fixed: the picture never came back after leaving and re-entering projection.** The cast flag was
  never reset, so a rebuilt Surface was never handed to the SDK, which kept decoding into an
  abandoned buffer queue (`-19`) — black screen with sound. The old Surface is now paused first.
- **Added: touch back-channel.** Taps and swipes on the car screen are now forwarded to the phone
  over ICCOA's UIBC channel, with the raw action and per-pointer ids the SDK expects.
- **Fixed: 32-bit and x86 head units crashed on launch** with `UnsatisfiedLinkError: libusbio.so`.
  Those ABIs have no protocol libraries at all, so the app now checks before touching the SDK and
  says so on the home screen — with the CPU architecture — instead of dying.
- The home screen shows the SDK error code when a connection fails, and the car identity no longer
  uses the `02:00:00:00:00:00` Bluetooth placeholder.

Verified here: builds and 708 unit tests pass, including 9 new tests pinning the state mapping and
the native-library check; the release APK installs and launches. **Not verified here:** a real
phone pairing end to end — no phone was available while building this, so the mapping is proven
against the SDK's own callbacks, not against a live session.


# MiAuto 0.1.1 — 2026-10-13

- Wording only; behaviour is identical to 0.1.0. ICCOA CarLink is a standard jointly initiated by Xiaomi, OPPO and vivo, so the home screen, the About and Support screens, the legal notice and the documentation now name Xiaomi CarWith, OPPO Car+ and vivo Jovi InCar together instead of Xiaomi alone.
- The legal notice on the home screen now also explains what ICCOA CarLink is: a jointly initiated in-car connectivity standard whose phone-side front ends are CarWith, Car+ and Jovi InCar.
- Added Traditional Chinese home-screen strings, which did not exist before, so a zh-TW device used to show the English ones.

# MiAuto 0.1.0 — 2026-10-13

- First MiAuto release: a head unit app that brings Xiaomi CarWith, OPPO Car+ and vivo Jovi InCar to the car, built on DiAuto Pro and Open Headunit.
- The phone protocol is ICCOA CarLink instead of Android Auto. The car pairs with a phone over Bluetooth and Wi-Fi (Wi-Fi Direct or a local hotspot), or over USB.
- Installs as its own package (com.miauto.app), so it can sit side by side with DiAuto on the same head unit.
- MiAuto branding throughout: a glass-style MA launcher icon and splash screen, its own app name, and its own About and Support screens.
- Car-friendly home screen showing the CarLink pairing code, with connection, diagnostics, auto-connect, display and performance, and permission settings.
- Diagnostic report export, automatic reconnect, and the inherited DiAuto and Open Headunit UI and theme.
- All inherited translations are available, including Simplified and Traditional Chinese.

Other head units and sustained performance are not guaranteed.
