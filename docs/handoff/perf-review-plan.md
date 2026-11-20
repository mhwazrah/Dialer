# MHWDialer perf/leak/integration plan (from 6-batch review)

## Corrections after advisor review (OVERRIDE the items below where they conflict)
- **Step 0 of every item:** re-read the cited lines and drop the finding if not reproducible. Only ~6 of 262 findings were verified by hand (M2-01, H5-01, A3-1/S6-01, C1-01, CallManager callback, D4-08 Settings cursor); the first review pass overstated one.
- **Process:** no per-item commits. Commit/push per phase only when the owner says so. Bump VERSION_NAME/VERSION_CODE on every build. Build/verify = `:app:assembleFossRelease` / `:app:installFossRelease` (see liquid-glass handoff for command), not assembleDebug/lint. No LeakCanary/StrictMode/5k fixture exist: verify with log counters, `dumpsys gfxinfo`, `dumpsys meminfo`, screenshots.
- **Emulator-testable** (adb emu gsm call/accept/cancel, outgoing CALL intent, hold, call waiting): single- and two-call paths of P1-12, P2-02, P2-03. **Real-device only:** conference split/merge (P1-06, P2-08), Bluetooth routing (P5-03), lock-screen PIN answer (P5-06). Also untestable on the API 35 emulator: targetSdk 37 behaviour, the English toggle (hidden on Android 13+).
- **Leftovers from Phase 1 (assigned):** P1-05 denied-permission toast -> P6; P1-09 try/catch inside notification-building callbacks -> P5-03; P1-11 blocked-number query off main -> P4-13; P1-13 CallHistory initContact re-run on every resume -> P4-16.
- **Observed on emulator:** a new outgoing call starts with Speaker shown ON -> P2-03 (speaker state from CallAudioState) must fix it.
- **P1-01:** do NOT prune notes on any load (even the full pass is capped by the query limit). Delete a note only when the user deletes that number's calls/history.
- **P1-04:** the same-DATE skip came in upstream 3.1.0 with no stated reason; row ids are unique, so replace it with dedupe on (DATE, NUMBER, TYPE) to keep protection against OEM duplicate rows without dropping distinct calls.
- **P3-01:** `directBootAware="true"` is set on 3 components -> create the Config singleton lazily (first access), not eagerly in `App.onCreate`.
- **P3-03:** no `markOwnWrite` time window (it would drop real changes). Make the cached-name/photo write idempotent (update only when the value differs) so self-triggered observer events stop after one debounced pass. Re-register observers after a runtime permission grant (registration currently swallows SecurityException).
- **P3-05..P3-08 number index:** index is a fast path only; on a miss fall back to the existing memoized `doesContainPhoneNumber` scan (commons matching rules, private contacts). `PhoneLookup` only as cold-start fallback for device contacts. (Reverses the earlier "left out" decision: owner informed.)
- **P4-08:** keep the pre-draw listener (commons is binary, no `updateColors` hook); only make its gate cheap (cache drawables, skip walk when state tag unchanged). Removing it risks the white-top / search-colour regressions.
- **P4-09:** confirm the swipe library exposes start/end callbacks before removing the per-row listener; otherwise keep it with a cheaper check.
- **P2-09:** `fallbackToDestructiveMigration()` already exists (AppDatabase.kt:33); do not add another wipe path.
- **Timer items (P1-07, P2-09, P4-15) stay:** timers are reachable from the call screen (CallActivity.kt:1385 call reminder).
- **Delay inventory addition:** `App.kt:128` timer-notification auto-hide after `timerMaxReminderSecs` = KEEP (user setting); `CallManager.kt:319` DTMF stop = same handling as C1-18 (single Handler, cancel previous). All 13 delay sites in source are now covered.
- **Upstream merges: NO (owner, 2026-11-20).** Fork diverges permanently (MySQL/MariaDB style). Edit upstream files directly; prefer the cleanest design over merge-friendliness. Dependencies updated to latest before Phase 1.

Source: evidence files `docs/handoff/review/batch1..6.md` (copied from scratchpad). 262 findings (C1 59, M2 38, A3 51, D4 38, H5 35, S6 41). Paths relative to `app/src/main/kotlin/com/goodwy/dialer`.
Convention: one work item = one commit (`fix:`/`perf:`/`refactor:`). Phases are dependency-ordered; each leaves the app buildable. `Closes` = primary owner; `Also` = partial overlap finished elsewhere. `(verify)` = depends on commons source / device behaviour, confirm before coding. Definition of done per item: `gradlew assembleDebug` + `lint` pass and the item's Verify steps pass on emulator (Android project: no pnpm).

## Root causes -> where fixed
| Root cause | Fixed by |
|---|---|
| Whole address book re-loaded 5x/resume, per call lookup, per recents row | P3-03..P3-08 (shared `ContactsRepository`) |
| `Context.config` allocs Config per access, `Gson()` per call | P3-01, P3-02 |
| Private-contacts cursor opened on main thread, unused/leaked | P2-01 (one helper), consumers in P3 |
| Per-keystroke main-thread dialpad search | P4-01, P4-02 |
| `notifyDataSetChanged`, per-bind alloc | P4-04..P4-07 |
| Per-frame pre-draw/global-layout/touch work | P4-08..P4-10 |
| Static CallManager state, camera singleton, callbacks | P2-02..P2-04 |
| Timer per-second DB+notification work | P4-15 (after P1-07 crash fixes) |
| ContentObserver gaps (contacts, call log), self-triggered reloads | P3-03 (observer ignores own writes) then P5-01/P5-02 |

## Delay inventory (all `postDelayed`/`delay`/sleep in review)
| Where | Disposition |
|---|---|
| M2-10 MainActivity 100 ms tab select | REPLACE: `viewPager.setCurrentItem(wantedTab,false)` before linking tabs, or `tabs.post{}` (P2-06) |
| D4-27 Dialpad `delay(1000)` clearInput | REPLACE: `clearOnReturn` flag consumed in `onResume` (P2-05) |
| C1-19 CallService 2000 ms full-screen fallback | KEEP, justified (no BAL-result API); store Runnable, cancel in `onCallRemoved/onDestroy` (P2-03) |
| M2-13 `runAfterAnimations` before full pass | REMOVE wait (P3-05) |
| M2-04 observer 400 ms | KEEP as debounce inside repository (burst coalescing) (P3-03) |
| C1-18 / D4-10 DTMF/tone min length | KEEP (deliberate min tone), single Handler + cancel previous (P2-03/P2-05) |
| D4-31 hide-dialpad 2000 ms, D4-26 long-press timeout | KEEP (UX timers), cancel in onDestroy/onPause (P2-05) |
| H5 `Thread.sleep` in MyCameraImpl | KEEP (SOS timing) |

## Shared design (P3-03, reused everywhere)
`helpers/ContactsRepository` (object, app-scoped, `applicationContext`):
- `@Volatile snapshot: Snapshot(contacts: List<Contact>, byNormalizedNumber: Map<String,Contact>, favorites: List<Contact>, searchKeys: List<SearchEntry>)` immutable, replaced atomically; built on a background thread (device contacts via `ContactsHelper.getContacts(getAll=true)` + private contacts via P2-01 helper only if `SMT_PRIVATE` not ignored).
- `refresh(force=false)`; `ensureLoaded(cb)`; `findByNumber(n)` (index, falls back to `PhoneLookup` single query when snapshot not loaded yet - needed for call process cold start); `addListener/removeListener` (UI-thread dispatch).
- `ContentObserver` on `ContactsContract.Contacts.CONTENT_URI` and `CallLog.Calls.CONTENT_URI`, registered once in `App.onCreate`, debounced 400 ms, marks dirty and notifies listeners; ignores events within a short window after our own writes (`markOwnWrite()` called by RecentsHelper writes). Activities/fragments reload only when dirty or version changed (`onResume` compares `version`).
- Contacts permission missing -> empty snapshot, no throw.

# Phase 1 - Data-loss and crash bugs (no new infrastructure)

### P1-01 Stop caller-note data loss
Files: fragments/RecentsFragment.kt, helpers/CallerNotesHelper.kt
Closes: M2-01
Fix: prune notes only after the FULL pass (`loadAll`), only if READ_CALL_LOG held, against the unfiltered call-number set (before blocked/private filters, never the cached/quick/empty list).
Risk L. Verify: >100 calls + note on an old number; open Recents, restart: note survives. Revoke call-log permission, open Recents: notes survive.

### P1-02 CallerNotesHelper hardening + in-memory cache
Files: helpers/CallerNotesHelper.kt, activities/CallActivity.kt (~1331,1351), activities/CallHistoryActivity.kt (233,243,259)
Closes: H5-18, C1-17, H5-11
Fix: `@Synchronized` read-modify-write; parsed list cached in the helper and written through; replace-by-key (`removeAll{ it.id.numberForNotes()==key }`) so a mismatched id no longer adds a duplicate; callers read the cache (no main-thread JSON parse). Uses shared Gson from P3-02 when it lands.
Risk L. Verify: add/edit/delete a note for a number on call screen and history screen; no duplicate entries in prefs.

### P1-03 Call-history delete removes only this number's calls
Files: activities/CallHistoryActivity.kt (1343-1376)
Closes: H5-01, H5-13
Fix: `filter { it.phoneNumber == current.phoneNumber }` (same predicate as `refreshCallLog`; becomes normalized compare in P3-08); delete caller note only when `showAll`; replace deprecated `onBackPressed()` with guarded `finish()` (`!isFinishing`).
Risk L. Verify: log has 5551 and 55512 + an unknown (empty-number) caller; delete history of 55512 -> others stay.

### P1-04 RecentsHelper read/write safety
Files: helpers/RecentsHelper.kt
Closes: M2-12, M2-19
Also: M2-16 (try/catch part; columns in P5-01)
Fix: loops use the `limit` parameter, drop `queryLimit` field; row dedupe by `id` (not `DATE`); `WRITE_CALL_LOG` check + try/catch `SecurityException` around delete/removeAll/bulkInsert.
Risk L. Verify: dual-SIM/imported rows with identical DATE both appear; revoke permission, swipe-delete: no crash; quick+full overlapping passes never truncate to 100.

### P1-05 Honour permission results (delete/call/SIM)
Files: adapters/RecentCallsAdapter.kt (459,628,1273), adapters/CallHistoryAdapter.kt (109), adapters/ContactsAdapter.kt (319,853), activities/CallHistoryActivity.kt (1343), extensions/CallExt.kt (76-82)
Closes: H5-02, A3-17, A3-23, C1-42
Fix: `handlePermission(X){ granted -> if (granted) action() else { selectedKeys.clear(); toast } }`; `launchCallIntent` branch only when granted.
Risk L. Verify: deny WRITE_CALL_LOG / WRITE_CONTACTS / READ_PHONE_STATE: no stale selected rows, contacts not removed from list, no crash.

### P1-06 Conference adapter stale position
Files: adapters/ConferenceCallsAdapter.kt (87-113)
Closes: C1-21
Fix: resolve index at click time (`data.indexOf(call)` / `bindingAdapterPosition`, bail on -1); end/split use it.
Risk L. Verify: 3-party conference, split rows in non-sequential order: right row removed, no IOOBE.

### P1-07 Timer crashes and orphan timers
Files: helpers/TimerHelper.kt, interfaces/TimerDao.kt, App.kt, receivers/TimerReceiver.kt, services/TimerService.kt
Closes: S6-20, S6-18, S6-24
Fix: nullable DAO result (`getTimer(id) ?: return`), `as? Running`, ignore `INVALID_TIMER_ID`; Start does `countDownTimers.remove(id)?.cancel()`, Restart posts `TimerEvent.Start(id, fullDuration)` instead of `.start()` on old object, Finish/Delete/Reset remove map entry; `TimerReceiver` uses `goAsync()` + `finish()` and sequences Reset before Start.
Risk M. Verify: start timer, delete during tick, double-tap pause, kill process then restart a Running timer: no crash, timer ticks and finishes.

