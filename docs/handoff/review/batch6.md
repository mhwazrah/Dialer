# Batch 6 findings (S6-*)

Source root: app/src/main/kotlin/com/goodwy/dialer

### S6-01 [Med] [perf] extensions/Context.kt:60 (confirmed) / helpers/Config.kt:29,64-106
Evidence: `val Context.config: Config get() = Config.newInstance(applicationContext)`; Config has `private val regionHint: String by lazy { ... telephonyManager.simCountryIso ... networkCountryIso ... }` and `normalizeCustomSIMNumber(number)` is called inside the `.firstOrNull { PhoneNumberUtils.compare(it.removePrefix(..), normalizeCustomSIMNumber(number)) }` lambda.
Problem: Confirmed: every `config` access allocates a new Config. The allocation itself is cheap (getSharedPreferences is cached per Context), so ~300 accesses in SettingsActivity.onResume are harmless. The real cost is that per-instance state is thrown away: `regionHint` (2 telephony binder calls) is recomputed on every `config.getCustomSIM(number)`, and the legacy-key fallback additionally re-normalizes `number` (E164 formatting) once per stored remember_sim_ key.
Fix: Cache one Config in App (`val config by lazy`) and make `Context.config` return it, or at minimum hoist `val normalized = normalizeCustomSIMNumber(number)` out of the lambda and make regionHint a companion-level lazy.

### S6-02 [Med] [perf] helpers/Config.kt:70-90
Evidence: `val migratedHandle = prefs.all.keys.filterIsInstance<String>().filter { it.startsWith(REMEMBER_SIM_PREFIX) }.firstOrNull { ... }`
Problem: `getCustomSIM` runs on every outgoing call/dial lookup. On every miss (the common case: no remembered SIM) it copies the entire SharedPreferences map (`prefs.all`, which also contains the 200-entry recentCallsCache JSON, caller notes, speed dial) and scans it. The migration code is marked "should be removed in future versions".
Fix: Run the legacy migration once (guard with a boolean pref) or delete the fallback.

### S6-03 [Med] [perf] helpers/Config.kt:237-244, activities/SettingsActivity.kt:1511-1517
Evidence: `var recentCallsCache: String get() = prefs.getString(RECENT_CALL, "")!!` ... `fun parseRecentCallsCache() { Gson().fromJson(...) }`; `config.recentCallsCache = Gson().toJson(recentsNew.take(RECENT_CALL_CACHE_SIZE))`
Problem: A 200-item JSON blob lives in the main prefs file, so every unrelated pref write (every settings toggle, every `apply()`) rewrites the whole XML file including it; the full string is held in memory in the prefs map. New Gson + TypeToken per parse. addSimCardColor parses and re-serializes it on the UI thread.
Fix: Move the cache to its own file/SharedPreferences ("recents_cache") or Room table; reuse a single Gson.

### S6-04 [Low] [correctness] helpers/Config.kt:326-327
Evidence: `get() = prefs.getStringSet(RECENT_OUTGOING_NUMBERS, hashSetOf(".")) as HashSet`
Problem: getStringSet returns the live internal set; callers that mutate the returned HashSet corrupt prefs state (documented "must not modify"). The setter does remove+put which is the right pattern, but the getter hands out the live set.
Fix: `HashSet(prefs.getStringSet(RECENT_OUTGOING_NUMBERS, hashSetOf(".")) ?: hashSetOf("."))`.

### S6-05 [Low] [correctness] helpers/Config.kt:254-263 and activities/SettingsActivity.kt:1002,1018,1034,1049-1055
Evidence: `ArrayList(prefs.getString(QUICK_ANSWERS, null)?.lines()?.map { it } ?: defaultList)`; `currentText = config.quickAnswers[index]`; `quickAnswers.removeAt(index)`
Problem: Answers are persisted joined with "\n" and read back with lines(). If a user answer contains a newline, or an empty string is stored, the list has the wrong size/shifted indices; `config.quickAnswers[index]` / `removeAt(index)` then crash with IndexOutOfBounds or shift answers 2/3. `"".lines()` returns `[""]`, not empty.
Fix: Strip/replace newlines in the ChangeTextDialog callback (or store as JSON), and guard `index < size`.

