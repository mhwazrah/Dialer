# Handoff: Liquid Glass redesign (MHWDialer)

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
1. **Glass needs content behind it.** Refraction is invisible over flat colour / smooth gradients. On list screens make the list fill the screen and scroll *under* the top bar, tab bar and FABs (list padding + `clipToPadding="false"`; bars overlay the list in the same parent). MainActivity: `mainHolder` currently sits below `mainMenu` (appbar_scrolling_view_behavior) — must change so content passes under both bars.
2. **Real glass (`GlassPanel`, dynamic) only on the controls layer:** top/search bar, bottom tab bar, FABs, dialpad keys + call button, in-call buttons, accept/decline, dialogs/menus.
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
