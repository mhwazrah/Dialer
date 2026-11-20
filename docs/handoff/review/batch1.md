# Batch 1 review - call flow (prefix C1-)

Source root: C:\Users\moham\Documents\projects\Right Dialer\app\src\main\kotlin\com\goodwy\dialer
(commons-library behaviours marked "verify" are from memory of Goodwy-Commons, not re-read.)

## Leaks

### C1-01 [High] [leak] activities/CallActivity.kt:354-375 (+1774-1783)
Evidence: `onDestroy()` removes `callCallback`, releases wake locks, stops SOS, but never calls `callDurationHandler.removeCallbacks(updateCallDurationTask)`. The task does `if (!isCallEnded && call.getStateCompat() != Call.REJECT_REASON_UNWANTED) { binding.callStatusLabel.text = ...; callDurationHandler.postDelayed(this, 1000) }`.
Problem: if the activity is destroyed while the call is live (back press with backPressedEndCall off, task removal, `finish()` from `endCall` paths where isCallEnded was never set), the runnable re-posts itself every second forever (`call == null` -> state DISCONNECTED != 1, isCallEnded stays false). It pins the destroyed Activity, its binding/views and background bitmaps, and wakes the main thread once a second for the life of the process.
Fix: in onDestroy add `callDurationHandler.removeCallbacksAndMessages(null)`; also stop the loop when `call == null`.

### C1-02 [Med] [leak] services/CallService.kt:140-147, helpers/CallManager.kt:21
Evidence: `var inCallService: InCallService? = null` (static, `@SuppressLint("StaticFieldLeak")`); CallService.onDestroy only does `callNotificationManager.cancelNotification()` + stopSOS. It is nulled only in onCallRemoved when `getPhoneState() == NoCall`.
Problem: if the service is unbound/destroyed with a call still in `calls` (Telecom crash/rebind, role change) the static keeps the dead service; `CallManager.calls`, `call`, `listeners` (holding CallActivity) are also never cleared. Next onCallAdded re-populates on top of stale calls.
Fix: in CallService.onDestroy: `if (CallManager.inCallService === this) CallManager.inCallService = null`, and add `CallManager.clear()` (calls.clear(); call = null; isSpeakerOn/isMicrophoneMuted reset).

### C1-03 [Med] [leak] activities/CallActivity.kt:317,374 and services/CallService.kt:36,121,136,145
Evidence: `MyCameraImpl.newInstance(this).toggleSOS()` (Activity) / `MyCameraImpl.newInstance(context).stopSOS()` (Service), called on every call state change.
Problem: (verify in commons) `MyCameraImpl.newInstance` caches a singleton that keeps the first `Context` passed; whichever of CallActivity or CallService calls first is retained statically. Also `newInstance` + `stopSOS()` is executed on every `onStateChanged` even when the flash was never started.
Fix: always pass `applicationContext`; in CallService only call stopSOS on RINGING->other transitions / call removed, not every state change.

### C1-04 [Med] [leak] activities/CallActivity.kt:1094-1099, 1106-1129, 1142-1144
Evidence: `RxAnimation.together(...).doAfterTerminate { ... updateState() }.subscribe()` and `RxAnimation.from(holdStatusLabel).shake().subscribe()` - returned Disposable discarded.
Problem: animation callbacks (`doAfterTerminate` in hideDialpad touches views, calls `telecomManager.callCapablePhoneAccounts` and `updateState()`) can run after the activity is destroyed; Rx chain holds the view/activity until terminate.
Fix: keep a `CompositeDisposable`, add the subscriptions, `dispose()` in onDestroy.

### C1-05 [Low] [leak] helpers/CallManager.kt:34-46
Evidence: `call.registerCallback(object : Call.Callback() { ... updateState() })` in `onCallAdded`; never `unregisterCallback` in `onCallRemoved`.
Problem: anonymous callback is only released when the Call object is GC'd; on repeat/late callbacks after removal `updateState()` runs with stale data.
Fix: store the callback in a `Map<Call, Call.Callback>` and unregister in onCallRemoved.

### C1-06 [Low] [leak] helpers/CallNotificationManager.kt:1050 (CallActivity.kt:1050 `CallNotificationManager(this).updateNotification()`)
Evidence: `CallNotificationManager(this).updateNotification()` in toggleMicrophone.
Problem: a new manager is built holding the Activity as `context`; its async callback (contact load, bitmap work) keeps the activity alive until it finishes and uses Activity resources. See also C1-10 (it reloads ALL contacts).
Fix: pass `applicationContext`, or better refresh through the service (see C1-13).

