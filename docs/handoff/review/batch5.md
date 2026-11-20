# Batch 5 review (prefix H5-)

Paths relative to `app/src/main/kotlin/com/goodwy/dialer`.

### H5-01 [High] [correctness] activities/CallHistoryActivity.kt:1356
Evidence: `if (showAll) allRecentCall.filter { currentRecentCall!!.phoneNumber.contains(it.phoneNumber) } as ArrayList<RecentCall>`
Problem: Substring match, not equality (every other filter in the file uses `==`). Any call whose number is a substring of this number is deleted from the call log: short codes, a shorter number embedded in a longer one, and entries with an empty `phoneNumber` (`"x".contains("")` is true), e.g. private/unknown callers. Deletes other people's history. `removeRecentCalls` is irreversible, and the caller notes are removed too.
Fix: `allRecentCall.filter { it.phoneNumber == currentRecentCall!!.phoneNumber }` (or the same predicate used in `refreshCallLog`).

### H5-02 [Med] [correctness] activities/CallHistoryActivity.kt:1343
Evidence: `handlePermission(PERMISSION_WRITE_CALL_LOG) { removeRecents() }`
Problem: The `granted` flag is ignored. If the permission is denied, `RecentsHelper.removeRecentCalls` runs `contentResolver.delete` on a background thread with no try/catch, so a `SecurityException` crashes the app (RecentsHelper.kt:430). `removeAllRecentCalls` checks `it`, this call site doesn't.
Fix: `handlePermission(PERMISSION_WRITE_CALL_LOG) { if (it) removeRecents() else toast(R.string.no_permissions) }`. Also wrap the delete in try/catch in RecentsHelper.

### H5-03 [Med] [perf] activities/CallHistoryActivity.kt:167-168
Evidence: `refreshCallLog(load = true, loadAll = false) { refreshCallLog(load = true, loadAll = true) {`
Problem: `loadAll` is never read in `refreshCallLog`, so "Show all" queries and resolves the whole call log (up to `queryLimitRecent`) twice back to back, and renders the list twice.
Fix: Call `refreshCallLog(load = true) { runOnUiThread { ... } }` once and drop the `loadAll` parameter.

### H5-04 [Med] [perf] activities/CallHistoryActivity.kt:88, 353-385
Evidence: `if (!initShowAll) refreshCallLog(false)` in `onResume`; `getRecentCalls(existingRecentCalls, queryCount) {...}`
Problem: Every resume (including coming back from any dialog) loads the whole recents list (call log query plus a contact lookup for every row) only to decide whether to show the "All" button. The history of ONE number is derived by filtering the global recents. It never queries `CallLog.Calls` for that number, so it is bounded by `queryLimitRecent` and older calls of this number never show.
Fix: Compute `callHistoryShowAll` visibility from the existing cache (`initShowAll`), or query `Calls.CONTENT_URI` with `NUMBER = ?` / `PhoneNumberUtils.compare` for this number only when "All" is tapped. Don't reload on every resume.

### H5-05 [Med] [integration] activities/CallHistoryActivity.kt:138, 356, 1111
Evidence: `recents.filter { it.phoneNumber == currentRecentCall!!.phoneNumber}` ... `.firstOrNull { it.normalizedNumber == currentRecentCall!!.phoneNumber }`
Problem: Raw string equality with no normalisation. The same person's calls logged as `+4670...` and `070...` (or with spaces) are split into separate histories and missed in "All". `normalizedNumber` (from the contact) is compared with the call-log number, which is rarely normalised the same way, so the number type label and primary star often don't show.
Fix: Compare with `PhoneNumberUtils.compare(a, b)` or `a.normalizePhoneNumber() == b.normalizePhoneNumber()` (cache the normalised current number once).

### H5-06 [Med] [perf] activities/CallHistoryActivity.kt:137
Evidence: `val recents = config.parseRecentCallsCache()` inside `initShowAll()`, called from `onCreate`
Problem: Parses the whole recents-cache JSON on the main thread during startup, just to count one number's calls (with the same `==` filter).
Fix: Do it in `ensureBackgroundThread` and post the visibility change, or drop it and rely on the lazy count.

