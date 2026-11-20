# Batch 3 findings (adapters + extensions + GlassPanel)

### A3-1 [High] [perf] extensions/Context.kt:60
Evidence: `val Context.config: Config get() = Config.newInstance(applicationContext)` and `fun newInstance(context: Context) = Config(context)` (helpers/Config.kt:26)
Problem: Every `.config` access allocates a new Config (BaseConfig + SharedPreferences lookup, and its `by lazy regionHint` is re-created each time so the lazy never caches). RecentCallsAdapter bind reads `activity.config.*` ~15-20x per row, ContactsAdapter ~12x, plus getItemViewType per row.
Fix: Cache one instance per application (`Config` singleton in App / `@Volatile` holder keyed by applicationContext) and return it from the getter; in adapters read the needed flags once per bind or once per adapter.

### A3-2 [Med] [perf] extensions/LiquidGlass.kt:52-59,66-82
Evidence: `setImageBitmap(context.brandedLetterAvatar(name))` ... `val bitmap = createBitmap(size, size)` with `size = contact_photo_big_size`, two `Paint` objects per call
Problem: Called from onBindViewHolder (RecentCallsAdapter 808/1036, ContactsAdapter 536, ConferenceCallsAdapter) for every contact without a photo: a full big-size ARGB bitmap (+2 Paints) is allocated and drawn on the main thread per bind, never cached or recycled -> GC churn / scroll jank, memory heavy on long lists.
Fix: Cache by (letter, primaryColor) in an LruCache<String, Bitmap> (or use a small LetterDrawable that draws on the fly); reuse the Paints.

### A3-3 [Low] [correctness] extensions/LiquidGlass.kt:52-59
Evidence: `helper.loadContactImage(photoUri, this, name)` ... else `setImageBitmap(context.brandedLetterAvatar(name))`
Problem: The branch that sets the letter bitmap does not cancel a pending Glide request left on the same ImageView by a previous bind of the same (non-recycled) holder (notifyItemChanged / notifyDataSetChanged rebinds), so a late photo load can overwrite the letter avatar of a different row.
Fix: `Glide.with(this).clear(this)` before `setImageBitmap(...)` in that branch.

### A3-4 [Med] [perf] extensions/String.kt:8-18
Evidence: `phoneUtil.parse(this, countryCode)` + `geocoder.getDescriptionForNumber(numberParse, Locale.getDefault())`
Problem: Called inside onBindViewHolder (RecentCallsAdapter 730/958) for every row whose name equals the number; libphonenumber parse plus offline geocoder (loads geocoding resource files on first use per prefix) on the main thread per bind/scroll; result never cached. Also only NumberParseException is caught.
Fix: Memoize per number (LruCache<String,String>) or precompute when building the RecentCall list on the background thread; catch Exception.

### A3-5 [Low] [perf] extensions/String.kt:22-33
Evidence: `replace("\\s".toRegex(), "").replace("\\+".toRegex(), "")...` (5 Regex compiled per call)
Problem: Regexes recompiled on every call. `numberForNotes()` is used inside CallerNotesHelper.kt:31/37 per element (`mCallerNotes.firstOrNull { it.id.numberForNotes() == number?.numberForNotes() }` = 10 regex compilations per list item per lookup) and in RecentsFragment:519/DialpadActivity:1512 over all recents.
Fix: `fun String.numberForNotes() = filterNot { it.isWhitespace() || it in "+()-" }`; same for removeNumberFormatting (`it in "()-*#"`), or hoist one Regex constant.

### A3-6 [Med] [perf] adapters/RecentCallsAdapter.kt:139,167,371,410,529
Evidence: `private var getBlockedNumbers = activity.getBlockedNumbers()` (property initializer); also `getBlockedNumbers = activity.getBlockedNumbers()` in prepareActionMode, showPopupMenu, and the UI-thread blocks after block/unblock
Problem: BlockedNumberContract provider query on the main thread at adapter construction, every time the action mode is prepared and every overflow long-press; the cached list is otherwise stale (swipedBlock at 1290 uses it without refresh, and it is stale if numbers are blocked elsewhere), may throw SecurityException when not default dialer/permission revoked.
Fix: Load blocked numbers via ensureBackgroundThread (init + after block/unblock) and post the result; in swipedBlock refresh in background before deciding block vs unblock; wrap in try/catch SecurityException.

