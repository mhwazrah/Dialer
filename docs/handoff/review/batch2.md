# Batch 2 review (ID prefix M2-)

Contact loads per MainActivity.onResume (counted): onResume -> `refreshItems(true)` -> `refreshFragments()`:
1. `cacheContacts()` -> `ContactsHelper.getContacts(getAll = true, ...)` (load 1)
2. `ContactsFragment.refreshItems` -> `getContacts(...)` (load 2) AND inside it `(activity as MainActivity).cacheContacts()` (load 3, same as load 1)
3. `FavoritesFragment.refreshItems` -> `getContacts { }` (load 4, whole book just to keep starred)
4. `RecentsFragment.refreshItems` -> quick pass (0 loads) then full pass -> `RecentsHelper.getRecentCalls` -> `getContacts(getAll = true, ...)` (load 5)
=> 5 full contact-book loads per resume (3 of them the identical getAll=true query), plus 4 main-thread private-contact cursor queries. Every call-log observer / RefreshCallLog event adds one more full load (recents full pass).

### M2-01 [High] [correctness] fragments/RecentsFragment.kt:517-520
Evidence:
```
//Deleting notes if a call has already been deleted
context.callerNotesHelper.removeCallerNotes(
    it.map { recentCall -> recentCall.phoneNumber.numberForNotes()}
)
```
(CallerNotesHelper.kt:29-33: `mCallerNotes.filter { allRecentsNumber.contains(...) }` then saves.)
Problem: runs after EVERY pass, including the quick pass (limit `QUERY_LIMIT = 100`), the blocked-number-filtered list, the private-filtered list, and the empty list returned when READ_CALL_LOG is missing/revoked. Any caller note whose number is not in that list is permanently deleted (data loss). Quick pass alone wipes notes for calls older than the newest 100.
Fix: only prune from the full pass (`loadAll`), only when the call-log permission is held, and against an unfiltered (not blocked/private-filtered) number set; ideally query the log for the note numbers instead.

### M2-02 [High] [perf] activities/MainActivity.kt:1099-1104 + fragments/ContactsFragment.kt:133 + fragments/FavoritesFragment.kt:80
Evidence:
```
fun refreshFragments() { cacheContacts(); getContactsFragment()?.refreshItems(); getFavoritesFragment()?.refreshItems(); getRecentsFragment()?.refreshItems() }
...
(activity as MainActivity).cacheContacts()      // ContactsFragment.refreshItems, again
ContactsHelper(context).getContacts { contacts ->  // FavoritesFragment: whole book for starred only
```
Problem: 5 full contact loads per resume (see header); 3 are the same getAll=true query; also reruns on every sort/filter dialog, adapter-triggered refresh and `startNameWithSurnameChanged`.
Fix: load once per refresh (getAll=true, with private contacts) in MainActivity, derive Contacts (ignored-source filter), Favorites (`starred==1`) and cachedContacts from that single list, and let RecentsHelper reuse it; at minimum delete the `cacheContacts()` call in ContactsFragment.refreshItems and the one in refreshFragments (the Contacts load can fill the cache).

### M2-03 [Med] [perf] activities/MainActivity.kt:293-295
Evidence:
```
if (/*!isSearchOpen && */!binding.mainMenu.isSearchOpen) {
    refreshItems(true)
}
```
Problem: unconditional full reload (5 contact loads, call log quick+full pass, adapter updates) on every resume, e.g. returning from the dialpad, a call screen or a permission dialog, even when nothing changed.
Fix: register ContactsContract (`ContactsContract.Contacts.CONTENT_URI`) and CallLog observers and reload only when marked dirty (or after N seconds away / config change); keep recents cache render on resume.

### M2-04 [Med] [integration] activities/MainActivity.kt:211-219, 1258-1262
Evidence:
```
contentResolver.registerContentObserver(android.provider.CallLog.Calls.CONTENT_URI, true, callLogObserver)
...
@Subscribe(threadMode = ThreadMode.MAIN)
fun refreshCallLog(event: Events.RefreshCallLog) { config.needUpdateRecents = true; getRecentsFragment()?.refreshItems(needUpdate = true) }
```
Problem: (a) the end of a call triggers BOTH the EventBus refresh (immediately) and the observer refresh (400 ms later) = two quick+full pipelines, (b) EventBus stays registered between onCreate/onDestroy so a stopped activity (e.g. behind CallActivity) runs the whole pipeline in the background although onResume reloads anyway, (c) there is no ContactsContract observer at all, so contact edits made elsewhere are only seen via the resume reload.
Fix: have the EventBus handler only set `needUpdateRecents = true` while not resumed (or drop it in favour of the observer); register the observer in onStart/onStop-safe fashion; add a contacts observer (see M2-03).

