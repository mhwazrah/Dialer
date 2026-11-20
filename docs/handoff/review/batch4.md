# Batch 4 findings (D4-)

Source root: app/src/main/kotlin/com/goodwy/dialer

### D4-01 [High] [perf] activities/DialpadActivity.kt:1196
Evidence: `val filtered = allContacts.filter { contact -> val convertedName = DialpadT9.convertLettersToNumbers(contact.name.normalizeString().uppercase(), lang) ... convertedNameToDisplay ... convertedCompany ...` (4 T9 conversions + 4 `.filter{isDigit}` + `doesContainPhoneNumber(text, convertLetters=true, search=true)` per contact)
Problem: Runs on the main thread on every keystroke over ALL contacts. Per contact per key it allocates ~12 strings, normalizes and converts the name 4 times (name and getNameToDisplay are usually the same string). The result for a contact depends only on the contact, not on the typed text, so it is recomputed needlessly.
Fix: Precompute once in gotContacts (background) a per-contact search key (list of converted strings and digit-only strings, deduplicated) and cache it with the contact list (e.g. `class SearchEntry(contact, keys: List<String>)`). Per keystroke only `keys.any { it.contains(text) }` plus the phone check. Run the filter off the main thread, or at least debounce.

### D4-02 [High] [perf] activities/DialpadActivity.kt:1196-1248
Evidence: with empty input `text == ""`, `convertedName.contains("", true)` is true for every contact, so `filtered` = whole address book; it is then sorted and `ContactsAdapter(... contacts = filtered ...)` is built and set, although `binding.dialpadList.beVisibleIf(filtered.isNotEmpty() && binding.dialpadInput.value.isNotEmpty() && ...)` hides it. Same when `config.searchContactsInDialpad` is false.
Problem: Full filter + sort + new adapter for a list that is not shown (every open of the dialpad, every clear, every backspace to empty, every onResume via refreshItems).
Fix: At top of the contacts section: `val filtered = if (text.isEmpty() || !config.searchContactsInDialpad) arrayListOf() else {...}` and skip adapter creation when empty (set `binding.dialpadList.adapter = null` or leave).

### D4-03 [Med] [perf] activities/DialpadActivity.kt:1190-1195
Evidence: `val collator = Collator.getInstance(sysLocale())` / `val callCounts = HashMap<String, Int>(); allRecentCalls.forEach { call -> val key = call.phoneNumber.normalizePhoneNumber() ...}` / `val isAutoLang = DialpadT9.getSupportedSecondaryLanguages().contains(langLocale)`
Problem: Per keystroke: Collator instance is cloned, normalizePhoneNumber (regex-ish) runs for every recent call (up to queryLimitRecent, can be thousands), and getSupportedSecondaryLanguages builds and sorts a new list. callCounts only changes when allRecentCalls changes; lang only changes with settings.
Fix: Compute `callCounts` in refreshCallLog/gotRecents (when `allRecentCalls` is assigned) and cache `lang`/collator in fields refreshed in onResume. Make DialpadT9.getSupportedSecondaryLanguages return a cached sorted list.

### D4-04 [Med] [perf] activities/DialpadActivity.kt:1219-1228
Evidence: `.sortedWith(compareByDescending<Contact> { contact -> contact.phoneNumbers.maxOfOrNull { callCounts[it.value.normalizePhoneNumber()] ?: 0 } ?: 0 }.thenByDescending { DialpadT9.convertLettersToNumbers(it.getNameToDisplay().normalizeString().uppercase(), lang).filter{...}.startsWith(text) }.thenBy(collator) { it.getNameToDisplay() })`
Problem: Selector lambdas are evaluated on every comparison (O(n log n) times): normalizePhoneNumber for each number, T9 conversion, collator-keyed compare. Then `.toMutableList() as ArrayList<Contact>` is an unchecked cast that works only because toMutableList happens to return ArrayList.
Fix: Compute call count and T9 prefix flag once per contact (map to a small holder, or `sortedBy` on a precomputed list) then sort. Use `ArrayList(list)` instead of the cast.

