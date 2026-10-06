# Lockscreen and always-on display widgets

The current umi product uses the legacy Keyguard blueprint (`scene_container` is false).
Portrait and split-shade blueprints include `AodWidgetSection`. An independent AppWidgetHost
(ID 117) holds up to eight independent owner-user widget instances. Launcher and communal widget instances remain separate.
SceneContainer/Compose, secondary-user and work-profile widgets are not supported.

## User controls

Settings → Display → Lockscreen and always-on display widget (also inside the AOD settings page).
Select a provider, complete its configuration and enable the widget. AOD must be enabled separately.
The management screen uses SettingsLib's collapsing toolbar, main switch, preference groups,
privacy switch and footer, with the system's expressive typography and dynamic accent colours.
The management and layout pages always use dark resources and a pure-black page, toolbar and
preview background, independently of the system light/dark setting. Preference cards retain their
native dark surfaces so rows and controls remain distinguishable. This is scoped to the management
Activity; other SystemUI surfaces keep their own theme. Provider-drawn backgrounds remain intact.
The provider picker shows the owning application's icon and name alongside each widget label,
grouped alphabetically by application and then widget label. Selection uses the displayed provider's
identity, including duplicate labels. Widget preview controls remain unavailable.
“Always-on display appearance” opens a separate editor with brightness (5–100%), colour saturation
(0–100%), Standard/High definition clarity, three presets and Reset. Colours and brightness preview
the same transform as AOD; actual panel brightness remains controlled by the system AOD policy.
Editing, presets and Reset remain drafts until Save, survive rotation and are discarded by Back.
The awake lockscreen retains the original provider colours and brightness.
“Screen layouts → Customise display layout” is the single full-screen geometry editor.
It edits four independent profiles (AOD portrait/landscape and lockscreen portrait/landscape).
Every widget instance can be selected, moved, resized, hidden or removed independently. The
management page adds instances rather than replacing the current widget. Provider binding and
configuration cancellation preserve all existing instances.

The editor has an immersive black canvas using the actual viewport. Floating controls select the
target profile and module; drag to move, use the bottom-right handle to resize, or use Fine adjust.
Hide controls to inspect the whole result. Real RemoteViews receive size updates during editing.
AOD preview reuses the content intensity/saturation matrix, snapshot resolution and clock refresher.
Native clock previews use an independent current clock style; date, notifications and indications
are drawn from the current native content. There are no invented placeholder notifications.
Save applies the four-profile draft atomically; Back discards it, including after recreation.
Changing the selected target orientation rotates the editor. The separate screen orientation setting
still controls actual Keyguard/AOD.

Version 2 layout JSON keys widget boxes by AppWidget ID, so adding/removing other instances does
not move an existing widget. Version 1 layouts and legacy dp geometry migrate on first mutation.
The canvas is black; physical AOD brightness, burn-in movement, authentication transitions,
wallpaper and third-party dynamic content are not reproduced exactly by the editor. Native content
which is unavailable remains absent. See the current feature record at
`docs/features/aod-multi-editor.md` in the checkout's personal tools repository for validation.

See [UI review and pure-black theme validation](aod-widget-ui-review.md) for the current visual
changes, device evidence and prioritized visual/functional improvements.

Secure settings for owner user 0:

| Key | Default | Meaning |
| --- | --- | --- |
| `aod_widget_ids` | unset | Ordered widget IDs owned by host 117; unset reads the legacy ID |
| `aod_widget_id` | -1 | Legacy primary widget ID, retained for migration and diagnostics |
| `aod_widget_enabled` | 0 | Show on both Keyguard and AOD |
| `aod_widget_show_when_locked` | 0 | Explicit consent on a secure, non-dismissible Keyguard |
| `aod_widget_width` | 320 | Requested width, 120–600 dp |
| `aod_widget_height` | 120 | Requested height, 64–320 dp |
| `aod_widget_position_x` | 50 | Horizontal position within available travel, 0–100% |
| `aod_widget_position_y` | 100 | Vertical position within available travel, 0–100% |
| `aod_widget_brightness` | 35 | AOD content intensity, 5–100%; 100 removes host dimming |
| `aod_widget_saturation` | 0 | AOD saturation, 0–100%; 0 grayscale, 100 original colour |
| `aod_widget_high_quality` | 0 | 0 half-resolution Standard, 1 full-resolution High definition |
| `aod_display_layouts` | unset | Versioned JSON containing four independent display profiles |
| `aod_display_rotation` | 0 | 0 system, 1 portrait, 2 landscape, 3 automatic rotation |