### A3-7 [Med] [perf] adapters/RecentCallsAdapter.kt:502-512
Evidence: `submitList(newItems)\n notifyDataSetChanged()\n finishActMode()` (highlight changed)
Problem: submitList is async (DiffUtil on background thread) so notifyDataSetChanged() runs against the OLD list, then the diff result dispatches again: two full rebinds, defeats stable-id animations, flicker on every keystroke while searching from the dialpad.
Fix: Drop notifyDataSetChanged(); make the highlight part of areContentsTheSame (compare a per-adapter highlight version) or submit then `notifyItemRangeChanged(0, itemCount)` in the submitList commit callback.

### A3-8 [Med] [perf] adapters/RecentCallsAdapter.kt:696-737,720-724,924-965,948-952
Evidence: `var numberToShow = if (formatPhoneNumbers) SpannableString(call.phoneNumber.formatPhoneNumber()) else ...` computed unconditionally; plus `call.phoneNumber.formatPhoneNumber()` again at 734/962 and `name.formatPhoneNumber()` at 697/925
Problem: formatPhoneNumber (libphonenumber/PhoneNumberUtils) is invoked up to 3 times per bind, and numberToShow is built even when it is not used (only used when `textToHighlight` non-empty and name != number). Same logic is duplicated verbatim in RecentCallViewHolder and RecentCallSwipeViewHolder (~230 duplicated lines).
Fix: Compute `formattedNumber` once per bind (lazily, only when needed) and reuse it; extract the shared bind body into one function used by both holders. Better: precompute display strings when the list is built on the background thread.

### A3-9 [Med] [perf] adapters/RecentCallsAdapter.kt:788-806,1016-1033
Evidence: `val drawable = AppCompatResources.getDrawable(activity, placeholderRes)?.mutate()` ... `findDrawableByLayerId(...)?.mutate()?.applyColorFilter(properPrimaryColor)`
Problem: Inflates/decodes a LayerDrawable (vector layers) and mutates two drawables on every bind for every row without a photo; no reuse.
Fix: Build the 3 tinted placeholder drawables once per adapter (or per color) and `constantState.newDrawable()` per bind, or set a cached drawable.

### A3-10 [Low] [perf] adapters/RecentCallsAdapter.kt:691,919 and ContactsAdapter.kt:543
Evidence: `if (getLastItem() == call || ...)` / `beInvisibleIf(getLastItem() == contact || ...)`
Problem: `==` on data classes (RecentCall includes groupedCalls list, Contact has many lists) does a deep equals for every bind (O(fields) or O(group size)), where a position check is enough; also wrong if two equal items exist.
Fix: Pass the position: `isLast = position == itemCount - 1`.

### A3-11 [Low] [perf] adapters/RecentCallsAdapter.kt:740-750,968-979
Evidence: `DateUtils.getRelativeDateTimeString(context, call.startTS, 1.minutes.inWholeMilliseconds, 2.days.inWholeMilliseconds, 0)` and `1204: val now = DateTime.now()` per date header bind (`now.minusDays(1).millis.getDayCode()` + `getDayCode()` twice)
Problem: Per-bind formatter/Formatter/DateTime allocations (getRelativeDateTimeString builds a Formatter + locale lookups each time).
Fix: Compute `todayCode/yesterdayCode` once per submitList/updateItems (field), reuse; cache formatted date strings in the model/list builder.

### A3-12 [Low] [perf] adapters/RecentCallsAdapter.kt:1131-1156,1146-1151 (swipe holder)
Evidence: `swipeActionColor(call, swipeLeftAction)` called 2-4x per bind, each doing `activity.config.currentSIMCardIndex`, `callUsingSameSim`, `simIconsColors` (JSON/pref parse?) and `resources.getColor`; `setImageResource` x2, `displayMetrics` + `getDimension` per bind
Problem: Redundant per-bind config reads and resource lookups (amplified by A3-1).
Fix: Compute `leftColor/rightColor` once per bind into locals and reuse for icon holder + ripple; cache swipe actions/colors per adapter and refresh on config change.

### A3-13 [Low] [perf] adapters/RecentCallsAdapter.kt:294-308
Evidence: `val recyclerViewState = layoutManager.onSaveInstanceState()` ... `super.submitList(listFilter) { layoutManager.onRestoreInstanceState(recyclerViewState) }`; `recyclerView.layoutManager!!`
Problem: State is saved before the async diff and restored after it: if the user scrolled in between, the list snaps back; saved state is restored even when an older submit is superseded; also filtering (`filterIsInstance`/`filter`) runs on main thread each submit and uses `activity.config` 2-3x; `!!` NPE if no layout manager.
Fix: Only restore when the list was empty/first load or when new items inserted at top and the user is at top; do the filter in the caller's background thread.