### D4-05 [Med] [perf] helpers/DialpadT9.kt:78-91
Evidence: `var res = ""; for (c in letters) { res += if (lang != null && lang.charMap.containsKey(c)) { lang.charMap[c] } ...`
Problem: O(n^2) string concatenation with boxed Int/Char, double map lookups; called ~5x per contact per keystroke (DialpadActivity, also ContactsAdapter.kt:408, RecentsFragment.kt:381-388).
Fix: `val sb = StringBuilder(letters.length); for (c in letters) sb.append(lang?.charMap?.get(c) ?: langEn?.charMap?.get(c) ?: c); return sb.toString()`.

### D4-06 [Med] [perf] helpers/DialpadT9.kt:94-101
Evidence: `fun getSupportedSecondaryLanguages(): List<String> { val res = MutableList(0) {""}; for (lang in languages.keys) {...}; res.sort(); return res }`
Problem: Builds + sorts a new list on every call; called from dialpadValueChanged (every keystroke), 3 initLetters* per resume, ContactsAdapter.kt:470 (per bind) and RecentsFragment.kt:378.
Fix: Cache: `private var supported: List<String>? = null` reset in readFromJson, return cached.

### D4-07 [Med] [correctness] activities/DialpadActivity.kt:1148-1153
Evidence: `allContacts = newContacts` ... `val privateContacts = MyContactsContentProvider.getContacts(this, privateCursor)` / `allContacts.addAll(privateContacts)` / `allContacts.sort()`
Problem: The shared field is published before the background thread mutates/sorts it. `dialpadValueChanged` on the main thread (typing, onResume->refreshItems, onActivityResult) can iterate it concurrently -> ConcurrentModificationException. Also the private query (`getContacts` + cursor) runs in the callback thread, fine, but `allContacts` is a non-volatile field read on main.
Fix: Build `val merged = ArrayList(newContacts); merged.addAll(private); merged.sort()` first, then `allContacts = merged` (inside runOnUiThread or @Volatile).

### D4-08 [Med] [leak] activities/DialpadActivity.kt:149 (also SettingsDialpadActivity.kt:130)
Evidence: `privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)` (field, created in onCreate)
Problem: ContentResolver query on the main thread in onCreate. Cursor is only closed if `gotContacts` runs (commons closes it with use{}); if contacts permission is missing/callback never fires, or the activity is destroyed before, the cursor leaks. In SettingsDialpadActivity the field `privateCursor` is never used or closed at all (confirmed: only lines 57, 130) -> guaranteed leaked cursor each open.
Fix: Dialpad: delete the field and call `getMyContactsCursor(...)` inside gotContacts (background thread) right before `MyContactsContentProvider.getContacts`. Settings: delete lines 57 and 130.

### D4-09 [Med] [leak] activities/SettingsDialpadActivity.kt:132 and helpers/ToneGeneratorHelper.kt:13
Evidence: `toneGeneratorHelper = ToneGeneratorHelper(this, DIALPAD_TONE_LENGTH_MS)` (Settings; field never read) / `private val toneGenerator: ToneGenerator? = try { ... ToneGenerator(DIAL_TONE_STREAM_TYPE, volume) }` with no `release()` anywhere
Problem: ToneGenerator owns native AudioTrack/resources and must be `release()`d. Settings creates one that is never used. DialpadActivity creates one in onCreate and a NEW one in onResume when the volume changed (line 182) without releasing the previous, and never releases in onDestroy. Relies on finalizer; can exhaust the limited tone generator instances.
Fix: Add `fun release() { handler.removeCallbacksAndMessages(null); toneGenerator?.release() }` to ToneGeneratorHelper; call it in DialpadActivity.onDestroy and before re-creating at line 182; delete the unused helper in SettingsDialpadActivity (lines 58, 132).

