# Wi-Fi display desktop mode on umi

## Scope

The first implementation adds an opt-in, independent desktop to an existing Miracast
connection. The phone keeps its mobile home screen; the wireless display hosts secondary
Home, freeform application windows, and the existing WM Shell window decorations.
The follow-up implementation provides a foreground phone touchpad in Cast settings after a
desktop connection is established. It supports movement, clicking, tap-then-touch dragging,
two-finger scrolling, and external Back/Home controls. Bluetooth/USB mice and keyboards can
also be connected to the phone. Phone IME bridging and receiver-side UIBC input are not
implemented by this change. The follow-up touchpad's interaction remains unverified until
its build is deployed and a human checks the receiver.

## Starting device state (2026-10-03)

The connected umi was running LineageOS 23.2 with framework SDK 36. Wi-Fi display and
protected buffers were enabled in the resolved framework resources; the Qualcomm WFD
service was installed and Wi-Fi P2P was advertised. No Miracast session or receiver was
connected during implementation. The existing ROM did not declare the freeform feature.

The resolved desktop capability was enabled, while internal-display desktop hosting was
disabled. Runtime flags enabled desktop windowing, projected desktop mode, connected-display
cursor routing, display topology, and the default-display topology switch. Dynamic display
content-mode management was disabled. `shouldIncludeDefaultDisplayInTopology` was false.
These are baseline observations, not validation of the newly built implementation.

The temporary root adb daemon used for reading flags was returned to non-root. No desktop,
mirroring, developer-option, or topology settings were changed on the device.

## Selection and lifetime

Settings > Connected devices > Connection preferences > Cast contains **Wireless desktop
mode** on devices with `config_enableWifiDisplayDesktopMode=true` and the
`android.software.freeform_window_management` feature. The default is screen mirroring.
The choice is stored in `Settings.Global.WIFI_DISPLAY_DESKTOP_MODE`
(`wifi_display_desktop_mode`, 0=mirror, 1=desktop).

The preference is disabled while connecting or connected. Disconnect before changing
modes, then reconnect. The adapter reads the choice when the encoder Surface becomes
available; it never destroys/recreates a live display in response to a preference change.
This avoids changing immutable Display flags and moving active tasks mid-session.

### Phone touchpad

The touchpad is a separate, non-exported Settings activity. It reserves system/navigation
insets, leaves the phone touchscreen bound to display 0, and creates its virtual mouse off the
UI thread. `InputManager.createWifiDisplayMouse` is a hidden system-only entry point using
the existing `VirtualInputDeviceController` and native uinput mouse path. It requires
`INJECT_EVENTS`, system/root UID, and a public trusted Wifi desktop with own-group and decor
flags. The input port is associated with the display's unique ID, so losing the external
display does not redirect the virtual mouse to the phone. The native pointer renderer is
shared with real mice; no synthetic cursor overlay is added.

The activity closes the device on pause, disconnect, or stale asynchronous creation. The
existing controller also closes it when the client Binder dies. Back/Home and keyboard
events forwarded while the phone has focus carry the external display ID. Held mouse
buttons and forwarded keys are released on exit. A mouse/keyboard remains usable after
leaving the touchpad. Text entry currently requires a keyboard connected to the phone.

## Framework integration

- `WifiDisplayAdapter` keeps the existing WFD encoder Surface and transport. In desktop mode
  it adds `OWN_CONTENT_ONLY`, `SHOULD_SHOW_SYSTEM_DECORATIONS`, and `OWN_DISPLAY_GROUP`.
  The adapter already marks system-created Wi-Fi displays as `TRUSTED`.
- `Display.isWifiDisplayDesktopMode()` identifies only public, trusted Wi-Fi displays with
  own-group and decor flags. The corresponding `DisplayDeviceInfo` check additionally requires
  own-content-only, which is an internal flag and has no `Display`-level equivalent.
- `DisplayWindowSettings` selects freeform as the default windowing mode for these displays.
  Input methods use the external display locally unless an explicit per-display policy exists.
  `WindowManagerService` and WM Shell allow their desktop eligibility independently of the
  global dynamic display-content-mode feature, which is disabled in the current bp4a build.
- `DisplayGroupAllocator` puts these sessions in the projected/extended group according to
  the existing internal-display policy. Wi-Fi desktop groups can be adjacent to the phone's
  default group. Separate display-group ownership is explicit even without separate-timeout
  grouping.