### C1-07 [Low] [leak/perf] activities/CallActivity.kt:549-570, 681-699, 715-733, 806-824
Evidence: `startArrowAnimation(...)` re-arms itself from `.withEndAction { if (!stopAnimation) startArrowAnimation(...) }` forever; started from `onGlobalLayout` even when the slider/arrows are `beGone()` (outgoing / active calls).
Problem: endless 1 s ViewPropertyAnimator chain keeps running on invisible views and while activity is paused/stopped (no pause in onPause/onStop).
Fix: use one `ObjectAnimator` with `repeatCount = INFINITE`, start only while incoming UI is visible, cancel in onPause/onDestroy.

## Performance

### C1-10 [High] [perf] helpers/CallContactHelper.kt:56
Evidence: `ContactsHelper(context).getContacts(getAll = true, showOnlyContactsWithNumbers = true) { contacts -> ... contacts.firstOrNull { it.doesHavePhoneNumber(number) }`
Problem: every `getCallContact` call loads the ENTIRE address book (all contacts, all phone numbers, nicknames, orgs) and linearly scans it. It is invoked on every `setupNotification()` (each onStateChanged / onDetailsChanged / audio toggle / notification button), on every `updateCallContactInfo`, on every `updateCallOnHoldState` (CallActivity.kt:1532, runs on every updateState) and once per conference row (C1-20). A single call produces dozens of full contact loads.
Fix: resolve by number with `ContactsContract.PhoneLookup` (`CONTENT_FILTER_URI`, projection of id/lookup/display name/photo uri) + organization/nickname for that contact only; cache the resulting `CallContact` per `Call`/handle in a small map cleared in `onCallRemoved`.

### C1-11 [Med] [perf] helpers/CallContactHelper.kt:62-69
Evidence: `val contactsWithMultipleNumbers = ...; val numbersToContactIDMap = HashMap<String, Int>() ... numbersToContactIDMap[phoneNumber.value] = contact.contactId`
Problem: map is built for every lookup and never read - pure waste on top of C1-10.
Fix: delete lines 62-69.

### C1-12 [Med] [perf] helpers/CallNotificationManager.kt:64-244, 267-469, 472-556
Evidence: each `setupNotification` re-creates 4 PendingIntents with `PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE`, calls `createNotificationChannel(...)` (IPC), `SimpleContactsHelper(context)` per call, decodes the avatar (`getCallContactAvatar`) and builds a circular bitmap again (`getCircularBitmap(callContactAvatar)` at :145 - the avatar from `getCallContactAvatar(photoUri)` is already round=true, so it is circled twice).
Problem: heavy per-update work; FLAG_CANCEL_CURRENT invalidates the PendingIntents of the notification currently on screen (tap during an update does nothing); FLAG_MUTABLE is unnecessary for explicit intents.
Fix: `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`; create channels once (e.g. in `init`); cache avatar/large icon per call; drop the double circular pass.

### C1-13 [Med] [integration] services/CallService.kt:125-130 (+ receivers/CallActionReceiver.kt:36,51, CallActivity.kt:1050)
Evidence: `onCallAudioStateChanged` only forwards to `CallManager.onAudioStateChanged(audioState)`; the notification is refreshed only manually (`CallNotificationManager(context).updateNotification()` right after `toggleSpeakerRoute()`).
Problem: notification speaker/mute icons are stale when the route changes from the system (BT connect/disconnect, headset plug, route change completing asynchronously after the toggle) or when mute is toggled from the activity of a different manager instance. `isMicrophoneMute` in the notification is read from `AudioManager`, not from `CallAudioState.isMuted`.
Fix: in CallService.onCallAudioStateChanged call `callNotificationManager.setupNotification()`; read mute from `audioState.isMuted`; remove the ad-hoc `CallNotificationManager(context)` instances in the receiver/activity (they also carry their own `isServiceActive` flag, so they can re-post after the service cancelled).

### C1-14 [Med] [perf] activities/CallActivity.kt:150
Evidence: `val wallpaperBlur = BlurFactory.fileToBlurBitmap(wallpaperManager.drawable!!, this, 0.2f, 25f)` inside `onCreate`.
Problem: wallpaper drawable load + blur on the main thread while the incoming-call screen is starting (pre-Tiramisu transparent background). Delays first frame of the call UI.
Fix: run on `ensureBackgroundThread` and set the background in `runOnUiThread`.

### C1-15 [Low] [perf] activities/CallActivity.kt:624, 656, 666, 671
Evidence: `callDraggableBackground.layoutParams = callDraggableBackground.layoutParams` and `AppCompatResources.getDrawable(...)` + `setImageDrawable` inside `ACTION_MOVE`.
Problem: a requestLayout and a drawable inflate/mutate on every touch move event.
Fix: only set the drawable when the state bucket changes; use `layoutParams.width = x; requestLayout()` only when width changed, or animate with `scaleX`/`translationX`.