### A3-14 [Low] [correctness] adapters/RecentCallsAdapter.kt:1325-1348 (RecentCallsDiffCallback, also used by CallHistoryAdapter)
Evidence: `areContentsTheSame` compares phoneNumber/name/photoUri/startTS/duration/type/simID/specificNumber/specificType/isUnknownNumber/groupedCalls size only
Problem: blockReason, contactID, isVoiceMail, company/nickname/jobPosition, features, simColor are not compared, so rows are not rebound when a number gets blocked/unblocked or linked to a contact (icon at 829 stays stale); grouped calls compared by size only.
Fix: Add those fields (or compare the data classes with `==` except the mutable id) in areContentsTheSame.

### A3-15 [Low] [correctness] adapters/RecentCallsAdapter.kt:829,1057 and CallHistoryAdapter.kt:228
Evidence: `call.blockReason != 0` where `blockReason: Int? = 0`
Problem: `null != 0` is true, so any call with null blockReason is shown as blocked.
Fix: `(call.blockReason ?: 0) != 0`.

### A3-16 [Med] [correctness] adapters/RecentCallsAdapter.kt:466-487
Evidence: `val recentCalls = currentList.filterIsInstance<RecentCall>().toMutableList().also { it.removeAll(callsToRemove) }` ... `refreshItemsListener?.refreshItems(needUpdate = true); submitList(recentCalls)`
Problem: filterIsInstance drops all CallLogItem.Date headers, so after delete the list is shown without day headers until the refresh completes (and the refresh/submitList race can leave the stale headerless list on top); grouped child calls are removed from the log but the group parent removal only matches the parent object; runs `currentList` read on the callback (background) thread.
Fix: `currentList.toMutableList().also { it.removeAll(callsToRemove) }` (keep dates, then drop orphan headers) or just rely on refreshItems; read currentList on the UI thread.

### A3-17 [Low] [correctness] adapters/RecentCallsAdapter.kt:459-461,1273-1276,628-639; CallHistoryAdapter.kt:109-111
Evidence: `activity.handlePermission(PERMISSION_WRITE_CALL_LOG) { if (it) removeRecents() }` after `selectedKeys.add(callId)` / `selectedKeys.add(call.id)`
Problem: When the permission is denied (or the swipe/popup action is cancelled at the permission step) the key stays in selectedKeys; the row renders selected (`itemRecentsHolder.isSelected = selectedKeys.contains(call.id)`) and the next bulk action operates on a stale selection. Same for swipedBlock when not default dialer path after `askConfirm*`.
Fix: `else selectedKeys.clear()` in the permission callback.

### A3-18 [Med] [correctness] adapters/RecentCallsAdapter.kt:489-494
Evidence: `if (isDialpad) (activity as DialpadActivity).allContacts ... else (activity as MainActivity).cachedContacts.find { it.doesHavePhoneNumber(recentCall.phoneNumber) }`
Problem: Hard casts: ClassCastException if the adapter is created from any other SimpleActivity (ShowGroupedCallsDialog constructs it with any SimpleActivity; currently dead code but one call away). The linear scan of all contacts with doesHavePhoneNumber (normalizing) runs on the main thread in prepareActionMode (line 184) and every showPopupMenu/showCallHistory.
Fix: Inject a `contactsProvider: () -> List<Contact>` (or `ContactLookup` lambda) into the adapter; use `as?`; precompute a number->Contact map once per contacts refresh.

### A3-19 [Low] [perf] adapters/RecentCallsAdapter.kt:229,514,519,521
Evidence: `getSelectableItemCount() = currentList.filterIsInstance<RecentCall>().size`, `getSelectedItems() = currentList.filterIsInstance<RecentCall>().filter { ... }`, `getSelectedPhoneNumber()`/`getSelectedName()` each re-run it; `isAllBlockedNumbers/isAllUnblockedNumbers` run isNumberBlocked per selected item twice
Problem: O(n) list copies repeated several times per action-mode refresh (prepareActionMode calls them 4-5 times).
Fix: Compute `selectedItems` once per call site and pass it; use `count { it is RecentCall }`.

### A3-20 [Low] [perf] adapters/RecentCallsAdapter.kt:241-246
Evidence: `is RecentCall -> if (activity.config.useSwipeToAction) VIEW_TYPE_CALL_SWIPE else VIEW_TYPE_CALL`
Problem: Config object + prefs read per item per layout; also the adapter keeps no consistent snapshot, so toggling the pref while a list is visible mixes holder types with `onViewRecycled` branches (line 282-290 uses the current pref, not the holder type) leaving Glide requests uncleared.
Fix: Cache `useSwipeToAction` in a field at construction; in onViewRecycled branch on `holder is ...` only (Glide.clear needs no pref check).