- `RootWindowContainer` and `DisplayTopologyCoordinator` include the Wi-Fi desktop in the
  connected-display topology. On umi the phone cannot host desktops, so the existing projected
  mode topology policy excludes it and directs the mouse to the external desktop. Removing the
  external desktop adds the phone back. `InputManagerCallback` also selects the Wi-Fi desktop
  for releases using legacy cursor routing. Input devices explicitly associated with another
  display retain their association.
- The umi device configuration declares freeform support and enables the resource overlay.
  No global force-desktop developer setting is required.

The transport's negotiated secure/protected-buffer flags are preserved. No always-unlocked
display flag is added. Existing keyguard and desktop task policies remain responsible for
locking, authentication, visibility, and task migration on disconnect.

## Build

Use the workspace's full-ROM wrapper and existing controls:

```bash
JOBS=6 bash tools/build-umi.sh
```

### First implementation build result

The full ROM build completed successfully on 2026-10-03 at 18:47:10 CST with exit code 0.
The wrapper used six Ninja jobs and its existing Soong GC controls. The initial sandboxed
attempt could not mount the existing ccache directory; the successful run used the same
wrapper outside the sandbox with user approval.

- Log: `out/build-logs/build-umi-20261003-180753-1755302-rom.log`
- ROM: `out/target/product/umi/lineage-23.2-20261003-UNOFFICIAL-umi.zip`
- Package size: 1,596,397,924 bytes; generated at 18:47:06 CST.
- SHA-256: `d5a879e5f9d771ab76041813ced41b43b9d81e96797f461c3906227a18f93869`
- `sha256sum -c` passed. `git diff --check` passed in all three implementation projects.
- The package includes the freeform permission declaration, device framework overlay, and
  updated Settings APK. It was installed on umi serial `e86ac055` on 2026-10-03, and Android
  completed boot with `ro.lineage.version=23.2-20261003-UNOFFICIAL-umi`. Miracast end-to-end
  interaction, keyguard, input, disconnect recovery, and performance remain unverified.

The source test additions cover ordinary mirroring, missing trust/decor/own-content flags,
private and non-Wi-Fi displays, and the Settings empty state when only the mode switch is
present. Their presence does not mean the Android test suites have been built or run.

### Device installation and cleanup

The original Recovery rejected the package signature (error 21, install status 2). Host
whole-package signature verification passed. Temporarily booting this build's `recovery.img`
with fastboot and repeating ADB sideload retained signature verification and succeeded:
`whole-file signature verified against RSA key 1`, then `Install completed with status 0`.
No factory reset or data-format operation was performed.

After the first Android boot, an old overlayfs copy of `services.jar` and a Settings update
in `/data/app` still overrode ROM code. Both were backed up before cleanup. The Settings
update was removed with `pm uninstall -k com.android.settings`, retaining its data, and
`adb enable-verity` tore down overlayfs on the subsequent reboot. Its AVB warning means this
operation does not establish that AVB verification is enabled. Final checks confirmed:

- Android boot completion and the new Lineage version.
- No overlayfs or scratch mount, and matching device/build SHA-256 values for `services.jar`,
  `framework.jar`, Settings, and SystemUI.
- Active Settings path `/system_ext/priv-app/Settings/Settings.apk`.
- `config_enableWifiDisplayDesktopMode=true` and both freeform/secondary-display features.
- `wifi_display_desktop_mode` unset (default mirroring), global force-desktop option 0,
  `mDesktopModeEnabled=false`, and no active Wi-Fi display connection.
- ADB restored to ordinary shell UID 2000.

Evidence and backups:

- `out/build-logs/flash-umi-wifi-desktop-20261003-recovery-last_log.txt` (initial rejection)
- `out/build-logs/flash-umi-wifi-desktop-20261003-recovery-install-success.txt`
- `out/build-logs/flash-umi-wifi-desktop-20261003-verification.json`
- `out/build-logs/flash-umi-wifi-desktop-20261003-old-overlay.tar`
- `out/build-logs/flash-umi-wifi-desktop-20261003-old-settings-update.apk`

### Follow-up: gesture regression and active SystemUI (2026-10-04)

