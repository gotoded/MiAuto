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