### D4-10 [Med] [correctness] helpers/ToneGeneratorHelper.kt:37-46
Evidence: `Handler(Looper.getMainLooper()).postDelayed({ toneGenerator?.stopTone() }, minToneLengthMs - timeSinceStartMs)`
Problem: (a) The delayed stop is never cancelled: a quick second tap (start key B within the min length) gets cut by the pending stop of key A. (b) New Handler per call; runnable keeps the helper (and thus the Activity-owned ToneGenerator) alive after destroy. The delay itself is a deliberate minimum tone length, not arbitrary.
Fix: One `private val handler = Handler(Looper.getMainLooper())` field; `handler.removeCallbacksAndMessages(null)` at the start of `startTone(char)` and in `release()`.

### D4-11 [Low] [perf] helpers/ToneGeneratorHelper.kt:22-24
Evidence: `audioManager.ringerMode in arrayOf(AudioManager.RINGER_MODE_SILENT, AudioManager.RINGER_MODE_VIBRATE)`
Problem: Allocates an array per key press.
Fix: `val mode = audioManager.ringerMode; return mode == RINGER_MODE_SILENT || mode == RINGER_MODE_VIBRATE`.

### D4-12 [Med] [perf] activities/DialpadActivity.kt:215-232
Evidence: `binding.dialpadList.addOnScrollListener(object : RecyclerView.OnScrollListener() {...})` and same for `dialpadRecentsList`, inside `onResume()`
Problem: A new listener is added on every resume and never removed, so after N resumes each scroll state change runs N listeners (the activity is resumed again after every call/back from settings/contact details).
Fix: Add the listeners once in onCreate (or keep a single field listener and `removeOnScrollListener` first). Same for lines 234-243 (idempotent, fine) and `setupCharClick` calls re-run in each initStyle (idempotent).

### D4-13 [Med] [perf] activities/DialpadActivity.kt:275-279 (also SettingsDialpadActivity.kt:376-378, 531-534, 633-636; MainActivity.kt:1252)
Evidence: `val reader = InputStreamReader(resources.openRawResource(R.raw.t9languages)); DialpadT9.readFromJson(reader.readText())`
Problem: Main-thread raw resource read + Gson parse, copy-pasted in 5 places; the reader/stream is never closed (leaked file descriptor until GC). `Initialized` is a plain public var.
Fix: Add `DialpadT9.ensureInitialized(context)` which does `context.resources.openRawResource(R.raw.t9languages).bufferedReader().use { readFromJson(it.readText()) }` guarded by `if (Initialized) return`; call it from all sites (ideally at app start on a background thread).

### D4-14 [Med] [perf] activities/DialpadActivity.kt:1561 (prepareCallLog) / 1502-1530
Evidence: `ContactsHelper(this).getContacts(showOnlyContactsWithNumbers = true) { contacts -> ensureBackgroundThread { val privateContacts = getPrivateContacts() ...` inside `prepareCallLog`, which runs for both `refreshCallLog(loadAll=false)` and the follow-up `refreshCallLog(loadAll=true)`; plus `getRecentCalls(queryLimit = queryCount, updateCallsCache = false)` in the `loadAll` branch.
Problem: Every refresh re-queries ALL contacts and the private contacts provider again (twice per onResume, plus in onCreate at line 164) although `allContacts` already holds them; and the loadAll refresh queries the whole call log a second time just to build `recentOutgoingNumbers`. onResume triggers this each time the screen returns.
Fix: Reuse `allContacts` (already loaded, includes private) in `updateNamesIfEmpty`; compute `recentOutgoingNumbers` from the `calls` already loaded in `getRecentCalls(loadAll=true)` instead of a second query; skip the second pass when nothing changed.

### D4-15 [Med] [perf] activities/DialpadActivity.kt:1592-1610
Evidence: `calls.map { call -> if (call.phoneNumber == call.name) { val privateContact = privateContacts.firstOrNull { it.doesContainPhoneNumber(call.phoneNumber) }; val contact = contactsWithNumbers.firstOrNull { it.phoneNumbers.first().normalizedNumber == call.phoneNumber }`
Problem: O(calls x contacts) linear scans with phone normalization inside. Also correctness: only the contact's FIRST phone number is compared, so calls from a contact's 2nd/3rd number stay unnamed; compares to `normalizedNumber` (not PhoneLookup).
Fix: Build once `val byNumber = HashMap<String, Contact>()` over all `phoneNumbers.map { it.normalizedNumber }` (plus private) and look up `byNumber[call.phoneNumber.normalizePhoneNumber()]`.