The user reported that an independent desktop appeared, hardware input could not control it,
and phone swipe-up Home/Overview stopped working even after disconnection. Quickstep recorded
the gestures and end targets but reported `Null mRecentTasks`; its SystemUiProxy had both
`mRecentTasks=null` and `mDesktopMode=null`.

The installation checks above compared the ROM's SystemUI APK, but missed its active
`/data/app` system update. That old update embedded resource ID `0x011101ca` for
`config_hasRecents`; in the new framework it is `config_hasPermanentDpad=false`, while the
correct new Recents ID is `0x011101cb`. Its desktop configuration IDs were similarly shifted.
This explains the absent Shell Recents and desktop interfaces without attributing it to
touchscreen remapping.

SystemUI is the protected keyguard package, so PackageManager rejected uninstalling its
updates. After backing up the APK, the framework was stopped and the old update directory was
moved from `/data/app` to `/data/local/tmp/umi-old-systemui-update-20261003`, followed by a
reboot. Application data was not cleared. The active package is now
`/system_ext/priv-app/SystemUI/SystemUI.apk`; Android boot completed, ADB runs as shell UID
2000, and Quickstep has non-null `IRecentTasks` and `IDesktopMode` interfaces. The user confirmed that
phone swipe-up Home/Overview recovered. The hardware mouse still could not operate the receiver.

Evidence: `out/build-logs/wifi-desktop-regression-20261003/`, including
`quickstep.txt`, `quickstep-after-systemui-restore.txt`, `systemui-package-before.txt`,
`resource-constant-references.json`, and `old-systemui-update.apk`.

For future deployment checks, inspect `pm path` for every changed system application, then
hash the **active** APK. Hashing only the system-partition copy misses stale `/data/app`
overrides even when overlayfs is absent.

### Follow-up: hardware mouse routing (2026-10-04)

After the SystemUI repair, live input diagnostics showed the paired Bluetooth mouse on display
0 with phone-sized motion ranges. The connected Wi-Fi desktop (display 3) existed, but the
native display topology contained only display 0. Connected-display input routing therefore
could not select the receiver; the legacy pointer-display callback alone is insufficient.

Inspection of the Oct 3 ROM's actual `services.jar` bytecode found that
`RootWindowContainer.setShouldShowSystemDecorationsForNewDisplay()` lacked the static Wi-Fi
fallback present in source. The topology coordinator's Wi-Fi eligibility addition and the
legacy pointer callback were already in that artifact, but the missing WM callback prevented
registration. This build started before those source edits, so its success did not establish
that every late edit entered the compiled artifacts.

The Oct 4 build includes the static Wi-Fi topology registration and eligibility changes,
along with the phone touchpad and scoped virtual mouse service. Inspect the resulting bytecode
before installation and check the active package paths after installation. The user requested
module replacement for non-flashing validation, so the new full ROM must not be installed.
Deploy the matching framework/services JARs, Settings APK, and their ART artifacts via overlayfs.
Physical mouse, keyboard, and touchpad behavior remains pending acceptance after module deployment.

Evidence: `display-mouse.txt`, `input-mouse.txt`, `window-mouse.txt`, `activity-mouse.txt`, and
`rom-services-topology-bytecode-before.json` under the regression evidence directory above.

### Module deployment instead of flashing (2026-10-04)

The user explicitly requested replacing the affected libraries/APKs/other files instead of
installing the full ROM. This change needs no native library replacement. It changes
`framework.jar` (hidden input API), `services.jar` (input service and topology registration),
and `Settings.apk` (touchpad activity and Cast entry). The boot image extensions for both ARM
and ARM64 and the affected services/Settings ART artifacts must match the updated framework.
Use the product installation outputs, which include hidden API metadata, rather than the
raw intermediate framework DEX jar.

Original device files are backed up in
`out/build-logs/wifi-desktop-modules-20261004/device-before.tar`. Deployment uses `adb root`
and `adb remount` overlayfs, stages only files whose hashes differ from the backup, then stops
the Android framework while replacing the set and reboots. The OTA ZIP is not installed.
The module manifest records both original and replacement hashes. Check the active Settings
APK path, boot completion, Quickstep bindings, crash logs, and input topology after restart;
keep actual receiver interaction results separate from file/boot checks.

### Module deployment result

The full build wrapper finished successfully at 00:53:11 CST on 2026-10-04. Its OTA ZIP was
not installed. The matching module set replaced 42 changed files (about 190 MiB) using
overlayfs. All 49 boot classpath JARs were compared by DEX: only `framework.jar` changed;
`framework-res.apk` remained byte-identical to the device copy. Application data was retained.