### S6-06 [Med] [perf] activities/SettingsActivity.kt:108-202
Evidence: `override fun onResume() { ... setupPurchaseThankYou() ... setupCustomizeColors() ... (about 90 setupXxx() calls) ... updateTextColors(binding.settingsHolder) }`
Problem: The entire screen is re-bound on every resume (return from any dialog/activity, e.g. color customization, ManageBlockedNumbers, permission dialogs), re-registering ~100 click listeners, rebuilding RadioItem lists, re-running telecom queries (S6-07), the blocked-number query (S6-08) and updateTextColors over the whole hierarchy. Most of it only needs to run once.
Fix: Move the one-time listener setup to onCreate; keep in onResume only the parts that depend on external state (top bar colors, blocked count, SIM availability, text colors).

### S6-07 [Med] [perf/integration] activities/SettingsActivity.kt:1292,1312,1401,1415,1491
Evidence: `settingsCallFromSameSimHolder.beVisibleIf(areMultipleSIMsAvailable())`, `val simList = getAvailableSIMCardLabels()`, `val areMultipleSIMsAvailable = areMultipleSIMsAvailable()` (initSimCardColor called twice per resume)
Problem: 5+ TelecomManager calls (callCapablePhoneAccounts + getPhoneAccount per account, each a binder IPC; getAvailableSIMCardLabels additionally creates a Config per account) on the main thread per onResume. Failure is swallowed (areMultipleSIMsAvailable returns false on SecurityException when READ_PHONE_STATE is revoked), so the SIM settings silently vanish.
Fix: Compute `val multiSim = areMultipleSIMsAvailable()` and `val sims = getAvailableSIMCardLabels()` once per onResume and pass them in; ideally only recompute when permission state changes.

### S6-08 [Med] [perf/integration] activities/SettingsActivity.kt:350-352
Evidence: `settingsManageBlockedNumbersCount.text = if (baseConfig.blockingEnabled) getBlockedNumbers().size.toString() else getString(R.string.off)`
Problem: getBlockedNumbers() is a BlockedNumberContract/ContentResolver query on the UI thread, executed on every onResume (including each return from ManageBlockedNumbersActivity). Can ANR with a large block list; if the app is not the default dialer/caller-id app the provider call throws SecurityException (depends on the commons implementation catching it).
Fix: Run in `ensureBackgroundThread { val n = getBlockedNumbers().size; runOnUiThread { ... } }` and guard with `isDestroyed`.

### S6-09 [Med] [perf] activities/SettingsActivity.kt:1072-1095
Evidence: `contentResolver.openInputStream(uri)!!.use { it.bufferedReader().readText() }` then `Json.decodeFromString<List<RecentCall>>(jsonString)` inside `importCallHistory` called straight from the ActivityResult callback.
Problem: Whole file read and JSON-decoded on the main thread (call-history exports can be thousands of entries with QUERY_LIMIT_MAX). Risk of ANR/jank. Only restoreRecentCalls itself hops to a background thread.
Fix: Wrap the read+decode in `ensureBackgroundThread { ... }` and post toasts via the main-safe `toast`.

### S6-10 [Med] [integration/correctness] activities/SettingsActivity.kt:1085 -> helpers/RecentsHelper.kt:449-467
Evidence: `put(Calls.NUMBER, it.phoneNumber) put(Calls.TYPE, it.type) put(Calls.DATE, it.startTS) put(Calls.DURATION, it.duration) put(Calls.CACHED_NAME, it.name)` + `context.contentResolver.bulkInsert(contentUri, values)`
Problem: Import inserts without any de-duplication (importing the same file twice doubles the call log), without Calls.NEW/IS_READ (imported missed calls default to NEW=1 so they show as unread/missed notifications after restore), without PHONE_ACCOUNT_ID/COMPONENT_NAME (SIM info lost), and without Calls.CACHED_NUMBER_TYPE/COUNTRY_ISO. bulkInsert result is ignored and no ContentObserver refresh is guaranteed; the callback toasts "importing_successful" even if the insert returned 0 or threw.
Fix: Put `Calls.NEW to 0` (and IS_READ 1 where supported), skip rows that already exist (same number+date+type), check the bulkInsert count, wrap in try/catch for SecurityException.