### D4-16 [Med] [correctness] activities/DialpadActivity.kt:1503-1508 and 1471-1475
Evidence: `getRecentCalls(loadAll) { allRecentCalls = it; runOnUiThread { gotRecents(it) }; callback?.invoke(); ...` with callback = `{ binding.dialpadRecentsList.runAfterAnimations { refreshCallLog(loadAll = true) } }`
Problem: The callback is invoked on the background thread produced by `prepareCallLog` (ensureBackgroundThread) and touches a view (`isComputingLayout`, `itemAnimator.isRunning`) off the main thread; `allRecentCalls` (non-volatile) is also written off-main while the main thread reads it in dialpadValueChanged. Callbacks also run after the activity is destroyed with no `isDestroyed` check (binding access).
Fix: Invoke the callback inside `runOnUiThread { gotRecents(it); callback?.invoke() }`; mark `allRecentCalls` @Volatile; early-return `if (isDestroyed) return` in the callbacks.

### D4-17 [Med] [integration] activities/DialpadActivity.kt:164
Evidence: `ContactsHelper(this).getContacts(showOnlyContactsWithNumbers = true) { allContacts -> gotContacts(allContacts) }` (only in onCreate)
Problem: No ContentObserver and no reload in onResume: contacts added/edited/deleted while the dialpad is alive (e.g. user taps "add to contact" via `startAddNumberToContact`, saves, comes back) stay stale in search; also `findContactByCall`, `startContactDetailsIntent` use the stale list. Permission revoked/granted while away is not handled either.
Fix: Reload contacts in `onRestart()` (or when returning from add/edit contact) via the same `getContacts` + gotContacts path (after D4-07 fix), or register a ContentObserver on `ContactsContract.Contacts.CONTENT_URI` with a debounce.

### D4-18 [Med] [perf] activities/DialpadActivity.kt:156-158
Evidence: `addTextChangedListener(PhoneNumberFormattingTextWatcher(Locale.getDefault().country))` then `onTextChangeListener { dialpadValueChanged(it) }`
Problem: PhoneNumberFormattingTextWatcher edits the Editable inside afterTextChanged, which re-fires afterTextChanged for the listeners after it; `dialpadValueChanged` (the whole search, D4-01..04) can run twice per key press (once with unformatted, once with formatted text) when formatting is on. Verify against commons `onTextChangeListener` (afterTextChanged-based).
Fix: Skip when the formatter is mid-edit, or debounce/coalesce: ignore the call if `textFormat == lastText`; better, make the search dispatch via a single posted Runnable that is replaced on each change (`removeCallbacks` + `post`) so only the final text is searched.

### D4-19 [Low] [perf] activities/DialpadActivity.kt:1264-1267, 1250-1262
Evidence: `val filteredRecents = allRecentCalls.filter {... doesContainPhoneNumber(text) ...}.sortedWith(...)` then `recentsAdapter?.updateItems(filteredRecents)` every keystroke
Problem: Also computed for every key even when the recents list is hidden (`!config.showRecentCallsOnDialpad`). updateItems per key triggers diff/rebind of a list that may be invisible.
Fix: Return early / skip when `!config.showRecentCallsOnDialpad`; only filter when `recentsAdapter` is visible.

