package com.goodwy.dialer.helpers

import android.annotation.SuppressLint
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.OperationApplicationException
import android.content.Context
import android.database.sqlite.SQLiteFullException
import android.os.RemoteException
import android.provider.CallLog
import android.provider.CallLog.Calls
import android.telephony.PhoneNumberUtils
import com.google.i18n.phonenumbers.PhoneNumberUtil
import android.provider.CallLog.Calls.PRESENTATION_UNAVAILABLE
import android.provider.CallLog.Calls.PRESENTATION_UNKNOWN
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.BlockedNumber
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.dialer.R
import com.goodwy.dialer.activities.SimpleActivity
import com.goodwy.dialer.extensions.config
import com.goodwy.dialer.extensions.getAvailableSIMCardLabels
import com.goodwy.dialer.models.RecentCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class RecentsHelper(private val context: Context) {
    companion object {
//        private const val COMPARABLE_PHONE_NUMBER_LENGTH = 9
        const val QUERY_LIMIT = 100

        /** Calls.PHONE_ACCOUNT_COMPONENT_NAME is hidden API; the column name is stable. */
        private const val PHONE_ACCOUNT_COMPONENT_NAME = "subscription_component_name"

        /** +4670... and 070... are the same person's history. */
        @Suppress("DEPRECATION")
        /**
         * Same subscriber number, e.g. 05x vs +9665x. Deliberately stricter than PhoneNumberUtils.compare, which only
         * checks the last 7 digits and would treat 00000111 as 0500000111 (deleting another number's history).
         * libphonenumber's SHORT_NSN_MATCH (one number merely ending in the other) is rejected for the same reason.
         */
        fun isSameNumber(a: String, b: String): Boolean {
            if (a == b) return true
            if (a.isBlank() || b.isBlank()) return false
            // Cheap loose check first: most pairs (different numbers) end here without libphonenumber parsing
            @Suppress("DEPRECATION")
            if (!PhoneNumberUtils.compare(a, b)) return false
            return when (PhoneNumberUtil.getInstance().isNumberMatch(a, b)) {
                PhoneNumberUtil.MatchType.EXACT_MATCH, PhoneNumberUtil.MatchType.NSN_MATCH -> true
                else -> false
            }
        }
    }

    private val contentUri = Calls.CONTENT_URI

    /** Per-load lookups: SIM maps, voicemail and blocked numbers are resolved once per getRecentCalls, not per query. */
    private class LoadLookups(
        val accountIdToSimIDMap: Map<String, Int>,
        val accountIdToSimColorMap: Map<String, Int>,
        val voiceMailNumbers: List<String>,
        /** Null when blocked numbers are shown, so nothing is filtered. */
        val blockedNumbers: ArrayList<BlockedNumber>?,
    )

    private fun loadLookups(): LoadLookups {
        val sims = context.getAvailableSIMCardLabels()
        val blocked = if (context.config.showBlockedNumbers) null else try {
            context.getBlockedNumbers()
        } catch (_: SecurityException) {
            ArrayList()
        }
        return LoadLookups(
            accountIdToSimIDMap = sims.associate { it.handle.id to it.id },
            accountIdToSimColorMap = sims.associate { it.handle.id to it.color },
            voiceMailNumbers = loadVoiceMailNumbers(),
            blockedNumbers = blocked,
        )
    }

    fun getRecentCalls(
        previousRecents: List<RecentCall> = ArrayList(),
        queryLimit: Int = QUERY_LIMIT,
        isDialpad: Boolean = false,
        updateCallsCache: Boolean = false,
        useCachedNames: Boolean = false,
        callback: (List<RecentCall>) -> Unit,
    ) {
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            callback(ArrayList())
            return
        }

        fun proceed(contacts: ContactsRepository.Snapshot) {
            //Do not use the current list if the recent ones have been changed in another activity.
            //The cached-names pass deliberately does NOT consume the flag, so the follow-up full
            //pass still starts from scratch and re-resolves every row against the contact book.
            val lookups = loadLookups()
            val needUpdateRecents = context.config.needUpdateRecents
            val previousRecentsOrEmpty = if (needUpdateRecents) ArrayList() else previousRecents
            if (needUpdateRecents && !isDialpad && !useCachedNames) context.config.needUpdateRecents = false
            val recentCalls = if (previousRecentsOrEmpty.isNotEmpty()) {
                val previousRecentCalls = previousRecentsOrEmpty
                    .flatMap { it.groupedCalls ?: listOf(it) }
                    .map { it.copy(groupedCalls = null) }

                // We take into account the size of previous entries in the total limit
                val remainingLimit = queryLimit - previousRecentCalls.size

                if (remainingLimit <= 0) {
                    // If there are already enough previous entries, we return them within the limit
                    previousRecentCalls.take(queryLimit)
                } else {

                    val newerLimit = (queryLimit * 0.3).toInt() // 30% limit on new entries
                    val newerRecents = getRecents(
                        contacts = contacts,
                        lookups = lookups,
                        selection = "${Calls.DATE} > ?",
                        selectionParams = arrayOf("${previousRecentCalls.first().startTS}"),
                        updateCallsCache = updateCallsCache,
                        limit = newerLimit
                    )

                    val olderLimit = queryLimit - newerRecents.size // Remaining limit on old entries
                    val olderRecents = getRecents(
                        contacts = contacts,
                        lookups = lookups,
                        selection = "${Calls.DATE} < ?",
                        selectionParams = arrayOf("${previousRecentCalls.last().startTS}"),
                        updateCallsCache = updateCallsCache,
                        limit = olderLimit
                    )

                    newerRecents + previousRecentCalls + olderRecents
                }
            } else {
                getRecents(contacts, lookups, updateCallsCache = updateCallsCache, limit = queryLimit)
            }

            callback(
                recentCalls
                    .sortedByDescending { it.startTS }
                    .distinctBy { it.id }
//                        .take(queryLimit)
            )
        }

        if (useCachedNames) {
            // Fast first paint: skip the full contact-book load and render from the call log's
            // own cached names/photos, which the full pass keeps up to date. The follow-up full
            // pass re-resolves everything against the real contact book.
            ensureBackgroundThread { proceed(ContactsRepository.Snapshot(emptyList(), emptyMap(), emptyList())) }
        } else {
            ContactsRepository.ensureLoaded(context) { snapshot ->
                ensureBackgroundThread { proceed(snapshot) }
            }
        }
    }

    /**
     * One number's calls (any formatting of it, see [isSameNumber]) straight from the call log, newest first.
     * Not grouped, and never touches the other numbers' rows.
     */
    fun getRecentCallsOfNumber(phoneNumber: String, queryLimit: Int, callback: (List<RecentCall>) -> Unit) {
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            callback(ArrayList())
            return
        }
        ContactsRepository.ensureLoaded(context) { snapshot ->
            ensureBackgroundThread {
                val numbers = loggedNumbers().filter { isSameNumber(it, phoneNumber) }
                if (numbers.isEmpty()) {
                    callback(ArrayList())
                    return@ensureBackgroundThread
                }
                var selection = "${Calls.NUMBER} IN (${getQuestionMarks(numbers.size)})"
                if ("" in numbers) selection += " OR ${Calls.NUMBER} IS NULL"
                callback(
                    getRecents(snapshot, loadLookups(), selection, numbers.toTypedArray(), updateCallsCache = false, limit = queryLimit)
                        .distinctBy { it.id }
                )
            }
        }
    }

    private fun loggedNumbers(): Set<String> {
        val numbers = HashSet<String>()
        context.contentResolver.query(contentUri, arrayOf(Calls.NUMBER), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                numbers.add(cursor.getStringValueOrNull(Calls.NUMBER).orEmpty())
            }
        }
        return numbers
    }

    /** Keeps the outgoing-numbers whitelist (used by the call screener) in step with the calls just loaded. */
    fun updateRecentOutgoingNumbers(calls: List<RecentCall>) {
        context.config.recentOutgoingNumbers = calls
            .flatMap { listOf(it) + (it.groupedCalls ?: emptyList()) }
            .filter { it.type == Calls.OUTGOING_TYPE }
            .mapTo(HashSet()) { it.phoneNumber }
    }

    fun getGroupedRecentCalls(
        previousRecents: List<RecentCall> = ArrayList(),
        queryLimit: Int = QUERY_LIMIT,
        isDialpad: Boolean = false,
        updateCallsCache: Boolean = false,
        useCachedNames: Boolean = false,
        callback: (List<RecentCall>) -> Unit,
    ) {
        getRecentCalls(previousRecents, queryLimit, isDialpad, updateCallsCache = updateCallsCache, useCachedNames = useCachedNames) { recentCalls ->
            callback(
                groupSubsequentCalls(calls = recentCalls)
            )
        }
    }

    private fun shouldGroupCalls(callA: RecentCall, callB: RecentCall): Boolean {
        val differentSim = callA.simID != callB.simID
        val differentDay = callA.dayCode != callB.dayCode
        val namesAreBothRealAndDifferent =
            callA.name != callB.name &&
                callA.name != callA.phoneNumber &&
                callB.name != callB.phoneNumber

        if (differentSim || differentDay || namesAreBothRealAndDifferent) return false

        // Strict match: a group is deleted as a unit, so a different number ending in the same digits must not join it.
        return isSameNumber(callA.phoneNumber, callB.phoneNumber)
    }

    private fun groupSubsequentCalls(calls: List<RecentCall>): List<RecentCall> {
        val result = mutableListOf<RecentCall>()
        if (calls.isEmpty()) return result

        var currentCall = calls[0]
        for (i in 1 until calls.size) {
            val nextCall = calls[i]
            if (shouldGroupCalls(currentCall, nextCall)) {
                if (currentCall.groupedCalls.isNullOrEmpty()) {
                    currentCall = currentCall.copy(groupedCalls = mutableListOf(currentCall))
                }

                currentCall.groupedCalls?.add(nextCall)
            } else {
                result += currentCall
                currentCall = nextCall
            }
        }

        result.add(currentCall)
        return result
    }

    @SuppressLint("NewApi")
    private fun getRecents(
        contacts: ContactsRepository.Snapshot,
        lookups: LoadLookups,
        selection: String? = null,
        selectionParams: Array<String>? = null,
        updateCallsCache: Boolean,
        limit: Int
    ): List<RecentCall> {
        val recentCalls = mutableListOf<RecentCall>()
        // Same timestamp alone is not a duplicate (dual-SIM / imported rows share it); OEM duplicate rows match on all three.
        val seenCalls = HashSet<Triple<Long, String?, Int>>()
//        val contactsNumbersMap = HashMap<String, String>()
        val contactPhotosMap = HashMap<String, String>()
        // Caches misses (null) too, so a number with no matching contact — the common case for
        // repeated unknown callers — doesn't rescan the whole contact list for every log row.
        val contactsMap = HashMap<String, Contact?>()

        val isHuawei = context.config.isEmui
        val voiceMailNumbers = lookups.voiceMailNumbers
        val accountIdToSimIDMap = lookups.accountIdToSimIDMap
        val accountIdToSimColorMap = lookups.accountIdToSimColorMap
        val blockedNumbers = lookups.blockedNumbers
        val blockedCache = HashMap<String, Boolean>()
        // Cached name/photo fixes, one per (number, country) per pass, written in a single batch afterwards
        val cacheUpdateKeys = HashSet<Pair<String, String?>>()
        val cacheUpdates = ArrayList<ContentProviderOperation>()

        val projection = if (isQPlus()) {
            arrayOf(
                Calls._ID,
                Calls.NUMBER,
                Calls.CACHED_NAME,
                Calls.CACHED_PHOTO_URI,
                Calls.DATE,
                Calls.DURATION,
                Calls.TYPE,
                Calls.PHONE_ACCOUNT_ID,
                PHONE_ACCOUNT_COMPONENT_NAME,
                Calls.NUMBER_PRESENTATION,
                Calls.FEATURES,
                Calls.COUNTRY_ISO,
                Calls.BLOCK_REASON
            )
        } else {
            arrayOf(
                Calls._ID,
                Calls.NUMBER,
                Calls.CACHED_NAME,
                Calls.CACHED_PHOTO_URI,
                Calls.DATE,
                Calls.DURATION,
                Calls.TYPE,
                Calls.PHONE_ACCOUNT_ID,
                PHONE_ACCOUNT_COMPONENT_NAME,
                Calls.NUMBER_PRESENTATION,
                Calls.FEATURES,
                Calls.COUNTRY_ISO
            )
        }

        // https://issuetracker.google.com/issues/175198972?pli=1#comment6
        // ponytail: blocked rows are dropped after the query, so over-fetch 3x to keep the visible window full; heavy blocking can still come up short.
        val fetchLimit = if (blockedNumbers.isNullOrEmpty()) limit else limit * 3
        val limitedUri = contentUri.buildUpon()
            .appendQueryParameter(Calls.LIMIT_PARAM_KEY, fetchLimit.toString())
            .build()
        val sortOrder = "${Calls.DATE} DESC" //TODO Sort call
        val cursor = context.contentResolver.query(limitedUri, projection, selection, selectionParams, sortOrder)

        cursor?.use {
            if (!cursor.moveToFirst()) {
                return@use
            }

            do {
                val id = cursor.getIntValue(Calls._ID)
                var isUnknownNumber = false
                val number = cursor.getStringValueOrNull(Calls.NUMBER)
                val presentation = cursor.getIntValueOrNull(Calls.NUMBER_PRESENTATION) ?: Calls.PRESENTATION_ALLOWED
                val presentationBlocked = presentation == PRESENTATION_UNKNOWN
                    || presentation == PRESENTATION_UNAVAILABLE
                    || presentation == Calls.PRESENTATION_RESTRICTED
                if (presentationBlocked || number.isNullOrBlank() || number == "-1") {
                    isUnknownNumber = true
                }

                var name = cursor.getStringValueOrNull(Calls.CACHED_NAME)
                if (name.isNullOrEmpty() || name == "-1") {
                    name = number.orEmpty()
                }

                var contact: Contact? = null
                if (number != null) {
                    contact = if (contactsMap.containsKey(number)) {
                        contactsMap[number]
                    } else {
                        contacts.find(number)
                            .also { contactsMap[number] = it }
                    }
                }

                //Without this, the call history does not reflect the contact name for the contact's second and subsequent numbers
                //TODO try again to find the contact name
                if (!isUnknownNumber) {
                    if (contact != null) name = contact.getNameToDisplay()
                }

                if (name.isEmpty() || name == "-1") {
                    name = context.getString(R.string.unknown)
                }

                val isVoiceMail = if (name == number)  voiceMailNumbers.contains(number) else false

                var photoUri = cursor.getStringValue(Calls.CACHED_PHOTO_URI).orEmpty()
                if (photoUri.isEmpty() && !number.isNullOrEmpty()) {
                    if (contactPhotosMap.containsKey(number)) {
                        photoUri = contactPhotosMap[number]!!
                    } else {
                        if (contact != null) {
                            photoUri = contact.photoUri
                            contactPhotosMap[number] = contact.photoUri
                        }
                    }
                }

                val startTS = cursor.getLongValue(Calls.DATE)
                val type = cursor.getIntValue(Calls.TYPE)
                if (!seenCalls.add(Triple(startTS, number, type))) continue

                // Hidden/unknown callers have no number to match: commons compares trimmed forms, and a blocked entry
                // without an E.164 form has an empty one, which would hide every private call. Hidden-caller blocking
                // is its own setting, applied when the call arrives.
                if (!isUnknownNumber && !blockedNumbers.isNullOrEmpty() &&
                    blockedCache.getOrPut(number.orEmpty()) { context.isNumberBlocked(number.orEmpty(), blockedNumbers) }
                ) continue

                val duration = cursor.getIntValue(Calls.DURATION)
                val features = cursor.getIntValue(Calls.FEATURES)
                val accountId = cursor.getStringValue(Calls.PHONE_ACCOUNT_ID)
                //var simID = accountIdToSimIDMap[accountId] ?: -1
                // Some users do not correctly detect the second ESIM card
                // https://stackoverflow.com/questions/63834168/identifying-a-sim-card-slot-with-phone-account-id-in-android-calllogcalls
                // On some devices PHONE_ACCOUNT_ID just returns the SIM card slot index
                var simID = -1
                if (isHuawei) {
                    if (accountId != null) {
                        if (accountId.length == 1) {
                            accountId.toIntOrNull()?.let {
                                simID = if (it >= 0) it + 1 else -1 //Huawei's sim card index returns (0,1...)
                            }
                        }
                    }
                    if (simID == -1) simID = accountIdToSimIDMap[accountId] ?: -1
                } else {
                    simID = accountIdToSimIDMap[accountId] ?: -1
                    if (simID == -1 && accountId != null) {
                        if (accountId.length == 1) {
                            accountId.toIntOrNull()?.let {
                                simID = if (it >= 0) it else -1
                            }
                        }
                    }
                }
                val simColor = accountIdToSimColorMap[accountId] ?: context.config.simIconsColors[0]

                var specificNumber = ""
                var specificType = ""

                //not work
//                val contactIdWithMultipleNumbers = numbersToContactIDMap[number]
//                if (contactIdWithMultipleNumbers != null) {
//                    val specificPhoneNumber =
//                        contacts.firstOrNull { it.contactId == contactIdWithMultipleNumbers }?.phoneNumbers?.firstOrNull { it.value == number }
//                    if (specificPhoneNumber != null) {
//                        specificNumber = specificPhoneNumber.value
//                        specificType = context.getPhoneNumberTypeText(specificPhoneNumber.type, specificPhoneNumber.label)
//                    }
//                }

                var nickname = ""
                var company = ""
                var jobPosition = ""
                var contactID: Int? = null
                if (contact != null) {
                    val phoneNumbers = contact.phoneNumbers.firstOrNull { it.normalizedNumber == number }
                    if (phoneNumbers != null) {
                        specificNumber = contact.phoneNumbers.first { it.normalizedNumber == number }.value
                        specificType = context.getPhoneNumberTypeText(phoneNumbers.type, phoneNumbers.label)
                    }

                    nickname = contact.nickname
                    company = contact.organization.company
                    jobPosition = contact.organization.jobPosition
                    contactID = contact.id
                }

                val blockReason = if (isQPlus()) cursor.getIntValueOrNull(Calls.BLOCK_REASON) ?: 0 else 0

                recentCalls.add(
                    RecentCall(
                        id = id,
                        phoneNumber = number.orEmpty(),
                        name = name,
                        nickname = nickname,
                        company = company,
                        jobPosition = jobPosition,
                        photoUri = photoUri,
                        startTS = startTS,
                        duration = duration,
                        type = type,
                        simID = simID,
                        simColor = simColor,
                        specificNumber = specificNumber,
                        specificType = specificType,
                        isUnknownNumber = isUnknownNumber,
                        contactID = contactID,
                        features = features,
                        isVoiceMail = isVoiceMail,
                        blockReason = blockReason,
                        phoneAccountId = accountId,
                        phoneAccountComponentName = cursor.getStringValueOrNull(PHONE_ACCOUNT_COMPONENT_NAME),
                        countryIso = cursor.getStringValueOrNull(Calls.COUNTRY_ISO),
                        numberPresentation = presentation,
                    )
                )

                if (updateCallsCache && !isUnknownNumber && !number.isNullOrEmpty()) {
                    val countryIso = cursor.getStringValueOrNull(Calls.COUNTRY_ISO)
                    // Only a built update claims the key, so an older row with a stale cached name still gets fixed
                    if ((number to countryIso) !in cacheUpdateKeys) {
                        buildCachedInfoUpdate(
                            number, countryIso, name, photoUri,
                            cursor.getStringValueOrNull(Calls.CACHED_NAME),
                            cursor.getStringValueOrNull(Calls.CACHED_PHOTO_URI)
                        )?.let {
                            cacheUpdateKeys.add(number to countryIso)
                            cacheUpdates.add(it)
                        }
                    }
                }
            } while (cursor.moveToNext() && recentCalls.size < limit)
        }

        applyCachedInfoUpdates(cacheUpdates)
        return recentCalls
    }

    @SuppressLint("MissingPermission")
    private fun loadVoiceMailNumbers(): List<String> {
        return if (context.hasPermission(PERMISSION_READ_PHONE_STATE)) {
            try {
                context.telecomManager.callCapablePhoneAccounts.mapNotNull { account ->
                    context.telecomManager.getVoiceMailNumber(
                        context.telecomManager.getPhoneAccount(account).accountHandle
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    fun removeRecentCalls(ids: List<Int>, callback: () -> Unit) {
        if (!context.hasPermission(PERMISSION_WRITE_CALL_LOG)) {
            callback()
            return
        }
        context.config.needUpdateRecents = true
        ensureBackgroundThread {
            try {
                ids.chunked(30).forEach { chunk ->
                    val selection = "${Calls._ID} IN (${getQuestionMarks(chunk.size)})"
                    val selectionArgs = chunk.map { it.toString() }.toTypedArray()
                    context.contentResolver.delete(contentUri, selection, selectionArgs)
                }
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
            callback()
        }
    }

    @SuppressLint("MissingPermission")
    fun removeAllRecentCalls(activity: SimpleActivity, callback: () -> Unit) {
        activity.handlePermission(PERMISSION_WRITE_CALL_LOG) {
            if (it) {
                context.config.needUpdateRecents = true
                ensureBackgroundThread {
                    try {
                        context.contentResolver.delete(contentUri, null, null)
                    } catch (e: SecurityException) {
                        e.printStackTrace()
                    }
                    callback()
                }
            }
        }
    }

    /** Inserts the calls that are not already in the log; [callback] gets the number of inserted rows. */
    fun restoreRecentCalls(activity: SimpleActivity, objects: List<RecentCall>, callback: (inserted: Int) -> Unit) {
        activity.handlePermission(PERMISSION_WRITE_CALL_LOG) { granted ->
            if (!granted) return@handlePermission
            ensureBackgroundThread {
                var inserted = 0
                try {
                    val existing = loadCallKeys(objects.minOf { it.startTS }, objects.maxOf { it.startTS })
                    val values = objects
                        .filter { existing.add(Triple(it.startTS, it.phoneNumber, it.type)) }
                        .sortedBy { it.startTS }
                        .map(::toContentValues)
                        .toTypedArray()
                    if (values.isNotEmpty()) inserted = context.contentResolver.bulkInsert(contentUri, values)
                    if (inserted > 0) context.config.needUpdateRecents = true
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }
                callback(inserted)
            }
        }
    }

    /** (date, number, type) of the logged calls in the range; the same triple identifies a duplicate on import. */
    private fun loadCallKeys(from: Long, to: Long): HashSet<Triple<Long, String?, Int>> {
        val keys = HashSet<Triple<Long, String?, Int>>()
        val projection = arrayOf(Calls.DATE, Calls.NUMBER, Calls.TYPE)
        context.contentResolver.query(contentUri, projection, "${Calls.DATE} BETWEEN ? AND ?", arrayOf("$from", "$to"), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                keys.add(Triple(cursor.getLongValue(Calls.DATE), cursor.getStringValueOrNull(Calls.NUMBER).orEmpty(), cursor.getIntValue(Calls.TYPE)))
            }
        }
        return keys
    }

    private fun toContentValues(call: RecentCall) = ContentValues().apply {
        put(Calls.NUMBER, call.phoneNumber)
        put(Calls.TYPE, call.type)
        put(Calls.DATE, call.startTS)
        put(Calls.DURATION, call.duration)
        put(Calls.PHONE_ACCOUNT_ID, call.phoneAccountId)
        put(PHONE_ACCOUNT_COMPONENT_NAME, call.phoneAccountComponentName)
        put(Calls.COUNTRY_ISO, call.countryIso)
        call.numberPresentation?.let { put(Calls.NUMBER_PRESENTATION, it) }
        call.features?.let { put(Calls.FEATURES, it) }
        put(Calls.CACHED_PHOTO_URI, UriUtils.uriToString(UriUtils.nullForNonContactsUri(UriUtils.parseUriOrNull(call.photoUri))))
        if (isQPlus()) put(Calls.BLOCK_REASON, call.blockReason ?: 0)
        if (call.name != call.phoneNumber) put(Calls.CACHED_NAME, call.name)
        // Imported calls are history, not unread
        put(Calls.NEW, 0)
        put(Calls.IS_READ, 1)
    }

    /**
     * Unread missed calls (NEW=1) per number, straight from the call log so it survives process death.
     * Blank/unknown numbers share the "" key. Blocking: call off the main thread.
     */
    fun getNewMissedCallCounts(): Map<String, Int> {
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) return emptyMap()
        val counts = HashMap<String, Int>()
        try {
            context.contentResolver.query(
                contentUri, arrayOf(Calls.NUMBER), "${Calls.NEW} = 1 AND ${Calls.TYPE} = ?",
                arrayOf(Calls.MISSED_TYPE.toString()), null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val key = missedCallKey(cursor.getStringValueOrNull(Calls.NUMBER))
                    counts[key] = (counts[key] ?: 0) + 1
                }
            }
        } catch (_: SecurityException) {
        }
        return counts
    }

    private fun missedCallKey(number: String?) = if (number.isNullOrBlank() || number == "-1") "" else number

    /**
     * Marks unread missed calls as read, only those of [number] when given ("" = unknown numbers).
     * Does nothing when there is nothing unread, so the call-log observers are not woken for no reason.
     * [callback] runs on the worker thread once the write is done.
     */
    //https://android.googlesource.com/platform/packages/services/Telecomm/+/master/src/com/android/server/telecom/ui/MissedCallNotifierImpl.java#189
    fun markMissedCallsAsRead(number: String? = null, callback: () -> Unit = {}) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                markMissedCallsAsReadNow(number)
            } catch (_: IllegalArgumentException) {
            } catch (e: Exception) {
                e.printStackTrace()
            }
            callback()
        }
    }

    private fun markMissedCallsAsReadNow(number: String?) {
        if (!context.hasPermission(PERMISSION_WRITE_CALL_LOG)) return
        var where = "(${Calls.NEW} = 1 OR ${Calls.IS_READ} = 0) AND ${Calls.TYPE} = ?"
        val args = arrayListOf(Calls.MISSED_TYPE.toString())
        if (number != null) {
            if (missedCallKey(number).isEmpty()) {
                where += " AND (${Calls.NUMBER} IS NULL OR ${Calls.NUMBER} IN ('', '-1'))"
            } else {
                where += " AND ${Calls.NUMBER} = ?"
                args.add(number)
            }
        }
        val argArray = args.toTypedArray()
        val hasUnread = context.contentResolver.query(contentUri, arrayOf(Calls._ID), where, argArray, null)
            ?.use { it.count > 0 } == true
        if (!hasUnread) return

        val values = ContentValues().apply {
            put(Calls.NEW, 0)
            put(Calls.IS_READ, 1)
        }
        context.contentResolver.update(contentUri, values, where, argArray)
    }

    /**
     * Returns an update of the call log's cached name/photo for this number, or null when the log already
     * holds the same values. Writing only on a difference keeps our own writes from re-triggering the
     * call-log observers more than once.
     */
    private fun buildCachedInfoUpdate(
        number: String,
        countryIso: String?,
        name: String,
        photoUri: String?,
        callLogName: String?,
        callLogPhotoUri: String?,
    ): ContentProviderOperation? {
        val newName = name.takeIf { it != number }
        val oldName = callLogName?.takeIf { it.isNotEmpty() && it != "-1" }
        val newPhoto = UriUtils.nullForNonContactsUri(UriUtils.parseUriOrNull(photoUri))
        val oldPhoto = UriUtils.parseUriOrNull(callLogPhotoUri)
        val nameChanged = newName != oldName
        val photoChanged = !UriUtils.areEqual(newPhoto, oldPhoto)
        if (!nameChanged && !photoChanged) return null

        val builder = if (countryIso == null) {
            ContentProviderOperation.newUpdate(contentUri)
                .withSelection("${Calls.NUMBER} = ? AND ${Calls.COUNTRY_ISO} IS NULL", arrayOf(number))
        } else {
            ContentProviderOperation.newUpdate(contentUri)
                .withSelection("${Calls.NUMBER} = ? AND (${Calls.COUNTRY_ISO} IS NULL OR ${Calls.COUNTRY_ISO} = ?)", arrayOf(number, countryIso))
        }
        if (nameChanged) builder.withValue(Calls.CACHED_NAME, newName)
        if (photoChanged) builder.withValue(Calls.CACHED_PHOTO_URI, UriUtils.uriToString(newPhoto))
        return builder.build()
    }

    private fun applyCachedInfoUpdates(operations: ArrayList<ContentProviderOperation>) {
        if (operations.isEmpty() || !context.hasPermission(PERMISSION_WRITE_CALL_LOG)) return
        try {
            context.contentResolver.applyBatch(CallLog.AUTHORITY, operations)
        } catch (e: SecurityException) {
            e.printStackTrace()
        } catch (e: OperationApplicationException) {
            e.printStackTrace()
        } catch (e: RemoteException) {
            e.printStackTrace()
        } catch (_: SQLiteFullException) {
        }
    }
}