### S6-11 [Low] [correctness] activities/SettingsActivity.kt:1102-1106
Evidence: `contentResolver.openOutputStream(uri)!!` ... `outputStream.use { it.write(jsonString.toByteArray()) }`
Problem: Default mode "w" does not guarantee truncation on all providers (can leave trailing garbage when overwriting an existing, larger file). The JSON string plus byte array doubles memory for huge exports.
Fix: `openOutputStream(uri, "wt")`; write via `bufferedWriter()`/`Json.encodeToStream`.

### S6-12 [Med] [correctness] activities/SettingsActivity.kt:326-332
Evidence: `config.useEnglish = settingsUseEnglish.isChecked; exitProcess(0)`
Problem: Preference writes use `apply()` (async disk write). exitProcess kills the process immediately; the write can be lost (the QueuedWork flush only happens in activity lifecycle callbacks), so the toggle sometimes does not persist. Killing the process also drops the foreground timer service/EventBus state and any pending work. MainActivity.kt:222 does the same with System.exit(0).
Fix: Persist with `commit()` before exiting, or better, recreate via `recreate()`/restart the launch activity with a new task instead of killing the process.

### S6-13 [Med] [perf] activities/SettingsActivity.kt (needRestart pattern: lines 384, 401, 430, 479, 497, 508, 519, 547, 568, 607, 645, 673, 699, 1266, 1276, 1391, 1519, 1591, 1616, 1642, 1666, 1691, 1723, 1734, 1762, 1796, 1931)
Evidence: `config.needRestart = true`; consumer MainActivity.kt:227-231 `if (config.needRestart || ...) { config.lastUsedViewPagerPage = 0; finish(); startActivity(intent); return }`
Problem: ~28 settings (swipe vibration/ripple, relative date, on-click actions, thumbnails, divider, etc.) force MainActivity to be finished and relaunched on return, which discards state (last tab reset to 0), re-inflates all three fragments and reloads the call log and contacts from scratch. Most of these are plain config reads at bind time and only need `adapter.notifyDataSetChanged()`/refresh of visible rows. Also reading `config.needRestart` in several places and resetting it in storeStateVariables (MainActivity:977) means a stale `true` set from SettingsDialpadActivity/CallActivity:1591 also triggers a MainActivity relaunch.
Fix: Reserve needRestart for things that truly change view structure (bottom nav, icon tabs, search bar, material design); for the rest set a lightweight "refreshAdapters" flag handled in the fragments' onResume.

### S6-14 [Low] [perf/leak] activities/SettingsActivity.kt:98-105
Evidence: `PurchaseHelper().checkPurchase(this@SettingsActivity, iapList = ..., subList = ..., ruStoreList = ...) { updatePro -> if (updatePro) updatePro() }`
Problem: App.kt force-sets isPro/isProNoGP, so the billing/RuStore connection and network round-trip opened every time Settings is created is pure overhead. The callback captures the Activity and calls `updatePro()` -> `binding.apply{...}` possibly after the activity is destroyed (billing callbacks are async).
Fix: Delete the checkPurchase call (and the product-id lists) since everything is unlocked; otherwise guard with `if (!isDestroyed)`.

### S6-15 [Low] [leak] activities/SettingsActivity.kt:610-612, 754-756, 832-834, 879-881, 1467-1473, 1803-1805
Evidence: `RxAnimation.from(settingsContactThumbnailsSizeHolder).shake(shakeTranslation = 2f).subscribe()`
Problem: Disposable is dropped, the animation holds the View after the activity finishes. All these branches are unreachable dead code (isPro is forced true so `pro`/`checkPro()` is always true): the "locked" labels, 0.4f alpha, snackbars, `settingsPurchaseThankYouHolder`, TipJar/launchPurchase.
Fix: Remove the locked-feature branches and RxAnimation usage (also removes the rxanimation dependency use here).