`AodDisplayLayoutGeometry` is shared by the full-screen editor and actual widget surfaces.
The old `AodWidgetGeometry` remains only for migration and appearance previews. Legacy dp keys
are no longer a second active geometry editing path.
Position percentages describe the remaining travel after fitting the widget; an oversized widget
can have no travel on an axis. The provider receives the actual rendered dimensions through
`updateAppWidgetSize`. When native geometry is retained, notification bounds reserve the primary
widget slot and the large clock is constrained above it. Custom profiles instead use their explicit
module boxes. Widget instances draw in list order above native clock faces, as in the editor.

By default, content requires a dismissible Keyguard. “Show content while locked” explicitly
authorizes the entire widget and its controls on secure Keyguard: providers offer no generic
notification-style public version. Providers declaring `WIDGET_CATEGORY_NOT_KEYGUARD` are excluded.
Selection commits only after binding/configuration succeed. Cancellation preserves the old widget;
pending IDs survive rotation and abandoned allocations are removed on the next management entry.
The management preview does not execute PendingIntents.

## Two display modes

On awake, visible Keyguard, a real AppWidgetHostView is attached. RemoteViews updates, buttons and
collection interactions use the provider's original view. Broadcast/service actions such as playback
controls execute normally. For two seconds after a broadcast/service click, the host observes app task
launches on the same display and owner user. If the operation opens or reopens a page, it requests the
system's Keyguard dismissal/authentication flow without resending the action. Refresh/playback-only
operations leave Keyguard showing. The observer stops on timeout, sleep, unlock, disable, user switch
or host removal; it excludes home/recents and SystemUI tasks. Activity PendingIntents first use the
same dismissal/authentication flow; the original fill-in intent and launch options are retained.
Host ownership, selection, provider
and owner user are checked again after authentication. User switching invalidates a pending launch.
Touch streams starting inside the live slot are dispatched to the widget rather than shade/hub gestures.
The widget is hidden while Keyguard is dismissed or occluded, a bouncer is visible, or the device sleeps.
Provider-specific minimum sizes, backgrounds and unsupported RemoteViews can limit the result.

Stable `DOZE_AOD` and `DOZE_AOD_DOCKED` display a snapshot in the same slot. The host view is
detached; RemoteViews updates are buffered and inflation/rendering coalesced on minute ticks and bounded content updates. Existing doze time ticks refresh TextClock descendants; no extra wake alarm/network request
is added. The defaults remain half resolution, grayscale and 35% brightness. Saved appearance
settings adjust RGB intensity and saturation without changing alpha or lifting black backgrounds.
High definition renders at the slot's full resolution (four times the bitmap memory); existing
burn-in movement, update coalescing and minute ticks apply in both modes. Colour settings update
the snapshot ImageView without redrawing widget content; resolution changes request a fresh frame.
Collections can render incompletely in this detached mode; auto-advance is not driven.
Proximity pauses, pulses and authentication transitions hide content. Provider jobs are not suspended.

AOD is display-only: normal wake gestures inside and outside the slot only wake the screen.
The snapshot is not clickable, including through accessibility. Interactions take place on the
live lockscreen widget. Default-layout clicks also use the same authenticated launch path;
management previews suppress both default-layout clicks and RemoteViews PendingIntents.

Detached TextClock, AnalogClock and running Chronometer descendants are refreshed after responsive
layout selection. Explicit timezones, clock formats and stopped timer text are retained. Timezone,
time and locale broadcasts and 12/24-hour changes invalidate the snapshot. Chronometer has no
started-state getter; the platform host reads the same-framework private `mStarted` field once per
refresh and uses the existing hidden `updateText()` method without altering its base or ticker.

RemoteViews and collection invalidations are buffered. Adapter data changes and detached child
layout requests mark the snapshot dirty. Dirty content is coalesced with a five-second minimum
render interval; initial frames, minute ticks, size and time-configuration changes are immediate.
Handler callbacks do not add a wake alarm; delayed updates can wait until the existing AOD wake.
Snapshot adapter observers are removed when returning to live mode or clearing the host.

Detached host error callbacks run on the main handler, guarded against obsolete host instances.
Host/inflation/render failures clear content and retry at most three times, after 2, 5 and 15 seconds,
using the existing handler. Attempts are scoped to the widget and cancelled when display eligibility
ends; successful display, a selection/layout change or a fresh display session resets recovery.
AOD eligibility explicitly rejects hidden/occluded Keyguard and either authentication bouncer.

## Verification

Build with `JOBS=6 bash tools/build-umi.sh`. Pure JVM policy/geometry tests exercise all doze states,
consent and owner isolation, lockscreen blockers, oversize portrait/landscape/short-screen layouts,
and drag coordinate conversion. These do not replace Android instrumentation or device testing.
Inspect runtime state without printing provider content:

```sh
adb -s <serial> shell dumpsys activity service com.android.systemui/.SystemUIService AodWidgetSection
```

1. In the editor, drag the slot and change dimensions; navigate back and verify settings stay unchanged.
   Save and verify bounds and provider size options change. Rotate with unsaved edits and reopen;
   verify values survive. Reset and save; reboot to check persistence.
2. On awake Keyguard verify `surface=LIVE`, `liveAttached=true`, `bitmapBytes=0`. Operate a provider
   button and verify its actual state changes while Keyguard remains showing; test collections where
   supported. Tap an activity action and verify authentication precedes launch; cancel authentication.
   Test broadcast/service operations that indirectly open a page, including a reused foreground task
   and a page in another app: Keyguard must dismiss, and the original operation must run only once.
   A refresh with no page launch must keep Keyguard showing and clear its launch observer after two
   seconds. On secure Keyguard, verify authentication/cancellation before the opened page is exposed.
3. Sleep and verify `surface=SNAPSHOT`, `liveAttached=false`, nonzero bitmap size. Inspect grayscale,
   size/position, clock, notification and fingerprint spacing. Wait a minute for TextClock refresh.
   Test the usual wake gesture inside and outside the slot: only wake, with no app launch or widget action.
4. Unlock, disable, occlude, trigger a bouncer/pulse/proximity pause and switch users: content must
   disappear. With secure Keyguard, revoke locked-content consent and verify both modes hide.
5. Cancel configurable replacement across rotation; verify the old widget and host ID count persist.
   Reboot and check Launcher/communal IDs remain untouched. Inspect SystemUI crash/ANR logs.
6. Repeat with custom clocks and landscape. PIN/biometric, secondary-user and provider-specific
   collection checks require an appropriately configured device.

### Indirect lockscreen launch validation (2026-10-03, Mi 10 / umi)

- Reproduced with the selected RSS widget, ID 84, from `com.sun.quickpay`. Collection row clicks
  send a broadcast PendingIntent whose receiver opens `com.sun.browser.MyWebPage`. Before this
  change the page started behind Keyguard and the live widget remained visible.
- `JOBS=6 bash tools/build-umi.sh` completed successfully in 10:51. ROM:
  `out/target/product/umi/lineage-23.2-20261003-UNOFFICIAL-umi.zip`.
  Build log: `out/build-logs/build-umi-20261003-130407-1421579-rom.log`.
  Product, target-files and staged/installed SystemUI APKs share SHA-256
  `6acc33b7617f7d7581fe8f060565f004e145e8575c30d3c90fa0594e266a95f9`.
- After the staged update and reboot, two different RSS rows opened their corresponding articles
  and dismissed Keyguard. Runtime state reported `surface=NONE`, `showing=false` and the browser
  page as the resumed Activity. The second click reused the existing browser Activity/task.
- A temporary configurable provider allocated pending ID 86 while original ID 84 stayed allocated.
  Its broadcast, service, direct Activity and cross-app Settings actions all dismissed Keyguard.
  Repeated broadcast navigation reused the same Home Activity/task. Broadcast counts advanced
  0 → 1 → 2, service count 0 → 1 and Home page deliveries 0 → 1 → 2 → 3 → 4, verifying exactly
  one operation/delivery per click. Cross-app navigation incremented its action counter once.
- Refresh-only clicks updated the provider counter while Keyguard remained showing. Monitoring
  was active immediately after the click, expired after two seconds, and cancelled immediately
  when entering AOD. A separate Activity launch after expiry left Keyguard showing. AOD remained
  a detached snapshot with no pending launch or trampoline listener.
- Original selection 84 and all seven widget settings were restored before cancelling configuration.
  Pending ID 86 was released and the fixture uninstalled. Original Launcher IDs 2, 3, 8, 49 and
  communal ID 45 retained their host ownership. The restored RSS widget was live on Keyguard at
  328 × 320 dp, position 38%, 44%. SystemUI PID 2715 stayed stable after reboot; crash buffer empty.
- Evidence is under `out/build-logs/aod-widget-launch-20261003/`. This device has no secure PIN;
  secure authentication/cancellation still needs the manual procedure above. Full SystemUITests
  instrumentation was not rerun.

### Current clock, collection and recovery validation (2026-10-03, Mi 10 / umi)

- `JOBS=6 bash tools/build-umi.sh` completed successfully in 11:14. ROM:
  `out/target/product/umi/lineage-23.2-20261003-UNOFFICIAL-umi.zip`.
  Build log: `out/build-logs/build-umi-20261003-100434-3658109-rom.log`.
  Product APK, target-files APK and the staged/rebooted, installed SystemUI share SHA-256
  `d97a78d24d21475da058aaa8bffacc7bb442615f1ce6936f1de4df7082771009`.