### H5-07 [Med] [perf] activities/CallHistoryActivity.kt:85-87, 582-599
Evidence: `ensureBackgroundThread { initContact() }` in `onResume`; `contacts.filter {...}.forEach { ContactsHelper(this).getContactWithId(it.id, it.isPrivate()) }`
Problem: On every resume the contact is re-fetched, duplicates are looked up (one `getContactWithId` query per duplicate, N+1), `getContactSources` is re-run, and all the messenger rows are re-inflated. Resumes can overlap, since a second `initContact` starts before the first finishes. `duplicateContacts.clear()/add()` on a bg thread races with `duplicateContacts.forEach` on the UI thread (`setupVideoCallActions`/`setupMessengersActions`/`setupEvents`), which can throw ConcurrentModificationException.
Fix: Load only when `contact == null` or on `onCreate`/`needUpdate`. Build a new local list in the bg thread and assign it (`duplicateContacts = list`) on the UI thread.

### H5-08 [Med] [correctness] activities/CallHistoryActivity.kt:472-481
Evidence: `ContactsHelper(this).getContacts(showOnlyContactsWithNumbers = true) { _ -> ... contact = privateContacts.firstOrNull { it.id == contactId } }` followed immediately by `if (contact == null) { runOnUiThread { updateButton() } }`
Problem: `getContacts` is asynchronous (own thread), so the `contact = ...` assignment happens after the `contact == null` check has already run. A private contact is never shown on first load. It also loads ALL contacts and discards the result (`_`) just to get the private cursor, and `MyContactsContentProvider.getContacts` is called with a nullable cursor when the Contacts app is absent.
Fix: Skip `getContacts`; call `getMyContactsCursor(false, true)` and `MyContactsContentProvider.getContacts(this, it)` directly (null-guarded) synchronously on the bg thread, before the null check.

### H5-09 [Med] [perf] activities/CallHistoryActivity.kt:604, 618, 626, 634, 642, 650, 740, 773, 806, 840, 876
Evidence: `val actions = getSocialActions(key.id)` inside `setupVideoCallActions()` / `setupMessengersActions()`, both invoked in `runOnUiThread`
Problem: `getSocialActions` is a ContactsContract Data query on the main thread, issued per source per messenger app, and issued twice (once for video, once for messengers) with identical results. The five messenger blocks are copy-pasted (~35 lines each), and `value.lowercase()` is recomputed 10 times per source.
Fix: Compute the social actions in the bg thread once per source (`Map<Contact, List<SocialAction>>`), pass them to both setups, and collapse the five blocks into one loop over `listOf(WHATSAPP, SIGNAL, VIBER, TELEGRAM, THREEMA)`.

### H5-10 [Med] [perf] activities/CallHistoryActivity.kt:215, 1311, 1326-1331
Evidence: `if (isNumberBlocked(currentRecentCall!!.phoneNumber, getBlockedNumbers()))` in `updateButtons()` (every `onResume`); `runOnUiThread { if (isNumberBlocked(..., getBlockedNumbers())) { deleteBlockedNumber(...) ... addBlockedNumber(...)`
Problem: BlockedNumberContract queries and writes run on the main thread (every resume, and on block/unblock, where `getBlockedNumbers()` is queried a second time after the confirmation dialog). `isNumberBlocked` against the full list is also an exact comparison, not a normalised one. On a revoked default-dialer role, `getBlockedNumbers` returns empty and the UI shows "Block" although the number is blocked.
Fix: Run the query/mutation in `ensureBackgroundThread` and update the button in `runOnUiThread`; use `BlockedNumberContract.isBlocked(ctx, number)` for a single-number check.

### H5-11 [Low] [perf] activities/CallHistoryActivity.kt:233, 243, 259
Evidence: `callerNotesHelper.getCallerNotes(currentRecentCall!!.phoneNumber)` in `setupCallerNotes()` (each `onResume`) and in click handlers.
Problem: Each call parses the whole notes JSON from SharedPreferences (`config.parseCallerNotes()`) on the main thread; see H5-18.
Fix: Cache the note in a field after the first load, and refresh it only after add/delete.

### H5-12 [Low] [correctness] activities/CallHistoryActivity.kt:447
Evidence: `val rawId = if (data.path!!.contains("lookup")) {`
Problem: `Uri.path` can be null (opaque URI), which gives an NPE in the bg thread and a crash.
Fix: `data.path?.contains("lookup") == true`.

