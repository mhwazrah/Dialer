# Handoff: Liquid Glass redesign (MHWDialer)

## STATUS (latest — read first)
Branch `feature/liquid-glass`: commits 57b5ce5 (redesign) + caf7e80 (test fixes) + **uncommitted review-fix work** (version 1.0.8 / 10008). User rule: commit only when asked — ask before committing.

### Done & verified on emulator (Pixel_8_Pro_API_35, x86_64, ps16k image; app installed via `:app:installFossRelease`)
- Glass rule (user): glass look, never see-through. `GlassPanel` = static opaque frosted panel (opaque base + sheen + rim) unless `app:backdropSourceId` is set (call screen → live glass over call bg) or API<31 (also static). `View.applyGlassSurface()` (opaque sheet) on the 3 dialpad keypad containers in DialpadActivity. Verified no rows show through top bar / keypad.
- All features unlocked: `App.unlockAllFeatures()` sets commons `isPro`, `isProNoGP`, `hadThankYouInstalled`; PurchaseHelper init removed → Project support banners hidden, appearance/answer styles unlocked. (Verify Customize appearance now unlocked.)
- Search field restyled (subagent): `MainActivity.styleGlassSearchField()` + `bg_glass_search_light/dark.xml` + `glass_search_*` colours (light fill/rim changed to faint navy #0F/#1A001E3C).
- Swipe rows: `showSwipeActionsOnlyWhileSwiping` (INVISIBLE, listener removed on detach). Right swipe = SMS (works), left = delete w/ confirm (works; note adb swipes must start >~150px from the right edge or the system back gesture eats them).
- Status-bar icons dark on Dialpad/Conference/SpeedDial (no `topBarColor = TRANSPARENT`; clear appbar after setupTopAppBar).
- Placeholders offset under floating bars (MainActivity.updateListInsets).
- API<33 x86 native-blur guard in `applyLiquidGlass` (lib ships ARM-only libnativegauss; app installs as x86_64 because androidx.graphics.path ships x86_64) — verified no crash at debugApiLevelCap 30/32 (cap removed again).
- Calls: incoming, accept (buttons + Slider style drag), mute, hold/unhold, call waiting, hang up, outgoing — all OK. Swap unclear (emulator listing showed one call).
- Search "Ahm", contacts tab, names in recents (earlier bare numbers were a seeding bug), top-tabs mode — OK.
- Earlier SIGSEGVs: accessibility (uiautomator dump) + HeapTaskDaemon + IME JIT → emulator ART instability (ps16k image); not reproduced in 5 swipe+dump rounds.

### DONE since: page title in top bar (MainActivity.updatePageTitle, cold start + tab change) and pill dialpad redesign (dialpad_grid.xml number row + pill keys + glass call button; input reparented into the row with backspace inset; sheet consumes touches) — verified typing, one-tap backspace, sheet touch blocking. Commit after 67638bf.

### Round after bd5e6cb (UNCOMMITTED, version 1.0.11 — user has not asked to commit yet)
- Dialpad pill keys: letters overflowed with a secondary language → pill digit base 28sp (dimen dialpad_pill_text_size, still scaled by the font-size setting; applyDialpadNumberFontSize now takes the percent), "+" at 60%, digits includeFontPadding=false, letters marginTop -6dp. Verified with Arabic secondary language.
- Dark theme (verified on emulator, theme set to Dark in Customize appearance): brand primary pair — SimpleActivity.syncBrandPrimaryWithTheme swaps default navy #001E3C ↔ #7FB2E5 (color brand_primary_dark_theme) by theme brightness, only when primary is a brand default. Per-activity localNightMode follows the app theme → drawable-night/bg_glass_card.xml (dark navy rows). Letter avatars use the primary (extensions/LiquidGlass.kt loadBrandedContactImage; navy letter on light primaries), unknown-number placeholders tinted primary (RecentCallsAdapter). Applied in Contacts, Recents (both row types), CallHistory header — NOT yet in ConferenceCallsAdapter, CallActivity, MissedCallReceiver notification.
- Light theme after these changes: not re-checked yet (emulator left on Dark theme).
- Still pending: vertical slider test, deferred visuals (FAB square patch, call-status pill clipping, on-hold banner edges, in-call dialpad empty input bar), swap-calls check on device, a "Rate" prompt (commons) appears on launch.

### PENDING (user asks, newest first)
1. (done) page title
2. (done) **Dialpad redesign like the user's reference** (dark screenshot): number display row with underline + backspace above the keypad, keys = wide pill buttons in 3 columns (≈ full-width pills, navy fill), round call button below — but with glass effect on each key and the call button, and the opaque keypad sheet rule. Applies to DialpadActivity default style (`else`/DIALPAD_ORIGINAL branch → dialpad_grid.xml via dialpadClearWrapper).
3. Dark theme now testable (features unlocked): set App theme Dark in Customize appearance; check primary #001E3C contrast on navy backdrop (selected tab, FAB, switches, "All" chip) — likely needs a lighter primary in dark.
4. Vertical slider answer style now selectable — test ring + drag on it.
5. Deferred visuals: square patch behind dialpad FAB, status pill clips "Is Calling…"/timer, on-hold banner overruns edges, empty input bar on in-call dialpad, confirm resized icon in all-apps.
6. detekt can't run: `detekt.yml` missing at repo root (pre-existing).
7. Commit (ask user), then push only if asked.

Test helpers (scratchpad, may be cleared): shot.ps1 (screenshot+crash check, `-Dump` for UI tree), tap.ps1 (tap by text). Device coords 1344x2992: tabs y=2812 (Fav 255, Rec 673, Con 1089), dialpad FAB (1202,2578), overflow (1220,248).

## Request
Redesign all screens in the Liquid Glass style (https://liquidglassdesign.com). User decisions:
- Library: **QWEA0/Liquid-Glass-Android** `com.github.QWEA0:liquidglass:v2.0.11` (XML Views; AGSL lens on API 33+, CPU blur fallback 24-32). Kyant0/AndroidLiquidGlass rejected (Compose-only; app has zero Compose).
- Scope: **everything** (bars, tabs, FABs, dialpad, call buttons, dialogs, list rows, settings cards).
- Delivery: **all at once**, one build at the end.
- Standing rules: bump version per build (scheme VERSION_CODE = major*10000+minor*100+patch, currently 1.0.0 / 10000 on the rebrand branch); Conventional Commits; never push unless told; build command below.

## Branch state
- `feature/rebrand-mhwdialer` (51df5a5, committed, not pushed): MHWDialer name + Liquid Glass launcher icon + version 1.0.0.
- `feature/liquid-glass` (branched from it, **uncommitted** foundation):
  - `gradle/libs.versions.toml` + `app/build.gradle.kts`: `liquid-glass` dependency (resolves, release build OK; JitPack already in settings).
  - `res/drawable/bg_liquid_backdrop_dark.xml` / `_light.xml`: colour-field screen backdrops (#001E3C based / pale).
  - `extensions/LiquidGlass.kt`: `Context.isLiquidLightTheme()`, `View.applyLiquidBackdrop()`, `LiquidGlassView.applyLiquidGlass(cornerRadiusDp?, backdrop?, dynamic, pressable)` — single source of truth for tint/material/dispersion.
  - `views/GlassPanel.kt`: `LiquidGlassView` subclass that self-applies the style; use in XML as `<com.goodwy.dialer.views.GlassPanel app:cornerRadius="..." app:backdropSourceId="@id/...">`.
  - Library property names verified against source: cornerRadius, material, glassTint, dispersionStrength, enableAdaptiveTint, enableSensorHighlight, enablePressEffect, collectFrameStats, enableDynamicBackground, backdropSource (all `var`).

## Design rules (from advisor review — follow these)
0. **User rule — glass look, never see-through.** Any surface floating over other content (bars, tab bar, dialpad keypad, sheets) has an opaque frosted base: live glass tint is 92% (`applyLiquidGlass`), floating panels use `View.applyGlassSurface()` (95%). Content behind (rows, text) must never be legible through it; only the plain colour-field backdrop may show through.
1. **Glass needs content behind it.** Refraction is invisible over flat colour / smooth gradients. On list screens make the list fill the screen and scroll *under* the top bar, tab bar and FABs (list padding + `clipToPadding="false"`; bars overlay the list in the same parent). MainActivity: `mainHolder` currently sits below `mainMenu` (appbar_scrolling_view_behavior) — must change so content passes under both bars.
2. **Real glass (`GlassPanel`, dynamic) only on the controls layer:** top/search bar, bottom tab bar, FABs, recents filter bar, in-call buttons, accept/decline, status/on-hold panels. **Dialpad keys use static `bg_glass_key(_round)`** — 14 live key panels measured ~3.6 s/frame on the emulator.
   - `GlassPanel` without `backdropSourceId` refracts `android.R.id.content` (which carries the backdrop); the library default (direct parent) rendered dark.
3. **Rows and settings cards = static glass drawable**, NOT a LiquidGlassView per row (they scroll with content → fixed backdrop anyway; per-row dynamic capture is a CPU-blur perf trap on API <31). Create e.g. `bg_glass_card.xml`: translucent tint (white ~22% light / navy ~30% dark), 1dp hairline stroke (white ~35%), top highlight, rounded corners. Apply to recents/contacts/favorites/history/conference/speed-dial rows and settings CardViews (make CardView backgrounds transparent).
4. **Opaque runtime paints override the backdrop** — neutralise them or apply backdrop after them, in one shared place where possible:
   - MainActivity.kt:299-302 (mainHolder + fragments getSurfaceColor), 654 (mainTabsHolder), 661, 792-798 (mainMenu.updateColors / setupSearchMenuScrollListener)
   - DialpadActivity.kt:93, 184, 204-208, 285-405 (key holders + each key)
   - SettingsDialpadActivity.kt:75, 243-334 (dialpad preview)
   - CallActivity.kt:141, 194-195 (callHolder)
   - CallHistoryActivity.kt:87, 215-226 (decorView, actionbar, contactActionsHolder, collapsingToolbar)
   - SettingsActivity.kt:84, 207, 942; ConferenceActivity.kt:21; ManageSpeedDialActivity.kt:48 (setupMaterialScrollListener — recolours the appbar on scroll)
   - **Not yet read:** `activities/SimpleActivity.kt` and the commons `BaseSimpleActivity` (`updateMaterialActivityViews`, onResume colour updates). Read first; ideal hook = one call in SimpleActivity that paints `applyLiquidBackdrop()` on the content root after commons paints.
5. **Call screen:** keep the user's call-background setting (photo / blurred avatar) as the backdrop — best refraction showcase. Only glass the buttons/status panels.
6. Text colour: on the dark backdrop force light text; light theme keeps commons' dark text (`isLiquidLightTheme()`).

## Screen map (from survey)
- MainActivity `activity_main.xml`: CoordinatorLayout; `mainMenu` = commons MySearchMenu; `mainHolder` ConstraintLayout (top TabLayout `mainTopTabsHolder` L48, `viewPager` L70); FABs `mainDialpadButton` L77, `mainCallButton` L93 (MyFloatingActionButton); bottom tabs = Material TabLayout `mainTabsHolder` L113, built in MainActivity.kt:877-921 (custom view `bottom_tablayout_item`), top tabs 819-827. Option: wrap mainTabsHolder in a GlassPanel pill (keep TabLayout logic) rather than swapping to LiquidGlassTabBar.
- Fragments: contacts/favorites → `fragment_letters_layout.xml` (MyRecyclerView `fragment_list` + FastScroller); recents → `fragment_recents.xml` (AppBarLayout `recents_filters_appbar` with chip MaterialButtons in `layout_recents_filters.xml`, `recents_list`).
- Rows: item_recent_call.xml, item_recent_call_swipe.xml (SwipeActionView), item_recents_date.xml, item_contact_with_number_info_swipe.xml, item_contact_with_number_grid_swipe.xml, item_call_history.xml, item_conference_call.xml, item_speed_dial.xml, item_filter_contact_source.xml.
- DialpadActivity `activity_dialpad.xml`: MyAppBarLayout+MaterialToolbar, `dialpadInput`, `dialpadHolder` (lists + includes dialpad_round / dialpad_grid (CardView) / dialpad_rectangle, keys = RelativeLayout+2 MyTextView; dialpad_call.xml).
- CallActivity `activity_call.xml` (+ layout-normal, layout-small variants!): `call_status_holder` L187, button groups L266/L640, accept/decline L713/L760, end L557.
- CallHistoryActivity: Collapsing toolbar, 4 AppCompatButtons `contactActionsHolder` L67-143, NestedScrollView L178 + MyRecyclerView L200.
- Settings / SettingsDialpad: NestedScrollView `settings_nested_scrollview` → `settings_holder` → CardView sections of RelativeLayout rows.
- Conference, ManageSpeedDial: MyAppBarLayout + MyRecyclerView.
- App dialogs in repo: ManageVisibleTabsDialog, ChangeSortingDialog, ExportCallHistoryDialog, `dialog_*.xml` — use static glass card bg.

## Out of scope (tell the user)
About, Customize colors, Blocked numbers, purchase screen and all commons dialogs (RadioGroupDialog, ConfirmationDialog, SelectSIMDialog, …) live in the pinned `goodwy-commons` dependency — cannot be glassed without forking commons. The fixed backdrop also overrides the user's custom background colour (accent/text colours still apply).

## Risks / verification
- Library `System.loadLibrary("nativegauss")` is unguarded; AAR ships arm64-v8a/armeabi-v7a only → x86 emulators may crash on the CPU path. Real ARM phones fine.
- Release is minified; library consumer rules have no JNI keep, but default `proguard-android-optimize.txt` keeps native methods. Verify on device anyway.
- No adb device was connected → visuals unverified. Build a vertical slice (main screen: backdrop + glass search bar + glass tab bar + glass FABs + glass rows) first, have the user install and screenshot, then fan out.
- Subagents: max 3 parallel, sonnet, disjoint files, they must NOT run gradle (one build at the end by the main session).

## Build
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; & "$env:JAVA_HOME\bin\java.exe" -classpath ".\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain :app:assembleFossRelease --console=plain -q
```
(gradlew.bat is broken. `app/build/outputs` is not readable by the agent — user installs the APK.)

## Next steps
1. Read SimpleActivity + commons base painting; add the shared backdrop hook.
2. Vertical slice: MainActivity + recents/contacts/favorites + row card drawable. Build, bump version (1.0.1 / 10001), user verifies on phone.
3. Fan out (≤3 sonnet subagents, disjoint files): (a) Dialpad + SettingsDialpad preview, (b) CallActivity ×3 layout variants, (c) CallHistory + Settings + Conference + SpeedDial + in-repo dialogs.
4. Final build, version bump, commit on `feature/liquid-glass`, report out-of-scope screens.