The phone completed boot after replacement, all 42 deployed file hashes matched, and ADB
returned to shell UID 2000. Active Settings came from the updated system-partition path;
Quickstep's Recents and desktop interfaces were non-null and the sampled crash buffer was
empty. The version string remained the existing Oct 3 ROM version. The first post-reboot
snapshot had no casting session, so receiver input acceptance was requested separately.

Evidence and the original file backup:
`out/build-logs/wifi-desktop-modules-20261004/README.md` and adjacent manifests/logs.
The validation overlay remains installed; no Recovery, OTA installation, partition flashing,
or application-data clearing was performed for this module deployment.

### Refinement: transmission, phone Overview, and fullscreen (2026-10-04)

The user confirmed the independent desktop/input feature works after the first module
deployment, and reported video stutter/audio skew, missing app movement between displays,
and phone-sized fullscreen content. The follow-up uses
`JOBS=6 bash tools/build-umi.sh desktop`, targeting services, SystemUI/WM Shell,
Launcher3QuickStep, and the WFD configuration. It does not build/install an OTA.

Phone Overview retains tasks belonging to a connected trusted Wi-Fi desktop, including
minimized desktop groups. Each application's task menu offers **移到投屏桌面** or
**移回手机**. Quickstep sends an explicit destination through the appended Shell desktop
IPC; Shell validates display type and task permissions, reparents the running task or
restarts the same recent task on the destination. SystemUI and Quickstep must be deployed
together because this is a Shell IPC change, not a public framework API change.

For Wi-Fi desktop fullscreen and return-to-phone transitions, Shell clears stale app bounds,
screen-size overrides, and density overrides, inheriting the target display configuration.
WM defaults the independent Wi-Fi desktop to ignore phone-oriented activity size restrictions;
the phone's own display policy remains as before. Application-specific opt-outs still apply.

This device uses Qualcomm ExtendedRemoteDisplay and `/vendor/etc/wfdconfig.xml`, so AOSP
stagefright encoder settings do not configure its actual source. The umi device copy overrides
the shared vendor XML without modifying the original blob. The initial balanced profile caps
codec capabilities at 1920x1080, 30 fps, and video bitrate at 6 Mbps (minimum at most 3 Mbps).
The offline generator `tools/wifi-display/tune_wfd.py` also supplies a 1280x720/30 fps/4 Mbps
smooth profile and optional explicit audio-delay adjustment. The vendor's existing dynamic
bitrate adaptation and audio offset of -150 ms are retained until direction/magnitude of
the actual skew are measured. This config also affects ordinary Wi-Fi mirroring.

The adapter's advertised 60 Hz is hardcoded and does not prove negotiated encoder frame rate.
Inspect native WFD logs and actual receiver behavior after reconnecting. Resolution/bitrate
caps are configuration inputs, not measured transmission results. Compare the same video
scene with balanced and smooth profiles; record whether audio is ahead/behind and the delay.
Configuration changes require a fresh WFD session/native-service configuration load.

The first refinement build completed at 08:51:01 CST. Twelve changed files (about 172 MiB)
replace services and its ART outputs, SystemUI/Quickstep APKs and preopt outputs, and the
vendor XML. Framework/boot classpath and Settings need no replacement in this iteration.
Both auto-generated app overlays were compared and remained byte-identical. The device
originals are in `out/build-logs/wifi-desktop-refinement-20261004/device-before.tar` and
`/data/local/tmp/umi-wifi-desktop-refinement-before-20261004.tar`; the manifest records
original/replacement hashes. Keep manual acceptance distinct from build/file/boot checks.

Post-deployment checks found all twelve final hashes matching, ordinary ADB UID 2000,
non-null Quickstep Recents/Desktop bindings, and an empty sampled crash buffer. A later
Quickstep-only rebuild/replacement includes minimized wireless tasks without requiring the
exploded-view flag; only Launcher was restarted for that follow-up, and casting remained
connected. The product XML integration was compiled separately and its generated copy rule
uses the device XML, with an exact hash match to the installed balanced profile.