### H5-13 [Low] [correctness] activities/CallHistoryActivity.kt:1349-1376
Evidence: `callerNotesHelper.deleteCallerNotes(callerNote)` after `RecentsHelper(this).removeRecentCalls(idsToRemove)`; with `showAll == false`, `callsToRemove = currentRecentCallList`
Problem: The notes for the number are deleted even when only the visible subset was removed (the "All" calls remain in the log). `onBackPressed()` is deprecated, and the delete callback runs on a bg thread then posts `onBackPressed` without checking `isDestroyed`.
Fix: Delete the note only when `showAll` (full removal). Use `finish()` guarded by `!isFinishing`.

### H5-14 [Low] [correctness] activities/CallHistoryActivity.kt:1214-1220, 1272-1276
Evidence: `if (areMultipleSIMsAvailable() && !call.isUnknownNumber) { ... val simList = getAvailableSIMCardLabels() ... val sim1 = simList[0]`
Problem: `simList[0]`/`[1]` assume a non-empty list. After SIM removal, or if the permission is revoked, `getAvailableSIMCardLabels()` is empty and this throws IndexOutOfBounds in the click handler. Telephony queries are also made twice per update.
Fix: Guard `simList.isEmpty()` and reuse the list from `updateButton()` in `updateDefaultSIMButton`.

### H5-15 [Low] [correctness] activities/CallHistoryActivity.kt:668-669, 916-917
Evidence: `ensureBackgroundThread { runOnUiThread { ... } }`
Problem: A useless thread hop (spawns a thread just to post back to the UI). No work happens in the bg thread, and the click then reacts late.
Fix: Remove `ensureBackgroundThread` and run the body directly.

### H5-16 [Low] [correctness] activities/CallHistoryActivity.kt:1394
Evidence: `startActivity(Intent.createChooser(this, getString(R.string.invite_via)))`
Problem: No try/catch. `ActivityNotFoundException` / `SecurityException` is possible for chooser launches on locked-down profiles.
Fix: Wrap in try/catch and `showErrorToast`.

### H5-17 [Low] [correctness] activities/CallHistoryActivity.kt:559-563, 569-571
Evidence: `contact!!.starred = newIsStarred` on the UI thread while the bg thread uses `contact!!` / `arrayListOf(contact!!)`; the icon flips optimistically.
Problem: `contact` is a plain field reassigned concurrently by `initContact` on a bg thread (and is stale after a failed load because it is never reset to null on resume), giving NPE and visibility races. A failed favourite write isn't reverted.
Fix: Snapshot `val c = contact ?: return@setOnMenuItemClickListener true`; reset `contact = null` at the start of `initContact`.

### H5-18 [Low] [perf] helpers/CallerNotesHelper.kt:13-38
Evidence: `val mCallerNotes = context.config.parseCallerNotes()` / `context.config.callerNotes = Gson().toJson(mCallerNotes)`
Problem: Every get/add/delete parses and serialises the entire notes list with a new `Gson()`. The read-modify-write is not atomic (bg `deleteCallerNotes` in `removeRecents` vs UI `addCallerNotes` lose updates). When `callerNote` is null (the stored id has a different number format), a duplicate entry is added. `Calendar.getInstance(Locale.ENGLISH).timeInMillis` is just `System.currentTimeMillis()`.
Fix: Hold one `private val gson = Gson()`, make the mutators `@Synchronized`, replace by id (`removeAll { it.id.numberForNotes() == key }`) instead of `remove(callerNote)`.

### H5-19 [Med] [leak] receivers/MissedCallReceiver.kt:72
Evidence: `val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)`
Problem: The cursor is never used or closed in the receiver (`getContactFromAddress` opens its own at Context.kt:422). It is a leaked ContentProvider cursor (plus a main-thread query on every missed call).
Fix: Delete the line.

### H5-20 [Med] [correctness] receivers/MissedCallReceiver.kt:53-122
Evidence: `override fun onReceive(...)` starts `context.getContactFromAddress(phoneNumber) { ... Handler(Looper.getMainLooper()).post { notificationManager.notify(...) } }` and `ensureBackgroundThread { ... }`, with no `goAsync()`
Problem: All the contact/bitmap work runs after `onReceive` returns, so the process may be frozen or killed before the notification is posted (the missed-call notification silently never appears). The post to the main looper is also unnecessary (`notify` is thread-safe). The `try/catch` only covers the synchronous part, so an exception in the bg callback is never caught and the notification is lost. The `catch` fallback is also never reached for async failures.
Fix: `val pending = goAsync()`; do the work on a bg thread inside try/finally and call `pending.finish()` after `notify`.

