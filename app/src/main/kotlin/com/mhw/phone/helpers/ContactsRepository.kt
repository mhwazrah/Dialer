package com.mhw.phone.helpers

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.PhoneLookup
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.hasPermission
import com.goodwy.commons.extensions.normalizePhoneNumber
import com.goodwy.commons.extensions.normalizeString
import com.goodwy.commons.helpers.ContactsHelper
import com.goodwy.commons.helpers.PERMISSION_READ_CONTACTS
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.models.contacts.Contact
import com.mhw.phone.extensions.loadPrivateContacts
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide, lazily loaded contact snapshot.
 *
 * - Nothing is loaded until a consumer asks ([ensureLoaded]); the observers only mark the snapshot dirty,
 *   so processes started for an incoming call or call screening never pay for an address-book load.
 * - A change in the contacts or call log (observer, or [markDirty]) is debounced, bumps [version], and notifies
 *   the listeners on the main thread. The data is re-read only on the next consumer request.
 * - The snapshot is immutable and replaced atomically; read it from any thread.
 */
object ContactsRepository {
    private const val DEBOUNCE_MS = 400L
    private const val LOOKUP_TIMEOUT_SECONDS = 10L
    private const val MIN_NOTIFY_INTERVAL_MS = 2000L // a sync burst notifies every ~1 s; refresh at most every 2 s

    class Snapshot(
        val contacts: List<Contact>,
        val byNormalizedNumber: Map<String, Contact>,
        val favorites: List<Contact>
    ) {
        private val listSearchKeys = IdentityHashMap<Contact, ListSearchKeys>()

        // Called by createSnapshot before the snapshot is published; read-only afterwards.
        internal fun buildListSearchKeys() {
            contacts.forEach { listSearchKeys[it] = ListSearchKeys.of(it) }
        }

        /** Normalised search fields of [contact]; computed on the spot for a contact that is not in this snapshot. */
        fun listSearchKeys(contact: Contact): ListSearchKeys = listSearchKeys[contact] ?: ListSearchKeys.of(contact)

        /** Index hit, else the commons matching rules ([Contact.doesContainPhoneNumber]) over all contacts. */
        fun find(number: String): Contact? {
            if (number.isBlank()) return null
            byNormalizedNumber[number.normalizePhoneNumber()]?.let { return it }
            return contacts.firstOrNull { it.doesContainPhoneNumber(number) }
        }

        private val searchKeysByLanguage = ConcurrentHashMap<String, Map<Contact, SearchKeys>>()

        /**
         * Dialpad search keys of every contact for the T9 secondary language [language], built once per snapshot
         * and language. Call from a background thread (the first call per language walks all contacts), after
         * [DialpadT9.ensureInitialized].
         */
        fun searchKeys(language: String): Map<Contact, SearchKeys> =
            searchKeysByLanguage.getOrPut(language) {
                contacts.associateWithTo(IdentityHashMap()) { SearchKeys.of(it, language) }
            }
    }

    /**
     * The accent-stripped ([normalizeString]) and Arabic-folded ([foldArabic]) text fields the Contacts/Favorites/Select-contact searches match
     * against when the query itself has no accents. The display name is left out: it depends on the sort/name
     * display settings, so it is derived per search.
     */
    class ListSearchKeys(
        val nickname: String,
        val addresses: List<String>,
        val notes: String,
        val company: String,
        val jobPosition: String
    ) {
        /**
         * Whether [query] is in nickname, addresses, notes, company, job position, emails, IMs or websites of [contact]
         * (the fields every list search shares; this object must be [contact]'s keys). The first five are matched
         * accent-stripped when [shouldNormalize], else as stored.
         */
        fun matchesSecondaryFields(contact: Contact, query: String, shouldNormalize: Boolean): Boolean {
            val nicknames = if (shouldNormalize) nickname else contact.nickname
            val notes = if (shouldNormalize) notes else contact.notes
            val company = if (shouldNormalize) company else contact.organization.company
            val jobPosition = if (shouldNormalize) jobPosition else contact.organization.jobPosition
            return nicknames.contains(query, true) ||
                (if (shouldNormalize) addresses else contact.addresses.map { it.value }).any { it.contains(query, true) } ||
                contact.emails.any { it.value.contains(query, true) } ||
                contact.IMs.any { it.value.contains(query, true) } ||
                notes.contains(query, true) ||
                company.contains(query, true) ||
                jobPosition.contains(query, true) ||
                contact.websites.any { it.contains(query, true) }
        }

        companion object {
            fun of(contact: Contact) = ListSearchKeys(
                nickname = contact.nickname.normalizeString().foldArabic(),
                addresses = contact.addresses.map { it.value.normalizeString().foldArabic() },
                notes = contact.notes.normalizeString().foldArabic(),
                company = contact.organization.company.normalizeString().foldArabic(),
                jobPosition = contact.organization.jobPosition.normalizeString().foldArabic()
            )
        }
    }