### P1-08 No process kill after apply()
Files: activities/SettingsActivity.kt (326-332), activities/MainActivity.kt (221-224)
Closes: S6-12, M2-22
Fix: `commit()` (or `apply()` then recreate path) and restart via `finishAffinity()`+launch intent / `recreate()` like the `needRestart` path; remove `exitProcess/System.exit`.
Risk M. Verify: toggle "use English" and tabs/phone-number display during an active call and at idle: setting persists, call UI/notification unaffected.

### P1-09 CallContactHelper must always call back
Files: helpers/CallContactHelper.kt
Closes: C1-30, C1-31
Fix: `else { callback(callContact) }` for non-`tel:` handles; wrap body in try/catch that still calls back; build notification inside callback guarded by try/catch (error-notification fallback). Cursor handling moves to P2-01; lookup rewrite in P3-06.
Risk L. Verify: SIP/voicemail/private-number call shows notification and call UI; revoke READ_CONTACTS: notification still posts.

### P1-10 CallService / receiver crash guards
Files: services/CallService.kt (115), receivers/CallActionReceiver.kt (21), activities/CallActivity.kt (124,323,1125)
Closes: C1-63, C1-64, C1-68, C1-69
Fix: try/catch around `startActivity`; accept before starting activity in receiver, catch trampoline failure; `details.handle?.let`; guard `callCapablePhoneAccounts` with permission/try-catch.
Risk L. Verify: answer from notification with app in background; call with unknown handle; revoke READ_PHONE_STATE then hide dialpad in call: no crash.

### P1-11 DialerActivity / NotificationActivity stuck-transparent and decoding
Files: activities/DialerActivity.kt, activities/NotificationActivity.kt, extensions/CallExt.kt (107-124)
Closes: C1-39, C1-40, C1-41, C1-43
Fix: `finish()` on every exit path; `Uri.decode(schemeSpecificPart)` for blocked check and custom SIM key; `getHandleToUse` calls `callback(null)` when permission denied; `IntentCompat.getParcelableExtra` + read custom SIM once; NotificationActivity `?: run { finish(); return }`. Blocked-number query off main thread (see P4-13).
Risk L. Verify: `tel:%2B1555...` dial intent; denied READ_PHONE_STATE; missing extra: no blank screen left.

### P1-12 Telecom direction/state correctness
Files: services/SimpleCallScreeningService.kt, extensions/Call.kt, helpers/CallManager.kt (229-253,87-89)
Closes: C1-34, C1-38, C1-62
Fix: screening ignores `DIRECTION_OUTGOING` on Q+; `isOutgoing` treats UNKNOWN by state; remove disconnected calls BEFORE computing phone state; 3+ calls without conference parent return `TwoCalls(active, hold)` not `NoCall`.
Risk M. Verify: blocking on + dial from another app/car: outgoing works; 3 calls (2 held + 1 active): call screen stays.

### P1-13 Cross-thread list mutation (ConcurrentModification)
Files: activities/MainActivity.kt (cachedContacts/cachedFavorites), fragments/RecentsFragment.kt (allRecentCalls), activities/DialpadActivity.kt (1148-1153), activities/ManageSpeedDialActivity.kt (54-61), activities/CallHistoryActivity.kt (contact/duplicateContacts/initContact), dialogs/FilterContactSourcesDialog.kt
Closes: M2-07, D4-07, D4-21, H5-07, H5-17, S6-32
Fix: build a new local list on the bg thread, publish via `@Volatile var x: List<..>` (immutable snapshot); CallHistory `initContact` only when `contact == null`/needUpdate, local `val c = contact ?: return`, reset on load; FilterContactSourcesDialog: `@Volatile` flags + `synchronized` `processDataIfReady` + `isFinishing/isDestroyed` check. (MainActivity cache removed later by P3-04.)
Risk M. Verify: rapid typing in dialpad during contact refresh; open history repeatedly; no CME in logcat.

### P1-14 Adapter list consistency on delete/block
Files: adapters/RecentCallsAdapter.kt (354-373,466-487), adapters/ContactsAdapter.kt (326-346), adapters/CallHistoryAdapter.kt (127-136)
Closes: A3-16, A3-21, A3-24, A3-33
Fix: keep Date headers on delete (`currentList.toMutableList()` read on UI thread, drop orphan headers) or rely on refresh only; block removes all rows of blocked numbers and checks `addBlockedNumber` result; ContactsAdapter removes items + notifies together on UI thread after success; CallHistoryAdapter submits on UI thread.
Risk M. Verify: delete/block a group in Recents: day headers intact; delete contacts with and without permission.

### P1-15 Null / `!!` / index guards
Files: CallHistoryActivity.kt (447,1214,1394), SettingsDialpadActivity.kt/DialpadActivity.kt (`langPref!!`), adapters/ContactsAdapter.kt (364-386,147-181), RecentCallsAdapter.kt/CallHistoryAdapter.kt (blockReason), extensions/Context.kt (72-103), helpers/Config.kt (254-263), SettingsActivity.kt (1002-1055), CallActivity.kt (1445), RecentsFragment.kt (296-300)
Closes: H5-12, H5-14, H5-16, D4-35, A3-28, A3-31, A3-15, A3-45, S6-05, C1-55, M2-31
Fix: `data.path?.contains(..)==true`; guard `simList.isEmpty()`; try/catch chooser; `config.dialpadSecondaryLanguage ?: LANGUAGE_NONE` + `Locale.forLanguageTag`; capture number before async shortcut, id `contact_${rawId}`; `getItemWithKey` by `rawId`; `(blockReason ?: 0) != 0`; `getPhoneAccount(..) ?: return@forEachIndexed`; strip newlines in quick answers and guard indices; `connectTimeMillis == 0L`; version the recents cache key (clear on app-version change) so old Gson rows with null non-null fields never reach adapters.
Risk L. Verify: unit-level spot checks + open each screen with revoked permissions / no SIM.

# Phase 2 - Leaks and lifecycle

### P2-01 One background-only private-contacts helper
Files: extensions/Context.kt (new `loadPrivateContacts()`), CallContactHelper.kt, MainActivity.kt (1224-1227), ContactsFragment.kt (122-128), FavoritesFragment.kt (79-85), DialpadActivity.kt (149), SettingsDialpadActivity.kt (57,130), receivers/MissedCallReceiver.kt (72), CallHistoryActivity.kt (472-481), services/SimpleCallScreeningService.kt
Closes: M2-06, D4-08, H5-19, A3-43, H5-08
Also: C1-30 (cursor), C1-35 (close cursor; thread move in P4-13)
Fix: `fun Context.loadPrivateContacts(favoritesOnly, withNumbers): List<Contact>` runs on caller's BACKGROUND thread, returns empty if `SMT_PRIVATE` ignored or cursor null, opens + consumes + closes cursor (`use`); delete every eager `getMyContactsCursor` field/local (Settings field is unused, receiver one unused). CallHistory calls it synchronously on its bg thread before the null check (no `getContacts` round trip). (verify) `SimpleContactsHelper.existsSync` closes the cursor.
Risk L. Verify: StrictMode shows no main-thread query; open/close Settings dialpad repeatedly: no leaked cursor warnings; private contact shows in history on first load.

### P2-02 CallActivity teardown
Files: activities/CallActivity.kt (onDestroy; 549-570,681-733,806-824,1050,1094-1144,1599-1651,1774-1783,336-339,1824), helpers/CallNotificationManager usage
Closes: C1-01, C1-04, C1-06, C1-07, C1-57, C1-59, C1-60
Fix: `callDurationHandler.removeCallbacksAndMessages(null)` + stop loop when `call==null`; `CompositeDisposable` for RxAnimations disposed in onDestroy; replace self-re-arming arrow `ViewPropertyAnimator` with one `ObjectAnimator(repeat INFINITE)` started only while incoming UI visible, cancelled in onPause/onDestroy; notification refresh via `applicationContext` (service-driven in P5-03); assign `callContact` on UI thread + `isDestroyed` guard; `setIntent(intent)` + re-read `NEED_SELECT_SIM` in `onNewIntent`; proximity wake lock acquired without timeout and released in endCall/onDestroy.
Risk M. Verify: back out of an active call, LeakCanary/heap dump shows no CallActivity after destroy; second outgoing call needing SIM selection prompts; 70-minute-call lock is held (or simulate with short timeout).

### P2-03 CallManager / CallService state and teardown
Files: helpers/CallManager.kt, services/CallService.kt, receivers/CallActionReceiver.kt, activities/CallActivity.kt (998-1005)
Closes: C1-02, C1-05, C1-50, C1-51, C1-52, C1-18, C1-19
Fix: `CallManager.clear()` (calls, call, listeners, flags) used in `CallService.onDestroy` and on NoCall; `inCallService` nulled when `=== this`; per-call `Call.Callback` map unregistered in `onCallRemoved`; mute/speaker derived from `CallAudioState` (`isMuted`, `route==ROUTE_SPEAKER`), no forced unmute in CallActivity, drop `audioManager.isMicrophoneMute`; single main-looper `Handler` for DTMF stop with `removeCallbacks` before post; 2 s full-screen fallback kept in a field, cancelled in `onCallRemoved/onDestroy` (justified, see delay table).
Risk M. Verify: mute, reopen call screen from notification: stays muted and icon right; end call on speaker, next call speaker button turns it ON; rapid DTMF keys full length.

### P2-04 Camera / SOS singleton
Files: helpers/MyCameraImpl.kt, helpers/CameraFlash.kt, services/CallService.kt (36,121,136,145), activities/CallActivity.kt (317,374)
Closes: C1-03, H5-26, H5-27, H5-28, H5-29, H5-30
Fix: `CameraFlash(context.applicationContext)`; no `handleCameraSetup()` in `init`; cancel scope in `releaseCamera()`; call `stopSOS` only when SOS active; `toggleFlashlight` rethrows/returns Boolean so strobe loop stops on first failure and toasts once; cache max brightness; pick camera id with `FLASH_INFO_AVAILABLE`, compare id in torch callbacks; `@Volatile`/`AtomicBoolean.compareAndSet`, daemon thread. (C1-03 "verify commons" is moot: MyCameraImpl is local; duplicate of H5-26.)
Risk M. Verify: incoming call with SOS on: blink works, stops on answer; no camera: single toast; heap dump no CallActivity via CameraFlash.

### P2-05 Dialpad handlers, tone generator, delays
Files: activities/DialpadActivity.kt (182,215-243,1034,1140-1145,1405-1409,701), helpers/ToneGeneratorHelper.kt, activities/SettingsDialpadActivity.kt (58,132,1034-1044,1078..1515)
Closes: D4-09, D4-10, D4-11, D4-12, D4-26, D4-27, D4-30, D4-31
Fix: `ToneGeneratorHelper.release()` (removeCallbacks + `toneGenerator.release()`) called in onDestroy and before re-create; delete the unused Settings instance; single Handler field cancelled on each `startTone`; ringer-mode check without array; scroll listeners added once in onCreate; `longPressHandler`/`hideDialpadHandler` cleared in onPause/onDestroy; `clearOnReturn` flag replaces `delay(1000)`; Rx disposables collected and disposed.
Risk L. Verify: open dialpad 10x via back/forth: one scroll callback per event (log), ToneGenerator count stable; press dial then return: input cleared with no timer.

### P2-06 MainActivity lifecycle
Files: activities/MainActivity.kt (211-219,728-770,1295-1319)
Closes: M2-10, M2-15, M2-23, M2-32
Fix: select wanted tab via `viewPager.setCurrentItem(wanted,false)` before tab linking (no 100 ms `Handler()`); `updateState()` at end of onResume and make `mainCallGlass` non-clickable at alpha 0; `fragmentsInitialized` guard in `initFragments`; keep one `Disposable`, dispose previous + in onDestroy.
Risk M. Verify: cold start restores last tab with no flicker; open app from ongoing-call notification: return-to-call button visible; grant permission twice: single listener.