### H5-21 [Med] [perf] receivers/MissedCallReceiver.kt:75-78 (and extensions/Context.kt:421-434)
Evidence: `context.getContactFromAddress(phoneNumber) { simpleContact -> val name = helper.getNameFromPhoneNumber(phoneNumber); val photoUri = helper.getPhotoUriFromPhoneNumber(phoneNumber)`
Problem: Three lookups per missed call. `getContactFromAddress` loads ALL contacts (`getAvailableContacts`) and linear-scans, then `PhoneLookup` is queried twice more for name and photo, which `simpleContact` already provides.
Fix: Use one `PhoneLookup` (or `SimpleContactsHelper.getAvailableContacts` result) and read `simpleContact.name`/`photoUri`; drop the two extra lookups.

### H5-22 [Med] [correctness] receivers/MissedCallReceiver.kt:35, 62-68, 126-143, 272-281
Evidence: `private val missedCallCounts = mutableMapOf<String, Int>()`; `missedCallCounts[phoneNumber] = newCount`; `updateUnreadCountAndGroup` -> `if (totalMissedCalls == 0) { ... context.clearMissedCalls() }`
Problem: (a) The counter lives only in process memory and is reset on process death, so counts and the app badge are wrong (restart at 1). The system broadcast's `EXTRA_NOTIFICATION_COUNT` is ignored apart from `!= 0`. (b) After a restart, swiping one notification gives total 0, so `clearMissedCalls()` marks EVERY missed call read (`markMissedCallsAsRead` updates all `NEW=1`) while other notifications are still showing. (c) Tapping the notification body (`setContentIntent(launchIntent)`, autoCancel) does not fire the delete intent and never clears the counter or marks the call read, so the next missed call from that number shows an inflated count.
Fix: Derive counts from `CallLog` (`NEW=1 AND TYPE=MISSED` grouped by number) or persist them. Clear the number's counter/mark that number's calls read in the content intent (route through `NotificationActivity` / MISSED_CALL_CANCEL), and mark only that number's calls read on swipe.

### H5-23 [Med] [correctness] receivers/MissedCallReceiver.kt:126-143 (+ AndroidManifest.xml:336-344, activities/NotificationActivity.kt:25-28)
Evidence: manifest `android:exported="true"` with `<action android:name="com.goodwy.phone.action.MISSED_CALL_CANCEL" />`; `NotificationActivity`: `clearMissedCallCount(it); notificationManager.cancel(it.hashCode())` (the `updateUnreadCountAndGroup` call is commented out)
Problem: (a) Any app can broadcast MISSED_CALL_CANCEL (without `phoneNumber`) to clear all counters/notifications and mark all missed calls read. (b) After "Call back"/"Message" from the notification, the app badge and group summary aren't updated and the call stays `NEW=1` in the log.
Fix: Put MISSED_CALL_CANCEL on a separate receiver with `exported="false"` (the PendingIntent targets it explicitly). In `NotificationActivity`, mark that number's calls read and update the badge via a shared helper.

### H5-24 [Low] [perf] receivers/MissedCallReceiver.kt:159-177, 246-247
Evidence: `createNotificationChannel(context)` on every broadcast (`existingChannel.setSound(null, null)` + `createNotificationChannel(existingChannel)`); `context.updateUnreadCountBadge(totalMissedCalls)` inside `buildNotification`.
Problem: Mutating an existing channel has no effect after creation (Android ignores it). The badge update is a side effect hidden in a builder. `clearMissedCalls` (ContentResolver update) is also called from `onReceive` paths on the main thread (`MISSED_CALL_CANCEL`), although `markMissedCallsAsRead` only dispatches async.
Fix: Create the channel only when null; move the badge update to the caller.

### H5-25 [Low] [correctness] receivers/MissedCallReceiver.kt:62-63, 102
Evidence: `?: context.getString(R.string.unknown_caller)` then `notificationManager.notify(phoneNumber.hashCode(), ...)` and `putExtra(CURRENT_PHONE_NUMBER, phoneNumber)`
Problem: For private numbers the localised label "Unknown caller" is used as a phone number: the Call back action dials that string, and the label is looked up as a number in contacts. Counts for different unknown callers merge.
Fix: Keep `phoneNumber` nullable; disable the call back/message actions (and number lookups) when the number is null.