    /**
     * [matchKeys]: T9 digit strings (and their digits-only forms) of name, display name, nickname and company,
     * deduped, empty ones dropped. [displayNameDigits]: the display name's digits-only T9 string, used to rank
     * "starts with" matches.
     */
    class SearchKeys(val matchKeys: List<String>, val displayNameDigits: String) {
        companion object {
            fun of(contact: Contact, language: String): SearchKeys {
                val name = t9(contact.name, language)
                val nickname = t9(contact.nickname, language)
                val company = t9(contact.organization.company, language)
                val displayName = t9(contact.getNameToDisplay(), language)
                val displayNameDigits = displayName.filter { it.isDigit() }
                val keys = LinkedHashSet<String>()
                listOf(name, nickname, company, displayName).forEach { keys.add(it); keys.add(it.filter { c -> c.isDigit() }) }
                keys.remove("")
                return SearchKeys(keys.toList(), displayNameDigits)
            }

            // Same conversion as the dialpad highlight, so a match is always highlighted where it was found
            private fun t9(value: String, language: String): String = DialpadT9.convertLettersToNumbers(value, language)
        }
    }

    private val NOT_LOADED = Snapshot(emptyList(), emptyMap(), emptyList())

    @Volatile
    var snapshot: Snapshot = NOT_LOADED
        private set

    /** Bumped on every detected change; a consumer keeps the value it last rendered and reloads when it differs. */
    @Volatile
    var version: Int = 0
        private set

    val isLoaded: Boolean get() = snapshot !== NOT_LOADED

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var pendingContactsChange = false // main thread only
    private var lastNotifyAt = 0L // main thread only
    private val markDirtyRunnable = Runnable {
        lastNotifyAt = android.os.SystemClock.uptimeMillis()
        // Only a contacts change invalidates the snapshot; a call-log change just tells listeners to refresh their lists.
        if (pendingContactsChange) synchronized(lock) { dirty = true }
        pendingContactsChange = false
        version++
        listeners.forEach { it() }
    }

    private var dirty = true // guarded by lock
    private var loading = false
    private val waiters = ArrayList<(Snapshot) -> Unit>()