- Twelve JVM tests passed using production render-policy, visibility-policy and geometry sources.
  Only temporary harness copies removed the Android runner/base class; full SystemUITests were
  not run. A platform-signed temporary instrumentation fixture compiled the production clock
  refresher and passed 20 detached-clock assertions: initialization, implicit/explicit zones,
  12/24-hour resynchronization, running/paused/countdown timers and preservation of ticker/base/state.
- The selected stock AnalogClock widget now shows the current hands on AOD. A clock fixture
  advanced its AOD snapshot from 10:23:34 to 10:24:00 on the existing minute tick: running timer
  01:06 → 01:32, countdown 03:03 → 02:38, with paused text still `PAUSED`.
- A collection fixture using only `notifyAppWidgetViewDataChanged` changed visible AOD rows
  from Batch 3 to Batch 4. Android 16 converts this provider's legacy factory to RemoteCollectionItems.
  Earlier testing also verified Batch 2 → Batch 3 within one minute. This does not establish that
  every third-party collection supports complete detached rendering or auto-advance.
- Invalid RemoteViews automatically recovered after a valid update arrived during backoff.
  A successfully inflated AnalogClock with an invalid hand drawable exercised the child-drawing
  exception path: attempts occurred after 2, 5 and 15 seconds, then stopped at four total attempts,
  with no visible content, no listener and no further retry callback. Disabling cancelled recovery;
  repairing and re-enabling restored a healthy snapshot with zero recovery attempts. Drawing
  failures were also injected on awake Keyguard before its normal return to AOD. SystemUI PID 2716
  remained stable throughout; its crash buffer contained no SystemUI crash.
- Accessibility click on the AOD snapshot returned `clicked=false`. An accessibility-injected
  double tap at the widget woke to `surface=LIVE`, attached host and `showing=true`, with no app
  launch. On Keyguard, the real Update rows button changed Batch 4 → Batch 5 without dismissal;
  tapping row 0 opened Home with the original fill-in value `Row: 0` and dismissed Keyguard.
  A separate provider retaining its initial/default layout verified default-click launch through
  the host override. This device has no PIN; secure authentication/cancellation and a new physical
  hardware gesture remain manual checks.
- Temporary instances 69 and 70 were cancelled before uninstalling the fixture. Original widget 67,
  enabled/consent settings and 292 × 218 dp layout at 51%, 55% were restored. Launcher IDs 2, 3, 8,
  43, 49 and communal ID 45 remained unchanged. Accessibility, 12/24-hour and rotation settings
  were restored. Evidence is under `out/build-logs/aod-widget-verification-20261003/`.

The following records are historical. The display-only AOD behavior above supersedes earlier
snapshot-click launch checks.

### Native Settings UI validation (2026-10-03, Mi 10 / umi)

- `JOBS=6 bash tools/build-umi.sh` completed successfully in 14:40. ROM remains
  `out/target/product/umi/lineage-23.2-20261002-UNOFFICIAL-umi.zip` (cached build date).
  The staged, installed SystemUI APK and product/target-files APKs share SHA-256
  `29b7d375b3b32da251341ec536d6ab8516cf33af08098e3df643f45227db04ed`.
- Main and layout pages were inspected in light and dark mode: native main switch,
  rounded preference groups, themed preview, system sliders and toolbar Save action.
  Dragging and sliders updated the diagram without writing saved settings. Unsaved
  168 × 166 dp edits survived night-mode changes and portrait/landscape recreation.
  Back returned to the management page and discarded edits. Saving a width change
  from 209 to 187 dp persisted it and refreshed the management-page summary.
- The main switch updated the enable setting. The locked-content switch displayed
  the native confirmation dialog; accepting it enabled the consent setting.
- A temporary configurable provider allocated pending ID 47 while selected ID 44
  remained intact. Cancelling configuration after rotation released ID 47, returning
  host 117 to one widget. The temporary provider APK was uninstalled.
- Original selection 44, enabled state and consent were restored, with the original
  209 × 218 dp layout at 66%, 40%. Launcher IDs 2, 3, 8, 43 and communal ID 45 remained
  intact. Night mode and rotation were restored. Final SystemUI crash and widget error
  buffers were empty. These checks cover the Settings UI change; full SystemUITests
  instrumentation and secure-authentication interaction were not rerun.

### Layout/live-widget validation (2026-10-03, Mi 10 / umi)