### A3-21 [Low] [correctness] adapters/RecentCallsAdapter.kt:354-373
Evidence: `callsToBlock.map { it.phoneNumber }.forEach { number -> activity.addBlockedNumber(number) }` ... `it.removeAll(callsToBlock)` ... `getBlockedNumbers = activity.getBlockedNumbers()` on UI thread
Problem: Only the selected rows are removed from the list, other rows of the same blocked number remain; the blocked list is re-queried on the main thread (provider IO); `activity.addBlockedNumber` result/failure (SecurityException when not default dialer) ignored and the UI still hides the rows.
Fix: Remove all rows with the blocked numbers (`filter { it.phoneNumber in numbers }`), check addBlockedNumber result, requery blocked numbers in the background block.

### A3-22 [Med] [perf] adapters/ContactsAdapter.kt:151-159,244-255
Evidence: `onActionModeCreated() { notifyDataSetChanged() }`, `onActionModeDestroyed() { notifyDataSetChanged() }`, `updateItems`: `if (newItems.hashCode() != contacts.hashCode()) { contacts = ArrayList(newItems); ...; notifyDataSetChanged(); finishActMode() }`
Problem: Full rebind of all visible rows when entering/leaving selection (only the info/drag icons change); updateItems hashes both entire contact lists (deep hashCode of Contact with phone/email lists) on the main thread each refresh, then does notifyDataSetChanged and finishActMode (which triggers a second notifyDataSetChanged via onActionModeDestroyed when active). No DiffUtil, no animations, scroll jank on large address books.
Fix: Convert to ListAdapter/DiffUtil keyed by rawId (areContentsTheSame on displayed fields), or `notifyItemRangeChanged(first,last)` for visible range on action mode toggles; drop finishActMode double notify.

### A3-23 [Med] [correctness] adapters/ContactsAdapter.kt:319-321, 853-855
Evidence: `activity.handlePermission(PERMISSION_WRITE_CONTACTS) { deleteContacts() }` (callback param ignored)
Problem: Delete proceeds even when the permission was denied: contacts are removed from the list immediately (line 333) while `deleteContactRawIDs` fails/throws SecurityException, so UI and provider diverge; selectedKeys left stale on swipe path.
Fix: `{ if (it) deleteContacts() else selectedKeys.clear() }`.

### A3-24 [Med] [correctness] adapters/ContactsAdapter.kt:326-346
Evidence: `contacts.removeAll(contactsToRemove)` (main thread, immediately) ... later `removeSelectedItems(positions)` in runOnUiThread
Problem: The backing list is mutated before the adapter is notified, and positions are notified afterwards -> RecyclerView "Inconsistency detected / IndexOutOfBounds" window if a layout/bind happens in between (itemCount already shrunk); if deletion fails nothing restores the removed items.
Fix: Remove items and call removeSelectedItems(positions) together on the UI thread after deletion succeeds (or notify immediately, restore on failure).

### A3-25 [Low] [perf] adapters/ContactsAdapter.kt:170-175
Evidence: `setupView(Binding.getByItemViewType(viewType, activity.config.useSwipeToAction).bind(itemView), contact, holder)`
Problem: Every bind allocates a new binding + wrapper adapter object (ItemContactWithNumberInfoBinding.bind = ~15 findViewById) and a Config; the holder already could keep the binding.
Fix: Create the binding once in onCreateViewHolder and store it in the holder (as RecentCalls adapter does).

### A3-26 [Low] [correctness] adapters/ContactsAdapter.kt:527-534
Evidence: `val drawable = ResourcesCompat.getDrawable(resources, R.drawable.placeholder_company, activity.theme)` ... `(drawable as LayerDrawable).findDrawableByLayerId(R.id.placeholder_company_background).applyColorFilter(color)`
Problem: No `mutate()` (RecentCallsAdapter comment at 797 notes colors mix between rows without it); `getLetterBackgroundColors()` (typed array read) per bind (RecentCalls caches it); unsafe cast/!! style lookup NPE if layer id missing.
Fix: `.mutate()` the layer, cache colors per adapter, use `as? LayerDrawable`.