### C1-16 [Low] [perf] activities/CallActivity.kt:1515-1518, 1004-1005
Evidence: `updateState()` -> `updateCallAudioState(...)`: `audioManager.isMicrophoneMute = isMicrophoneOff; CallManager.inCallService?.setMuted(isMicrophoneOff)`, `setImageResource`, `createOrUpdateAudioRouteChooser(..., create=false)` on every state/details/audio change.
Problem: Binder IPC to AudioManager/Telecom and popup rebuild (it even computes items and dismisses the popup) on every callback; `onDetailsChanged` fires very often.
Fix: re-apply mute only when the stored value differs from `audioState.isMuted`; only touch the popup if it is showing.

### C1-17 [Low] [perf] activities/CallActivity.kt:1331, 1351 (helpers/CallerNotesHelper.kt:15,36)
Evidence: `callerNotesHelper.getCallerNotes(number)` -> `context.config.parseCallerNotes()` (Gson parse of the whole notes JSON from prefs) on the main thread in `updateOtherPersonsInfo`, which runs on every primary-call change.
Problem: JSON parse of all notes on the UI thread each time; same again in `changeNoteDialog`.
Fix: cache the parsed list in config or load it in the contact-load background callback.

### C1-18 [Low] [perf] helpers/CallManager.kt:317-322
Evidence: `Handler().postDelayed({ call?.stopDtmfTone() }, DIALPAD_TONE_LENGTH_MS)`
Problem: deprecated no-arg Handler (implicit looper, lint error on newer targets); a new Handler per key press; fast typing: the first key's delayed stop cuts the tone of the next key short.
Fix: single `Handler(Looper.getMainLooper())` field; `removeCallbacks(stopTone)` before posting.

### C1-19 [Low] [perf] services/CallService.kt:90-96
Evidence: `Handler(Looper.getMainLooper()).postDelayed({ if (!CallActivity.isActivityVisible && CallManager.getPhoneState() != NoCall) {...} }, 2000)`
Problem: arbitrary 2 s delay workaround for blocked background launch (documented, but still a delay); Runnable pins the service and is not cancelled in onCallRemoved/onDestroy, so it can post a full-screen notification after the call already ended quickly.
Fix: keep the Runnable in a field, `removeCallbacks` in onCallRemoved/onDestroy; ideally use the activity-start result (`ActivityOptions`/BAL callback) instead of a timer.

## Conference screen

### C1-20 [High] [perf] adapters/ConferenceCallsAdapter.kt:56
Evidence: `getCallContact(itemView.context, call) { callContact -> root.post { ... } }` inside `onBindViewHolder`.
Problem: N+1: every bind (and every re-bind on scroll/notify) triggers a full address-book load (C1-10), runs an `ensureBackgroundThread`, and the late `root.post` writes into a holder that may already be recycled/reassigned (wrong name/photo on another row) or into a destroyed activity.
Fix: resolve all participants once (map call -> CallContact) before setting the adapter, or cache per call; in the callback verify `holder.bindingAdapterPosition`/tag still matches.

### C1-21 [High] [correctness] adapters/ConferenceCallsAdapter.kt:87-95, 105-113
Evidence: `itemConferenceCallSplit.setOnClickListener { call.splitFromConference(); data.removeAt(position); notifyItemRemoved(position) ...}` (same in End button).
Problem: `position` is captured at bind time; after one row is removed the other rows keep stale positions, so the next click removes the wrong element or throws `IndexOutOfBoundsException`. Also the list is optimistically edited before Telecom confirms.
Fix: `val pos = data.indexOf(call); if (pos == -1) return@setOnClickListener` (or `holder.bindingAdapterPosition`) and remove by that; better, rebuild from `CallManager.getConferenceCalls()` on the state callback.

### C1-22 [Med] [correctness] activities/ConferenceActivity.kt:22
Evidence: `conferenceList.adapter = ConferenceCallsAdapter(this@ConferenceActivity, conferenceList, ArrayList(CallManager.getConferenceCalls())) {}`
Problem: one-time snapshot, no `CallManagerListener`; when a participant hangs up remotely or the conference ends, the screen stays stale (and shows dead calls with enabled buttons). Also, when the call ends while this activity is open nothing closes it except the user pressing back.
Fix: register a CallManagerListener in onCreate/onDestroy, refresh `data` on `onStateChanged` and `finish()` on `NoCall`/non-conference.

### C1-23 [Low] [correctness] activities/ConferenceActivity.kt:39
Evidence: `startActivity(Intent(this, CallActivity::class.java))` in `onBackPressedCompat`, returns true without finishing.
Problem: ConferenceActivity stays in the back stack under CallActivity (singleTask, brings it to front); and no `CallActivity.getStartIntent`.
Fix: `finish()` (CallActivity is already below it) or use `getStartIntent`.