### D4-20 [Med] [correctness] activities/ManageSpeedDialActivity.kt:86-92
Evidence: `if (allContacts.isEmpty()) { showAddSpeedDialDialog(clickedContact) }` (no return) then `var readPhoneState = false; handlePermission(PERMISSION_READ_PHONE_STATE) { permission -> readPhoneState = permission }` then builds the items/RadioGroupIconDialog
Problem: (1) When contacts are not yet loaded/empty, two dialogs are shown stacked (add-number dialog and the options dialog). (2) `handlePermission` is asynchronous when the permission is not granted: `readPhoneState` is read before the callback fires, so the voicemail option is missing and a permission prompt pops up concurrently with the dialog, on every click.
Fix: `return@SpeedDialAdapter` after showAddSpeedDialDialog; use `hasPermission(PERMISSION_READ_PHONE_STATE)` (no prompt) for the item list and request the permission only when the voicemail item is chosen.

### D4-21 [Med] [correctness] activities/ManageSpeedDialActivity.kt:54-61
Evidence: `ContactsHelper(this).getContacts(...) { contacts -> allContacts.addAll(contacts); ... allContacts.addAll(privateContacts); allContacts.sort() }`
Problem: Background thread mutates/sorts the list that the main thread reads (click handler at line 86, SelectContactDialog) -> race / CME; callback after destroy retains activity-owned list only briefly (minor).
Fix: Build a local sorted list in the callback and assign `allContacts = list` via `runOnUiThread` (make field `@Volatile var`).

### D4-22 [Low] [correctness] activities/ManageSpeedDialActivity.kt:146-173
Evidence: `if (voiceMailNumber !=null) {...} else { toast(R.string.unknown) }; updateAdapter(); showHideVoicemailIcon(clickedContact.id, true)`
Problem: When no voicemail number is found, nothing was assigned but the user is still asked "show voicemail icon". `telecomManager.getVoiceMailNumber(handle)` also needs READ_PHONE_STATE and can throw SecurityException if the permission was revoked (no try/catch).
Fix: Move `updateAdapter()`/`showHideVoicemailIcon` into the non-null branch; wrap the call in try/catch(SecurityException).

### D4-23 [Low] [perf] activities/ManageSpeedDialActivity.kt:83-189 (updateAdapter)
Evidence: `SpeedDialAdapter(this, speedDialValues, this, binding.speedDialList) {...}.apply { binding.speedDialList.adapter = this }`, called after every edit.
Problem: A new adapter (and click lambda with all the dialog logic) is built for each change instead of `notifyItemChanged`; also `config.speedDial` is saved only in onStop (data lost if the process is killed while backgrounded before onStop completes is unlikely, but edits are not persisted when the app is killed from recents while still resumed).
Fix: Keep one adapter and call `adapter.notifyDataSetChanged/notifyItemChanged(index)`; persist `config.speedDial = Gson().toJson(speedDialValues)` also in onPause.

### D4-24 [Low] [correctness] dialogs/AddSpeedDialDialog.kt:37-39
Evidence: `val newTitle = binding.addSpeedDialEditText.text.toString(); callback(newTitle); alertDialog.dismiss()`
Problem: Input is not trimmed, so " 123" is stored as the number and dialed with a leading space (isValid trims, the dial path does not); an empty entry is accepted and then ManageSpeedDialActivity.showAddSpeedDialDialog still prompts about the voicemail icon (`showHideVoicemailIcon(..., true)` at line 212) even though no voicemail was set. `private var dialog` is assigned but never read; `hint = speedDial.number` is redundant with setText.
Fix: `val newNumber = text.toString().trim()`; ignore/toast on empty; remove the unused `dialog` field; only call showHideVoicemailIcon when number is voicemail (not for plain numbers, line 212 passes `true` for any typed number).

### D4-25 [Low] [correctness] models/SpeedDial.kt:15-17
Evidence: `if (type != null && label != null) { "$displayName - ${context.getPhoneNumberTypeText(type!!, label!!)}"`
Problem: `type` and `label` are mutable vars; `!!` after the null check can NPE if modified from another thread; style only.
Fix: `val t = type; val l = label; if (t != null && l != null) ...`.