### M2-05 [Med] [integration] helpers/RecentsHelper.kt:499-543 (observer feedback)
Evidence:
```
.update(Calls.CONTENT_URI, values, Calls.NUMBER + " = ? AND " + Calls.COUNTRY_ISO + " IS NULL", arrayOf(number))
```
Problem: (a) modern call-log rows normally have COUNTRY_ISO populated, so this update matches few/none: the cached name/photo never get corrected for those rows, `updateCallsCache = loadAll` re-issues the same failing/no-op update for every row on every full pass, and the quick pass (which paints from CACHED_NAME) shows stale names until the full pass; (b) when it does match, the write notifies MainActivity's own callLogObserver, which schedules another full reload (self-triggered loop until the cache converges); (c) one update per ROW even when the same number repeats, plus `hasPermission` per row.
Fix: dedupe by number per pass (HashSet), match on `NUMBER = ?` only (or `NUMBER = ? AND (COUNTRY_ISO IS NULL OR COUNTRY_ISO = ?)`), batch via `applyBatch`, and ignore observer events for a short window after our own writes (or skip refresh when only CACHED_* columns changed).

### M2-06 [Med] [leak] activities/MainActivity.kt:1224-1227, fragments/ContactsFragment.kt:122-128, fragments/FavoritesFragment.kt:79-85
Evidence:
```
val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
ContactsHelper(this).getContacts(...) { contacts ->
    if (SMT_PRIVATE !in config.ignoredContactSources) {
        val privateContacts = MyContactsContentProvider.getContacts(this, privateCursor)
```
Problem: `getContacts(ctx, cursor)` is what closes the cursor (use{}); when SMT_PRIVATE is ignored (or the callback never runs, e.g. no contacts permission) the cursor opened here is never closed (3 call sites, every refresh). Also the query itself runs on the MAIN thread (all three are called from UI-thread refreshItems/refreshFragments).
Fix: obtain the cursor inside the callback/background thread only in the branch that consumes it (`if (SMT_PRIVATE !in ignored) { getMyContactsCursor(...)... }`), or `privateCursor?.close()` in the else branch.

### M2-07 [Med] [correctness] activities/MainActivity.kt:1234-1238 (+ fragments/RecentsFragment.kt:665)
Evidence:
```
try { cachedContacts.clear(); cachedContacts.addAll(contacts) } catch (_: Exception) { }
...
(activity as MainActivity).cachedContacts.find { it.doesHavePhoneNumber(recentCall.phoneNumber) }
```
Problem: `cachedContacts` is a plain ArrayList mutated on ContactsHelper's background thread (clear then addAll) while the UI thread iterates it in `findContactByCall` (profile icon click / open): ConcurrentModificationException, or a transient empty list making a saved contact open the "add number to contact" flow. The try/catch only hides writer-side errors. Same unsynchronized pattern for `cachedFavorites` (written on UI thread, read inside `ensureBackgroundThread` in createContactShortcuts line 611) and `RecentsFragment.allRecentCalls` (written on bg thread in refreshCallLog line 506, read on UI thread).
Fix: build a new list and assign it to a `@Volatile var cachedContacts: List<Contact>` (immutable snapshot); same for cachedFavorites/allRecentCalls.