### C1-24 [Low] [correctness] adapters/ConferenceCallsAdapter.kt:93, 111
Evidence: `activity.onBackPressed()` (deprecated) after `data.size == 1`.
Problem: deprecated API, bypasses `onBackPressedCompat` dispatcher on 33+ predictive-back.
Fix: call `activity.finish()` or the project's back helper.

### C1-25 [Low] [correctness] adapters/ConferenceCallsAdapter.kt:63-67
Evidence: `resources.getDrawable(R.drawable.placeholder_voicemail, activity.theme)` then `.findDrawableByLayerId(...).applyColorFilter(color)`
Problem: `getDrawable` shares a constant state; tinting a layer without `mutate()` can tint every other instance of that drawable (same pattern CallActivity.kt:1211-1215).
Fix: `drawable.mutate()` before applying the filter.

### C1-26 [Low] [cleanup] activities/ConferenceActivity.kt:12
Evidence: `import com.goodwy.dialer.helpers.SHOW_RECENT_CALLS_ON_DIALPAD` unused; `setupTopAppBar(...)`/`setBackgroundColor` re-run in every `onResume`.
Fix: remove import; move app-bar setup to onCreate.

## Contact / CallLog / Telecom integration

### C1-30 [High] [correctness] helpers/CallContactHelper.kt:23-24, 32-38, 113
Evidence: `val privateCursor = context.getMyContactsCursor(...)` then `ensureBackgroundThread { ... if (handle == null) { callback(callContact); return@ensureBackgroundThread } ... if (uri.startsWith("tel:")) { ... } }` (no else).
Problem: (1) For a handle that is not `tel:` (sip:, voicemail:, no scheme) the callback is NEVER invoked -> notification never posted (setupNotificationOld/New/Error all live inside that callback), call UI never gets contact info. (2) `privateCursor` is only consumed inside `MyContactsContentProvider.getContacts` (closes it); on the `handle == null` return, the non-`tel:` path, or any exception it is never closed (leak). (3) The cursor is opened on the caller (main) thread - ContentResolver query on main thread.
Fix: `else { callback(callContact) }` for non-tel; open/close the cursor inside the background block with `use {}`; wrap the whole block in try/catch that still calls `callback`.

### C1-31 [Med] [correctness] helpers/CallContactHelper.kt:56-112, helpers/CallNotificationManager.kt:56-61, 464-468
Evidence: the contact callback runs on ContactsHelper's background thread; `try { ... setupNotificationOld(...) } catch (e: Exception) { cancelNotification(); ...; setupNotificationForError(...) }`.
Problem: all notification building happens inside the async callback, outside the try/catch, so an exception (bad avatar, resource, Person/CallStyle) crashes the app on a background thread instead of falling back to the error notification. Also if READ_CONTACTS is revoked, `ContactsHelper.getContacts` may never invoke the callback (no notification).
Fix: put try/catch inside the callback body (and a fallback `callback(emptyContact)` on failure/permission denied).

### C1-32 [Med] [correctness] helpers/CallContactHelper.kt:81, 86
Evidence: `contacts.firstOrNull { it.doesHavePhoneNumber(number) }` ... `contact.phoneNumbers.firstOrNull { it.normalizedNumber == number }`
Problem: lookup by raw `number` (decoded from handle) vs stored numbers - first-match linear scan, no PhoneLookup/E.164 comparison; the label match compares `normalizedNumber == number` (a normalized vs raw string), so the number-type label is missing whenever the dialed form differs ("+1 555.." vs "555.."). Different contacts sharing a number resolve to whichever is first.
Fix: use `PhoneLookup` (or `PhoneNumberUtils.compare`) and take the matching `PhoneNumber` from that contact via the same comparator.

### C1-33 [Low] [correctness] helpers/CallContactHelper.kt:48
Evidence: `if (voiceMailNumber == number) { callContact.isVoiceMail = true }`
Problem: raw string compare of voicemail number vs handle number; `getVoiceMailNumber` also needs READ_PHONE_STATE and is called per account for every lookup.
Fix: `PhoneNumberUtils.compare(voiceMailNumber, number)`; or `telecomManager.isVoiceMailNumber(handle, number)`.

### C1-34 [Med] [correctness] services/SimpleCallScreeningService.kt:19-57
Evidence: `override fun onScreenCall(callDetails: Call.Details) { val number = ...; when { ... blockUnknownNumbers ... } }` - no `callDetails.callDirection` check.
Problem: `onScreenCall` is also called for OUTGOING calls on Q+. With "block unknown numbers" or a blocked number, outgoing calls the user dials (not yet in `recentOutgoingNumbers`, e.g. dialed from another app/car) get disallowed/rejected.
Fix: `if (isQPlus() && callDetails.callDirection == Call.Details.DIRECTION_OUTGOING) { respondToCall(callDetails, CallResponse.Builder().build()); return }`.