### A3-27 [Low] [perf] adapters/ContactsAdapter.kt:463-473
Evidence: `name.normalizeString()`, `textToHighlight.normalizeString()`, `DialpadT9.getSupportedSecondaryLanguages().contains(langLocale)`, `activity.config.dialpadSecondaryLanguage`, `Locale.getDefault()` and `highlightTextFromNumbers` (uppercase + convertLettersToNumbers + StringBuilder + IntArray) per bind while searching
Problem: Search-time per-row allocations/normalization; the search text normalisation and language resolution are loop-invariant.
Fix: Compute `normalizedSearch`, resolved `lang` once in updateItems and store in fields.

### A3-28 [Low] [correctness] adapters/ContactsAdapter.kt:364-386
Evidence: `ShortcutInfo.Builder(activity, contact.hashCode().toString())` and `data = Uri.fromParts("tel", getSelectedPhoneNumber(), null)` evaluated inside async callback
Problem: Shortcut id derives from hashCode of a mutable data class (changes whenever contact fields change -> duplicate pinned shortcuts, cannot be updated); `getSelectedPhoneNumber()` is re-read after the async image load, selection may have changed/cleared -> wrong number or `Uri.fromParts(null ssp)` NPE; the contact used for the label is the earlier one.
Fix: Capture `val number = contact.getPrimaryNumber()` before the async call; use `"contact_${contact.rawId}"` as id.

### A3-29 [Low] [perf] adapters/ContactsAdapter.kt:623-637
Evidence: `activity.config.isCustomOrderSelected = true` inside onRowMoved
Problem: A SharedPreferences write (new Config + apply()) on every swap step during drag.
Fix: Set once when the drag starts / in onRowClear.

### A3-30 [Low] [perf] adapters/ContactsAdapter.kt:186,199,223,239
Evidence: `activity.isContactBlocked(contact) {..}`, `activity.unblockContact(contact)`, `activity.blockContact(contact)`
Problem: (verify in commons) these perform BlockedNumberContract queries/inserts for each phone number; `unblockContact`/`blockContact` return a Boolean synchronously and are called from UI-thread callbacks (dialog click / swipe), i.e. provider IO on the main thread, with no SecurityException handling when not default dialer.
Fix: Wrap in ensureBackgroundThread + runOnUiThread for the toast; catch SecurityException.

### A3-31 [Low] [correctness] adapters/ContactsAdapter.kt:147-149,181
Evidence: `getItemSelectionKey = contacts[position].rawId` but `getItemWithKey(key) = contacts.firstOrNull { it.id == key }` (used by callContact via `selectedKeys.first()`)
Problem: Key type mismatch (rawId vs id). Harmless only if `Contact.id == rawId`; if they differ the cab_call action silently does nothing/calls wrong contact. Also getItemKeyPosition/getSelectedItems are O(n) per call.
Fix: Use `it.rawId == key` in getItemWithKey.

### A3-32 [Low] [perf] adapters/CallHistoryAdapter.kt:190,197,244
Evidence: `call.startTS.formatDateOrTime(context, ...)`, `call.duration.getFormattedDuration()` per bind; `private val colorSimIcons = activity.config.colorSimIcons` captured once
Problem: Per-bind date formatting (SimpleDateFormat/Locale work); `colorSimIcons` snapshot is stale if the setting changes while the adapter lives; `getAdjustedSimColor` is a no-op cache (returns the key) in both RecentCallsAdapter:1192 and CallHistoryAdapter:243.
Fix: Remove the pointless HashMap cache; precompute strings in the list builder.

### A3-33 [Low] [correctness] adapters/CallHistoryAdapter.kt:127-136
Evidence: `RecentsHelper(activity).removeRecentCalls(idsToRemove) { ... val recentCalls = currentList.toMutableList().also { it.removeAll(callsToRemove) } ... refreshItemsListener?.refreshItems(); submitList(recentCalls) }`
Problem: `currentList` is read on the helper's callback thread (not UI); `refreshItems()` and then `submitList` of a locally filtered list race (refresh may land first, then be overwritten by the older filtered list).
Fix: Read currentList and submit on the UI thread inside runOnUiThread; call refreshItems only.

### A3-34 [Low] [correctness] adapters/FilterContactSourcesAdapter.kt:67,73-75
Evidence: `filterContactSourceHolder.setOnClickListener { viewClicked(!isSelected, contactSource) }` and `toggleItemSelection(select, contactSource, adapterPosition)`
Problem: `isSelected` captured at bind time -> two fast taps before the rebind compute the same state; `adapterPosition` (deprecated) can be NO_POSITION (-1) -> notifyItemChanged(-1); selection keyed by `ContactSource.hashCode()`; setColors with 3 config/theme reads per bind.
Fix: `val sel = selectedKeys.contains(key)` at click time; use `bindingAdapterPosition` and return if NO_POSITION; cache the 3 colors in fields.