- `JOBS=6 bash tools/build-umi.sh` completed successfully in 14:46. ROM:
  `out/target/product/umi/lineage-23.2-20261002-UNOFFICIAL-umi.zip` (cached build date in filename).
  Installed SystemUI, build APK and target-files APK share SHA-256:
  `a740286dabe4198e2417f24fb713c2f07f2b6931814a3b45d057d31ccaee358b`.
  Both SystemUI and Settings were installed through a ready staged session and reboot.
- Seven policy/geometry tests passed on the JVM using production sources, with Android test-runner
  annotations/base class removed only from temporary harness copies. Full SystemUITests were not run.
- The layout editor was inspected on device. Slider changes and diagram dragging changed the
  displayed values; Cancel left secure settings untouched. Unsaved 171 × 171 dp edits and position
  values survived landscape/portrait recreation. Reset displayed 320 × 120 dp, 50%, 100% without
  dismissing the dialog. Cancelling retained the previously saved layout.
- Saving 208 × 230 dp at 21%, 40% produced a 572 × 632 px slot on device. Awake Keyguard reported
  `surface=LIVE`, `liveAttached=true`, no bitmap; AOD reported `surface=SNAPSHOT`, detached live host,
  361504-byte bitmap. Screenshots verified original colours on Keyguard, grayscale on AOD and
  separation from the current clock/notification/fingerprint areas. Burn-in movement changes the
  AOD x offset; the live slot returns to its configured position.
- A temporary configurable provider was bound as pending ID 41 while the original widget
  ID 39 remained allocated. Temporarily selecting the pending instance allowed isolated operation
  tests without committing replacement or deleting the user's widget. An injected touch on its
  broadcast button changed actual provider state from 0 to 1 while `showing=true`. Its activity
  button opened the provider Home activity and dismissed Keyguard. An accessibility click on the
  AOD snapshot opened Home with the action counter still at 1. This verifies the snapshot click path,
  not a new physical inside/outside wake-gesture test.
- Original selection 39 was restored before cancellation. Pending ID 41 was released, the test APK
  and accessibility service removed. Launcher IDs 2, 3, 8 and communal ID 5 remained allocated.
  Disabling the feature on AOD stopped listening, hid the slot and cleared the bitmap.
  Saved 208 × 230 dp and 21%, 40% values survived a reboot; the original widget was still present
  and displayed live after re-enabling. Test layout overrides were removed to restore the original
  320 × 120 dp bottom-centred layout. Rotation and screen timeout settings were restored.
  The restored AOD snapshot advanced its render timestamp from 85105 to 141405 on the existing
  minute tick. SystemUI crash and widget error log buffers were empty during the final check.
- The device has no secure PIN: secure authentication/cancellation, secondary-user transitions,
  provider collection scrolling and custom-clock/split-shade layout remain manual checks.

### Previous snapshot-only validation (2026-10-03, Mi 10 / umi)

- `JOBS=6 bash tools/build-umi.sh` completed successfully in 11:11. The installed SystemUI,
  staged build APK and target-files APK have identical SHA-256
  `cf806063a32dc3ec29acc95ec8a574f923279f7dcd1771d584cb3fdd43872e21`.
- The three policy tests passed on the JVM using the production policy and doze-state enum.
  This does not constitute running the full Android SystemUITests instrumentation suite.
- The device retained the selected music widget across the staged updates and reboots. Its AOD
  dump reports `DOZE_AOD`, listening and visible, with a 290400-byte snapshot. Minute ticks
  advance the render timestamp. Disabling the setting clears the bitmap and stops listening.
- An accessibility click on the AOD snapshot entered `org.lineageos.twelve/.MainActivity` through
  the actual SystemUI launch path. Playback remained `NONE`; no widget playback action ran.
- A configurable test provider was cancelled after rotation. The original music widget remained,
  and host 117 returned from two IDs to one. Launcher IDs 2, 3 and 8 and communal ID 5 remained.
  The temporary test package and accessibility service were removed; rotation settings restored.
- Management-page insets were inspected on device: description starts below the action bar.
- Human-assisted input capture recorded real touch coordinates in the widget area and the normal
  touch double-tap wake path. Final inside/outside gesture behaviour still needs confirmation on
  the updated APK. Secure PIN/biometric authentication, secondary users, proximity transitions
  and custom clocks remain manual checks; this device currently has no secure screen lock.

The build wrapper sets a default Go GC target (`GOMEMLIMIT=12GiB`, `GOGC=25`) and Soong forwards
these explicitly supplied settings into its isolated builder environment. This limits graph
generation pressure independently of Ninja's `JOBS=6`; the Go target is a soft memory limit.