### C1-35 [Med] [perf] services/SimpleCallScreeningService.kt:27-47
Evidence: `isNumberBlocked(number)`, `getMyContactsCursor(...)`, `SimpleContactsHelper(this).existsSync(number, privateCursor)`
Problem: `onScreenCall` runs on the main thread of the service; BlockedNumberContract query, contacts lookup and the private-contacts provider query are synchronous there. Telecom enforces a ~5 s timeout, and on a slow/locked device this risks missing the response (call then rings anyway). `privateCursor` is not closed if `existsSync` does not close it (verify).
Fix: run the work on a worker thread (`ensureBackgroundThread`) and call `respondToCall` from it; close `privateCursor` with `use {}`.

### C1-36 [Low] [correctness] services/SimpleCallScreeningService.kt:27, 40, 49
Evidence: `number != null && isNumberBlocked(number) && baseConfig.blockingEnabled ->` (blockingEnabled already checked by the first branch); `config.recentOutgoingNumbers` read twice.
Problem: redundant checks, `recentOutgoingNumbers` is parsed from prefs StringSet each read; blocked-number check is done even when `doNotBlockContactsAndRecent` would allow the call, so a blocked number that is also a contact is let through (design) but a non-blocked number falls to the next branch only when not blocked.
Fix: drop the redundant `&& baseConfig.blockingEnabled`; read `recentOutgoingNumbers` once.

### C1-37 [Med] [correctness] AndroidManifest.xml:206-216, 270-280, 330-334 with services/CallService.kt:80,108 and CallActivity.kt
Evidence: `android:directBootAware="true"` on CallActivity, CallService, CallActionReceiver while code uses `baseConfig`/`config` (default SharedPreferences, credential-encrypted storage).
Problem: a call before first unlock (reboot, incoming call) touches `config.*` in direct-boot mode -> `IllegalStateException`/unavailable prefs -> crash in the InCallService.
Fix: wrap `config` reads in CallService/CallActivity in a safe accessor (defaults when `!isUserUnlocked`) or keep prefs in device-protected storage.

### C1-38 [Low] [integration] extensions/Call.kt:33-39
Evidence: `details.callDirection == Call.Details.DIRECTION_OUTGOING`
Problem: `DIRECTION_UNKNOWN` (-1, used by some carriers/IMS/conference legs) is treated as incoming, which in CallService:57-66 changes full-screen/notification handling.
Fix: `isOutgoing = direction == OUTGOING || (direction == UNKNOWN && state in OUTGOING_CALL_STATES)`.

### C1-39 [Low] [correctness] activities/NotificationActivity.kt:23, 26
Evidence: `val phoneNumber = intent.extras?.getString(CURRENT_PHONE_NUMBER) ?: return` ... `phoneNumber.let { ... }`
Problem: the early `return` skips `finish()` so a transparent/empty activity stays on screen (blocks the UI) when the extra is missing; `.let` wrappers are redundant. The missed-call group summary update (`updateUnreadCountAndGroup` is commented out) and CallLog `NEW/IS_READ` flags are not cleared here (verify MissedCallReceiver does it) so notification and call-log badge can go out of sync.
Fix: `?: run { finish(); return }`; drop `.let`; mark the number's missed calls read via `CallLog.Calls.NEW/IS_READ` update off the main thread.

### C1-40 [Med] [correctness] activities/DialerActivity.kt:43-47
Evidence: `Intent(Intent.ACTION_DIAL).apply { ... launchActivityIntent(this) }` in the `blockCallFromAnotherApp` branch - no `finish()`.
Problem: unlike `initOutgoingCall` (always finishes) this path leaves DialerActivity (transparent theme) alive in the back stack/behind the dialpad.
Fix: `finish()` after `launchActivityIntent(this)`.

### C1-41 [Med] [correctness] activities/DialerActivity.kt:59, 65 and extensions/CallExt.kt:107-124
Evidence: `isNumberBlocked(callNumber.toString().replace("tel:", ""), getBlockedNumbers())` / `getHandleToUse(intent, callNumber.toString()) {...}`; `handlePermission(PERMISSION_READ_PHONE_STATE) { if (it) {...} }` with no else.
Problem: (1) number is not `Uri.decode`d (`tel:%2B1555...` stays encoded) so blocked-number check and `config.getCustomSIM(phoneNumber)` use the wrong key; `callNumber.toString()` still includes `tel:` when the scheme replace misses (e.g. `TEL:`). (2) `getBlockedNumbers()` is a BlockedNumberContract/DB query on the main thread. (3) If READ_PHONE_STATE is denied the callback is never called and DialerActivity never finishes (blank transparent activity stuck).
Fix: `val number = Uri.decode(callNumber!!.schemeSpecificPart)`; do the blocked check on a background thread; in getHandleToUse call `callback(null)` when permission denied (and finish in DialerActivity).