### P2-07 DialpadT9 initialised once, off main
Files: helpers/DialpadT9.kt, DialpadActivity.kt (275-279), SettingsDialpadActivity.kt (376-378,531-534,633-636), MainActivity.kt (1251-1256), App.kt
Closes: D4-13, M2-21
Fix: `DialpadT9.ensureInitialized(context)` using `bufferedReader().use`, guarded by `Initialized`, called once from `App.onCreate` on a background thread; remove the 5 copies.
Risk L. Verify: dialpad letters/T9 search work on first launch and after process restart.

### P2-08 Conference screen lifecycle
Files: activities/ConferenceActivity.kt, adapters/ConferenceCallsAdapter.kt
Closes: C1-22, C1-23, C1-24, C1-25
Fix: register `CallManagerListener` in onCreate/onDestroy, rebuild list from `CallManager.getConferenceCalls()` on state change and `finish()` on non-conference/NoCall; back = `finish()`; `activity.finish()` instead of `onBackPressed()`; `mutate()` before tinting layers.
Risk L. Verify: remote participant leaves: list updates; conference ends: screen closes.

### P2-09 Timer service / App / DB lifecycle and dead billing
Files: services/TimerService.kt, databases/AppDatabase.kt, App.kt, activities/SettingsActivity.kt (98-105), activities/SettingsDialpadActivity.kt (198-232)
Closes: S6-21, S6-25, S6-26, S6-27, S6-29, S6-14, D4-33
Fix: `stopForeground(REMOVE)` + `stopSelf()` on main thread, `isStopping` checked in posted runnable; `@Volatile db`, `fallbackToDestructiveMigration(true)`; seed row via `onCreate(db)` SQL (no extra executor, `applicationContext`); `DefaultLifecycleObserver`; remove `onTerminate`; delete `PurchaseHelper().checkPurchase(...)` (everything force-unlocked) and `setupPurchaseThankYou` re-runs.
Risk M. Verify: finish last timer: service stops (`dumpsys activity services`); fresh install seeds timer 1; Settings opens without billing connection.

### P2-10 Glide request and RecyclerView callback hygiene
Files: extensions/LiquidGlass.kt (52-59), extensions/RecyclerView.kt, extensions/Context.kt (200-222,397-407), dialogs/SelectSIMDialog.kt, SelectSimButtonDialog.kt
Closes: A3-3, A3-40, A3-44, S6-36
Fix: `Glide.with(view).clear(view)` before letter bitmap; `runAfterAnimations` callbacks guard `isAttachedToWindow` and use `postOnAnimation`; avatar callback invoked outside try, `get(5, SECONDS)` + `clear(target)` in finally; set dismiss listener inside the `setupDialogStuff` callback.
Risk L. Verify: fast scroll in Recents: no wrong avatars; notification avatar failure invokes callback once.

# Phase 3 - Shared data layer and observers

### P3-01 App-scoped Config singleton
Files: extensions/Context.kt (60), helpers/Config.kt (26-106,326), App.kt
Closes: A3-1, S6-01, S6-02, S6-04
Fix: `Context.config` returns one `Config` (created in `App.onCreate`, `applicationContext`); hoist `normalizeCustomSIMNumber(number)` out of lambda; legacy `remember_sim_` migration runs once (boolean pref) then deleted; `regionHint` not cached across SIM changes (read per call or companion lazy invalidated on SIM change); `recentOutgoingNumbers` returns a copy. Note: A3-1 rated High, S6-01 Med: SharedPreferences are live-shared so a singleton is safe; real win is per-instance state.
Risk L. Verify: change a setting in Settings, open Recents/Dialpad: new value visible without restart; custom SIM lookup fast (no `prefs.all` copy).

### P3-02 Process-wide Gson
Files: new `helpers/GsonProvider` (or top-level `val gson`), extensions/SharedPreferences.kt, helpers/Converters.kt, FavoritesFragment.kt (282), Config.kt, CallerNotesHelper.kt
Closes: A3-42, H5-33
Fix: single `Gson`; `Converters` reused/lazy `gsonTimer`; catch `Exception`, null-check result; `?: arrayListOf()` for favorites order.
Risk L. Verify: SIM handle model and favourites order round-trip.

### P3-03 ContactsRepository + ContentObservers (core)
Files: new helpers/ContactsRepository.kt, App.kt, activities/MainActivity.kt (211-219,293-295,1258-1262)
Closes: M2-03, M2-04, D4-17
Fix: implement design above. MainActivity registers as listener; `onResume` reloads only when repository `version` changed or dirty; remove MainActivity's private `callLogObserver` and EventBus double refresh (EventBus `RefreshCallLog` only marks dirty; refresh happens once when resumed). Observer events from our own call-log writes ignored (prevents M2-05 loop).
Risk M. Verify: edit a contact in system Contacts while app backgrounded: updated on return, one load (log count); end a call: one recents refresh, not two.

### P3-04 Main/Contacts/Favorites on the repository
Files: MainActivity.kt (cacheContacts/refreshFragments/cacheFavorites), fragments/ContactsFragment.kt, FavoritesFragment.kt, MyViewPagerFragment.kt
Closes: M2-02, M2-27, M2-28, M2-29, M2-37
Fix: fragments read `snapshot` (Contacts: apply ignored-source filter; Favorites: `starred==1`); delete `cacheContacts()` calls and `MainActivity.cachedContacts`; empty-favorites branch updates adapter + dynamic shortcuts; set sort once in MainActivity then one refresh; `onConfigurationChanged` no reload; placeholder-permission grant calls `refresh()`.
Risk M. Verify: resume = 1 contacts load (was 5, check `getContacts` call count log); remove last favourite: strip/shortcuts update.

### P3-05 Recents pipeline on repository
Files: helpers/RecentsHelper.kt, fragments/RecentsFragment.kt (281-324,504-522)
Closes: M2-13, M2-17, M2-18
Fix: `getRecentCalls` takes contacts from the snapshot (no own `getContacts`); build `byNormalizedNumber` index once (no per-number linear scan); SIM maps, voicemail list, blocked numbers computed once per `getRecentCalls`; `@Volatile refreshGeneration` drops stale results; callback invoked inside `runOnUiThread`; full pass starts straight after quick pass (no `runAfterAnimations` wait); blocked filter applied before the SQL limit window is filled (or over-fetch).
Risk M. Verify: rapid resume/observer events never show older data over newer; quick+full yields consistent list.

### P3-06 Call-contact lookup via index/PhoneLookup with per-call cache
Files: helpers/CallContactHelper.kt, helpers/CallNotificationManager.kt, adapters/ConferenceCallsAdapter.kt (56), receivers/MissedCallReceiver.kt (75-78), extensions/Context.kt (421-434)
Closes: C1-10, C1-11, C1-32, C1-33, C1-20, H5-21
Fix: `getCallContact` = `ContactsRepository.findByNumber` (index, else single `PhoneLookup` incl. organisation/nickname for that contact), compare with `PhoneNumberUtils.compare`, label via same comparator; per-`Call` cache cleared in `onCallRemoved`; delete unused `numbersToContactIDMap`; voicemail via `telecomManager.isVoiceMailNumber`/`PhoneNumberUtils.compare`; ConferenceCallsAdapter resolves all participants once before `setAdapter` and re-checks holder tag in callback; MissedCallReceiver/`getContactFromAddress` read name+photo from the one lookup (drop the two extra `PhoneLookup` queries and the third any-pass).
Risk M. Verify: incoming call from contact with 2nd number: name+type label correct; log shows 0 full contact loads during a call.

### P3-07 Dialpad on repository
Files: activities/DialpadActivity.kt (164,1148-1153,1502-1610)
Closes: D4-14, D4-15, D4-16
Fix: `allContacts` = repository snapshot (+ listener for changes); `updateNamesIfEmpty` uses `byNormalizedNumber` (all numbers, not only first); `recentOutgoingNumbers` derived from the already-loaded `calls`; pipeline callback via `runOnUiThread`, `@Volatile allRecentCalls`, `isDestroyed` guards.
Risk M. Verify: add contact from dialpad "add number" flow: appears in search on return; unnamed calls from a contact's 2nd number get named.

### P3-08 CallHistory and dialogs on repository, normalized compare
Files: activities/CallHistoryActivity.kt (85-138,356,1111,1356), adapters/RecentCallsAdapter.kt (489-494), dialogs/FilterContactSourcesDialog.kt, dialogs/SelectContactDialog.kt, dialogs/FilterContactSourcesDialog
Closes: A3-18, H5-04, H5-05, S6-33, S6-34
Fix: RecentCallsAdapter takes a `contactsProvider: () -> List<Contact>` lambda (no `as MainActivity/DialpadActivity` casts) and uses the number index; CallHistory: no reload-all on resume, history for one number via `Calls.CONTENT_URI` `NUMBER=?`/normalized compare only when "All" tapped, `PhoneNumberUtils.compare` in all filters; FilterContactSourcesDialog counts via `groupingBy{source}.eachCount()` on the snapshot (no deep copy), compares `config.ignoredContactSources` (verify `getVisibleContactSources` return type) and skips callback when unchanged.
Risk M. Verify: same person `+4670..` vs `070..` shows one history; filter dialog opens instantly; OK without change does not reload.

# Phase 4 - Performance on top of the shared layer

### P4-01 DialpadT9 conversion
Files: helpers/DialpadT9.kt (78-101)
Closes: D4-05, D4-06
Fix: StringBuilder with single map lookup; cached sorted `getSupportedSecondaryLanguages()` invalidated in `readFromJson`.
Risk L. Verify: T9 output identical for en/ru/ar samples (compare before/after).

### P4-02 Dialpad search index, off-main, debounced
Files: activities/DialpadActivity.kt (1140-1267), helpers/ContactsRepository.kt (`SearchEntry`)
Closes: D4-01, D4-02, D4-03, D4-04, D4-18, D4-19
Fix: repository precomputes per-contact `searchKeys` (deduped T9/digit strings) once per load; dialpad filters with `keys.any{contains(text)}` + phone check on a single-thread executor, results posted; empty text or `searchContactsInDialpad=false` skips filter/sort/adapter; `callCounts` computed once when `allRecentCalls` changes; collator/lang cached in fields; sort keys precomputed (`ArrayList(list)` not cast); recents filter skipped when recents list hidden; text-change dispatch coalesced via one replaced Runnable (handles formatter double-fire; verify watcher order).
Risk M. Verify: 5k-contact fixture: key-press to list update < 1 frame jank (Systrace/`dumpsys gfxinfo`), empty input does zero work, results identical to before.

### P4-03 List search without per-keystroke regex/normalise
Files: fragments/ContactsFragment.kt, FavoritesFragment.kt, RecentsFragment.kt (343-368), dialogs/SelectContactDialog.kt, extensions/FastScrollerView.kt
Closes: M2-25, M2-26, H5-31, A3-41
Fix: top-level `Regex` constant; use repository `searchKeys` for contacts, per-call normalised number computed once; initials via `mapNotNullTo(HashSet())` (first code point); fast-scroller lambda reads adapter's current list (setup once); debounce typing.
Risk L. Verify: search in Contacts/Select dialog identical results, smooth typing.

### P4-04 RecentCallsAdapter bind cost
Files: adapters/RecentCallsAdapter.kt, extensions/LiquidGlass.kt (66-82), extensions/String.kt (8-18), extensions/Context.kt (106-112)
Closes: A3-2, A3-4, A3-8, A3-9, A3-10, A3-11, A3-12, A3-19, A3-20, A3-46
Fix: `LruCache<String,Bitmap>` for letter avatars keyed (letter,color) at `contact_photo_big_size` reuse Paints (also used by Contacts/Conference adapters); LruCache for geocoded location; format number once per bind (lazy) and share one bind body between both holders; build tinted placeholder drawables once per adapter (`constantState.newDrawable()`); `isLast = position == itemCount-1`; today/yesterday codes + formatted dates cached per list; swipe colors computed once per bind; `useSwipeToAction`/`areMultipleSIMsAvailable` cached in fields (refresh onResume); `onViewRecycled` branches on holder type; selection helpers compute once.
Risk M. Verify: scroll 1000-row log with GPU profile: no GC storms; screenshots identical.