### A3-35 [Low] [correctness] adapters/SpeedDialAdapter.kt:39,41,62,77
Evidence: `getItemSelectionKey(position) = speedDialValues.getOrNull(position)?.hashCode()`
Problem: Selection key is the hashCode of a mutable data class (id, number, displayName, type, label); if any field changes while selected the selection is lost, and equal-valued rows (e.g. two empty slots differ only by id so OK, but collision risk) map to the same key; indexOfFirst/filter O(n) per call.
Fix: Use `speedDial.id` as the key.

### A3-36 [Med] [perf] extensions/LiquidGlass.kt:97-111
Evidence: `val listener = ViewTreeObserver.OnPreDrawListener { val swiping = content.translationX != 0f; actions.forEach { if (it.isInvisible == swiping) it.isInvisible = !swiping }; true }` registered per swipe row
Problem: A per-frame pre-draw listener for every attached swipe row (RecentCalls/Contacts, ~10-20 rows) runs on every draw of the whole window (including unrelated animations/scrolling), allocating the vararg iteration each frame; the listener also mutates visibility inside onPreDraw (invalidate/relayout risk). `listener` is also captured by a permanent tag so it lives as long as the view.
Fix: Drive visibility from the SwipeGestureListener callbacks (onSwipedActivated/Deactivated + after return animation), or listen to `content` translation via a single `OnLayoutChange`/animation end; avoid vararg iteration per frame.

### A3-37 [Low] [perf] views/GlassPanel.kt:29-37,63-68
Evidence: `applyLiquidGlass(pressable = isClickable)` runs before the static decision, then `backdropSource = rootView.findViewById<View>(android.R.id.content)` in onAttachedToWindow even when `useStaticGlass`
Problem: For static panels the library still gets configured with blur/dispersion/dynamic background flags and is pointed at the whole window content as backdrop source; if the library captures/observes the source irrespective of onDraw (pre-draw listeners / render effects), it burns the per-frame cost this class says it avoids (verify in library). `isClickable` is read once in init so `pressable` is wrong if clickable is set later in code. The static frost is also computed once from the theme, never refreshed.
Fix: Only call applyLiquidGlass / set backdropSource when `!useStaticGlass`; for static, just set the background.

### A3-38 [Low] [perf] extensions/LiquidGlass.kt:128-137
Evidence: `enableDynamicBackground = true` for all panels, CPU blur path "below Android 13 ... native library" vs GlassPanel doc "Below Android 12 the library has no GPU pipeline"
Problem: The two comments disagree: on API 31-32 ARM devices (GlassPanel treats them as GPU) applyLiquidGlass says the blur is CPU-based (`effectiveApiLevel < 33`), so dynamic-backdrop panels on the call screen may do a per-frame CPU blur on 12/12L. Also `System.loadLibrary("nativegauss")` runs on the main thread on first panel (only SecurityException/UnsatisfiedLinkError path handled).
Fix: Verify on an API 31/32 device; if CPU, treat `< 33` like `< 31` (static glass) or turn dynamic background off there.

### A3-39 [Low] [correctness] extensions/EditText.kt:6-28
Evidence: `else -> KeyEvent.KEYCODE_POUND` and `dispatchKeyEvent(getKeyEvent(...))` with only ACTION_DOWN, downTime/eventTime 0
Problem: Any unmapped char (e.g. 'p', 'w', '-', letters from T9) silently inserts '#'; no ACTION_UP is sent, and the zero eventTime/downTime makes each dispatch look like the same repeat sequence to key handlers/TextWatchers that inspect event time.
Fix: Map '#' explicitly, ignore/`append` unknown characters via `text.insert(selectionStart, char)`; send DOWN then UP with `SystemClock.uptimeMillis()`.

### A3-40 [Low] [leak] extensions/RecyclerView.kt:5-19
Evidence: `if (isComputingLayout) { post { runAfterAnimations(callback) }; return }` ... `animator.isRunning { post(callback) }`
Problem: Callbacks (capturing fragment/activity) are queued on the view without a lifecycle/attach check; the `isRunning` listener is held by the item animator until animations finish; if the view is detached the callback runs later against a destroyed fragment (binding null -> NPE) or never.
Fix: Guard with `if (!isAttachedToWindow) return` inside callbacks (or run under viewLifecycleOwner) and use `postOnAnimation`.