### C1-42 [Low] [correctness] extensions/CallExt.kt:76-82
Evidence: `handlePermission(PERMISSION_READ_PHONE_STATE) { val wantedSimIndex = ...; launchCallIntent(recipient, handle, ...) }` (the `granted` lambda argument is ignored)
Problem: runs even if the permission was denied; `getAvailableSIMCardLabels()` then runs without READ_PHONE_STATE (SecurityException) or falls back silently to the wrong SIM.
Fix: `{ granted -> if (granted) {...} }`.

### C1-43 [Low] [correctness] extensions/CallExt.kt:113-114
Evidence: `callback(intent.getParcelableExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE)!!)`
Problem: deprecated untyped `getParcelableExtra` (API 33) and `!!` on a possibly-wrong-typed extra; `config.getCustomSIM(phoneNumber)` queried twice (:117-118).
Fix: `IntentCompat.getParcelableExtra(intent, key, PhoneAccountHandle::class.java)`; read custom SIM once.

### C1-44 [Low] [cleanup] activities/DialerActivity.kt:82-83, 94
Evidence: deprecated `onActivityResult`, `catch (ignored: Exception) {}`.
Fix: activity-result API; log/ignore intentionally.

## Call state / audio correctness

### C1-50 [Med] [correctness] activities/CallActivity.kt:998-1002
Evidence: `if (!isMicrophoneInitialized) { audioManager.isMicrophoneMute = false; CallManager.inCallService?.setMuted(false); isMicrophoneOff = false; isMicrophoneInitialized = true }`
Problem: every new CallActivity instance (re-opened from notification, task removed, call-waiting relaunch at :1725/1731) force-unmutes the mic during an ongoing call on its first audio-state update, without updating `CallManager.isMicrophoneMuted` - the user silently becomes unmuted and the notification toggle (`!CallManager.isMicrophoneMuted` in CallActionReceiver:29) is now inverted.
Fix: initialise from `CallManager.getCallAudioState()?.isMuted` instead of forcing false; keep `CallManager.isMicrophoneMuted` in sync.

### C1-51 [Med] [correctness] receivers/CallActionReceiver.kt:29-34, helpers/CallManager.kt:324-328
Evidence: `val newMuteState = !CallManager.isMicrophoneMuted; ... CallManager.inCallService?.setMuted(newMuteState); context.audioManager.isMicrophoneMute = newMuteState`
Problem: toggle derives from a static flag that is never reset between calls and not synced with `CallAudioState.isMuted`; also sets the process-global `AudioManager.isMicrophoneMute` in addition to Telecom's mute (double mechanism - can leave system mic muted if the call ends abnormally).
Fix: `val muted = CallManager.getCallAudioState()?.isMuted ?: false; inCallService?.setMuted(!muted)`; drop `audioManager.isMicrophoneMute`; reset the statics in `CallManager.clear()` when NoCall.

### C1-52 [Med] [correctness] helpers/CallManager.kt:25, 54-59, 158-185
Evidence: `var isSpeakerOn: Boolean = false` set only by toggle/setAudioRoute; `onAudioStateChanged` only forwards the route.
Problem: static speaker flag is never derived from the system route nor reset between calls: a call that ends with speaker on makes the next call's first `toggleSpeakerRoute()` go to the "turn off" branch (route restore) instead of turning speaker on; same after BT/headset changes the route.
Fix: in `onAudioStateChanged` set `isSpeakerOn = audioState.route == ROUTE_SPEAKER`; reset on NoCall.

### C1-53 [Med] [correctness] activities/CallActivity.kt:862-864, 1030
Evidence: `if (btDevices.isNotEmpty()) { requestBluetoothConnectPermissionIfNeeded() }` inside `createOrUpdateAudioRouteChooser`, which `updateCallAudioState` calls on every audio-state change with `create = false`.
Problem: when BLUETOOTH_CONNECT is not granted and a BT device is present, the permission dialog is re-requested on every audio-state/ call-state update (also over the incoming-call screen), not only when the user opens the chooser.
Fix: request only when `create == true` (user opened the chooser), and only once per call (flag).

### C1-54 [Low] [correctness] activities/CallActivity.kt:1507, 1778
Evidence: `if (state == Call.REJECT_REASON_UNWANTED) changeProximitySensor = false` and `call.getStateCompat() != Call.REJECT_REASON_UNWANTED`
Problem: compares a call STATE with a reject-reason constant (value 1 == STATE_DIALING); works only by coincidence and the intent is unclear.
Fix: use `Call.STATE_DIALING` (or whatever was intended).