### H5-26 [Med] [leak] helpers/MyCameraImpl.kt:32, 169 (+ helpers/CameraFlash.kt:12-16; activities/CallActivity.kt:374; services/CallService.kt:36,121,136,145)
Evidence: `private var cameraFlash: CameraFlash? = null` (static, `@SuppressLint("StaticFieldLeak")`); `MyCameraImpl.cameraFlash = CameraFlash(context, cameraTorchListener)`; `MyCameraImpl.newInstance(this).stopSOS()`
Problem: `CameraFlash` holds the `Context` it was created with. `newInstance(this)` runs `handleCameraSetup()` in `init`, so even a bare `stopSOS()` call (CallActivity.kt:374, CallService:36/121/136/145 when SOS never started) creates the static `CameraFlash` capturing the CallActivity/CallService context. It is only nulled when the strobe thread ends or in `releaseCamera()` (never called anywhere), so the activity can stay pinned until the next SOS. Each `newInstance` also creates a `CoroutineScope(Dispatchers.Default)` that is never cancelled.
Fix: Create `CameraFlash` with `context.applicationContext`, don't call `handleCameraSetup()` from `init` (create lazily in `toggle*`), and cancel `scope` in `releaseCamera()`.

### H5-27 [Med] [correctness] helpers/CameraFlash.kt:37-52 (+ helpers/MyCameraImpl.kt:274-289)
Evidence: `catch (e: Exception) { context.showErrorToast(e); ... Handler(context.mainLooper).post(mainRunnable) }` inside `toggleFlashlight`, and in the strobe loop `try { cameraFlash!!.run { toggleFlashlight(true) } ... } catch (e: Exception) { shouldStroboscopeStop = true }`
Problem: `toggleFlashlight` swallows every exception, so the strobe loop's catch never runs. If the camera is in use or `cameraId` is null (`cameraId!!`), the SOS loop keeps running for the whole ring duration and shows an error toast and posts an EventBus `CameraUnavailable` on every blink (every 200 ms to 1.4 s) from a background thread.
Fix: Let `toggleFlashlight` rethrow (or return Boolean) and stop the loop on first failure; show the toast once.

### H5-28 [Low] [perf] helpers/CameraFlash.kt:39, 60-72 (+ MyCameraImpl.kt:277)
Evidence: `if (supportsBrightnessControl() && enable) {` -> `manager.getCameraCharacteristics(cameraId!!)` every call.
Problem: Each blink (on) performs a `getCameraCharacteristics` binder call just to read a constant.
Fix: Cache `maxBrightnessLevel` in a lazy field in `CameraFlash`.

### H5-29 [Low] [correctness] helpers/CameraFlash.kt:29-35
Evidence: `cameraId = manager.cameraIdList[0] ?: "0"`
Problem: Takes the first camera id with no check for a flash unit (`FLASH_INFO_AVAILABLE`) or back facing. On devices where id 0 has no torch the SOS never works (and shows an error toast). An empty list leaves `cameraId` null and every later `cameraId!!` throws. `torchCallback` also ignores the `cameraId` argument, so torch events from other cameras flip state.
Fix: `manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }`; compare `cameraId` in the callbacks.

### H5-30 [Low] [correctness] helpers/MyCameraImpl.kt:21-41, 260-270
Evidence: `var isFlashlightOn = false`, `private var shouldEnableFlashlight = false` (non-volatile statics shared between the UI thread and the strobe `Thread`); `if (isStroboscopeRunning || isSOSRunning) return@Runnable ... isSOSRunning = true`
Problem: The check-then-set on the running flags is not atomic, so two fast `toggleSOS` calls can start two strobe threads. `stopSOS()` followed immediately by `toggleSOS()` is dropped (the old thread is still in `sleep`, `isSOSRunning` is still true). The `shouldEnable*` flags are racy and non-volatile. The strobe `Thread` is non-daemon and unnamed, so it blinks until stopped.
Fix: Mark the flags `@Volatile` (or guard with a lock/`AtomicBoolean.compareAndSet`) and set the thread `isDaemon = true`.