### S6-16 [Low] [correctness] activities/SettingsActivity.kt:1197-1205 vs 1145-1178
Evidence: `config.groupSubsequentCalls = settingsGroupSubsequentCalls.isChecked` (separate switch) and `GROUP_CALLS_ALL -> { groupSubsequentCalls = false; groupAllCalls = true }`
Problem: Two independent controls write the same pair of flags. Toggling the standalone subsequent-calls switch while groupAllCalls is true yields both true; getGroupCallsText()/getGroupCallsCheckedItemId() then silently prefer subsequent. (Only reachable if the switch is visible in the layout; verify.)
Fix: Remove setupGroupSubsequentCalls or make it also clear groupAllCalls.

### S6-17 [Low] [perf] activities/SettingsActivity.kt:1517
Evidence: `config.recentCallsCache = Gson().toJson(recentsNew.take(RECENT_CALL_CACHE_SIZE))` / `recents.forEach { ... recentsNew.add(...) }` in addSimCardColor
Problem: Main-thread parse+copy+serialize of the whole recents cache on each color pick; also only SIM color is changed so a `map {}` suffices.
Fix: `ensureBackgroundThread` or simply invalidate the cache (`config.recentCallsCache = ""`) and rely on needUpdateRecents.

### S6-18 [Med] [correctness] App.kt:76-90, 100-113
Evidence: `countDownTimers[event.timerId]?.cancel(); countDownTimers[event.timerId]?.start()` (Restart) and `countDownTimers[event.timerId] = countDownTimer` (Start)
Problem: (a) Restart only works if a CountDownTimer object is still in the map. After process death (no entry) the DB state is set to Running(duration, duration) but nothing ticks, so the timer never finishes and sits Running forever (and `onAppForegrounded` only starts timers for ids with a null entry, which it then does, but only on next ON_START). (b) When the entry exists, `start()` restarts the old object with its original millisInFuture, which after the foreground-resume path is the remaining tick, not the full `timer.seconds` that the saved state claims. (c) Start does not cancel an existing CountDownTimer for the same id before overwriting it, leaving an orphan that keeps ticking and writing state. (d) Entries are never removed on Finish/Delete/Reset, so the map holds stale timers forever.
Fix: On Restart post `TimerEvent.Start(id, fullDuration)` instead of calling `.start()` on the old object; in Start do `countDownTimers.remove(id)?.cancel()` first; `remove()` in Finish/Delete/Reset.

### S6-19 [Med] [perf] App.kt:102-113,145-158 + services/TimerService.kt:97-102
Evidence: `onTick(tick) { updateTimerState(event.timerId, TimerState.Running(event.duration, tick)) }`; updateTimerState = `timerHelper.getTimer(...)` -> `insertOrUpdateTimer(...)` -> `post(TimerEvent.Refresh)`; TimerService.onMessageEvent(Refresh) -> updateNotification() -> `timerHelper.getTimers` -> `startForeground(...)` with a new RemoteViews.
Problem: Every second while a callback timer runs: 3 new threads (ensureBackgroundThread per DB call), 2 Room reads + 1 write (REPLACE insert), a new TimerHelper object, a new NotificationChannel object + createNotificationChannel binder call, a new RemoteViews and a notification update. Also happens while the app is in the foreground (DB writes per second). Wasteful battery drain for a reminder timer.
Fix: Persist the absolute `endTimeMillis` once on Start/Pause and compute the remaining time from it; do not write per tick. In the notification use `setUsesChronometer(true).setChronometerCountDown(true).setWhen(endTime)` so the system draws the countdown without per-second updates; create the channel once.