    private var contactsObserverRegistered = false
    private var callLogObserverRegistered = false
    private val contactsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) = markDirty(contactsChanged = true)
    }
    private val callLogObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) = markDirty(contactsChanged = false)
    }

    /** Listeners run on the main thread after the debounce. Remove them with the same reference. */
    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Debounced; main thread. [contactsChanged] = false for call-log-only changes: listeners are notified,
     * but the contact snapshot is not reloaded.
     */
    fun markDirty(contactsChanged: Boolean = true) {
        if (contactsChanged) pendingContactsChange = true
        mainHandler.removeCallbacks(markDirtyRunnable)
        val sinceLast = android.os.SystemClock.uptimeMillis() - lastNotifyAt
        mainHandler.postDelayed(markDirtyRunnable, maxOf(DEBOUNCE_MS, MIN_NOTIFY_INTERVAL_MS - sinceLast))
    }

    /**
     * Registers the contacts and call-log observers (idempotent, only registers what is permitted and not yet registered).
     * Call from App.onCreate, and again after a runtime permission grant.
     */
    // The contacts provider notifies its authority root on changes, not Contacts.CONTENT_URI, so observe the root.
    @Synchronized
    fun registerObservers(context: Context) {
        val resolver = context.applicationContext.contentResolver
        if (!contactsObserverRegistered) contactsObserverRegistered = register(resolver, ContactsContract.AUTHORITY_URI, contactsObserver)
        if (!callLogObserverRegistered) callLogObserverRegistered = register(resolver, CallLog.Calls.CONTENT_URI, callLogObserver)
    }

    /** A permission that affects the data was just granted: start observing it and force the next request to reload. */
    fun onPermissionGranted(context: Context) {
        registerObservers(context)
        markDirty()
    }

    private fun register(resolver: android.content.ContentResolver, uri: Uri, observer: ContentObserver): Boolean = try {
        resolver.registerContentObserver(uri, true, observer)
        true
    } catch (_: SecurityException) {
        false // permission not granted yet; retried by onPermissionGranted
    }

    /**
     * Calls [callback] on the main thread with an up-to-date snapshot. Loads on a background thread only when the
     * snapshot was never loaded, was marked dirty, or [force] is set; concurrent requests share one load.
     */
    fun ensureLoaded(context: Context, force: Boolean = false, callback: (Snapshot) -> Unit = {}) {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (force) dirty = true
            // While a load runs, even a clean-looking snapshot may be the old one: wait for the running load.
            if (!dirty && isLoaded && !loading) {
                val current = snapshot
                mainHandler.post { callback(current) }
                return
            }
            waiters.add(callback)
            if (loading) return
            loading = true
        }
        ensureBackgroundThread { loadLoop(appContext) }
    }

    fun refresh(context: Context, callback: (Snapshot) -> Unit = {}) = ensureLoaded(context, force = true, callback)

    private fun loadLoop(context: Context) {
        val batch = synchronized(lock) {
            dirty = false
            ArrayList(waiters).also { waiters.clear() }
        }
        buildSnapshot(context) { loaded, complete ->
            snapshot = loaded
            var loadAgain = false
            synchronized(lock) {
                if (dirty && waiters.isNotEmpty()) {
                    loadAgain = true // changed while loading and someone is still waiting: serve them from a newer load
                } else {
                    batch.addAll(waiters)
                    waiters.clear()
                    loading = false
                }
                // No permission yet or a failed read: serve it, but re-check on the next request
                // (a permission granted from system Settings never reaches onPermissionGranted).
                if (!complete) dirty = true
            }
            mainHandler.post { batch.forEach { it(loaded) } }
            if (loadAgain) loadLoop(context)
        }
    }

    private fun buildSnapshot(context: Context, onBuilt: (snapshot: Snapshot, complete: Boolean) -> Unit) {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            onBuilt(Snapshot(emptyList(), emptyMap(), emptyList()), false)
            return
        }
        // Contacts without numbers are fetched too, only so favourites can honour "show only contacts with numbers"
        // as they did before; every other list uses the with-numbers set.
        ContactsHelper(context).getContacts(getAll = true, showOnlyContactsWithNumbers = false) { deviceContacts ->
            try {
                val all = ArrayList(deviceContacts)
                val privateContacts = context.loadPrivateContacts()
                if (privateContacts.isNotEmpty()) {
                    // The private provider's full list carries no favourite flag; its favourites query does.
                    val favoriteIds = context.loadPrivateContacts(favoritesOnly = true).mapTo(HashSet()) { it.id }
                    privateContacts.mapTo(all) { if (it.id in favoriteIds) it.copy(starred = 1) else it }
                    all.sort()
                }
                onBuilt(createSnapshot(all, context.baseConfig.showOnlyContactsWithNumbers), true)
            } catch (_: Exception) {
                onBuilt(snapshot.takeIf { isLoaded } ?: Snapshot(emptyList(), emptyMap(), emptyList()), false)
            }
        }
    }

    private fun createSnapshot(all: List<Contact>, favoritesNeedNumbers: Boolean): Snapshot {
        val contacts = all.filter { it.phoneNumbers.isNotEmpty() }
        val index = HashMap<String, Contact>()
        contacts.forEach { contact ->
            contact.phoneNumbers.forEach { phone ->
                // normalizedNumber is the provider's E.164 form when it has one; the raw value's digits match
                // national-format numbers (e.g. 05x vs +9665x) that the call log stores as dialled.
                if (phone.normalizedNumber.isNotEmpty()) index.putIfAbsent(phone.normalizedNumber, contact)
                val digits = phone.value.normalizePhoneNumber()
                if (digits.isNotEmpty()) index.putIfAbsent(digits, contact)
            }
        }
        val favorites = (if (favoritesNeedNumbers) contacts else all).filter { it.starred == 1 }
        return Snapshot(contacts, index, favorites).also { it.buildListSearchKeys() }
    }

    /**
     * The contact owning [number], or null. Background thread only.
     * Loaded snapshot: index hit, else the commons matching rules ([Contact.doesContainPhoneNumber]) over the snapshot.
     * Snapshot not loaded yet (call process cold start): a single PhoneLookup query, device contacts only.
     */
    fun findByNumber(context: Context, number: String): Contact? {
        if (number.isBlank()) return null
        // Only a clean, settled snapshot is trusted: a contact added or renamed since must not show the old name.
        // The provider reports changes seconds late, so a miss may be a contact added moments ago: one PhoneLookup covers it.
        if (isLoaded && isFresh()) return snapshot.find(number) ?: lookupDeviceContact(context.applicationContext, number)
        val appContext = context.applicationContext
        return lookupDeviceContact(appContext, number)
            // PhoneLookup only matches across formats (+9665x vs 05x) when the network country is known: on a miss,
            // load the snapshot once (it then serves later calls) and apply the commons matching rules, which also
            // cover private contacts.
            ?: loadBlocking(appContext)?.find(number)
    }

    // Background thread only: waits for the shared load instead of starting a second one.
    private fun loadBlocking(context: Context): Snapshot? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: Snapshot? = null
        ensureLoaded(context) {
            result = it
            latch.countDown()
        }
        return if (latch.await(LOOKUP_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) result else null
    }

    private fun isFresh(): Boolean = synchronized(lock) { !dirty && !loading }

    /**
     * Private contacts live in another app's provider, which can't be observed: when the snapshot holds any,
     * mark it dirty so the next request re-reads it. Returns whether it did.
     */
    fun invalidateIfHoldsPrivateContacts(): Boolean {
        if (!isLoaded || snapshot.contacts.none { it.isPrivate() }) return false
        synchronized(lock) { dirty = true }
        return true
    }

    private fun lookupDeviceContact(context: Context, number: String): Contact? {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) return null
        return try {
            val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val lookupKey = context.contentResolver.query(uri, arrayOf(PhoneLookup.LOOKUP_KEY), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            lookupKey?.let { ContactsHelper(context).getContactWithLookupKey(it) }
        } catch (_: Exception) {
            null
        }
    }
}