### H5-31 [Low] [perf] dialogs/SelectContactDialog.kt:131-164, 63-82
Evidence: `val fixedText = query.trim().replace("\\s+".toRegex(), " ")` ... `fixedText.normalizePhoneNumber()` evaluated per phone number per contact ... `setupLetterFastScroller(contactsToShow)` per keystroke.
Problem: On every keystroke (main thread) the regex is recompiled, `normalizePhoneNumber()`/`getProperText()` is recomputed for each contact and each field, and the letter-scroller setup re-walks the list calling `getNameToDisplay()` twice per contact. `substring(0, 1)` splits surrogate pairs.
Fix: Hoist the regex (a top-level `Regex`), compute `val normalizedQuery = fixedText.normalizePhoneNumber()` once, `distinctBy` the first code point, and debounce typing.

### H5-32 [Low] [correctness] models/CallLogItem.kt:15
Evidence: `is Date -> -(timestamp / (DAY_SECONDS * 1000L)).toInt()`
Problem: The id is the UTC day number, while `dayCode` is the local day. Two different local days around midnight can map to the same stable id (and the same item id if two Date headers fall within one UTC day), which breaks `setHasStableIds`/DiffUtil identity.
Fix: Derive the id from `dayCode.hashCode()` (or the local-day number) instead of the raw timestamp.

### H5-33 [Low] [perf] helpers/Converters.kt:13-33 (used at fragments/FavoritesFragment.kt:282)
Evidence: `Converters().jsonToStringList(favoritesOrder)`
Problem: Each `Converters()` builds a `Gson`, a `TypeToken`, a `RuntimeTypeAdapterFactory` with four subtypes and a second `Gson` (`gsonTimer`) just to parse a string list. `jsonToStringList` also returns a platform type that can be null for `"null"`.
Fix: Use a shared `Converters` instance (or make `gsonTimer` lazy), and use `?: arrayListOf()` on the result.

### H5-34 [Low] [correctness] helpers/CallHistoryTopBehavior.kt:34-38
Evidence: `val screenWidthDp = context.resources.configuration.screenWidthDp * density` ... `if (screenWidthDp < 1200) 0.092 else 0.088`
Problem: The variable is named dp but holds pixels (dp x density), so the 1200 breakpoint behaves differently per screen density (wrong factor on 3x screens). `setUpViews` is evaluated once, so it does not follow a size change without recreation.
Fix: Compare the dp value (`configuration.screenWidthDp < 1200`) and multiply by density only for the offset.

### H5-35 [Low] [correctness] dialogs/ShowGroupedCallsDialog.kt:13-38
Evidence: `class ShowGroupedCallsDialog(...)` with only caller commented out (adapters/RecentCallsAdapter.kt:430: `//        ShowGroupedCallsDialog(activity, recentCalls)`)
Problem: Dead code. The `dialog` field is written but never read, and `activity.runOnUiThread` inside `init` is pointless.
Fix: Delete the file.

## Not found / notes
- No arbitrary delays (postDelayed/sleep workarounds) in these files. `Thread.sleep` in MyCameraImpl is the intended SOS/strobe timing. `post { scrollToPosition(0) }` in SelectContactDialog is legitimate.
- ExportCallHistoryDialog.kt, UriUtils.kt: no issues found.
- CallHistoryActivity does not register a ContentObserver on CallLog, so the open history screen is stale until the next resume (e.g. a new call arriving while it is open). It is also only refreshed via `onResume`.

## Coverage (lines read)
- activities/CallHistoryActivity.kt: lines read 1-1441
- receivers/MissedCallReceiver.kt: lines read 1-283
- helpers/CallHistoryTopBehavior.kt: lines read 1-115
- helpers/CallerNotesHelper.kt: lines read 1-39
- dialogs/ShowGroupedCallsDialog.kt: lines read 1-38
- dialogs/ExportCallHistoryDialog.kt: lines read 1-34
- dialogs/SelectContactDialog.kt: lines read 1-176
- models/CallLogItem.kt: lines read 1-19
- helpers/Converters.kt: lines read 1-47
- helpers/MyCameraImpl.kt: lines read 1-368
- helpers/CameraFlash.kt: lines read 1-94
- helpers/UriUtils.kt: lines read 1-53