### S6-20 [Med] [correctness] helpers/TimerHelper.kt:19, App.kt:139, receivers/TimerReceiver.kt:31
Evidence: `callback.invoke(timerDao.getTimer(timerId)!!)`; `(timer.state as TimerState.Running).tick`; `context.timerHelper.getTimer(timerId) { timer -> ... timer.id!! ...}`
Problem: Background-thread NPE/ClassCastException crashes the whole app: (a) a pending tick/Finish/Pause/Refresh for a timer deleted in the meantime (Delete -> deleteTimer racing the last onTick -> updateTimerState -> getTimer!!) throws; (b) Pause when the stored state is not Running (double tap, Pause after Finish) throws ClassCastException; (c) TimerReceiver's default branch (any action other than hide/restart, incl. INVALID_TIMER_ID = -1) calls getTimer(-1)!!. CallActivity.kt:1386 posts `TimerEvent.Start(1, ...)` which assumes the seeded timer id 1 still exists.
Fix: Use the nullable DAO result (`getTimer(id) ?: return@ensureBackgroundThread`), `(timer.state as? TimerState.Running) ?: return`, and ignore `timerId == INVALID_TIMER_ID` in the receiver.

### S6-21 [Med] [leak] services/TimerService.kt:86-88,104-107
Evidence: `} else { stopService() }` ... `private fun stopService() { isStopping = true; stopForeground(STOP_FOREGROUND_REMOVE) }`
Problem: The service never calls `stopSelf()`. After the last timer finishes or the app comes to the foreground (TimerStopService) it just leaves foreground mode but remains a started service holding its EventBus registration until the system reclaims it. `stopService()` is also invoked from the background thread inside the getTimers callback (stopForeground off-main) and a previously posted `startForeground` Handler runnable can re-promote the service after it was stopped.
Fix: `stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()` on the main thread (post to Handler), and check `isStopping` inside the posted runnable.

### S6-22 [Low] [perf] services/TimerService.kt:120-126
Evidence: `NotificationChannel(channelId, label, importance).apply { setSound(null, null); notificationManager.createNotificationChannel(this) }` inside `notification(...)`
Problem: Channel re-created (binder call) for every notification build (every second, see S6-19). The channel id here (`right_dialer_alarm_timer`) differs from Timer.channelId (`right_dialer_timer_channel`, AppDatabase:63), so two channel IDs exist for one concept.
Fix: Create the channel once in `onCreate`.

### S6-23 [Low] [correctness] services/TimerService.kt:159-172
Evidence: `if (number != "") { builder.addAction(... message ...) } if (number != "") { builder.addAction(... call_back ...) }`
Problem: Two identical conditions (cosmetic duplication). Also `getFormattedDuration()`/`firstTimer.label` is used as the phone number; if the label is a name rather than a number, the SMS/call actions target garbage (verify what CallActivity:1380-1386 stores in `label`).
Fix: Merge the ifs; validate the label as a number.

### S6-24 [Low] [correctness] receivers/TimerReceiver.kt:28-34
Evidence: `context.timerHelper.getTimer(timerId) { ... EventBus.getDefault().post(TimerEvent.Start(...)) }` with no `goAsync()`
Problem: onReceive returns immediately and spawns a thread; if the process had only been started for this broadcast it can be killed before the DB read completes. Also posts Reset (async DB write) and Start (reading the same row) concurrently, so Start can read the pre-Reset state.
Fix: `val pending = goAsync()` ... `pending.finish()` in the callback; post Start from the Reset completion or skip the Reset when the timer is Idle.

### S6-25 [Low] [correctness] databases/AppDatabase.kt:26-46
Evidence: `private var db: AppDatabase? = null` with `if (db == null) { synchronized(...) { if (db == null) {`
Problem: Double-checked locking on a non-@Volatile field (can publish a partially constructed instance). Also `.fallbackToDestructiveMigration()` (deprecated no-arg overload) means any future schema bump without a migration silently wipes timers.
Fix: `@Volatile private var db`; use `fallbackToDestructiveMigration(true)` or drop it.

### S6-26 [Low] [leak] databases/AppDatabase.kt:48-67
Evidence: `Executors.newSingleThreadScheduledExecutor().execute { val config = context.config; db!!.TimerDao().insertOrUpdateTimer(...) }`
Problem: A new non-daemon executor is created and never shut down (thread leaked for the process lifetime) each time the DB is created (first install or after a destructive migration). It also captures the `context` passed in (possibly an Activity) and uses the `db` companion field from inside the onCreate callback.
Fix: Insert synchronously with the `db: SupportSQLiteDatabase` passed to `onCreate` (`db.insert("timers", ...)`/`execSQL`), or use `ensureBackgroundThread`; use `context.applicationContext`.