### A3-41 [Low] [perf] extensions/FastScrollerView.kt:8-19
Evidence: `setupWithRecyclerView(recyclerView, { position -> ... contacts[position].getFirstLetter() ... })` with the captured `contacts`
Problem: Captures a snapshot of the list; if the adapter later swaps its list (ContactsAdapter.updateItems creates a new ArrayList) the fast-scroller indexes stale data until re-setup (call sites: ContactsFragment:222, FavoritesFragment:197, SelectContactDialog:81 re-call it, but each call re-evaluates getFirstLetter for the whole list on the main thread, and the lambda is retained by the FastScrollerView).
Fix: Setup once with a lambda reading the adapter's current list (`(recyclerView.adapter as? ContactsAdapter)?.contacts`), or precompute letters on the background thread.

### A3-42 [Low] [perf] extensions/SharedPreferences.kt:17,28
Evidence: `Gson().toJson(myPhoneAccountHandleModel)` / `Gson().fromJson(json, T::class.java)`
Problem: New Gson (reflection-heavy builder + type adapters) per call; `getPhoneAccountHandleModel` is used by Config.getCustomSIM/legacy lookups that run per selected recents row, per popup menu, and in call flows. Only JsonSyntaxException is caught (JsonIOException/ClassCast on bad data propagates) and a stored JSON "null" returns null for a non-null T.
Fix: Hoist a single `private val gson = Gson()`; catch `Exception`; null-check result.