### D4-26 [Low] [leak] activities/DialpadActivity.kt:1405-1409, 262-268, 1034
Evidence: `longPressHandler.postDelayed({ performLongClick(view, char) }, longPressTimeout)`; onPause/onDestroy never call `longPressHandler.removeCallbacksAndMessages(null)`.
Problem: A pending long-press runnable (holds view/Activity) can fire after the activity is paused/destroyed and launch SettingsActivity/SettingsDialpadActivity or edit the input. Duration is only the long-press timeout, so a short-lived leak, but still wrong behavior when the app is backgrounded mid-press.
Fix: `longPressHandler.removeCallbacksAndMessages(null)` in onPause and onDestroy.

### D4-27 [Low] [perf] activities/DialpadActivity.kt:1140-1145
Evidence: `lifecycleScope.launch { delay(1000); clearInput() }`
Problem: Arbitrary 1000 ms delay (lets the call UI come up before clearing). Not a leak (lifecycleScope), but stacked taps launch multiple coroutines, and `clearInput` then triggers a full dialpadValueChanged on a possibly paused activity.
Fix: Keep a `Job` field, cancel before relaunching; or clear in onResume/onStart when a flag `clearOnReturn` is set (no timer needed).

### D4-28 [Low] [perf] activities/DialpadActivity.kt:148, 188, 270-273
Evidence: `speedDialValues = config.getSpeedDialValues()` in onCreate, onResume and onRestart
Problem: Gson/prefs parse on the main thread three times on a normal restart (onRestart is always followed by onResume).
Fix: Remove the onRestart override and the onCreate assignment; keep the one in onResume (same in SettingsDialpadActivity lines 129, 156, 235-238; the value is not even used there, `speedDialValues` is write-only in Settings, delete).

### D4-29 [Low] [perf] activities/DialpadActivity.kt:173-260 (onResume) and 275-420 (initStyle)
Evidence: onResume re-runs initStyle -> initLetters* (re-setting typefaces/text/sizes of 9 labels, all listeners, `ResourcesCompat.getDrawable`, `applyGlassSurface()`, `updateTextColors`) on every resume.
Problem: Heavy, repeated work whenever the user returns (after each call). Only needed after a settings change, which already triggers `finish(); startActivity(intent)` at line 175-179 via needRestart/style/colour checks.
Fix: Move the static setup into onCreate (after DialpadT9 init) and keep only `refreshItems()` and the volume/TalkBack checks in onResume.

### D4-30 [Low] [leak] activities/DialpadActivity.kt:701-703 and SettingsDialpadActivity.kt:1078-1080, 1193-1195, 1513-1515
Evidence: `RxAnimation.from(dialpadCallButtonIosHolder).shake().subscribe()`
Problem: Returned Disposable is ignored; if the Activity is destroyed during the animation the subscription keeps the view (and Activity) alive until the animation completes. Short-lived.
Fix: Keep a `CompositeDisposable` and dispose in onDestroy, or accept.

### D4-31 [Low] [leak] activities/SettingsDialpadActivity.kt:59, 1034, 1037-1044
Evidence: `hideDialpadHandler.postDelayed(updateHideDialpadTask, 2000)`; no `removeCallbacks` in onDestroy/onPause.
Problem: Runnable (an Activity-capturing lambda) lives up to 2 s after the activity is destroyed and touches `binding` of a dead activity. The 2000 ms delay is a deliberate preview-hide timeout.
Fix: `hideDialpadHandler.removeCallbacks(updateHideDialpadTask)` in onDestroy.

### D4-32 [Low] [perf] activities/SettingsDialpadActivity.kt:754-757 (and 793-796, 848-851, 916-919, 956-959, 1485-1489)
Evidence: `override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) { updateDialpadSize(progress); config.dialpadSize = progress }`
Problem: A SharedPreferences write on every progress tick while dragging (and on every +/- tap and also whenever onResume re-sets `progress` programmatically, e.g. line 715/1464 with the listener already attached), 6 seekbars.
Fix: Update UI in onProgressChanged, persist in onStopTrackingTouch (and when `!fromUser`, only if the value differs from the stored one).

