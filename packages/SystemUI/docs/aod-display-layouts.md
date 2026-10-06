# Independent AOD and lockscreen layouts

User controls: Settings → Display → Lockscreen and always-on display widget → Screen layouts.
The editor works even when no widget is selected. It contains four independent profiles:
AOD portrait, AOD landscape, lockscreen portrait and lockscreen landscape.

Choose a profile, enable it, select a module and drag/resize it on the schematic canvas or
adjust the percentage sliders. Each module has its own visibility switch. Save commits all
four drafts atomically; Back discards them. Activity recreation retains draft changes.
Restore system default affects only the selected profile, disables its custom layout and
requires Save. Screen orientation is a separate, immediately applied setting with system,
portrait, landscape and automatic policies. Defaults preserve existing system layouts.

## Source chain

- `AodDisplayLayoutFragment` and `AodDisplayLayoutPreview`: native SettingsLib editor,
  four drafts, drag/resize, module controls and saved instance state.
- `AodDisplayLayoutStore`: one versioned owner-user Secure setting for all four profiles.
  Invalid/future/incomplete data falls back to native layouts; geometry is bounded.
- `AodDisplayLayoutGeometry`: shared runtime/editor viewport and percentage calculations.
  Coordinates describe remaining travel after fitting dimensions. Portrait and landscape
  modules can extend to the screen bottom, including the unlock/fingerprint region; geometry
  does not consult the entry target or sensor location. The diagram has no reserved-area marker.
  `AodWidgetGeometry` uses the same unrestricted bottom boundary for the original widget editor.
  Existing top/side margins and screen bounds remain in force. Actual AOD burn-in movement
  is bounded by the viewport after placement, so bottom-edge content remains on screen without
  restoring an excluded editing region. Widget movement is recalculated after its view is laid out.
- `AodDisplayLayoutSection`: applies module transforms to native Keyguard views in the
  default and split-shade blueprints, retains native time/data/controllers, restores original
  transforms when leaving Keyguard or entering authentication, and adds existing burn-in offsets
  on AOD. The native burn-in Layer pauses its transforms while customised, keeping its reference
  list and cached views intact and avoiding competing transforms. AOD has no new timer or repeated
  Settings reads during drawing. The native date SliceView is included when Smartspace is disabled.
  Full-width date containers reflow into the chosen column before fitting, and blank indication
  rows do not shrink the visible battery message. Native SliceView constraints explicitly restore
  their original width when leaving a custom layout.
- `AodWidgetSection`: renders snapshots and live widget views using the selected profile's
  slot; existing provider selection, lock-content consent, gestures and PendingIntent launching
  remain authoritative. Without a custom profile the previous widget layout remains active.
- `NotificationShadeWindowControllerImpl`: applies the owner-user orientation preference
  while Keyguard/AOD is visible, observes preference changes, and uses the native orientation
  policy for the bouncer and unlocked applications.

## Scope and boundaries

Modules: time; standalone date/weather/smartspace; notifications (AOD icons or native live
lockscreen rows); one existing widget; battery/indication area. Native controllers decide
whether content exists: a visibility switch cannot create absent weather or notifications.
Clocks embedding their own date/weather remain a single clock module. Notification row
heights and fonts remain native; the editor controls their region and horizontal width/position
through native ConstraintLayout bounds so rows reflow rather than compressing text.
Authentication controls, shortcuts and status bar retain their native handling.
Arbitrary widget/provider backgrounds are preserved. Pixel removal is not part of layout editing.
The existing legacy-Keyguard/owner-user restriction remains; SceneContainer and other users
are outside this implementation. Screen-off sensor rotation and custom clock varieties need
physical-device validation before claiming universal compatibility.

## Validation (2026-10-04)

- Twelve production-source JVM tests passed: independent-profile persistence, malformed/versioned
  data fallback, corruption clamping, profile selection, viewport bounds, landscape geometry,
  zero-travel drag calculations and non-overlapping initial layouts. They also prove that a
  module can occupy the former fingerprint region and that both layout engines reach the
  actual screen bottom on portrait and landscape viewports. Burn-in offset tests cover all edges,
  normal interior movement and full-screen modules.
  Logs: `out/build-logs/aod-layout-designer-20261004/`.
- Both builds used exactly `JOBS=6 bash tools/build-umi.sh`. The final build passed in 11:13:
  `out/build-logs/build-umi-20261004-140633-3462604-rom.log` (exit 0).
  Only SystemUI was installed through ready staged session `1245678661` and reboot.
  Installed, product and target-files APK SHA-256 all match:
  `047f4cd6f4e0b7aaf60271ff2410a727469bc51bb0ff7eb760597e24e6afb1a0`.
- Final Mi 10 / umi checks covered all four profiles. With widget vertical position 100%, actual
  live/snapshot regions reached the viewport bottom within 1 px integer rounding and stayed on
  screen under native burn-in movement: portrait AOD `(396,1777,644,563)`, portrait Keyguard
  `(391,1777,644,563)` in 1080×2340; landscape Keyguard/AOD `(890,831,1405,248)` in 2340×1080.
  Native AOD battery text also remained visible at the bottom. SystemUI PID 2701 remained stable;
  the final crash buffer is empty. Evidence: `bottom-final-*-state.txt` / `.png`,
  `bottom-final-verification.txt`, `bottom-final-crash-buffer.txt` in the log directory above.
- The native profile editor has no reserved-bottom shade/lock marker. The original widget
  editor was dragged to the bottom in a draft and exited without saving; its fingerprint marker
  is gone. Screenshots: `unrestricted-editor.png`, `unrestricted-legacy-bottom-draft.png`.
- Temporary four-profile geometry and orientation settings were restored exactly to the latest
  user baseline. Current widget selection 98 and appearance/legacy position preferences were
  untouched; no widget allocations, fixture APKs or notifications were created by these checks.
- Earlier profile regression checks covered independent AOD/awake layouts, module visibility,
  an all-hidden AOD (all-black pixels), corrupt-data native fallback, date column reflow and
  native notification width. Their evidence remains in the same directory. The focused tests
  do not constitute universal custom-clock/provider compatibility verification.

## Manual checks

1. Open Customise display layout, select each desired screen/orientation and enable its profile.
   Select Widget (or another module), set vertical position to 100% and Save. The region can
   reach the bottom edge, including the previously excluded unlock/fingerprint area.
2. Repeat with portrait and landscape on both awake Keyguard and AOD. The original size/position
   editor also allows its widget to reach the bottom at vertical position 100%.
3. Check the chosen layout with the actual PIN/fingerprint flow and provider buttons. Native
   authentication controls retain their normal handling; this geometry change introduces no
   new authentication or widget-launch code.

Physical automatic rotation while asleep, secure PIN/fingerprint interaction, all third-party
clock/provider combinations and the full SystemUITests suite are not covered by the focused
layout checks. Prior widget business-action verification is not rerun by this geometry patch.