### A3-43 [Med] [integration] extensions/Context.kt:421-434
Evidence: `val privateCursor = getMyContactsCursor(...)` then `SimpleContactsHelper(this).getAvailableContacts(false) { var contact = it.firstOrNull { it.doesHavePhoneNumber(address) } ... if (contact == null) { MyContactsContentProvider.getSimpleContacts(this, privateCursor) ...`
Problem: (1) The private-contacts cursor is opened eagerly on the caller thread (the receiver main thread, see MissedCallReceiver:75) and is only closed (by getSimpleContacts' use{}) when no normal contact matches; when a contact is found the cursor leaks. (2) A full contact load + linear `doesHavePhoneNumber` scan is used instead of ContactsContract.PhoneLookup, so lookup is slow and not equivalent to Telecom number matching; the receiver also opens its own second private cursor (MissedCallReceiver:69). (3) Third fallback `it.phoneNumbers.map { it.value }.any { it == address }` allocates a list per contact. Callback only invoked after the full load, no timeout.
Fix: Open the private cursor lazily inside the fallback branch (and close it in a finally), prefer `ContactsContract.PhoneLookup` for the primary lookup, drop the redundant third pass.

### A3-44 [Low] [correctness] extensions/Context.kt:200-222
Evidence: `callback(bitmap)\n } catch (_: Exception) {\n callback(placeholder.bitmap)\n }`
Problem: `callback` is invoked inside the try: if the callback itself throws (e.g. IllegalState from the shortcut/notification code) the catch invokes it a second time with the placeholder. `FutureTarget.get()` has no timeout (can block the worker forever) and the Glide target is not cleared on failure.
Fix: `val bmp = try { ...get() } catch (...) { placeholder.bitmap }; callback(bmp)`; use `get(5, SECONDS)` and `Glide.with(this).clear(target)` in finally. Same for getNotificationBitmap (line 397-407).

### A3-45 [Low] [correctness] extensions/Context.kt:72-103
Evidence: `val phoneAccount = telecomManager.getPhoneAccount(account); var label = phoneAccount.label.toString(); var address = phoneAccount.address.toString()` inside one try/catch(Exception)
Problem: getPhoneAccount can return null (account removed) or have null address/label; the exception aborts the whole forEachIndexed and the function silently returns a partial/empty list, so SIM selection UI/call routing sees fewer SIMs. Each call also does 1 + N Telecom binder IPCs plus a new Config per iteration; callers invoke it per call/per settings screen (RecentsHelper:189,193 loops it twice).
Fix: `val phoneAccount = telecomManager.getPhoneAccount(account) ?: return@forEachIndexed`, use `?.address?.toString().orEmpty()`, hoist `config`, cache the list per call flow.

### A3-46 [Low] [perf] extensions/Context.kt:106-112
Evidence: `telecomManager.callCapablePhoneAccounts.size > 1`
Problem: Binder IPC invoked repeatedly on main thread: RecentCallsAdapter prepareActionMode (163) and showPopupMenu (534) call it again although the adapter already stores `areMultipleSIMsAvailable`; the field itself is a construction-time snapshot (stale after SIM change).
Fix: Use the cached field in the adapter; refresh it in onResume.

### A3-47 [Low] [integration] extensions/Context.kt:256-285
Evidence: `timer.channelId ?: "right_dialer_timer_channel_${soundUri}_${System.currentTimeMillis()}"` ... `notificationManager.deleteNotificationChannel(channelId)` then `createNotificationChannel(this)` on every notification
Problem: The channel is deleted and recreated each time the reminder fires: Android keeps "deleted channel" counts, restores the old user settings of a re-created id (new sound/vibration ignored), and deleting a channel that has an active notification removes it. When `timer.channelId` is null a new timestamped channel id is minted (channel proliferation), and the DB write `timerHelper.insertOrUpdateTimer(timer.copy(channelId = ...))` is a fire-and-forget background write racing the notification post. `timer.id!!` x3 NPE risk.
Fix: Create the channel once per (sound, vibrate) combination, never delete per notification; use a stable id per sound; replace `!!` with a guard.

### A3-48 [Low] [integration] extensions/Context.kt:114-127
Evidence: `updateUnreadCountBadge(0)\n RecentsHelper(this).markMissedCallsAsRead()` with `// telecomManager.cancelMissedCallsNotification()` and `// notificationManager.cancelAll()` commented out
Problem: Calls are marked read in the CallLog and the badge cleared, but the app's own missed-call notification (MissedCallReceiver) is not cancelled here, so notification/badge/log can disagree; any exception in the first statement skips marking read. markMissedCallsAsRead also only updates NEW=1 rows (IS_READ not in the WHERE).
Fix: Cancel the app's missed-call notifications (by tag/id) in this function and mark read in a finally-independent order; include `IS_READ = 0` in the selection.

### A3-49 [Low] [integration] extensions/ActivityExt.kt:75-90
Evidence: `SimpleContactsHelper(this).getContactLookupKey((contact).rawId.toString())` then `Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey)` and `addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)`
Problem: If the helper returns "" (contacts without a structured-name row, e.g. company-only contacts, or removed contact) the URI collapses to the bare CONTENT_LOOKUP_URI and QuickContact fails/does nothing; the lookup uses only the lookup key (not `Contacts.getLookupUri(contactId, lookupKey)`) so renamed/merged contacts won't resolve. FLAG_ACTIVITY_CLEAR_TASK without NEW_TASK is ignored/invalid. `isPackageInstalled` is called up to 8 times (PackageManager IPC) on the main thread per click (lines 54-55,62-65).
Fix: Use `ContactsContract.Contacts.getLookupUri(contact.contactId.toLong(), lookupKey)`, bail out when lookupKey is blank, drop CLEAR_TASK, resolve installed package once.

### A3-50 [Low] [correctness] extensions/ActivityExt.kt:206-220
Evidence: `val digits = phoneNumber.filter { it.isDigit() }` ... `Uri.parse("https://wa.me/$digits")`
Problem: wa.me requires international format; national numbers ("0501234567") and numbers with extensions/`;`/`,` pauses produce a wrong or invalid chat link; the leading '+' is stripped without country normalization.
Fix: Normalize to E.164 with libphonenumber (default region from SIM/locale) before building the link; toast if parse fails.

### A3-51 [Low] [perf] extensions/ActivityExt.kt:179-204,222-246
Evidence: `(0..config.appRecommendationDialogCount).random() == 2 && (!isPackageInstalled(...) ...)`
Problem: Promotional dialog gate: random `Random` allocation and Config creation per action; when the random hits, 4 PackageManager IPCs; design nit but also interrupts the user's SMS/contact action with a promo (user intent: remove?).
Fix: Remove the recommendation dialogs (the fork's own build) or evaluate installed-package state once and cache.

## Lines read
- adapters/RecentCallsAdapter.kt: lines read 1-1349
- adapters/ContactsAdapter.kt: lines read 1-885
- adapters/CallHistoryAdapter.kt: lines read 1-252
- adapters/SpeedDialAdapter.kt: lines read 1-82
- adapters/FilterContactSourcesAdapter.kt: lines read 1-77
- extensions/LiquidGlass.kt: lines read 1-147
- views/GlassPanel.kt: lines read 1-73
- extensions/Context.kt: lines read 1-435
- extensions/ActivityExt.kt: lines read 1-300
- extensions/String.kt: lines read 1-33
- extensions/EditText.kt: lines read 1-32
- extensions/RecyclerView.kt: lines read 1-19
- extensions/FastScrollerView.kt: lines read 1-19
- extensions/SharedPreferences.kt: lines read 1-35