Live Qualcomm source statistics reported roughly 30 encoder fps and about 7 ms average
encoding time. Bilibili fullscreen activities on display 2 received 1920x1080 bounds at
320 dpi with no WM letterboxing. These observations do not measure receiver presentation
latency, actual bitrate, or audio/video skew. The phone-to-Wi-Fi task transition was logged;
visual acceptance and the return path await human confirmation. For XML-only A/B iterations,
`tools/wifi-display/switch_wfd.py` checks disconnection, backs up the XML, replaces it with
checksum checks, and reloads the running native WFD services; this helper has not yet been
used to switch the live profile.

Refinement acceptance on the receiver:

1. Reconnect wireless desktop after deployment. Open an app on the phone, swipe up and hold,
   tap its icon above the task thumbnail, and select **移到投屏桌面**.
2. Reopen phone Overview, find that external app (possibly inside its desktop group), tap
   its icon, and select **移回手机**. Confirm the same task/app state survived the round trip.
3. On the external desktop compare windowed and fullscreen layouts, then return to the phone.
   Record app name and any aspect-ratio/black-bar/cropping problem; inspect task/activity
   configuration and display IDs while the failure is visible.
4. Play the same video scene for at least 30 seconds, note stutter and audio skew, and compare
   the 720p profile if needed. Confirm phone Home/Overview gestures after disconnecting.

### Follow-up: periodic receiver-visible stutter (2026-10-04)

The user confirmed the other refinements work, but video and mouse transmission pause every
few seconds. A sixty-second sample found ~30 fps source encoding with maximum encode time
15.466 ms. Outgoing RTP header sampling found no gap over 100 ms (maximum ~26 ms). This
observation is before wireless delivery and does not rule out receiver or radio stalls.
The actual P2P group is 2412 MHz, while phone internet Wi-Fi is 5180 MHz; strong RSSI/high
PHY rate and differing bands alone do not identify the cause.

For a single-parameter comparison, only the live vendor XML's PeriodicIDRInterval changed
from 5 to 15 seconds. Bitrate, resolution and audio offset remain as before; native services
were reloaded and casting reconnected without a device reboot or module build. Candidate
encoding/packet sampling also remains steady, but receiver-visible acceptance and actual
keyframe cadence are not yet measured. This is an experiment, not a confirmed fix. Permanent
device XML source stays at the balanced baseline pending human comparison. Temporary native
diagnostic binaries and the Java control helper were removed, and ADB returned to UID 2000.
Evidence, exact hashes, baseline XML backup and restore instructions are in
`out/build-logs/wifi-desktop-stutter-20261004/README.md`. The generator now accepts an explicit
`--idr-interval-seconds` while preserving the original interval by default.

### Follow-up: fullscreen apps and touchpad tap-drag (2026-10-12)

Apps opened on an independent desktop (Wi-Fi or admitted USB) now start in fullscreen
windowing mode instead of a freeform window. Cast settings has **Open apps in fullscreen**
(`Settings.Global` `wifi_display_desktop_fullscreen`, unset/1 = fullscreen, 0 = previous
windowed behavior). The key is a string literal shared by Settings and WM Shell, so no
framework API changes. The setting is read when a task is placed and applies without
reconnecting.

WM Shell `DesktopTasksController` applies it only to new placements:

- `handleFreeformTaskPlacement` with `EnterReason.TASK_LAUNCH`, no requested bounds, and a
  task not yet in a desk is placed with `addMoveToFullscreenChanges` instead of the desk.
- `shouldFullscreenTaskLaunchSwitchToDesktop` keeps such fullscreen launches fullscreen.
- Moving a phone task to the desktop and relaunching a background recent task there use
  fullscreen.

The user's own switch to a window (handle/caption menu), client fullscreen exits, drags with
requested bounds, and tasks already in a desk keep upstream behavior. Shell skips placement
while the keyguard is locked, as before.

The phone touchpad no longer presses the button on touch or long press. A tap clicks after
the system double-tap timeout. Touching again within that window holds the left button, so
moving drags and lifting completes a double click. Two-finger tap right-clicks. The cost is a
double-tap-timeout delay for a single tap; the on-screen Left click button stays immediate.

Build: `JOBS=6 bash tools/build-umi.sh desktop-apps` (SystemUI and Settings only), log
`out/build-logs/build-umi-20261012-222145-1418664-desktop-apps.log`. The six SystemUI/Settings
APK/odex/vdex files were deployed to the remounted `/system_ext` overlay on umi `e86ac055`
with matching SHA-256 values. Only SystemUI and Settings were restarted; the live USB desktop
session stayed connected. Backups, outputs and the deployment record are in
`out/desktop-maximize-touchpad-drag/20261012-210301/`.