### P4-05 RecentCallsAdapter list updates and DiffUtil
Files: adapters/RecentCallsAdapter.kt (139,167,294-308,371,410,502-512,529,1325-1348), adapters/CallHistoryAdapter.kt (190,197,243), models/CallLogItem.kt
Closes: A3-6, A3-7, A3-13, A3-14, H5-32, A3-32
Fix: blocked numbers loaded on bg thread (init + after block/unblock), try/catch SecurityException; drop `notifyDataSetChanged()` after `submitList` (highlight part of `areContentsTheSame` or commit-callback range change); restore scroll state only on first load/top insert; diff compares blockReason, contactID, isVoiceMail, company/nickname/jobPosition, features, simColor, full group; Date id from local `dayCode`; remove no-op `getAdjustedSimColor` map, precompute date/duration strings in list builder.
Risk M. Verify: block a number: row icon updates without reload; typing in dialpad search: no flicker.

### P4-06 ContactsAdapter
Files: adapters/ContactsAdapter.kt
Closes: A3-22, A3-25, A3-26, A3-27, A3-29, A3-30
Fix: ListAdapter/DiffUtil keyed by `rawId` (no deep `hashCode` compare, no double notify); action-mode toggles use `notifyItemRangeChanged` of visible range; binding created in `onCreateViewHolder`; `mutate()` + cached letter colours + `as?`; normalised search text and language resolved once in `updateItems`; `isCustomOrderSelected` written once per drag; block/unblock calls on bg thread with SecurityException handling (verify commons).
Risk M. Verify: 5k contacts: enter/leave selection no full rebind; search + delete + drag reorder still work.

### P4-07 Small adapters
Files: adapters/FilterContactSourcesAdapter.kt, adapters/SpeedDialAdapter.kt
Closes: A3-34, A3-35
Fix: read selection at click time, `bindingAdapterPosition` + NO_POSITION guard, cache colours; selection key = `speedDial.id`.
Risk L. Verify: fast double-tap on sources; speed-dial selection survives edit.

### P4-08 MainActivity per-frame work
Files: activities/MainActivity.kt (283-318,733-735,799-863,873-908), activities/SimpleActivity.kt (39-45), fragments/FavoritesFragment.kt (129-132,244)
Closes: M2-08, M2-09, M2-35, M2-24, M2-38, M2-11
Fix: remove pre-draw listener, run `clearMainMenuPaint/styleGlassSearchField` when commons repaints (hook `updateColors` call sites) with flag/tag gate before drawable creation; `scrollChange()` only in `onPageSelected`/IDLE; `updateListInsets` early-returns when computed values unchanged and caches views; `setupColors`/page transformer/backdrop skipped when (text,primary,accent,transformer,theme) unchanged; keep LayoutManager unless view type changed. (verify) `applyLiquidBackdrop` cost.
Risk M. Verify: `dumpsys gfxinfo` janky frames while swiping tabs; theme/colour change still applies.

### P4-09 Glass panels and swipe-row listeners
Files: extensions/LiquidGlass.kt (97-137), views/GlassPanel.kt
Closes: A3-36, A3-37, A3-38
Fix: drive swipe-action visibility from `SwipeGestureListener` callbacks + return-animation end (no pre-draw listener per row); static panels skip `applyLiquidGlass` and `backdropSource`; `pressable` evaluated at apply time; on API 31/32 treat CPU-blur path as static glass or disable dynamic background; load `nativegauss` off main. (verify on API 31/32 device; liquid-glass handoff doc rule: frosted yes, readable content behind bars no.)
Risk M. Verify: no `OnPreDrawListener` per row (heap/`viewTreeObserver`), glass visuals unchanged.

### P4-10 CallActivity per-event work
Files: activities/CallActivity.kt (150,624-671,1004-1005,1515-1518)
Closes: C1-14, C1-15, C1-16
Fix: wallpaper blur on background thread then set; update drawable only when state bucket changes and change layout width only when changed; re-apply mute only when differing from `CallAudioState`, touch route popup only when showing.
Risk L. Verify: incoming-call UI first frame not delayed; slider drag smooth.

### P4-11 Call notification build cost
Files: helpers/CallNotificationManager.kt, helpers/CallContactAvatarHelper.kt
Closes: C1-12, C1-66
Fix: `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`; channels created once in init; avatar/large icon cached per call, single circular pass; centre-crop square before drawing, real default size, recycle temp bitmap, `ImageDecoder`/Glide instead of `MediaStore.getBitmap`.
Risk M. Verify: tap notification action while it updates: works; avatar round and undistorted for non-square photo.

### P4-12 Recents cache storage
Files: helpers/Config.kt (237-244), fragments/RecentsFragment.kt (296-298,511), activities/SettingsActivity.kt (1511-1517), activities/SettingsDialpadActivity.kt (1232-1241), activities/CallHistoryActivity.kt (137)
Closes: M2-14, S6-03, S6-17, D4-34, H5-06
Fix: move cache out of main prefs into its own file/prefs (`recents_cache`); parse on background thread then post; write only after full pass and not while searching; SIM-colour change just invalidates cache + `needUpdateRecents`; CallHistory initShowAll counts on bg thread.
Risk M. Verify: cold start shows cached rows quickly; changing a setting no longer rewrites 200-row blob (file size/time).

### P4-13 Main-thread I/O sweep
Files: activities/MainActivity.kt (192-194), CallHistoryActivity.kt (215,1311-1331), SettingsActivity.kt (350-352,1072-1106,1292-1491), services/SimpleCallScreeningService.kt, activities/DialerActivity.kt, extensions/Context.kt (SIM labels)
Closes: M2-34, H5-10, S6-08, S6-09, C1-35, C1-36, S6-07, S6-11
Fix: wrap BlockedNumberContract/getBlockedNumbers/addBlockedNumber in `ensureBackgroundThread` + try/catch SecurityException (single-number check via `BlockedNumberContract.isBlocked`); import read+decode on bg thread; export `openOutputStream(uri,"wt")`; screening work on worker thread then `respondToCall`, `use{}` cursor, read `recentOutgoingNumbers` once; SIM list/`areMultipleSIMsAvailable` computed once per resume and passed down.
Risk M. Verify: StrictMode DiskRead/Network clean for these screens; large import does not ANR; screening still blocks within timeout.

### P4-14 Settings and dialpad-settings re-bind, pref writes
Files: activities/SettingsActivity.kt (108-202 + 28 `needRestart` sites), activities/SettingsDialpadActivity.kt (129,156,235-238,754-1489), activities/DialpadActivity.kt (148,173-420), App.kt (37-42)
Closes: S6-06, S6-13, S6-28, D4-28, D4-29, D4-32
Fix: one-time listener setup moves to onCreate, onResume refreshes only external-state parts; `needRestart` reserved for structural changes, others set a lightweight `refreshAdapters` flag read in fragments' onResume; seekbar persist in `onStopTrackingTouch`; Dialpad static setup in onCreate, drop `onRestart` + duplicate `speedDialValues` loads; App isPro writes guarded by `if (!isPro)`.
Risk M. Verify: Settings returns instantly; changing swipe/relative-date setting reflects in Recents without MainActivity relaunch or tab reset.

### P4-15 Timer per-second work
Files: App.kt (102-113,145-158), services/TimerService.kt, models/TimerState.kt, models/TimerEvent.kt
Closes: S6-19, S6-22, S6-30
Fix: persist absolute `endTimeMillis` on Start/Pause; remaining derived from it (also fixes total duration and elapsed-while-dead); no DB write per tick; notification uses `setUsesChronometer(true).setChronometerCountDown(true).setWhen(end)`; channel created once in `onCreate` (single channel id).
Risk M. Verify: 5-min timer: DB writes only at start/pause/finish; app killed and reopened mid-timer shows correct remaining; finish fires on time.

### P4-16 CallHistoryActivity load and rebuild
Files: activities/CallHistoryActivity.kt (167-168,604-876,668-669,916-917)
Closes: H5-03, H5-09, H5-15
Fix: single `refreshCallLog` (drop `loadAll` double pass); social actions loaded once per source on bg thread, passed to video+messenger setups; five messenger blocks collapse to one loop; remove empty `ensureBackgroundThread{runOnUiThread{}}` hops.
Risk L. Verify: open history of a contact with WhatsApp/Signal: rows identical, one query per source.

### P4-17 Misc
Files: MainActivity.kt (608-651), DialpadActivity.kt (1308,1634)
Closes: M2-33, D4-37, D4-38
Fix: snapshot favourites on UI thread, compare ids only for shortcuts; `initCall` reuses `recentsHelper` with `updateCallsCache=false` + `isDestroyed` guard; `toHashSet` deletion filter.
Risk L. Verify: shortcuts not rewritten when unchanged; redial-last-number keeps cache intact.

# Phase 5 - Integration polish

### P5-01 Call-log write paths
Files: helpers/RecentsHelper.kt (449-543), activities/SettingsActivity.kt (1072-1095)
Closes: M2-05, M2-16, S6-10
Fix: cached name/photo update dedupes by number per pass, matches `NUMBER=?` (and `COUNTRY_ISO IS NULL OR =?`), `applyBatch`, calls `ContactsRepository.markOwnWrite()`; restore/import write PHONE_ACCOUNT_ID/COMPONENT_NAME, COUNTRY_ISO, NUMBER_PRESENTATION, FEATURES, CACHED_PHOTO_URI, BLOCK_REASON, `NEW=0`/`IS_READ=1`, CACHED_NAME only when name != number (extend `RecentCall` fields); import dedupes (number+date+type), checks `bulkInsert` count, toasts success only if >0. (verify M2-05(a): actual COUNTRY_ISO state of rows on device.)
Risk M. Verify: delete+undo missed call keeps SIM and read state; import same file twice: no duplicates; no observer-triggered reload loop.

### P5-02 Missed-call and read/new lifecycle
Files: receivers/MissedCallReceiver.kt, activities/NotificationActivity.kt, extensions/Context.kt (114-127), helpers/RecentsHelper.kt (472-497), AndroidManifest.xml (336-344)
Closes: M2-20, A3-48, H5-20, H5-22, H5-23, H5-24, H5-25
Also: C1-39 (read part)
Fix: `goAsync()` with `finish()` after `notify`; counts derived from CallLog (`NEW=1 AND TYPE=MISSED`) per number, not process memory; content intent routes through `NotificationActivity` which marks only that number read, cancels its notification, updates group/badge via one shared helper; swipe delete marks only that number; `MISSED_CALL_CANCEL` moved to non-exported receiver; mark-read selection `(NEW=1 OR IS_READ=0) AND TYPE=?`, `WRITE_CALL_LOG` guard, skip when unread count 0; channel created only if absent; null number keeps label out of dialed/looked-up value and disables call-back/message actions; remove main-looper hop before `notify`.
Risk M. Verify: miss 3 calls from 2 numbers, restart process, swipe one: other notification + log flag intact; tap body clears that number; foreign app cannot clear via broadcast.

### P5-03 Call notification stays in sync
Files: services/CallService.kt (125-130), receivers/CallActionReceiver.kt, helpers/CallNotificationManager.kt
Closes: C1-13, C1-65
Fix: `onCallAudioStateChanged` calls `setupNotification()`; mute read from `audioState.isMuted`; remove ad-hoc `CallNotificationManager(context)` instances (single instance in service); delete `if(false)` block, emoji labels, `setTimeoutAfter(-1)`; pass `forceFullScreen` through fallback.
Risk M. Verify: connect/disconnect Bluetooth mid-call: notification icons follow; TalkBack reads action labels cleanly.