### D4-33 [Low] [perf] activities/SettingsDialpadActivity.kt:198-232
Evidence: onResume calls `setupPurchaseThankYou(); setupDialpadStyle(); ... setupToneVolume(); setupButtonSize()` and `PurchaseHelper().checkPurchase(...)` on every resume (callback `if (updatePro) updatePro()` touches `binding` after possible destroy).
Problem: Re-runs all listener setup and a billing query each resume; no destroyed check in the async callback.
Fix: Do setup once in onCreate; in onResume only refresh pro state; guard callback with `if (!isDestroyed)`.

### D4-34 [Low] [perf] activities/SettingsDialpadActivity.kt:1232-1241
Evidence: `val recents = config.parseRecentCallsCache(); ... config.recentCallsCache = Gson().toJson(recentsNew.take(RECENT_CALL_CACHE_SIZE))`
Problem: Parses and rewrites the whole recents cache JSON on the main thread inside a colour-picker callback.
Fix: Move into `ensureBackgroundThread { ... }`.

### D4-35 [Low] [correctness] activities/SettingsDialpadActivity.kt:1296-1305, 383, 538, 640
Evidence: `val locale = Locale(lang!!)` / `if (langPref!! != LANGUAGE_NONE && ...)` (DialpadActivity 459, 662, 806 too)
Problem: `dialpadSecondaryLanguage` is nullable; `!!` throws NPE if the pref is null. DialpadActivity.kt:1185 correctly uses `?: ""`. `Locale(String)` is deprecated.
Fix: `val langPref = config.dialpadSecondaryLanguage ?: LANGUAGE_NONE`; use `Locale.forLanguageTag(lang)`.

### D4-36 [Low] [integration] activities/DialpadActivity.kt:1049-1061
Evidence: `checkDialIntent()` only called from `gotContacts`, reads `intent` of the first launch; no `onNewIntent`.
Problem: If DialpadActivity is already running and another `tel:` DIAL intent arrives (manifest declares no singleTop, so usually a new instance, but with `launchActivityIntent`/finishAffinity flows it can reuse), the number is ignored; also on recreate after rotation the intent number overwrites what the user typed (rotation is handled via configChanges="orientation" so low risk). Also `Uri.decode(dataString).substringAfter("tel:")` keeps `?`/`;` URI params.
Fix: Override onNewIntent to `setIntent(intent); checkDialIntent()`; strip params with `Uri.parse(dataString).schemeSpecificPart.substringBefore(';')`.

### D4-37 [Low] [perf] activities/DialpadActivity.kt:1308 (initCall with empty number)
Evidence: `RecentsHelper(this).getRecentCalls(queryLimit = 1) { val mostRecentNumber = ...; runOnUiThread { binding.dialpadInput.setText(...) } }`
Problem: New helper per tap (a `recentsHelper` field exists); callback runs after destroy without guard; `getRecentCalls` default may update calls cache (check `updateCallsCache` default) and overwrite the stored recents cache with just 1 entry.
Fix: Use the `recentsHelper` field with `updateCallsCache = false`; guard with `if (isDestroyed) return`.

### D4-38 [Low] [perf] activities/DialpadActivity.kt:1634
Evidence: `itemDelete = { deleted -> allRecentCalls = allRecentCalls.filter { it !in deleted } }`
Problem: O(n x m) list `in` check on the main thread (data-class equals).
Fix: `val del = deleted.toHashSet(); allRecentCalls = allRecentCalls.filterNot { it in del }`.

## Coverage (lines read)
- activities/DialpadActivity.kt: lines read 1-1741
- helpers/DialpadT9.kt: lines read 1-103
- helpers/ToneGeneratorHelper.kt: lines read 1-67
- activities/SettingsDialpadActivity.kt: lines read 1-1521
- models/SpeedDial.kt: lines read 1-20
- activities/ManageSpeedDialActivity.kt: lines read 1-241
- dialogs/AddSpeedDialDialog.kt: lines read 1-45
(Also checked extensions/LiquidGlass.kt 1-140 and RecyclerView.kt for context only.)