### C1-55 [Low] [correctness] activities/CallActivity.kt:1445
Evidence: `val isBusy = connectTimeMillis.toInt() == 0`
Problem: truncating an epoch-millis Long to Int; can read 0 for a connected call (low 32 bits zero) and negative values otherwise.
Fix: `connectTimeMillis == 0L`.

### C1-56 [Low] [correctness] activities/CallActivity.kt:1630-1632
Evidence: `val aspectRatio = windowHeight / windowWidth` (Int/Int) ... `bg.cropCenter(bg.width / aspectRatioNotZero, bg.height)`
Problem: integer division (2400/1080 = 2) crops the avatar to half width instead of the screen aspect; also `callHolder.width/height` may still be 0 when this async callback runs before first layout, giving `Size(0,0)` -> `loadThumbnail` throws -> no background.
Fix: `val w = (bg.height * windowWidth / windowHeight.toFloat()).toInt().coerceAtMost(bg.width)`; wait for layout (`doOnLayout`) before loading.

### C1-57 [Low] [correctness] activities/CallActivity.kt:1603, 1599-1651
Evidence: `callContact = contact` assigned in the background-thread callback; `runOnUiThread { ... updateOtherPersonsInfo(...) }` with no `isDestroyed` guard.
Problem: non-volatile field written off-thread and read on UI thread; UI update after the activity is destroyed (touches `binding` and `Glide`).
Fix: assign inside `runOnUiThread`, guard with `if (isDestroyed || isFinishing) return`.

### C1-58 [Low] [correctness] activities/CallActivity.kt:1711-1714
Evidence: `audioManager.mode = AudioManager.MODE_NORMAL` in `endCall()` (also executed when only one of two calls ends), and `audioManager.mode = AudioManager.MODE_IN_CALL` at :129.
Problem: the dialer is not supposed to set the global audio mode (Telecom owns it); setting NORMAL while another call is still active can break call audio until the new CallActivity sets IN_CALL again.
Fix: remove both manual mode changes, or only reset when `CallManager.getPhoneState() == NoCall`.

### C1-59 [Low] [correctness] activities/CallActivity.kt:336-339, 123
Evidence: `override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); updateState() }` (no `setIntent`), `needSelectSIM = intent.getBooleanExtra(NEED_SELECT_SIM, false)` only in `onCreate`.
Problem: singleTask activity: a second outgoing call that needs SIM selection delivers a new intent that is ignored, so `NEED_SELECT_SIM` is never honoured and `getHandleToUse(intent, ...)` uses the stale first intent.
Fix: `setIntent(intent)` and re-evaluate `needSelectSIM` in onNewIntent.

### C1-60 [Low] [correctness] activities/CallActivity.kt:1824
Evidence: `proximityWakeLock!!.acquire(60 * MINUTE_SECONDS * 1000L)`
Problem: lock expires after 60 min and is never re-acquired for the same call, so long calls lose proximity screen-off.
Fix: re-acquire from `updateCallDurationTask` when `!isHeld`, or acquire without timeout and release in onDestroy/endCall.

### C1-61 [Low] [correctness] activities/CallActivity.kt:1801, 1792-1797
Evidence: `keyguardManager.requestDismissKeyguard(this, null)` plus deprecated `FLAG_DISMISS_KEYGUARD`/`FLAG_SHOW_WHEN_LOCKED`/`FLAG_TURN_SCREEN_ON` and `FULL_WAKE_LOCK`.
Problem: on a secure device every incoming call screen asks for credentials; redundant with `setShowWhenLocked/setTurnScreenOn` (:1787-1790), deprecated wake lock.
Fix: drop FLAG_* duplicates and requestDismissKeyguard unless the user taps an action that needs unlock.

### C1-62 [Low] [correctness] helpers/CallManager.kt:229-253, 87-89
Evidence: `updateState()` computes `getPhoneState()` first, then `calls.removeAll { it.getStateCompat() == Call.STATE_DISCONNECTED }`; `val conference = calls.find { it.isConference() } ?: return NoCall` for >2 calls.
Problem: listeners are notified with a state that still counts disconnected calls (removal happens after the dispatch); 3+ calls without a conference parent (two held + one active) return `NoCall`, which makes CallActivity finish while calls are live.
Fix: remove disconnected calls before computing state; for the >2 fallback return `TwoCalls(active, hold)` instead of `NoCall`.