### P5-04 Permission and revocation handling
Files: AndroidManifest.xml (206-216,270-280,330-334), activities/CallActivity.kt (862-864,1030), activities/ManageSpeedDialActivity.kt (86-173), fragments/RecentsFragment.kt (98-104,435)
Closes: C1-37, C1-53, D4-20, D4-22, M2-30
Fix: (verify) direct-boot: `config` reads in CallService/CallActivity/CallActionReceiver use defaults when `!isUserUnlocked` (or drop `directBootAware` if not needed); Bluetooth permission requested only when user opens the chooser, once per call; ManageSpeedDial returns after add dialog, checks `hasPermission` without prompting and requests only when voicemail chosen, voicemail item only updates adapter on success + SecurityException caught; recents placeholder text set from current permission inside `gotRecents`, cache not rendered without READ_CALL_LOG.
Risk M. Verify: incoming call before first unlock after reboot; deny BLUETOOTH_CONNECT: one request; revoke call-log permission: correct placeholder.

### P5-05 Contact intents, matching and timer channel
Files: extensions/ActivityExt.kt (54-90,206-220), activities/DialpadActivity.kt (1049-1061), extensions/Context.kt (256-285)
Closes: A3-49, A3-50, D4-36, A3-47
Fix: `ContactsContract.Contacts.getLookupUri(contactId, lookupKey)`, bail on blank key, drop `CLEAR_TASK`, resolve installed package once; wa.me link built from E.164 via libphonenumber (toast on failure); `onNewIntent` re-runs `checkDialIntent`, strip `;`/`?` params via `schemeSpecificPart`; timer notification channel created once per (sound, vibrate), never deleted per notification, `timer.id!!` guarded.
Risk L. Verify: tap company-only contact photo opens QuickContact; national number to WhatsApp; second `tel:` intent while dialpad open.

### P5-06 Audio mode and keyguard
Files: activities/CallActivity.kt (1711-1714,129,1787-1801)
Closes: C1-58, C1-61
Fix: stop setting `AudioManager.mode` (Telecom owns it) or reset only on NoCall; remove duplicate `FLAG_*`/`FULL_WAKE_LOCK` and unconditional `requestDismissKeyguard` (request only when an action needs unlock). (verify on device: lock-screen answer, speaker/BT routing, call waiting.)
Risk H (behavioural). Verify: lock-screen incoming call with PIN: answer works, call-waiting audio intact, no credential prompt until an action needs it.

# Phase 6 - Low-severity cleanup