### S6-27 [Low] [perf] App.kt:49-74 and TimerHelper.kt (all methods)
Evidence: `@OnLifecycleEvent(Lifecycle.Event.ON_STOP) private fun onAppBackgrounded()`, `class App : RightApp(), LifecycleObserver`, `fun getTimers(callback) { ensureBackgroundThread { ... } }`
Problem: LifecycleObserver/@OnLifecycleEvent is deprecated and resolved via reflection at startup; every ON_START/ON_STOP of the whole app (each activity switch from the user's perspective is only a process-level start/stop, but still) posts TimerStopService and reads the timers table on a freshly spawned thread even when no timer was ever used. A `new TimerHelper` is created on every `timerHelper` access (Context.kt:228) and every call spawns a thread.
Fix: Implement `DefaultLifecycleObserver`; skip DB work when no timer exists (cache a "hasRunningTimers" flag), and use a single executor/coroutine for TimerHelper.

### S6-28 [Low] [perf] App.kt:37-42
Evidence: `baseConfig.isPro = true; isProNoGP = true; hadThankYouInstalled = true; wasAppRated = true`
Problem: Four prefs writes (each `apply()` and each with a new BaseConfig) on the main thread at every cold start in Application.onCreate. Negligible per run but constant.
Fix: Guard with `if (!baseConfig.isPro)` (or write once with a version flag).

### S6-29 [Low] [leak] App.kt:44-47
Evidence: `override fun onTerminate() { EventBus.getDefault().unregister(this); super.onTerminate() }`
Problem: onTerminate is never called on real devices, so the unregister is dead code. It is harmless (Application lives as long as the process), noting only for completeness: EventBus registration lifecycle in App is correct (register once in onCreate), TimerService register/unregister in onCreate/onDestroy is balanced.
Fix: Remove the dead override or leave as is.

### S6-30 [Low] [correctness] models/TimerEvent.kt:7-12 / App.kt:60-72
Evidence: `TimerEvent.Start(timerId = timer.id!!, duration = (timer.state as TimerState.Running).tick)` in onAppForegrounded
Problem: When resuming a Running timer after the app was in the background, Start is called with the remaining tick as `duration`, and onTick rewrites the state as `Running(event.duration, tick)`, so the original total duration is lost (progress display, Restart). The remaining time is also stale (last persisted tick) because no wall-clock time elapsed while the process was dead is accounted for. Same root cause as S6-19: no absolute end time.
Fix: Store `endTime` in TimerState.Running and derive the remaining from it.

### S6-31 [Low] [correctness] services/MyTileService.kt:20-64
Evidence: `override fun onStartListening() { updateTile() }` ... `setDefaultCallerIdApp()` (no tile update) ... `qsTile?.icon = Icon.createWithResource(...)`
Problem: Tile lifecycle is fine (no listeners to leak; Tile object only used between onStartListening/onStopListening). Minor issues: state is not refreshed when blockingEnabled changes in the app while the shade is open (no onStopListening/observer, acceptable); after sending the user to set the default caller-id role the tile stays in the old state until the next listen; an Icon + 4 BaseConfig instances are rebuilt on each update.
Fix: Read `val enabled = baseConfig.blockingEnabled` once in updateTile; optional.

### S6-32 [Med] [correctness] dialogs/FilterContactSourcesDialog.kt:25-77
Evidence: `contactHelper.getContactSources { ... this@..contactSources ...; isContactSourcesReady = true; processDataIfReady() }` and `contactHelper.getContacts(getAll = true) { it.mapTo(contacts) {...}; ...; isContactsReady = true; processDataIfReady() }`
Problem: Per the brief, both callbacks run on their own background threads, so `contactSources`/`contacts` (plain ArrayLists) and the non-volatile flags are touched from two threads: processDataIfReady can run concurrently (both threads see the other flag false and one result is dropped, or the dialog is built twice, or ConcurrentModificationException on `contactSources.clear()/addAll`). `activity.runOnUiThread { ... setupDialogStuff }` also runs on a possibly finished activity (BadTokenException/leak of the dialog+binding).
Fix: Mark flags @Volatile and synchronize processDataIfReady (or post both callbacks to the UI thread first); check `activity.isFinishing || activity.isDestroyed` before showing.

### S6-33 [Med] [perf] dialogs/FilterContactSourcesDialog.kt:34-41
Evidence: `contactHelper.getContacts(getAll = true) { it.mapTo(contacts) { contact -> contact.copy() } ... MyContactsContentProvider.getContacts(...) }` only to compute `contacts.count { it.source == contactSource.name }`
Problem: Loads every contact (with all data) and deep-copies every Contact just to count per source; `count` is O(sources x contacts); `clearContactSourcesCache()` forces a fresh load. Opening the dialog on a 5k-contact device is heavy.
Fix: Don't copy (`contacts.addAll(it)`), or count per source with a map: `contacts.groupingBy { it.source }.eachCount()`.

### S6-34 [Low] [correctness] dialogs/FilterContactSourcesDialog.kt:63,81-90
Evidence: `val selectedSources = activity.getVisibleContactSources()` (called inside runOnUiThread) and `if (activity.getVisibleContactSources() != ignoredContactSources)`
Problem: (a) getVisibleContactSources() queries the device contact sources through ContentResolver on the UI thread, twice. (b) It returns visible source *names* (ArrayList<String>) while it is compared to a HashSet of *ignored full identifiers*, so the list != set comparison is always true; the "only save if changed" guard never fires, so every OK press rewrites the setting, clears the cache and invokes `callback()` (a full contacts reload) even when nothing changed. (Verify getVisibleContactSources' return type in commons.)
Fix: Compare `config.ignoredContactSources != ignoredContactSources`; compute selected sources off the UI thread (or from the already loaded `contactSources`).

### S6-35 [Low] [correctness] dialogs/SelectSimButtonDialog.kt:23,36-43,58-66
Evidence: `val textColor = activity.baseConfig.simIconsColors[1].getContrastColor()` reused for `sim2Button` and `cancelButton`; `setPadding(2, 2, 2, 2)`
Problem: Text color for SIM 2 and the red cancel button is computed from SIM 1's background color (wrong contrast when the colors differ); only two SIM buttons exist, a third SIM is silently ignored; paddings are in raw pixels (2px), not dp.
Fix: Compute contrast per button (`simIconsColors[2].getContrastColor()`, cancel from 0xFFEB5545); use dp for padding; hide/handle extra SIMs.

### S6-36 [Low] [leak] dialogs/SelectSimButtonDialog.kt:46-56 / dialogs/SelectSIMDialog.kt:59-69
Evidence: `dialog?.setOnDismissListener { onDismiss() }` right after `setupDialogStuff(...) { alertDialog -> dialog = alertDialog }`
Problem: Works only if commons invokes the callback synchronously inside setupDialogStuff; if it is deferred (e.g. when the dialog is shown later) `dialog` is null at this point and `onDismiss` is never wired. The `activity.getAvailableSIMCardLabels()` telecom call is on the UI thread (and @SuppressLint("MissingPermission") hides the READ_PHONE_STATE check). Both dialogs hold `activity` strongly for their lifetime (fine, dialog-scoped).
Fix: Set the dismiss listener inside the `setupDialogStuff` callback.

### S6-37 [Low] [perf] dialogs/ChooseSocialDialog.kt:22-35
Evidence: `activity.getPackageDrawable(action.packageName)` per action, `actions.sortBy { it.type }`
Problem: `getPackageDrawable` is a PackageManager call per row on the UI thread (small N, low impact); `sortBy` mutates the caller's list as a side effect.
Fix: `actions.sortedBy { it.type }`; optionally cache the drawables.

### S6-38 [Low] [correctness] dialogs/ManageVisibleTabsDialog.kt:40-53
Evidence: `if (binding.root.findViewById<MyAppCompatCheckbox>(value).isChecked) { result += key }` ... `activity.config.showTabs = result`
Problem: No callback or needRestart is set; the change is only picked up through MainActivity's storedShowTabs comparison on resume, which relays on that path staying intact (MainActivity:335-345). Unchecking all three silently resets to ALL_TABS_MASK without feedback.
Fix: Accept a callback (or set `needRestart`) and show a toast for the reset case. Low priority.

### S6-39 [Low] [correctness] dialogs/ChangeSortingDialog.kt:13-36,77-105
Evidence: `private var config = activity.config` (snapshot kept for dialog lifetime), `config.sorting = sorting` when `showCustomSorting` and sorting is SORT_BY_CUSTOM leaves `sorting` unchanged; `callback()` always invoked.
Problem: No functional bug found; `config` is `var` but never reassigned, and the callback always triggers a full contacts reload even if nothing changed.
Fix: `val config`; skip callback if sorting/order/symbolsFirst are unchanged.

### S6-40 [Low] [correctness] models/Events.kt, helpers/Constants.kt
Evidence: `class StateChanged(val isEnabled: Boolean)`, `class StopStroboscope`, `class StopSOS`, constants `MIN_BRIGHTNESS_LEVEL`, `DEFAULT_BRIGHTNESS_LEVEL`, `NEED_SELECT_SIM`, `MISSED_CALL_NOTIFICATION_ID`, `SHOW_ALL_RECENT_IN_HISTORY`, `CURRENT_RECENT_CALL(_LIST)`, `transparentCallScreen`/`alwaysShowFullscreen` ("not used")
Problem: Dead code from the flashlight app this was forked from (CameraUnavailable is the only member still posted, CameraFlash.kt:48; StateChanged/StopStroboscope/StopSOS appear unused). `ObfuscatedTimer` in Timer.kt is also unused unless referenced by import code. `whatsNewList()` rebuilds a list per call (negligible).
Fix: Delete unused classes/constants after a grep confirms no references.

### S6-41 [Low] [correctness] models/Timer.kt:9-13
Evidence: `@Serializable data class Timer(... val state: TimerState ...)` with `@PrimaryKey(autoGenerate = true) var id: Int?`
Problem: `@Entity` + `@kotlinx.serialization.Serializable` with a sealed TimerState requires a Room TypeConverter (Converters.kt via RuntimeTypeAdapterFactory/Gson) and Finished/Idle objects; `TimerState.Idle`/`Finished` are `object`s, so Gson deserialization can create a *second instance* (not `===` the singleton). Code uses `is`, so it works, but any `==`/`===` comparison on the state would silently fail.
Fix: Keep using `is` checks (current usage is fine); no change required unless equality checks are added.

## Coverage (lines read)
- activities/SettingsActivity.kt: lines read 1-1939
- helpers/Config.kt: lines read 1-376
- helpers/Constants.kt: lines read 1-192
- App.kt: lines read 1-159
- services/TimerService.kt: lines read 1-193
- helpers/TimerHelper.kt: lines read 1-55
- receivers/TimerReceiver.kt: lines read 1-37
- interfaces/RefreshItemsListener.kt: lines read 1-5
- interfaces/RemoveSpeedDialListener.kt: lines read 1-5
- interfaces/TimerDao.kt: lines read 1-26
- databases/AppDatabase.kt: lines read 1-75
- services/MyTileService.kt: lines read 1-111
- models/Timer.kt: lines read 1-42
- models/TimerState.kt: lines read 1-23
- models/TimerEvent.kt: lines read 1-13
- models/Events.kt: lines read 1-13
- models/PhoneAccountHandleModel.kt: lines read 1-17
- dialogs/ChangeSortingDialog.kt: lines read 1-106
- dialogs/FilterContactSourcesDialog.kt: lines read 1-93
- dialogs/SelectSimButtonDialog.kt: lines read 1-81
- dialogs/SelectSIMDialog.kt: lines read 1-80
- dialogs/ChangeTextDialog.kt: lines read 1-63
- dialogs/ManageVisibleTabsDialog.kt: lines read 1-54
- dialogs/ChooseSocialDialog.kt: lines read 1-48