### C1-63 [Low] [correctness] services/CallService.kt:115-117
Evidence: `if (wasPrimaryCall) { startActivity(CallActivity.getStartIntent(this)) }` (no try/catch, unlike :73-82)
Problem: background-activity-start restrictions can throw/no-op; an uncaught exception here kills the InCallService.
Fix: wrap in try/catch like the first start.

### C1-64 [Low] [correctness] receivers/CallActionReceiver.kt:21
Evidence: `if (!context.config.keepCallsInPopUp) context.startActivity(CallActivity.getStartIntent(context))` from a notification-action broadcast.
Problem: notification-trampoline restrictions (targetSdk 31+) block starting an activity from a receiver fired by a notification action; `onReceive` has no try/catch and the order starts the activity before `CallManager.accept()`.
Fix: answer first, then try/catch the start (or make the accept action a `PendingIntent.getActivity` to CallActivity); use `goAsync()` if the async `updateNotification()` must survive.

### C1-65 [Low] [cleanup] helpers/CallNotificationManager.kt:536-545, 202-206, 63, 266
Evidence: `if (false /*callState != Call.STATE_RINGING*/) {...}` dead block; emoji glyphs in action labels (`"🔊"`, `"🎤"`, `"📞"`); `.setTimeoutAfter(-1)`; `updateNotification()` ignores `forceFullScreen`; catch in setupNotificationNew re-calls `setupNotificationOld(lowPriority)` without `forceFullScreen`.
Problem: dead code; emoji labels are read out by TalkBack and may render poorly; forceFullScreen lost on fallback.
Fix: delete dead block; use icons/text only; pass `forceFullScreen` through the fallback.

### C1-66 [Low] [perf] helpers/CallContactAvatarHelper.kt:13-50
Evidence: `MediaStore.Images.Media.getBitmap(contentResolver, photoUriParse)` (pre-Q), `createBitmap(bitmap.width, bitmap.width)` vs `Rect(0, 0, bitmap.width, bitmap.height)`, `Size(width, height)` with defaults `0,0`.
Problem: pre-Q decodes full-size photo; the round bitmap is `width x width` but the source rect uses `height` so non-square photos are squashed/cropped wrongly; default size 0x0 throws and returns null when `round=false` is used without sizes; the source bitmap is not recycled.
Fix: center-crop to a square before drawing; require/default a real size; recycle the temporary source.

### C1-67 [Low] [cleanup] activities/CallActivity.kt:98, 213-215, 53, 57-58
Evidence: `private var dialpadHeight = 0f` + `dialpadWrapper.onGlobalLayout { dialpadHeight = ... }` (never read); unused imports `kotlin.div`, `kotlin.hashCode`.
Problem: dead state plus an extra global-layout listener (only in THEME_BACKGROUND branch).
Fix: delete.

### C1-68 [Low] [correctness] activities/CallActivity.kt:124, 323
Evidence: `if (needSelectSIM) initOutgoingCall(CallManager.getPrimaryCall()!!.details.handle)`
Problem: `details.handle` can be null (e.g. conference / unknown) -> NPE inside onCreate; `initOutgoingCall(Uri)` takes non-null.
Fix: `CallManager.getPrimaryCall()?.details?.handle?.let { initOutgoingCall(it) }`.

### C1-69 [Low] [correctness] activities/CallActivity.kt:1125-1127
Evidence: `val accounts = telecomManager.callCapablePhoneAccounts` inside `hideDialpad().doAfterTerminate`
Problem: needs READ_PHONE_STATE; if revoked it throws SecurityException from an Rx callback (crash).
Fix: guard with `hasPermission(PERMISSION_READ_PHONE_STATE)` / try-catch.

## Lines read coverage
- activities/CallActivity.kt: lines read 1-1900
- helpers/CallManager.kt: lines read 1-358
- services/CallService.kt: lines read 1-149
- helpers/CallNotificationManager.kt: lines read 1-557
- helpers/CallContactHelper.kt: lines read 1-115
- helpers/CallContactAvatarHelper.kt: lines read 1-51
- extensions/Call.kt: lines read 1-49
- extensions/CallExt.kt: lines read 1-190
- activities/ConferenceActivity.kt: lines read 1-44
- adapters/ConferenceCallsAdapter.kt: lines read 1-134
- receivers/CallActionReceiver.kt: lines read 1-55
- services/SimpleCallScreeningService.kt: lines read 1-87
- activities/DialerActivity.kt: lines read 1-102
- activities/NotificationActivity.kt: lines read 1-53
- models/AudioRoute.kt: lines read 1-18
- Also checked: res/layout/activity_call.xml ids referenced from code (dialpadInputGlass, callNetworkType, callAcceptAndDecline, callDetails, incomingCallHolder, ongoingCallHolder, callSimId, callSimImage, addCallerNote present), AndroidManifest.xml entries for the call components.