### P6-01 Dead code removal
Files: ConferenceActivity.kt, CallActivity.kt (53-58,98,213-215), RecentsFragment.kt (370-410,715-727), dialogs/ShowGroupedCallsDialog.kt, models/Events.kt, helpers/Constants.kt, models/Timer.kt, SettingsActivity.kt (locked-feature branches, Rx shakes), ActivityExt.kt (179-246)
Closes: C1-26, C1-67, M2-36, H5-35, S6-40, S6-15, A3-51
Fix: delete unused imports, `dialpadHeight` + its layout listener, `updateSearchDialpadResult`, `BottomSpaceDecoration`, `ShowGroupedCallsDialog`, unused events/constants (grep first), unreachable `pro` branches; remove recommendation dialogs (owner: confirm intended, fork's own build).
Risk L. Verify: build + lint clean, grep shows 0 references.

### P6-02 Call UI polish and deprecated callers
Files: activities/CallActivity.kt (1507,1778,1630-1632), activities/DialerActivity.kt (82-94)
Closes: C1-54, C1-56, C1-44
Fix: `Call.STATE_DIALING` instead of reject-reason constant; crop with float aspect ratio after `doOnLayout`; Activity Result API instead of `onActivityResult`, log instead of empty catch.
Risk L. Verify: avatar background fills screen correctly on 20:9 device; dial-intent chooser result still handled.

### P6-03 Hoist regex / String helpers
Files: extensions/String.kt (22-33)
Closes: A3-5
Fix: `numberForNotes()` / `removeNumberFormatting()` via `filterNot { it.isWhitespace() || it in "+()-" }` (or constant `Regex`).
Risk L. Verify: output identical for sample numbers.

### P6-04 Input, model and dialog polish
Files: extensions/EditText.kt, dialogs/AddSpeedDialDialog.kt, activities/ManageSpeedDialActivity.kt (83-189), models/SpeedDial.kt, activities/SettingsActivity.kt (1145-1205), services/TimerService.kt (159-172), dialogs/SelectSimButtonDialog.kt, dialogs/ChooseSocialDialog.kt, dialogs/ManageVisibleTabsDialog.kt, dialogs/ChangeSortingDialog.kt, services/MyTileService.kt, helpers/CallHistoryTopBehavior.kt
Closes: A3-39, D4-23, D4-24, D4-25, S6-16, S6-23, S6-35, S6-37, S6-38, S6-39, S6-31, H5-34
Fix: map `#` explicitly + DOWN/UP with `uptimeMillis`; trimmed non-empty speed-dial number, drop unused `dialog` field, voicemail prompt only for voicemail; keep one SpeedDial adapter + persist in onPause; local vals instead of `type!!`; subsequent-calls switch also clears `groupAllCalls` (verify switch visible in layout); merge duplicate `if`, validate numeric label; per-button contrast + dp padding; `sortedBy`; toast/needRestart for tab reset; `val config` + skip callback if unchanged; tile reads `blockingEnabled` once; compare `screenWidthDp` not pixels.
Risk L. Verify: each dialog opens/saves as before; build + lint.

# Excluded
- S6-41 (Gson `object` singleton identity): code already uses `is` checks, reviewer says no change required; no action.

# Coverage table (finding → work item; +x = partial overlap)
**C1**: 1→P2-02 | 2→P2-03 | 3→P2-04 | 4→P2-02 | 5→P2-03 | 6→P2-02 | 7→P2-02 | 10→P3-06 | 11→P3-06 | 12→P4-11 | 13→P5-03 | 14→P4-10 | 15→P4-10 | 16→P4-10 | 17→P1-02 | 18→P2-03 | 19→P2-03 | 20→P3-06 | 21→P1-06 | 22→P2-08 | 23→P2-08 | 24→P2-08 | 25→P2-08 | 26→P6-01 | 30→P1-09(+P2-01) | 31→P1-09 | 32→P3-06 | 33→P3-06 | 34→P1-12 | 35→P4-13(+P2-01) | 36→P4-13 | 37→P5-04 | 38→P1-12 | 39→P1-11(+P5-02) | 40→P1-11 | 41→P1-11 | 42→P1-05 | 43→P1-11 | 44→P6-02 | 50→P2-03 | 51→P2-03 | 52→P2-03 | 53→P5-04 | 54→P6-02 | 55→P1-15 | 56→P6-02 | 57→P2-02 | 58→P5-06 | 59→P2-02 | 60→P2-02 | 61→P5-06 | 62→P1-12 | 63→P1-10 | 64→P1-10 | 65→P5-03 | 66→P4-11 | 67→P6-01 | 68→P1-10 | 69→P1-10

**M2**: 1→P1-01 | 2→P3-04 | 3→P3-03 | 4→P3-03 | 5→P5-01 | 6→P2-01 | 7→P1-13 | 8→P4-08 | 9→P4-08 | 10→P2-06 | 11→P4-08 | 12→P1-04 | 13→P3-05 | 14→P4-12 | 15→P2-06 | 16→P5-01(+P1-04) | 17→P3-05 | 18→P3-05 | 19→P1-04 | 20→P5-02 | 21→P2-07 | 22→P1-08 | 23→P2-06 | 24→P4-08 | 25→P4-03 | 26→P4-03 | 27→P3-04 | 28→P3-04 | 29→P3-04 | 30→P5-04 | 31→P1-15 | 32→P2-06 | 33→P4-17 | 34→P4-13 | 35→P4-08 | 36→P6-01 | 37→P3-04 | 38→P4-08

**A3**: 1→P3-01 | 2→P4-04 | 3→P2-10 | 4→P4-04 | 5→P6-03 | 6→P4-05 | 7→P4-05 | 8→P4-04 | 9→P4-04 | 10→P4-04 | 11→P4-04 | 12→P4-04 | 13→P4-05 | 14→P4-05 | 15→P1-15 | 16→P1-14 | 17→P1-05 | 18→P3-08 | 19→P4-04 | 20→P4-04 | 21→P1-14 | 22→P4-06 | 23→P1-05 | 24→P1-14 | 25→P4-06 | 26→P4-06 | 27→P4-06 | 28→P1-15 | 29→P4-06 | 30→P4-06 | 31→P1-15 | 32→P4-05 | 33→P1-14 | 34→P4-07 | 35→P4-07 | 36→P4-09 | 37→P4-09 | 38→P4-09 | 39→P6-04 | 40→P2-10 | 41→P4-03 | 42→P3-02 | 43→P2-01 | 44→P2-10 | 45→P1-15 | 46→P4-04 | 47→P5-05 | 48→P5-02 | 49→P5-05 | 50→P5-05 | 51→P6-01

**D4**: 1→P4-02 | 2→P4-02 | 3→P4-02 | 4→P4-02 | 5→P4-01 | 6→P4-01 | 7→P1-13 | 8→P2-01 | 9→P2-05 | 10→P2-05 | 11→P2-05 | 12→P2-05 | 13→P2-07 | 14→P3-07 | 15→P3-07 | 16→P3-07 | 17→P3-03 | 18→P4-02 | 19→P4-02 | 20→P5-04 | 21→P1-13 | 22→P5-04 | 23→P6-04 | 24→P6-04 | 25→P6-04 | 26→P2-05 | 27→P2-05 | 28→P4-14 | 29→P4-14 | 30→P2-05 | 31→P2-05 | 32→P4-14 | 33→P2-09 | 34→P4-12 | 35→P1-15 | 36→P5-05 | 37→P4-17 | 38→P4-17

**H5**: 1→P1-03 | 2→P1-05 | 3→P4-16 | 4→P3-08 | 5→P3-08 | 6→P4-12 | 7→P1-13 | 8→P2-01 | 9→P4-16 | 10→P4-13 | 11→P1-02 | 12→P1-15 | 13→P1-03 | 14→P1-15 | 15→P4-16 | 16→P1-15 | 17→P1-13 | 18→P1-02 | 19→P2-01 | 20→P5-02 | 21→P3-06 | 22→P5-02 | 23→P5-02 | 24→P5-02 | 25→P5-02 | 26→P2-04 | 27→P2-04 | 28→P2-04 | 29→P2-04 | 30→P2-04 | 31→P4-03 | 32→P4-05 | 33→P3-02 | 34→P6-04 | 35→P6-01

**S6**: 1→P3-01 | 2→P3-01 | 3→P4-12 | 4→P3-01 | 5→P1-15 | 6→P4-14 | 7→P4-13 | 8→P4-13 | 9→P4-13 | 10→P5-01 | 11→P4-13 | 12→P1-08 | 13→P4-14 | 14→P2-09 | 15→P6-01 | 16→P6-04 | 17→P4-12 | 18→P1-07 | 19→P4-15 | 20→P1-07 | 21→P2-09 | 22→P4-15 | 23→P6-04 | 24→P1-07 | 25→P2-09 | 26→P2-09 | 27→P2-09 | 28→P4-14 | 29→P2-09 | 30→P4-15 | 31→P6-04 | 32→P1-13 | 33→P3-08 | 34→P3-08 | 35→P6-04 | 36→P2-10 | 37→P6-04 | 38→P6-04 | 39→P6-04 | 40→P6-01 | 41→EXCL

Totals: 262 findings; 261 assigned to 60 work items; 1 excluded.

# Conflicts / doubts between reviewers
1. A3-1 (High) vs S6-01 (Med) on `config`: allocation is cheap, real cost is discarded per-instance state (`regionHint`, migration scan); fixed once in P3-01 either way.
2. C1-03 claims commons caches the Context; H5-26 shows `MyCameraImpl`/`CameraFlash` are local and statically retain the Context. H5-26 is the confirmed one; C1-03 merged (P2-04).
3. M2-05 (own COUNTRY_ISO update triggers observer loop) must be handled by P3-03 "ignore own writes" BEFORE P5-01 changes the write path; order enforced.
4. M2-04 suggests dropping EventBus refresh; plan keeps EventBus as a dirty-mark only (CallActivity still posts it).
5. M2-07 (volatile snapshot) vs P3-04 (repository replaces the cache): P1-13 applies the cheap volatile fix; P3-04 deletes it.
6. M2-13/D4-16 overlap with P3 pipeline rewrite: fixed in P3-05/P3-07 not Phase 1 to avoid doing it twice.
7. C1-19: reviewer wants BAL result instead of timer; no such callback exists, so timer kept (cancellable).
8. Items flagged (verify): C1-37, C1-35, A3-30, A3-37, A3-38, A3-43, D4-18, M2-05(a), M2-38, S6-16, S6-34; P5-06 and P2-03 need on-device confirmation.

## Phase 1 Status (implemented, :app:assembleFossRelease green, not committed)
- P1-01 done: pruning removed (RecentsFragment, DialpadActivity, `removeCallerNotes` deleted); a note is deleted only on "clear history" for that number (CallHistoryActivity, showAll). Recents swipe/multi-delete and "clear all" keep notes (a number can still have other calls).
- P1-02 done: CallerNotesHelper singleton, synchronized, cached against the raw pref string, replace-by-key; `Config.parseCallerNotes` removed.
- P1-03 done: exact-number filter, note only on showAll, guarded finish().
- P1-04 done: `limit` used, `queryLimit` field dropped, dedupe on (DATE, NUMBER, TYPE), WRITE_CALL_LOG check + SecurityException guards.
- P1-05 done: deny clears selection (RecentCalls/CallHistory/Contacts adapters), CallHistoryActivity, `callContactWithSim` only on grant. Toast on deny not added (no suitable string verified).
- P1-06 done (real-device test pending): index resolved at click time.
- P1-07 done: null-safe `getTimer` (callback skipped if missing), Start/Restart/Pause/Finish/Delete/Reset manage the map, Restart posts Start, receiver uses goAsync; Reset removed from the receiver Start path (it raced Start and deleted one-shot timers).
- P1-08 done: no exitProcess/System.exit; English toggle sets needRestart + recreate(); MainActivity tab/phone-number change uses the existing restart path.
- P1-09 done: always calls back; notification-body try/catch inside callbacks NOT added (callbacks run on a bg thread; existing fallbacks unchanged).
- P1-10 done. P1-11 done (blocked-number query still on main thread -> P4-13). P1-12 done (real-device test pending).
- P1-13 done: volatile immutable snapshots (MainActivity, DialpadActivity, ManageSpeedDial, CallHistory contact/duplicates, RecentsFragment), FilterContactSourcesDialog synchronized. CallHistory `initContact` re-run policy left as is.
- P1-14 done. P1-15 done (recents cache invalidated per VERSION_CODE; `dialpadSecondaryLanguage` now non-null).

## Phase 2 Status (implemented, :app:assembleFossRelease green, not committed)
- P2-01 done: `Context.loadPrivateContacts()` / `lookupContactSync()` (extensions/Context.kt) open the cursor lazily, skip it when SMT_PRIVATE is ignored (`includeIgnored=true` only for Recents/Dialpad "hide private calls" and the source-filter dialog, which need the data exactly because the source is ignored); all eager/unused `getMyContactsCursor` sites removed; CallHistory loads the private contact synchronously on its bg thread.
- P2-02 done: duration handler cleared, Rx disposables, one infinite ObjectAnimator per arrow (cancelled on touch/answer/destroy, restarted on a new ringing call), contact assigned on UI thread with isDestroyed guards, onNewIntent re-reads NEED_SELECT_SIM, proximity lock held until released. Notification refresh via the activity removed (service-driven).
- P2-03 done: shared Call.Callback (unregistered in onCallRemoved), `CallManager.clear(service)` on NoCall/onDestroy (listeners left to their owners), speaker/mute derived from CallAudioState (no cached statics, no forced unmute), notification refreshed on real audio change, single Handler for DTMF stop and the 2 s full-screen fallback (cancelled).
- P2-04 done: applicationContext, no camera setup in init, stopSOS no-op if SOS never started, toggleFlashlight returns Boolean (strobe stops on first failure), cached max brightness, flash-capable camera id + id check in torch callbacks, AtomicBoolean strobe guard, daemon thread, scope cancelled in releaseCamera.
- P2-05 done: ToneGeneratorHelper.release(), single stop Runnable, scroll listeners in onCreate, handlers cleared, `clearOnReturn` replaces delay(1000), Settings tone instance deleted. Not reproduced: hideDialpadHandler (no such handler/2000 ms in current code).
- P2-06 done: wanted tab selected with the pager before first layout (no timer), fragmentsInitialized guard, updateState at end of onResume, call button non-clickable without a call, single Disposable.
- P2-07 done: `DialpadT9.ensureInitialized` (synchronized, bufferedReader.use), warmed from App on a bg thread; screens still call it (cheap no-op, blocks only if the warm-up is still running).
- P2-08 done (real-device test pending): listener-driven list, finish on <2 participants, back = finish(), mutate() before tinting.
- P2-09 done: TimerService stops on main (stopSelf, isStopping), db @Volatile + seed row via SQL in onCreate callback, App uses DefaultLifecycleObserver and no onTerminate, checkPurchase removed from both Settings screens (updatePro() called once). `fallbackToDestructiveMigration` untouched.
- P2-10 done: Glide clear before letter avatar, runAfterAnimations attached-guard + postOnAnimation, blocking Glide loads use get(5s)+clear and callback outside try, dismiss listener set inside setupDialogStuff callback.

## Orchestration state (keep updated; survives context compaction)
- Owner directives (2026-11-20): implement ALL phases, advisor reviews each phase and its issues are fixed, test everything testable on emulator, no report back until DoD (all issues fixed, clean code, advisor-reviewed, build green, e2e tests executed and passing). Commit only when owner says.
- Dependencies updated to latest (AGP 9.5.1, Gradle 9.9.2, Kotlin 2.4.21, KSP 2.3.14, commons 7712ef3eff, SwipeActionView 94ab9268eb, compile/target SDK 37, etc.). Build green.
- Phase 1: implemented, advisor-reviewed, review fixes applied (CallManager lone-disconnected-call ordering, removeRecentCalls callback, Pause on main thread, getDuplicateContacts callback, ManageSpeedDial null handle). Emulator-tested: tabs, incoming call remote hang-up closes UI, outgoing call local hang-up closes UI.
- Phase 2: implemented, advisor-reviewed. Emulator-tested: two calls (active + held via outgoing while active), end active -> stays on held call with correct duration, end last -> UI closes; mute on/off reflected by system mute icon. Speaker toggle NOT testable (emulator supports only the speaker route; telecom shows ActiveSpeakerRoute always) -> real device.
- Emulator recipes: incoming `adb emu gsm call N` / `gsm accept N` / `gsm cancel N`; outgoing `adb shell am start -a android.intent.action.CALL -d tel:N` (MHWDialer set as "Always"); end button at (673,2555), mute (357,1538), speaker (988,1538), tabs y=2812 x=255/673/1089 on 1344x2992. Second INCOMING call while active is not delivered by this emulator's modem; use outgoing-while-active for two-call tests. Helper scripts: scratchpad shot.ps1/tap.ps1.
- Known emulator noise: ART/JIT SIGSEGVs in system_server, Gboard and occasionally the app (one NoSuchFieldError in RecentsFragment coincident with a JIT SIGSEGV, not reproducible in 6 retries). If the same NoSuchFieldError recurs -> treat as real R8 bug and stop.
- Checkpoints: scratchpad/checkpoints/phaseN.patch (+ .untracked.tar from Phase 2 on).
- Next: Phase 3 split in two runs: 3a = P3-01..P3-03 (Config singleton lazy, Gson, ContactsRepository + observers; NO eager load at App start: observers only mark dirty; load on first consumer request), build+smoke; 3b = P3-04..P3-08 consumers.

## Phase 3a Status (implemented, :app:assembleFossRelease green, not committed)
- P3-01 done: `Config.getInstance` lazy singleton (applicationContext), `Context.config` unchanged; regionHint read per call, legacy `remember_sim_` keys migrated once (flag `legacy_sim_keys_migrated`), `recentOutgoingNumbers` returns a copy.
- P3-02 done: `helpers/GsonProvider.kt` (`gson`), all `Gson()` sites replaced; `Converters.jsonToStringList` null-safe, favourites order parsed with the shared gson.
- P3-03 done: `helpers/ContactsRepository` (lazy load, debounced dirty/version, listeners, observers registered from App, re-registered via `onPermissionGranted`); MainActivity uses it instead of its private observer. Fragments/dialpad/call history not migrated yet (3b).
- Phase 3a: implemented + advisor-reviewed; review fixes applied by orchestrator: ensureLoaded waits while loading (no stale snapshot), call-log changes notify only (no contact reload; RefreshCallLog -> markDirty(contactsChanged=false)), empty/no-permission/failed snapshot stays dirty, cold-start findByNumber falls back to private contacts, index also keyed by value.normalizePhoneNumber() (commons normalizedNumber is E.164), legacy SIM-key migration never drops unreadable keys. Measured (TEMP-PERF log counters, tag MHWPERF, remove before sign-off): launch recents loads=4, after call+resume=6, idle=0 (no observer loop).
- Open items for sign-off: cold start with each default-tab setting + open from missed-call notification; fresh-data launch proves the timer seed row (Phase 2 P2-09); contact-edit -> exactly 1 snapshot load; incoming call in cold process -> 0 snapshot loads, caller name shown; remove TEMP-PERF lines.

## Phase 3b Status (implemented, :app:assembleFossRelease green, not committed)
- P3-04 done: Contacts/Favorites read the repository snapshot (`Context.listedContacts`: visible-source filter, merge-duplicates, sort); MainActivity.cacheContacts/cachedContacts removed; resume refreshes lists only when repository version / needUpdateRecents / list-settings key changed; sort set once in MainActivity (fragment `startNameWithSurnameChanged` removed); onConfigurationChanged no reload; empty favourites updates adapter + shortcuts; permission grant -> `onPermissionGranted` (listener refreshes once). Snapshot now flags private favourites (starred=1).
- P3-05 done: RecentsHelper uses `ensureLoaded` + `Snapshot.find` (index, scan fallback, per-pass miss memo); RecentsFragment: generation guard, UI-thread callback, no runAfterAnimations wait, skips reload when version/settings unchanged, shared `updateRecentOutgoingNumbers`. Not done: SIM/voicemail/blocked maps hoisted per getRecentCalls (still per getRecents; only 1 call in the full pass), blocked filter before SQL limit.
- P3-06 done: getCallContact via `findByNumber` (no snapshot load), label via PhoneNumberUtils.compare; MissedCallReceiver one lookup (name+photo from the contact), `getContactFromAddress` deleted; ConferenceCallsAdapter holder-tag recheck. Not done: per-Call cache (lookup is index/PhoneLookup now), voicemail via isVoiceMailNumber (existing loop kept).
- P3-07 done: Dialpad contacts from repository + listener/version reload, names via index, outgoing numbers derived from loaded calls (second full query removed), UI-thread callbacks.
- P3-08 done: RecentCallsAdapter uses `ContactsRepository.snapshot.find` (no activity casts), CallHistory compares with PhoneNumberUtils.compare, FilterContactSourcesDialog counts via snapshot, fixed always-true change check, reloads repository only when the private source toggles. ManageSpeedDial also on the repository. Not done: CallHistory call-log query by NUMBER (stays in P4-16).
- Phase 3b: implemented + advisor-reviewed; orchestrator fixes: cold launch double load (checkContactPermissions only marks dirty on a NEW grant), caller ID across formats (PhoneLookup miss -> blocking shared snapshot load + commons matching), findByNumber trusts only a clean settled snapshot and on a miss also tries PhoneLookup (provider notifies ~4-6 s late), contacts observer on ContactsContract.AUTHORITY_URI, search-time changes applied when search closes, private-contact snapshot invalidated on resume, favourites honour showOnlyContactsWithNumbers again (snapshot fetches no-number contacts only for favourites). Measured: cold launch snapshot=1 recents=2; warm resume 0/0; after call+resume 0/2; idle 0/0; cold incoming exact format 0 loads, other format 1 load, name shown; contact added 2 s before a call -> name shown; star fg/bg -> 1 load, strip updates.
- Phase 4 plan: 4a dialpad+search (P4-01..P4-03), 4b adapters (P4-04..P4-07), 4c per-frame+glass (P4-08..P4-10, compare light/dark screenshots), 4d rest (P4-11..P4-17). Also assign 3b leftovers: SIM/voicemail/blocked maps once per getRecentCalls + blocked filter before limit -> 4d (P4-12/P4-13 area); per-Call contact cache in getCallContact -> 4d (P4-11).
- Perf baseline: seed 1000 contacts + 300 calls (scratchpad seed.sh), measure on the pre-Phase-4 build (v1.0.23): dumpsys gfxinfo for dialpad typing and Recents/Contacts scrolling.

## Phase 4a Status (implemented, :app:assembleFossRelease green, not committed)
- P4-01 done: DialpadT9.convertLettersToNumbers uses one StringBuilder + one map lookup per char; getSupportedSecondaryLanguages cached, invalidated in readFromJson.
- P4-02 done: Snapshot.searchKeys(language) (per snapshot+language, warmed on the dialpad load thread) + ListSearchKeys; dialpad search runs on a single-thread executor, keystrokes coalesced through one replaced Runnable, stale results dropped by generation, no contact filter/sort/adapter work on empty input or contact search off, callCounts cached per recents list, collator cached. Deliberate deviation: contact search off + empty contact result no longer makes the recents list appear when showRecentCallsOnDialpad is off.
- P4-03 done: toSearchQuery() (hoisted regex), shared Snapshot.listSearchKeys (built with the snapshot) + matchesSecondaryFields for Contacts/Favorites/Select-contact, numeric query normalised once, initials counted by first code point via mapNotNullTo(HashSet), FastScroller set up once and reads the adapter list (library re-indexes on adapter notify/swap), Recents T9 language hoisted out of the per-row filter, RecentCall.doesContainPhoneNumber normalises once. Skipped: typing debounce (filters no longer normalise per keystroke), name-to-display key (depends on name display settings, derived per search).

## Phase 4b Status (implemented, :app:assembleFossRelease green, not committed, not installed)
- P4-04 done (partial): shared LruCache letter avatars (letter,colour,size) in LiquidGlass.kt; geocode LruCache in String.kt; one shared `bindCallRow` for plain+swipe rows; isLast by identity; no-op getAdjustedSimColor removed; today/yesterday codes computed once per submit. Skipped: placeholder drawable cache (colour filter on shared constantState risks visual change), per-list date/duration string precompute (cheap vs risk), swipe colours once per bind.
- P4-05 done: blocked numbers loaded on bg thread with SecurityException handling (init, updateItems, after block/unblock); notifyDataSetChanged removed (highlight change = ranged notify after the diff commits); areContentsTheSame = RecentCall data-class equality; scroll state not restored while the user is scrolling.
- P4-06 done: ContactsAdapter stays on MyRecyclerViewAdapter (drag reorder mutates the list) but updateItems uses DiffUtil by rawId; action-mode enter/exit = ranged notify with payload; binding created once per holder (WeakHashMap); search normalisation/language once per highlight change; isCustomOrderSelected written once at drag end; block/unblock on bg thread with SecurityException handling (isContactBlocked is commons callback API, untouched).
- P4-07 done: filter-sources click reads selection at click time + NO_POSITION guard + cached colours; speed-dial selection key = id.

## Phase 4c Status (implemented, :app:assembleFossRelease green, not committed, not installed)
- P4-08 done: pre-draw listener kept but cheap: clearMainMenuPaint walks only when commons repainted the menu (menu background non-null), styleGlassSearchField compares a cached glass constantState (no getDrawable per frame), updateListInsets early-returns on an unchanged (insets, heights, fragments) value, scrollChange only on page selected/idle, fragment setupColors skipped when (text, primary, accent, background, fragments) unchanged, page transformer only when the setting changed, LiquidBackdrop re-applied only if theme changed or background replaced, Favourites keeps its LayoutManager unless the view type changed.
- P4-09 done (partial): SwipeActionView (94ab9268eb) has no start/cancel/return callbacks (only onSwipedActivated/Left/Right/Complete), so the per-row pre-draw listener stays, but only touches the action views when the swiping state flips. Static GlassPanels no longer run applyLiquidGlass or set backdropSource (all effects off instead). nativegauss loaded on a bg thread from App (SDK < 33). Not done: API 31/32 CPU-blur treatment (needs a device).
- P4-10 done: wallpaper blur on a bg thread (once per activity), slider icon/width only on change, mic button only when mute state changed, route chooser with create=false just dismisses (no item build, no BT permission prompt).
- Phase 4a/4b/4c: implemented (agents), installed v1.0.24. Orchestrator fixes: swipe action colours visible at rest (listener compared a remembered state; adapter re-shows views on rebind -> compare real visibility); snapshot build retry once (unconfirmed cause). Advisor review of 4a-c IN PROGRESS: reproduce missing-names-under-churn after a version bump; review DialpadActivity/ContactsRepository searchKeys/RecentCallsAdapter/ContactsAdapter.updateItems/CallActivity route chooser diffs; functional tests (dialpad search, list search, multiselect, swipe delete, block, drag reorder, call answer/decline, settings font/grouping/tap action, call history, filter chips); clean perf runs x3 with seed stopped and call-log rows; churn min interval 2-3 s; 4b skips (placeholder constantState, date strings per submit) to do or list as Excluded; TEMP-PERF logs (some print names) must be removed at sign-off. Native crashes seen in app process so far: 2 (JIT/main SIGSEGV), both emulator-runtime, not reproducible.
- Behaviour change to report: dialpad no longer shows Recents when contact search is off and "show recents on dialpad" is off.
- Phase 4a-c review fixes (orchestrator): Recents refresh coalescing (one refresh at a time, queued follow-up, change-driven refreshes skip the quick pass) fixed names vanishing under contact churn after a cache invalidation (full pass was always superseded); repository notifications min 2 s apart; snapshot retry removed (cause was elsewhere). Under churn: recents loads 28 -> 10. Route chooser: dismiss-on-audio-change matches original behaviour (original also dismissed), not a regression. ContactsAdapter.updateItems diff measured < 1 ms at 478 contacts (stays on main). Dialpad T9 search verified (254 -> Ali*, Ahmed Ali, Sara Khan via digits-only name, same as old code). Perf (478 contacts, 336 calls, 3 runs, emulator software rendering, noisy): contacts scroll p90 34-38 ms, recents p90 27-36 ms, dialpad matching p90 32-40 ms vs no-match 28-32 ms -> remaining dialpad cost is drawing, not search. Report leads with load counters.
- Still to test after 4d (functional e2e): multiselect+exit, swipe delete recent, block/unblock, favourites drag reorder persists, half-swipe release, answer+decline with on-screen controls, Settings font size / call grouping / tap action apply on return, call history screen, Recents filter chips, dark theme via app theme setting, top/tab bar pixel diff, Arabic-name dialpad search.

## Phase 4d run 1 Status (implemented, :app:assembleFossRelease green, not committed, not installed)
- P4-11 done: notification PendingIntents UPDATE_CURRENT|IMMUTABLE; channels created once per manager; avatar decoded once per photo (cache cleared on cancel), single circular pass, centre-crop square, temp bitmap recycled, real default size, no MediaStore.getBitmap.
- P4-12 done: recents cache in its own prefs file `recents_cache` (lazy, old keys removed from main prefs); Recents/Dialpad persist only after the full pass; Recents cache parsed on bg then painted only if nothing newer arrived; SIM-colour change just clears the cache + needUpdateRecents; CallHistory initShowAll on bg. Coalescing logic untouched.
- P4-13 done: blocked-number reads/writes off main with SecurityException handling (DialerActivity, MainActivity first-launch, SettingsActivity count, CallHistory, CallActivity block, adapter add/delete); screening service decides on a worker then respondToCall (recentOutgoingNumbers read once); call-history import/export on bg, export opens "wt"; Settings SIM availability once per resume. Skipped: SIM-list hoisting in Dialpad/SettingsDialpad (one cheap binder call per setup, would touch many call sites).
- 3b leftovers done: RecentsHelper SIM maps/voicemail/blocked numbers once per getRecentCalls; blocked rows dropped inside the loop with a 3x over-fetch (can still come up short with heavy blocking); per-Call contact cache cleared in CallManager.onCallRemoved/clear.
- 4b leftovers: placeholder drawables use a per-adapter constantState cache with per-bind newDrawable().mutate() and per-bind tint (tint-once not used: colour filters are not carried by constantState, not verifiable without install); RecentCallsAdapter date/duration strings memoised per submitted list.

## Phase 4d run 2 Status (implemented, :app:assembleFossRelease green, not committed, not installed)
- P4-14 done: Settings binds all rows once in onCreate; onResume rebinds only when (colours, pro, SIM count) changed, otherwise refreshes the thank-you row + blocked count. `needRestart` kept only for English, speech-to-text, tab navigation style, search bar, top-bar colour, icon tabs, floating-button style (+ dialpad-settings sites). All list/row display settings (font, dividers, thumbnails, ellipsize, colours, SIM colours, number format, relative date/date format, swipe options, click actions, nickname, slide animation) now apply through `displaySettingsKey()` compared on MainActivity/Dialpad resume: adapters are rebuilt (data, tab and search kept). SettingsDialpad: one bind, no onRestart/unused speedDialValues, seekbars persist on stop (immediately for +/- buttons). Dialpad: listeners in onCreate, no onRestart/duplicate speed-dial loads, SIM availability read once per resume; App isPro writes guarded.
- P4-15 done: Running(duration, endTime); no DB write per tick (write at start/pause/finish only); countdown is a single callback; service notification uses chronometer countdown; channel created once. CallActivity reminder inserts the row, then posts Start from the insert callback (redundant Delete removed).
- P4-16 done: one refreshCallLog (unused loadAll pass removed); social actions loaded once per source on bg and shared by video + messenger rows; five messenger blocks -> one; empty bg/UI hops removed; initContact only when stale (first resume, after edit/add/open-with, repository version change). Not done: call-log query by NUMBER (RecentsHelper API change).
- P4-17 done: shortcut change check compares (id, name, photo) keys; redial reuses recentsHelper (updateCallsCache=false) + isDestroyed guard; deletion filters use HashSet.

## Phase 5 Status (implemented, :app:assembleFossRelease green, not committed, not installed)
- P5-01 done: cached name/photo fixes are built per (number, country) only when the log row differs (never cache name == number), applied in one `applyBatch`, match `NUMBER=? AND (COUNTRY_ISO IS NULL OR =?)`; no markOwnWrite. Restore/import writes account id + component, country, presentation, features, photo, block reason, NEW=0/IS_READ=1, CACHED_NAME only when name != number (RecentCall gained 4 optional fields); dedupes on (date, number, type) against the log and within the file; callback gets the inserted count, success toast only if > 0. Not reproduced / not done: M2-05(a) COUNTRY_ISO state on device; there is no delete-undo caller of restore.
- P5-02 done: counts from the call log (`getNewMissedCallCounts`, NEW=1 AND TYPE=MISSED per number, "" = hidden), `goAsync()` in MissedCallReceiver, `MissedCallCancelReceiver` (not exported) replaces the exported MISSED_CALL_CANCEL action, body tap routes through NotificationActivity (`MISSED_CALL_OPEN`, no CLEAR_TASK) and marks only that number read, shared `clearMissedCallsOf`/`syncMissedCallsState` (badge + group from the log); mark-read selection `(NEW=1 OR IS_READ=0) AND TYPE=?`, WRITE_CALL_LOG guard, skipped when nothing unread; channel created only if absent; hidden number: no call-back/message actions and no lookup; no main-looper hop. Addition: `clearMissedCalls()` (Recents opened) now also cancels all missed-call notifications.
- P5-03 done: service refreshes through `setupNotification()` on audio change; speaker action no longer builds an ad-hoc manager (mute/speaker both refresh via the service); `if(false)` block, emoji labels, `setTimeoutAfter(-1)` removed; forceFullScreen passed to the fallback; P1-09 try/catch inside all three callback builders with fallback chain New -> Old -> CallStyle fallback (API 31+) / bare notification; outer catches no longer call cancelNotification (it deactivated the fallback). Left: MainActivity's `CallNotificationManager(this).cancelNotification()`.
- P5-04 done (partly decisions): `directBootAware` REMOVED from CallActivity, CallService, CallActionReceiver (config/prefs unreadable before first unlock; revert = re-add the 3 attributes; not verified on device); BT permission asked once per call screen; ManageSpeedDial returns after the add dialog, no prompt when opening, READ_PHONE_STATE asked only when voicemail is picked, voicemail row only updated on success (SecurityException caught); Recents placeholder text from the current permission whenever shown, cache not rendered without READ_CALL_LOG. Skipped: C1-53 line 1030 (mute button code, not permission related).
- P5-05 done: QuickContact via `Contacts.getLookupUri(contactId, key)`, blank key bails, no CLEAR_TASK, contacts package resolved once; wa.me from E.164 (`Config.regionHint()`), toast on failure; DialpadActivity `onNewIntent` + `tel:` parsed with schemeSpecificPart (`;`/`?` stripped); timer channel stable per (sound, vibrate), created once, never deleted, no per-notification timer row write, `timer.id` guarded.
- P5-06 done (HIGH risk, real-device verify): AudioManager.mode no longer written (neither IN_CALL nor NORMAL); lock-screen flags: setShowWhenLocked/TurnScreenOn on API 27+ (window flags only on 26), KEEP_SCREEN_ON kept, FLAG_DISMISS_KEYGUARD, `requestDismissKeyguard` and the FULL_WAKE_LOCK removed. Relies on the system asking for the PIN when an action starts another activity.
- Also (4d run 2 leftover) done: CallHistoryActivity loads one number's calls with `RecentsHelper.getRecentCallsOfNumber` (logged NUMBERs filtered by `isSameNumber`, then `NUMBER IN (...)`), no global recents list.

## Phase 6 Status (implemented, :app:assembleFossRelease green, not committed, not installed)
- P6-01 done: dead code removed (ShowGroupedCallsDialog + commented caller, RecentsFragment `updateSearchDialpadResult`/`BottomSpaceDecoration`, CallActivity `dialpadHeight` + layout listener + 2 unused imports, Events `StateChanged`/`StopStroboscope`/`StopSOS`, `ObfuscatedTimer`, constants `MISSED_CALL_NOTIFICATION_ID`/`SHOW_ALL_RECENT_IN_HISTORY`/`ALWAYS_SHOW_FULLSCREEN` + Config `alwaysShowFullscreen`). C1-26 not reproduced (import already gone). S6-15: the six unreachable "locked feature" else-branches (Rx shake + snackbar, SIM-colour fallback) and the RxAnimation imports removed from SettingsActivity; the always-true `pro` conditionals and locked-label helper are left. A3-51: the three promo dialogs (`launchSendSMSIntentRecommendation`, `startContactDetailsIntentRecommendation`, `newAppRecommendation`) removed, call sites use `launchSendSMSIntent` / `startContactDetailsIntent`. Kept: `MIN_BRIGHTNESS_LEVEL`, `DEFAULT_BRIGHTNESS_LEVEL`, `NEED_SELECT_SIM`, `CURRENT_RECENT_CALL(_LIST)` (still referenced).
- P6-02 done (partial): `Call.STATE_DIALING` instead of the reject-reason constant (same value 1); avatar crop uses float aspect ratio (guards windowHeight == 0, width coerced to 1..bg.width). C1-56 `doOnLayout` not added: the existing `windowWidth != 0` guard already skips the crop before layout. C1-44: empty catch narrowed to `ActivityNotFoundException`; `onActivityResult` kept because `launchSetDefaultDialerIntent` is commons (binary) and starts the request with `REQUEST_CODE_SET_DEFAULT_DIALER`.
- P6-03 done: `numberForNotes()` / `removeNumberFormatting()` use `filterNot` (no Regex).
- P6-04 done: EditText `addCharacter` maps `#` explicitly, inserts unknown chars as text, sends DOWN+UP with `uptimeMillis`; AddSpeedDialDialog trims, unused `dialog` field/hint removed; ManageSpeedDial keeps one adapter (`notifyDataSetChanged`), persists in onPause, typed number no longer offers "show voicemail icon" (passes false like the contact path); SpeedDial.getName local vals; group-subsequent switch (always `gone` in the layout, no code made it visible) removed from SettingsActivity + layout; TimerService duplicate `if` merged (label is the call-contact number, no validation needed); SelectSimButtonDialog per-button contrast + dp padding; ChooseSocialDialog `sortedBy`; ChangeSortingDialog `val config` and no callback if nothing changed; MyTileService reads `blockingEnabled` once; CallHistoryTopBehavior breakpoint compares dp.
- P1-05 leftover done: toast `com.goodwy.commons.R.string.no_permission` when delete (calls/history/contacts) is cancelled by a denied permission (RecentCallsAdapter, CallHistoryAdapter, ContactsAdapter, CallHistoryActivity). Block flow has no permission step (default-dialer check already toasts).

## Excluded / deferred (with reason)
- S6-41: Gson `object` singleton identity; code already uses `is` checks.
- S6-38 (tab-reset feedback): needs a new string; the change is already picked up through MainActivity's storedShowTabs comparison. Left as is.
- C1-44 (Activity Result API): blocked by commons-owned `launchSetDefaultDialerIntent` (request code in a binary library). Empty catch narrowed only.
- C1-56 (`doOnLayout`): not needed, see Phase 6 Status.
- P4-09 API 31/32 CPU-blur treatment: needs a device on that API level.
- P4-03 typing debounce and name-to-display key: no longer needed after the search-key work (per-keystroke normalisation removed).
- P4-13 SIM-list hoisting in Dialpad/SettingsDialpad: one cheap binder call per setup, would touch many call sites.
- 3b leftover: voicemail detection via `isVoiceMailNumber` (existing loop kept); blocked-row filter before SQL limit uses 3x over-fetch and can still come up short with heavy blocking.
- P5-01 M2-05(a) COUNTRY_ISO state: not reproduced / needs device; P2-05 hideDialpadHandler: not present in current code; P5-04 C1-53 line 1030: mute button code, not permission related.
- P5-03 leftover: `CallNotificationManager(this).cancelNotification()` in MainActivity.onResume kept as a stale-notification safety net when no call exists.
- Real-device only (unchanged): conference split/merge (P1-06, P2-08), Bluetooth routing (P5-03), lock-screen PIN answer (P5-06), speaker toggle (P2-03), `directBootAware` removal (P5-04).
- Phases 4d, 5, 6 implemented. Phase 5 emulator-tested: missed-call counts from call log survive process kill; swipe one notification clears only that number; body tap opens app + clears that number; foreign MISSED_CALL_CANCEL broadcast ignored; screen-off incoming call wakes + shows call UI; vertical slider answer (drag up) / decline (drag down) / end button work; blocked number incoming rejected, outgoing to blocked refused, normal outgoing works; Remind-me timer: chronometer countdown notification, end time survives force-stop (notification title empty = pre-existing upstream).
- FINAL CHECKLIST (advisor, in order): 1 install Phase 6 + smoke; 2 caller note survives reload/restart, clear-history keeps longer/hidden numbers; 3 Call History screen (Omar, All, header vs old screenshot), Settings + Dialpad settings scroll/toggle (check activity_settings.xml dangling layout_below), font size + tap action apply on return without relaunch, dialpad keys * # + backspace; 4 idle loop counters 0 for 10 s + call log cached names correct; 5 pm clear + role DIALER + no contacts perm -> grant -> contacts appear, reminder works; 6 multiselect, swipe delete, block UI, drag reorder, filter chips, default tabs, missed-call tap tab, dark theme via app setting; 7 PIN lock (locksettings set-pin 1234) incoming: UI over keyguard, answer w/o PIN, stays locked after; 8 reboot + gsm call before unlock (directBootAware removal) - if nothing rings restore attrs and fix pre-unlock crashes; static: lintFossRelease NewApi in touched files; remove TEMP-PERF/MHWPERF + updateItemsInner wrapper; final rebuild+reinstall+smoke+call.
- FINAL VERIFICATION (v1.0.32, all TEMP-PERF logging removed, lint baseline not added): checklist items 1-8 executed on emulator. Fixes found during final e2e and applied: hidden-number calls vanished from Recents (blocked filter matched empty numbers) -> skipped for unknown numbers; clear-history deleted a different number ending in the same digits (00000111 vs 0500000111 via PhoneNumberUtils.compare 7-digit match) -> isSameNumber uses libphonenumber EXACT/NSN match; permission granted in system Settings not picked up until restart -> MainActivity compares a permissions key on resume, re-runs fragment setup; wrong menu/title on cold start when the start tab differs (lost with the removed 100 ms delay) -> refreshMenuItems after first layout; SettingsDialpadActivity indentation. Verified: caller note survives reload+restart; clear-history removes only that number (other number + hidden call kept); idle 10 s 0 loads; call-log cached names correct (contact named, unknown null); fresh pm clear -> contacts appear after grant, reminder timer works; multiselect + exit; swipe delete removes exactly 1 row; favourites drag reorder persists after restart; filter chips; dark theme via app setting; font size applies without relaunch; dialpad * # + backspace; PIN lock: call UI over keyguard, Accept works, phone stays locked after; reboot before first unlock: system dialer handles the call (directBootAware removal - behaviour change, answerable); lint: no NewApi errors.
- FINAL ROUND 2 (v1.0.35): advisor's last blocking items done. Fixes: stale tab title/menu when the contacts-permission dialog was dismissed without an answer (page listeners only registered from its callback) -> initFragments idempotently from onResume; CallerNotesHelper never overwrites notes it failed to parse (raw kept in caller_notes_backup); Gson-persisted models (CallerNote, SpeedDial, PhoneAccountHandleModel) pinned with @SerializedName to the R8 letters release builds already wrote (a/b/c...) + readable alternates -> future builds can't silently blank them; pre-1.0.19 running timers (Running(duration,tick)) converted to an end time and persisted on first app start; recents grouping uses strict isSameNumber (a group is deleted as a unit). Verified: upgrade v1.0.18 -> v1.0.35 keeps caller note, favourites custom order, running reminder (end time stable across force-stop, alarm fired on time); reminder fired on time after 2 process kills; Settings full scroll (96 rows) no crash; Dialpad settings slider persists and applies; tap action applies without relaunch; block from UI hides rows, show/hide toggle, unblock; default tab Contacts and Last used (title/menu correct); no TEMP-PERF; lint: no NewApi.
- Optional not done: isSameNumber does not treat 0500000003 and 966500000003 (no +, no 00) as the same (old code used plain equality there).
- DONE (v1.0.36): targetSdk reverted to 36 (no API 36/37 emulator image to test target-37 behaviour; compileSdk stays 37); timer persist only for non-expired timers (no re-fire race); isSameNumber cheap PhoneNumberUtils pre-check. Verified on v1.0.36: speed dial assign -> survives force-stop -> long-press dials; real first run (pm clear, role removed, default-dialer prompt accepted by tap) -> contacts, tabs, titles OK; incoming + outgoing call; 0 crashes. Blocking from Call History hides rows immediately (pre-restart screenshot confirmed).