Results on the live `usbcast-desktop` display 3, with the phone unlocked:

- With the setting at its default, Shell logged `placeAsFullscreenTask=true`. The Calculator
  task was `fullscreen` with bounds `[0,0][1920,1080]`.
- With the setting at 0, the same launch was `placeAsDesktopTask=true` and opened as a window.
- The user then reported these manual receiver checks as passed:
  - fullscreen apps without a window frame;
  - moving tasks from the phone;
  - switching back to a window, and the setting;
  - touchpad move/tap/tap-drag behavior.

No full ROM was built.

## Real-device acceptance procedure

1. Deploy the matching new modules (or a full ROM only when explicitly requested), keep the
   phone unlocked, and enable the receiver's Miracast
   mode. A mouse/keyboard can optionally be paired directly with the phone.
   Confirm the target using `adb devices -l`, and set `DEVICE_SERIAL` to the umi serial.
2. Verify the configured capability:

   ```bash
   adb -s "$DEVICE_SERIAL" shell cmd overlay lookup android android:bool/config_enableWifiDisplayDesktopMode
   adb -s "$DEVICE_SERIAL" shell pm list features
   adb -s "$DEVICE_SERIAL" shell settings get global force_desktop_mode_on_external_displays
   ```

   Expect the overlay to be true, the freeform feature to be declared, and no requirement
   to enable the global force-desktop option.
3. Open Cast settings, enable Wireless desktop mode, and connect to the receiver. Inspect:

   ```bash
   adb -s "$DEVICE_SERIAL" shell settings get global wifi_display_desktop_mode
   adb -s "$DEVICE_SERIAL" shell dumpsys display
   adb -s "$DEVICE_SERIAL" shell dumpsys activity activities
   adb -s "$DEVICE_SERIAL" shell dumpsys input
   ```

   Expect setting 1, a `type WIFI` display with own-content/decor/trusted/own-group flags,
   `mDesktopModeEnabled=true`, and secondary Home/application tasks on a nonzero display ID.
4. Start two different applications on the external desktop. Move, resize, maximize, and
   close windows. Change the phone's app independently; the receiver must retain its own
   desktop rather than reproducing the phone screen. Verify pointer location, wheel, right
   click, physical keyboard input, focus switching, and Chinese input.
   Open **Wireless desktop touchpad** from Cast settings. Check single-finger movement/tap,
   tap-then-touch drag, two-finger scroll, right click, and external Back/Home. Confirm the phone's
   bottom swipe-up Home/Overview gestures work before connecting, during connection, while
   using the pad, after leaving it, and after disconnection. Switch between touchpad and a
   hardware mouse; expect one native cursor. Confirm virtual input devices disappear on pad
   exit, phone lock, activity recreation, disconnection, and Settings process death.
5. Disconnect. Applications must follow existing display-removal policy, and the pointer
   must be usable on the phone. Reconnect and repeat. Confirm the mode switch is disabled
   while connecting/connected and becomes editable after disconnecting.
6. Turn desktop mode off and reconnect. Confirm ordinary phone-screen mirroring and absence
   of the desktop-only flags. Inspect the no-devices state with Wi-Fi display disabled.
7. Exercise lock/unlock, authentication cancellation, screen timeout, audio output, unstable
   Wi-Fi, receiver power loss, and a 30-minute session. Measure latency, actual negotiated
   resolution/frame rate, text clarity, and thermal behavior.

Simulated secondary displays and successful compilation do not establish Miracast end-to-end
behavior. The adapter's reported 60 Hz is a pre-existing placeholder, not a measurement of
the encoder or receiver frame rate.

## Pre-development checkpoints

The user's existing changes were saved before this implementation:

| Repository | Commit |
|---|---|
| build/soong | 03065a290 |
| frameworks/base | 9ed8b4f2d7b1 |
| frameworks/native | e15f7746b3 |
| packages/apps/Settings | f717d187873 |
| packages/modules/Connectivity | 6d165b7f96 |
| packages/modules/Wifi | 8600b7f001 |
| packages/services/Telephony | c18b43cf8 |