### M2-08 [Med] [perf] activities/MainActivity.kt:822-826, 832-836, 855-863
Evidence:
```
binding.mainMenu.viewTreeObserver.addOnPreDrawListener {
    clearMainMenuPaint()
    styleGlassSearchField()
    true
}
...
val glassBackground = getDrawable(if (light) R.drawable.bg_glass_search_light else R.drawable.bg_glass_search_dark)
if (holder.tag == light && holder.background?.constantState == glassBackground?.constantState) return
```
Problem: runs on every draw of the toolbar: `clearMainMenuPaint` walks the whole subtree (allocating a `children` sequence per ViewGroup), and `styleGlassSearchField` creates a new Drawable via `getDrawable` BEFORE the cheap gate check. Per-frame allocation while scrolling/animating.
Fix: gate first on `holder.tag == light && holder.background is the glass drawable` without creating a drawable (cache the two drawables' constantState or a flag set when applied); only walk the tree when commons has actually repainted (hook the commons `updateColors` call sites instead of pre-draw).

### M2-09 [Med] [perf] activities/MainActivity.kt:733-735, 799-817
Evidence:
```
override fun onPageScrolled(...) { if (config.bottomNavigationBar && config.changeColourTopBar) scrollChange() }
...
private fun scrollChange() { ... getCurrentFragment() ... updateColors ... setupSearchMenuScrollListener(...) ; clearMainMenuPaint(); styleGlassSearchField() }
```
Problem: `scrollChange()` (fragment lookups via findViewById x3, config reads, `updateColors`, re-installing the scroll listener, subtree walk, drawable creation) runs on every frame of a page swipe, on top of the pre-draw listener in M2-08.
Fix: do it in `onPageSelected` / `onPageScrollStateChanged(IDLE)` only.

### M2-10 [Med] [perf] activities/MainActivity.kt:756, 770
Evidence:
```
binding.mainTabsHolder.onGlobalLayout {
    Handler().postDelayed({ ... getTabAt(wantedTab)?.select(); refreshMenuItems() }, 100L)
}
```
Problem: arbitrary 100 ms delay used as a workaround ("selecting the proper tab sometimes glitches"), deprecated no-arg `Handler()`, delayed runnable captures the activity and is never cancelled (runs after destroy, `intent`/binding accessed). Selects the tab a visible 100 ms after first layout.
Fix: do the select directly inside the `onGlobalLayout` callback (or `binding.mainTabsHolder.post {}`), and use `Handler(Looper.getMainLooper())` / `view.postDelayed` if a delay stays.

### M2-11 [Med] [perf] fragments/FavoritesFragment.kt:129-132, 244
Evidence:
```
val viewType = context.config.viewType
setViewType(viewType, allContacts.size)
...
binding.fragmentList.layoutManager = layoutManager   // new MyGridLayoutManager / MyLinearLayoutManager every refresh
```
Problem: every refresh (i.e. every resume, sort, filter) allocates a new LayoutManager and swaps it, forcing full detach/re-layout of all rows and resetting scroll position to the top.
Fix: only replace the layout manager when the view type changed (`if ((lm is MyGridLayoutManager) != (viewType == VIEW_TYPE_GRID))`), otherwise just update `spanCount`.

### M2-12 [Med] [correctness] helpers/RecentsHelper.kt:397 + 33,49
Evidence:
```
this.queryLimit = queryLimit        // instance field written in proceed()
...
} while (cursor.moveToNext() && recentCalls.size < queryLimit)   // getRecents reads the FIELD, not its `limit` parameter
```
Problem: one RecentsHelper is shared by overlapping pipelines (resume + observer + EventBus; quick limit 100 vs full limit `queryLimitRecent`). A quick pass overwriting the field mid-way makes a running full pass stop at 100 rows (truncated list). Also the `newerLimit`/`olderLimit` loops are bounded by the field rather than their own `limit`.
Fix: use the `limit` parameter in the loop condition and drop the `queryLimit` field (`limit` default = QUERY_LIMIT).

### M2-13 [Med] [correctness] fragments/RecentsFragment.kt:281-324, 504-522
Evidence:
```
refreshCallLog(loadAll = false) { binding.recentsList.runAfterAnimations { refreshCallLog(loadAll = true) } }
...
getRecentCalls(loadAll) { allRecentCalls = it; ... activity?.runOnUiThread { gotRecents(it) }; callback?.invoke() ...
```
Problem: no sequencing/cancellation between overlapping pipelines: a slower older pass (or a quick pass of pipeline B) finishing after a newer full pass overwrites `allRecentCalls` and the adapter with older data. Also `callback` (which touches `binding.recentsList.runAfterAnimations`, `isComputingLayout`, `itemAnimator.isRunning`) is invoked on the BACKGROUND thread, before the posted `gotRecents` has run. The wait for animations before the full pass is also unnecessary (the full query is pure background work).
Fix: keep a `@Volatile refreshGeneration` int, capture at start and drop results when stale; invoke the callback inside `runOnUiThread`; start the full pass directly after the quick pass without `runAfterAnimations`.

### M2-14 [Med] [perf] fragments/RecentsFragment.kt:296-298, 511
Evidence:
```
recents = activity!!.config.parseRecentCallsCache()      // Gson parse, UI thread (refreshItems called from onResume)
...
context.config.recentCallsCache = Gson().toJson(it.take(RECENT_CALL_CACHE_SIZE))   // after BOTH quick and full pass
```
Problem: Gson deserialization of up to RECENT_CALL_CACHE_SIZE rows on the main thread on every resume; a new `Gson()` per call; serialization + prefs write after the quick pass AND the full pass (also while a search query is active, and for filtered/partial lists).
Fix: parse on a background thread then post; keep one shared Gson; write the cache only after the full pass (`loadAll`), skip when `searchQuery` is non-empty.

### M2-15 [Med] [correctness] activities/MainActivity.kt:1295-1319 + res/layout/activity_main.xml:93-99
Evidence:
```
CallManager.addListener(callCallback)   // only in onCreate; updateState() only runs from listener callbacks
```
(layout: `mainCallGlass` has `android:alpha="0"` `scaleX/Y 0.6`.)
Problem: if MainActivity is created/resumed while a call is already active (opened from the ongoing-call notification, re-created after a uiMode change), the "return to call" button stays invisible because `updateState()` is never called initially.
Fix: call `updateState()` at the end of onResume (and make `mainCallGlass` non-clickable while alpha 0 / gone).

### M2-16 [Med] [correctness] helpers/RecentsHelper.kt:424-447, 449-470
Evidence:
```
fun removeRecentCalls(ids...) { ... ensureBackgroundThread { ... context.contentResolver.delete(contentUri, selection, selectionArgs) ... } }
fun removeAllRecentCalls(...) { ... ensureBackgroundThread { context.contentResolver.delete(contentUri, null, null); callback() } }
... context.contentResolver.bulkInsert(contentUri, values)
```
Problem: no WRITE_CALL_LOG check in removeRecentCalls, and none of delete/bulkInsert is wrapped in try/catch: SecurityException (permission revoked, not default dialer) crashes the background thread. `restoreRecentCalls` (undo) writes only NUMBER/TYPE/DATE/DURATION/CACHED_NAME: loses PHONE_ACCOUNT_ID (SIM), COUNTRY_ISO, NUMBER_PRESENTATION, FEATURES, CACHED_PHOTO_URI, BLOCK_REASON, IS_READ/NEW (restored missed calls may reappear as unread), and `it.name` for an unknown number stores the number/"Unknown" string as CACHED_NAME. Grouped entries are restored by whoever calls it (check `groupedCalls` is flattened by caller).
Fix: try/catch SecurityException around the three calls; add the missing columns (need the fields on RecentCall: accountId, presentation, features, countryIso) and `Calls.IS_READ = 1`/`NEW = 0` for restored rows; only put CACHED_NAME when `name != phoneNumber`.

### M2-17 [Low] [perf] helpers/RecentsHelper.kt:188-198, 400-403
Evidence:
```
context.getAvailableSIMCardLabels().forEach { accountIdToSimIDMap[...] }
val accountIdToSimColorMap...; context.getAvailableSIMCardLabels().forEach {...}   // second Telecom walk
val voiceMailNumbers = loadVoiceMailNumbers()
...
val blockedNumbers = context.getBlockedNumbers()   // BlockedNumberContract query
```
Problem: `getRecents` runs up to 3x per pass (newer/older/plain); each run calls `getAvailableSIMCardLabels()` twice (telecom IPC per account), `loadVoiceMailNumbers()` and (when blocked numbers hidden) `getBlockedNumbers()`, which are identical across the 3 runs. Blocked filtering is applied AFTER the SQL limit so a pass can return fewer rows than `limit` with older rows hidden.
Fix: compute SIM maps (one call), voicemail numbers and blocked numbers once per `getRecentCalls` and pass them in; use one `getAvailableSIMCardLabels()` call for both maps.

### M2-18 [Low] [perf] helpers/RecentsHelper.kt:262-266
Evidence:
```
contacts.firstOrNull { it.doesContainPhoneNumber(number) }.also { contactsMap[number] = it }
```
Problem: one linear scan over every contact (each `doesContainPhoneNumber` normalises/compares all numbers) per distinct number: O(distinct numbers x contacts x numbers) per full pass.
Fix: build a `HashMap<normalizedNumber, Contact>` once per pass (or use `PhoneLookup` for misses only) and look up by normalised number.

### M2-19 [Low] [correctness] helpers/RecentsHelper.kt:295-299
Evidence:
```
if (previousStartTS == startTS) { continue } else { previousStartTS = startTS }
```
Problem: drops distinct call-log rows that share the same DATE (e.g. dual-SIM, imported/restored entries); `continue` in do-while also still evaluates the loop condition so it is safe, but the row (and its notes/count) is lost.
Fix: compare `id` (or `id` + `startTS`) instead of only the timestamp.

### M2-20 [Low] [integration] helpers/RecentsHelper.kt:472-497
Evidence:
```
CoroutineScope(Dispatchers.IO).launch { ... update(contentUri, values, "${Calls.NEW} = 1 AND ${Calls.TYPE} = ?", arrayOf(MISSED_TYPE)) }
```
Problem: called from `clearMissedCalls()` on every resume while on Recents and on every page selection (MainActivity 319, 748), no WRITE_CALL_LOG check (silently swallowed by the generic catch with printStackTrace), unscoped coroutine scope per call, and rows with `IS_READ = 0` but `NEW = 0` are never marked read. Each effective update notifies the CallLog observer (extra recents reload, see M2-04/05).
Fix: guard with `hasPermission(PERMISSION_WRITE_CALL_LOG)`; use `WHERE (NEW = 1 OR IS_READ = 0) AND TYPE = ?`; only call when `getUnreadCount > 0` / skip if already cleared this session.

### M2-21 [Low] [perf] activities/MainActivity.kt:1251-1256
Evidence:
```
val reader = InputStreamReader(resources.openRawResource(R.raw.t9languages))
DialpadT9.readFromJson(reader.readText())
```
Problem: raw-resource read + JSON parse on the main thread inside onCreate, and the reader/stream is never closed (leak until GC).
Fix: `resources.openRawResource(...).bufferedReader().use { it.readText() }`, and ideally run it on a background thread (the dialpad is the only consumer).

### M2-22 [Low] [correctness] activities/MainActivity.kt:221-224
Evidence:
```
if (storedShowTabs != config.showTabs || storedShowPhoneNumbers != config.showPhoneNumbers) {
    System.exit(0)
    return
}
```
Problem: kills the whole process (also the CallService/InCall process state, notifications) to apply a setting; abrupt UX and can drop an active call's UI state.
Fix: `finish(); startActivity(intent)` as done for needRestart (lines 227-232).

### M2-23 [Low] [correctness] activities/MainActivity.kt:728-752 (initFragments)
Evidence:
```
binding.viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener { ... })
```
Problem: `initFragments()` is reachable from `checkContactPermissions()` both in onCreate and in onActivityResult(REQUEST_CODE_SET_DEFAULT_DIALER) (and permission callback), so listeners / global-layout hooks can be added more than once (duplicate refreshMenuItems/clearMissedCalls on each page change, second `launchDialpad` guarded only by `launchedDialer`).
Fix: guard with a `fragmentsInitialized` flag.

### M2-24 [Low] [perf] activities/MainActivity.kt:283-290, 306-318; fragments/ContactsFragment.kt:100-119
Evidence:
```
getAllFragments().forEach { it?.setupColors(properTextColor, properPrimaryColor, getProperAccentColor()) }
binding.viewPager.setPageTransformer(true, animation)
...
favoritesAdapter.notifyDataSetChanged()   // setupColors, every resume
letterFastscrollerThumb.setupWithFastScroller(letterFastscroller)
```
Problem: every resume re-runs setupColors on every fragment (favourites strip full rebind + image reloads, drawable getDrawable/mutate in `applyFavoritesExpanded`, fast scroller re-wiring) and `setPageTransformer` (re-populates the ViewPager and requests layout), `invalidateOptionsMenu`, fragment background resets, although nothing changed.
Fix: remember the last applied (text, primary, accent, transformer type) and skip when unchanged.

### M2-25 [Low] [perf] fragments/ContactsFragment.kt:307-334, fragments/FavoritesFragment.kt:206-229, fragments/RecentsFragment.kt:343-368
Evidence:
```
val fixedText = text.trim().replace("\\s+".toRegex(), " ")
allContacts.filter { contact -> getProperText(contact.getNameToDisplay(), shouldNormalize).contains(...) || ... 11 fields ... }
```
Problem: per keystroke, on the UI thread (Contacts/Favorites), a regex is compiled and every contact's name/nickname/addresses/notes/company/job are normalised (allocation heavy); Recents does the same per call row incl. `PhoneNumberUtils.compare` + 3x `normalizePhoneNumber()` in `RecentCall.doesContainPhoneNumber`.
Fix: hoist the `Regex` to a constant, precompute normalised search strings per contact once per load (or filter on a background thread), compute `normalizePhoneNumber()` once per call.

### M2-26 [Low] [perf] fragments/ContactsFragment.kt:194-199
Evidence:
```
val allNotEmpty = contacts.filter { it.getNameToDisplay().isNotEmpty() }
val all = allNotEmpty.map { it.getNameToDisplay().substring(0, 1) }
```
Problem: two passes with two `getNameToDisplay()` string builds per contact on the UI thread on every refresh just to count distinct initials.
Fix: `contacts.mapNotNullTo(HashSet()) { it.getNameToDisplay().firstOrNull() }`.

### M2-27 [Low] [correctness] fragments/FavoritesFragment.kt:106-111, 124
Evidence:
```
if (contacts.isEmpty()) { fragmentPlaceholder.beVisible(); fragmentList.beGone() } else { ...; updateListAdapter() }
```
Problem: when the last favourite is removed the adapter is not updated and `cacheFavorites()` is not called, so `cachedFavorites` and the launcher dynamic shortcuts keep the removed contact; the stale adapter content is shown again if the list becomes visible before the next load.
Fix: in the empty branch also `(adapter as? ContactsAdapter)?.updateItems(allContacts)` and `(activity as MainActivity).cacheFavorites(allContacts)`.

### M2-28 [Low] [correctness] fragments/MyViewPagerFragment.kt:45-52 + MainActivity.kt:254-259
Evidence:
```
(this@MyViewPagerFragment.activity!! as MainActivity).refreshFragments()   // per fragment (Contacts AND Favorites)
```
Problem: when "start name with surname" changes, onResume calls `startNameWithSurnameChanged` on both Contacts and Favorites fragments (2x `refreshFragments`) and then `refreshItems(true)` runs a third full reload straight after.
Fix: set the sort once in MainActivity, then rely on the single `refreshItems(true)` that follows.

### M2-29 [Low] [perf] fragments/ContactsFragment.kt:341-345 (+ correctness)
Evidence:
```
ContactsHelper(context).getContacts { contacts -> activity?.runOnUiThread { gotContacts(contacts) } }
```
Problem: after granting the permission via the placeholder, `allContacts` is never assigned (stays empty) so search and the favourites strip operate on nothing until the next resume reload; also no private contacts/cacheContacts and different filter flags than refreshItems.
Fix: call `refreshItems()` here.

### M2-30 [Low] [correctness] fragments/RecentsFragment.kt:98-104, 435
Evidence:
```
val placeholderResId = if (context.hasPermission(PERMISSION_READ_CALL_LOG)) R.string.no_previous_calls else R.string.could_not_access_the_call_history
binding.recentsPlaceholder.text = ...   // only in setupFragment()
```
Problem: placeholder text is computed once; after permission revocation `gotRecents(empty)` shows the "no previous calls" text with the "request access" link (and the reverse after a grant outside this screen). Cached rows from `recentCallsCache` are also displayed first when permission was revoked.
Fix: set the placeholder text inside `gotRecents`' empty branch from the current permission state; skip the cache render when READ_CALL_LOG is missing.

### M2-31 [Low] [correctness] fragments/RecentsFragment.kt:296-300 / models/RecentCall.kt:14-34
Evidence:
```
try { recents = activity!!.config.parseRecentCallsCache() } catch (_: Exception) { ...cache = "" }
```
Problem: Gson reconstructs RecentCall without running Kotlin constructors/defaults (`UnsafeAllocator`), so a cache written by an older app version that lacks newer fields (`nickname`, `company`, `jobPosition`...) yields `null` in non-null Kotlin properties; the NPE happens later (search/adapter), outside this try/catch.
Fix: validate after parsing (`recents.filter { it.nickname != null ... }`) or version the cache key (clear `recentCallsCache` when the app version changes).

### M2-32 [Low] [leak] activities/MainActivity.kt:1299-1316
Evidence:
```
RxAnimation.together(binding.mainCallGlass.scale(1.1f), binding.mainCallGlass.fadeIn(duration = 260)).andThen(...).subscribe()
```
Problem: Disposable is dropped; if the activity is destroyed mid-animation the chain keeps the view (and activity) alive until it completes, and rapid state changes stack overlapping animations.
Fix: keep a `Disposable` field, dispose the previous one before subscribing and in onDestroy.

### M2-33 [Low] [perf] activities/MainActivity.kt:608-651
Evidence:
```
val starred = cachedFavorites.filter { it.phoneNumbers.isNotEmpty() }.take(3)   // inside ensureBackgroundThread
```
Problem: a new background thread per `cacheFavorites()` (each Favorites refresh) iterating a list that the UI thread mutates (see M2-07); `storedContactShortcuts != starred` compares full Contact objects; all existing dynamic shortcuts are removed then re-pushed whenever any field of those contacts differs.
Fix: snapshot the list on the UI thread before launching, compare ids only (`map { it.id }`).

### M2-34 [Low] [correctness] activities/MainActivity.kt:192-194
Evidence:
```
if (getBlockedNumbers().isNotEmpty() || ...) { baseConfig.blockingEnabled = true }
```
Problem: BlockedNumberContract query on the main thread in onCreate (first launch only) and unguarded if the app is not default dialer (SecurityException is handled inside commons? unverified).
Fix: wrap in try/catch and run on a background thread.

### M2-35 [Low] [perf] activities/MainActivity.kt:827, 873-908
Evidence:
```
binding.mainHolder.viewTreeObserver.addOnGlobalLayoutListener { updateListInsets() }
```
Problem: runs on every layout pass of the root (tab animations, keyboard, list updates): several `findViewById` calls and `getAllFragments()` (3 more) per pass, `updatePadding` + `scrollToPosition(0)` side effects. Mostly no-ops but it is permanent per-layout work.
Fix: cache the fragment/header views and early-return when the computed insets/heights equal the last applied values.

### M2-36 [Low] [correctness] fragments/RecentsFragment.kt:370-410, 715-727
Evidence: `private fun updateSearchDialpadResult()` (no caller), `class BottomSpaceDecoration` (no usage anywhere in `kotlin/`)
Problem: dead code (the former is also a duplicate of `updateSearchResult` with per-row language lookups).
Fix: delete both.

### M2-37 [Low] [correctness] activities/MainActivity.kt:377-380
Evidence:
```
override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); refreshItems() }
```
Problem: manifest declares `configChanges="orientation|screenSize"`, so every such change runs the full 5-load refresh (and `viewPager.adapter` stays) for what is only a re-layout.
Fix: drop the reload (adapters re-layout themselves), or only refresh when the font/size actually changed.

### M2-38 [Low] [perf] activities/SimpleActivity.kt:39-45
Evidence:
```
if (usesLiquidBackdrop) findViewById<View>(android.R.id.content).applyLiquidBackdrop()
```
Problem (verify in extensions): re-applied on every resume of every activity, even when light/dark/theme did not change (any allocation inside `applyLiquidBackdrop` repeats per resume).
Fix: skip when the already-applied backdrop key (theme + colour) equals the current one.

No issues found in: adapters/ViewPagerAdapter.kt (getCount/getFragment re-filter lists per call, trivial).

## Coverage (lines read)
- activities/MainActivity.kt: lines read: 1-1363
- fragments/RecentsFragment.kt: lines read: 1-727
- helpers/RecentsHelper.kt: lines read: 1-544
- fragments/ContactsFragment.kt: lines read: 1-401
- fragments/FavoritesFragment.kt: lines read: 1-285
- fragments/MyViewPagerFragment.kt: lines read: 1-98
- adapters/ViewPagerAdapter.kt: lines read: 1-54
- models/RecentCall.kt: lines read: 1-51
- activities/SimpleActivity.kt: lines read: 1-65
(Extra context checked: extensions/RecyclerView.kt, extensions/Context.kt clearMissedCalls, helpers/CallerNotesHelper.kt, Config.parseRecentCallsCache, AndroidManifest MainActivity entry, activity_main.xml mainCallGlass.)
