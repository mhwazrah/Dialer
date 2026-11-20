package com.goodwy.dialer.fragments

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.provider.CallLog.Calls
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.goodwy.commons.dialogs.CallConfirmationDialog
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.beGoneIf
import com.goodwy.commons.extensions.beVisible
import com.goodwy.commons.extensions.getContrastColor
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.getSurfaceColor
import com.goodwy.commons.extensions.hasPermission
import com.goodwy.commons.extensions.hideKeyboard
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.extensions.launchActivityIntent
import com.goodwy.commons.extensions.launchCallIntent
import com.goodwy.commons.extensions.underlineText
import com.goodwy.commons.helpers.CONTACT_ID
import com.goodwy.commons.helpers.FontHelper
import com.goodwy.commons.helpers.IS_PRIVATE
import com.goodwy.commons.helpers.PERMISSION_READ_CALL_LOG
import com.goodwy.commons.helpers.SMT_PRIVATE
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.dialer.BuildConfig
import com.goodwy.dialer.R
import com.goodwy.dialer.activities.CallHistoryActivity
import com.goodwy.dialer.activities.SimpleActivity
import com.goodwy.dialer.adapters.RecentCallsAdapter
import com.goodwy.dialer.databinding.FragmentRecentsBinding
import com.goodwy.dialer.extensions.loadPrivateContacts
import com.goodwy.dialer.extensions.callContactWithSim
import com.goodwy.dialer.extensions.config
import com.goodwy.commons.extensions.launchSendSMSIntent
import com.goodwy.dialer.extensions.launchSendWhatsAppIntent
import com.goodwy.dialer.extensions.startAddNumberToContact
import com.goodwy.dialer.extensions.startContactDetailsIntent
import com.goodwy.dialer.extensions.toSearchQuery
import com.goodwy.dialer.helpers.CURRENT_RECENT_CALL
import com.goodwy.dialer.helpers.ContactsRepository
import com.goodwy.dialer.helpers.CURRENT_RECENT_CALL_LIST
import com.goodwy.dialer.helpers.FILTER_RECENT_CALLS_ALL
import com.goodwy.dialer.helpers.FILTER_RECENT_CALLS_CONTACTS
import com.goodwy.dialer.helpers.RECENT_CALL_CACHE_SIZE
import com.goodwy.dialer.helpers.RecentsHelper
import com.goodwy.dialer.helpers.SWIPE_ACTION_CALL
import com.goodwy.dialer.helpers.SWIPE_ACTION_MESSAGE
import com.goodwy.dialer.helpers.SWIPE_ACTION_OPEN
import com.goodwy.dialer.helpers.SWIPE_ACTION_WHATSAPP
import com.goodwy.dialer.interfaces.RefreshItemsListener
import com.goodwy.dialer.models.CallLogItem
import com.goodwy.dialer.models.RecentCall
import com.google.android.material.button.MaterialButton
import com.goodwy.dialer.helpers.gson

class RecentsFragment(
    context: Context, attributeSet: AttributeSet,
) : MyViewPagerFragment<MyViewPagerFragment.RecentsInnerBinding>(context, attributeSet), RefreshItemsListener {

    private lateinit var binding: FragmentRecentsBinding
    @Volatile private var allRecentCalls = listOf<CallLogItem>()
    private var recentsAdapter: RecentCallsAdapter? = null

    private var searchQuery: String? = null
    private var recentsHelper = RecentsHelper(context)

    // Drops results of a refresh that was overtaken by a newer one.
    @Volatile private var refreshGeneration = 0
    // Main thread only. One refresh runs at a time; changes arriving meanwhile queue a single follow-up,
    // so a burst of notifications (e.g. a contacts sync) can't keep discarding the full pass.
    private var refreshRunning = false
    private var refreshQueued = false
    // What the last started refresh was for: nothing is reloaded while these are unchanged.
    private var loadedVersion = -1
    private var loadedSettings = ""

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentRecentsBinding.bind(this)
        innerBinding = RecentsInnerBinding(binding)
    }

    override fun setupFragment() {
        // transparent so the liquid backdrop shows through
        binding.recentsFragment.setBackgroundColor(Color.TRANSPARENT)
        binding.recentsFiltersHolder.setBackgroundColor(Color.TRANSPARENT)

        binding.recentsPlaceholder2.apply {
            underlineText()
            setOnClickListener {
                requestCallLogPermission()
            }
        }

        updateFilterButton()
        binding.recentsFilters.apply {
            val typeface = FontHelper.getTypeface(context)

            allCalls.setTypeface(typeface)
            allCalls.setOnClickListener {
                if (context.config.filterRecentCalls != FILTER_RECENT_CALLS_ALL) {
                    context.config.filterRecentCalls = FILTER_RECENT_CALLS_ALL
                    updateItems()
                    updateFilterButton()
                }
            }

            missedCalls.setTypeface(typeface)
            missedCalls.setOnClickListener {
                if (context.config.filterRecentCalls != Calls.MISSED_TYPE) {
                    context.config.filterRecentCalls = Calls.MISSED_TYPE
                    updateItems()
                    updateFilterButton()
                } else {
                    context.config.filterRecentCalls = FILTER_RECENT_CALLS_ALL
                    updateItems()
                    updateFilterButton()
                }
            }

            incomingCalls.setTypeface(typeface)
            incomingCalls.setOnClickListener {
                if (context.config.filterRecentCalls != Calls.INCOMING_TYPE) {
                    context.config.filterRecentCalls = Calls.INCOMING_TYPE
                    updateItems()
                    updateFilterButton()
                } else {
                    context.config.filterRecentCalls = FILTER_RECENT_CALLS_ALL
                    updateItems()
                    updateFilterButton()
                }
            }

            outgoingCalls.setTypeface(typeface)
            outgoingCalls.setOnClickListener {
                if (context.config.filterRecentCalls != Calls.OUTGOING_TYPE) {
                    context.config.filterRecentCalls = Calls.OUTGOING_TYPE
                    updateItems()
                    updateFilterButton()
                } else {
                    context.config.filterRecentCalls = FILTER_RECENT_CALLS_ALL
                    updateItems()
                    updateFilterButton()
                }
            }

            rejectedCalls.setTypeface(typeface)
            rejectedCalls.setOnClickListener {
                if (context.config.filterRecentCalls != Calls.REJECTED_TYPE) {
                    context.config.filterRecentCalls = Calls.REJECTED_TYPE
                    updateItems()
                    updateFilterButton()
                } else {
                    context.config.filterRecentCalls = FILTER_RECENT_CALLS_ALL
                    updateItems()
                    updateFilterButton()
                }
            }

            contactCalls.setTypeface(typeface)
            contactCalls.setOnClickListener {
                if (context.config.filterRecentCalls != FILTER_RECENT_CALLS_CONTACTS) {
                    context.config.filterRecentCalls = FILTER_RECENT_CALLS_CONTACTS
                    updateItems()
                    updateFilterButton()
                } else {
                    context.config.filterRecentCalls = FILTER_RECENT_CALLS_ALL
                    updateItems()
                    updateFilterButton()
                }
            }
        }
    }

    private fun updateFilterButton() {
        binding.recentsFilters.apply {
            when (context.config.filterRecentCalls) {
                Calls.MISSED_TYPE -> {
                    allCalls.setFilterDisableColor()
                    missedCalls.setFilterEnableColor()
                    incomingCalls.setFilterDisableColor()
                    outgoingCalls.setFilterDisableColor()
                    rejectedCalls.setFilterDisableColor()
                    contactCalls.setFilterDisableColor()
                }

                Calls.INCOMING_TYPE -> {
                    allCalls.setFilterDisableColor()
                    missedCalls.setFilterDisableColor()
                    incomingCalls.setFilterEnableColor()
                    outgoingCalls.setFilterDisableColor()
                    rejectedCalls.setFilterDisableColor()
                    contactCalls.setFilterDisableColor()
                }

                Calls.OUTGOING_TYPE -> {
                    allCalls.setFilterDisableColor()
                    missedCalls.setFilterDisableColor()
                    incomingCalls.setFilterDisableColor()
                    outgoingCalls.setFilterEnableColor()
                    rejectedCalls.setFilterDisableColor()
                    contactCalls.setFilterDisableColor()
                }

                Calls.REJECTED_TYPE -> {
                    allCalls.setFilterDisableColor()
                    missedCalls.setFilterDisableColor()
                    incomingCalls.setFilterDisableColor()
                    outgoingCalls.setFilterDisableColor()
                    rejectedCalls.setFilterEnableColor()
                    contactCalls.setFilterDisableColor()
                }

                FILTER_RECENT_CALLS_CONTACTS -> {
                    allCalls.setFilterDisableColor()
                    missedCalls.setFilterDisableColor()
                    incomingCalls.setFilterDisableColor()
                    outgoingCalls.setFilterDisableColor()
                    rejectedCalls.setFilterDisableColor()
                    contactCalls.setFilterEnableColor()
                }

                else -> { //FILTER_RECENT_CALLS_ALL
                    allCalls.setFilterEnableColor()
                    missedCalls.setFilterDisableColor()
                    incomingCalls.setFilterDisableColor()
                    outgoingCalls.setFilterDisableColor()
                    rejectedCalls.setFilterDisableColor()
                    contactCalls.setFilterDisableColor()
                }
            }
        }
    }

    private fun MaterialButton.setFilterEnableColor() {
        val primaryColor = context.getProperPrimaryColor()

        setBackgroundColor(primaryColor)
        setTextColor(primaryColor.getContrastColor())
        strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
    }

    private fun MaterialButton.setFilterDisableColor() {
        val textColor = context.getProperTextColor()
        val colorWithAlpha = ColorUtils.setAlphaComponent(textColor, 102) //40%

        setBackgroundColor(Color.TRANSPARENT)
        setTextColor(textColor)
        strokeColor = ColorStateList.valueOf(colorWithAlpha)
    }

    override fun setupColors(textColor: Int, primaryColor: Int, accentColor: Int) {
        binding.recentsPlaceholder.setTextColor(textColor)
        binding.recentsPlaceholder2.setTextColor(primaryColor)

        recentsAdapter?.apply {
            updatePrimaryColor()
            updateBackgroundColor(context.getProperBackgroundColor())
            updateTextColor(textColor)
            initDrawables(textColor)
        }
    }

    /** Rebinds the visible rows: their SIM pickers show the per-number saved SIM, which can change on the profile. */
    fun rebindRows() {
        recentsAdapter?.let { it.notifyItemRangeChanged(0, it.itemCount) }
    }

    override fun refreshItems(invalidate: Boolean, needUpdate: Boolean, callback: (() -> Unit)?) {
        val config = activity!!.config
        val version = ContactsRepository.version
        val settings = settingsKey()
        val unchanged = !invalidate && !needUpdate && searchQuery.isNullOrEmpty() && !config.needUpdateRecents &&
            version == loadedVersion && settings == loadedSettings
        if (unchanged) {
            callback?.invoke()
            return
        }
        if (refreshRunning && !invalidate) {
            refreshQueued = true
            callback?.invoke()
            return
        }
        loadedVersion = version
        loadedSettings = settings
        refreshRunning = true
        val generation = ++refreshGeneration

        if (invalidate) {
            allRecentCalls = emptyList()
            config.recentCallsCache = ""
        }

        // Quick pass (paints from the call log's cached names), then one full pass that resolves against the contact book.
        val mustReload = needUpdate || !searchQuery.isNullOrEmpty() || config.needUpdateRecents
        if (!mustReload && !invalidate && context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            // Render the cache instantly; the quick pass then picks up calls made while the app was closed.
            ensureBackgroundThread {
                val cached = try {
                    config.parseRecentCallsCache()
                } catch (_: Exception) {
                    config.recentCallsCache = ""
                    emptyList()
                }
                activity?.runOnUiThread {
                    // A real load may have arrived while the cache was parsing; never paint over it.
                    if (cached.isNotEmpty() && generation == refreshGeneration && allRecentCalls.isEmpty()) gotRecents(cached)
                }
            }
        }
        val fullPass = {
            refreshCallLog(loadAll = true, generation = generation) {
                callback?.invoke()
                finishRefresh(generation)
            }
        }
        // The quick pass only matters while nothing is on screen; a change-driven refresh goes straight to the full pass.
        if (invalidate || allRecentCalls.isEmpty()) refreshCallLog(loadAll = false, generation = generation) { fullPass() } else fullPass()
    }

    private fun finishRefresh(generation: Int) {
        if (generation != refreshGeneration) return
        refreshRunning = false
        if (refreshQueued) {
            refreshQueued = false
            refreshItems(invalidate = false, needUpdate = true, callback = null)
        }
    }

    // Settings that change what the list contains without a restart.
    private fun settingsKey(): String = with(context.config) {
        "$groupSubsequentCalls|$groupAllCalls|$queryLimitRecent|${context.baseConfig.ignoredContactSources}|${context.baseConfig.showNicknameInsteadNames}"
    }

    override fun rebuildAdapter() {
        if (recentsAdapter == null) return
        recentsAdapter = null
        binding.recentsList.adapter = null
        gotRecents(allRecentCalls)
    }

    override fun onSearchClosed() {
        searchQuery = null
        showOrHidePlaceholder(allRecentCalls.isEmpty())
        recentsAdapter?.updateItems(allRecentCalls)
    }

    override fun onSearchQueryChanged(text: String) {
        searchQuery = text
        updateSearchResult()
    }

    fun updateItems() {
        if (searchQuery != null) updateSearchResult()
        else recentsAdapter?.updateItems(allRecentCalls)
    }

    @Suppress("UNCHECKED_CAST")
    private fun updateSearchResult() {
        ensureBackgroundThread {
            val fixedText = searchQuery!!.toSearchQuery()
            val recentCalls = allRecentCalls
                .filterIsInstance<RecentCall>()
                .filter {
                    it.name.contains(fixedText, true) ||
                        it.doesContainPhoneNumber(fixedText) ||
                        it.nickname.contains(fixedText, true) ||
                        it.company.contains(fixedText, true) ||
                        it.jobPosition.contains(fixedText, true)
                }
                .sortedWith(
                    compareByDescending<RecentCall> { it.dayCode }
                        .thenByDescending { it.name.startsWith(fixedText, true) }
                        .thenByDescending { it.startTS }
                )

            prepareCallLog(recentCalls) {
                activity?.runOnUiThread {
                    showOrHidePlaceholder(recentCalls.isEmpty())
                    recentsAdapter?.updateItems(it, fixedText)
                }
            }
        }
    }

    private fun requestCallLogPermission() {
        activity?.handlePermission(PERMISSION_READ_CALL_LOG) {
            if (it) {
                ContactsRepository.onPermissionGranted(context)
                binding.recentsPlaceholder.text = context.getString(R.string.no_previous_calls)
                binding.recentsPlaceholder2.beGone()
                refreshCallLog()
            }
        }
    }

    private fun showOrHidePlaceholder(show: Boolean) {
        if (show /*&& !binding.progressIndicator.isVisible()*/) {
            // Always from the current permission: it can be revoked while the app is open
            binding.recentsPlaceholder.text = context.getString(
                if (context.hasPermission(PERMISSION_READ_CALL_LOG)) R.string.no_previous_calls else R.string.could_not_access_the_call_history
            )
            binding.recentsPlaceholder.beVisible()
        } else {
            binding.recentsPlaceholder.beGone()
        }
    }

    private fun gotRecents(recents: List<CallLogItem>) {
//        binding.progressIndicator.hide()
        if (recents.isEmpty()) {
            binding.apply {
                showOrHidePlaceholder(true)
                recentsPlaceholder2.beGoneIf(context.hasPermission(PERMISSION_READ_CALL_LOG))
                recentsList.beGone()
            }
        } else {
            binding.apply {
                showOrHidePlaceholder(false)
                recentsPlaceholder2.beGone()
                recentsList.beVisible()

//                recentsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
//                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
//                        super.onScrollStateChanged(recyclerView, newState)
//                        activity?.hideKeyboard()
//                    }
//                })
                recentsList.setOnTouchListener { _, _ ->
                    activity?.hideKeyboard()
                    false
                }
            }

            if (binding.recentsList.adapter == null) {
                recentsAdapter = RecentCallsAdapter(
                    activity = activity as SimpleActivity,
                    recyclerView = binding.recentsList,
                    refreshItemsListener = this,
                    showOverflowMenu = true,
                    showCallIcon = context.config.onRecentClick == SWIPE_ACTION_OPEN,
                    hideTimeAtOtherDays = true,
                    itemDelete = { deleted ->
                        val deletedSet = deleted.toHashSet()
                        allRecentCalls = allRecentCalls.filter { it !in deletedSet }
                    },
                    itemClick = {
                        itemClickAction(context.config.onRecentClick, it as RecentCall)
                    },
                    profileInfoClick = { recentCall ->
                        actionOpen(recentCall)
                    },
                    profileIconClick = {
                        val recentCall = it as RecentCall
                        val contact = findContactByCall(recentCall)
                        if (contact != null) {
                            actionOpen(recentCall) // the row's own number, not the contact's main one
                        } else {
                            activity?.startAddNumberToContact(recentCall.phoneNumber)
                        }
                    }
                )

                recentsAdapter?.addBottomPadding(64)

                binding.recentsList.adapter = recentsAdapter
                recentsAdapter?.updateItems(recents)
            } else {
                recentsAdapter?.updateItems(recents)
            }
        }
    }

    private fun callRecentNumber(recentCall: RecentCall) {
        if (context.config.callUsingSameSim && recentCall.simID > 0) {
            val sim = recentCall.simID == 1
            activity?.callContactWithSim(recentCall.phoneNumber, sim)
        }
        else {
            activity?.launchCallIntent(recentCall.phoneNumber, key = BuildConfig.RIGHT_APP_KEY)
        }
    }

    private fun refreshCallLog(loadAll: Boolean = false, generation: Int = refreshGeneration, callback: (() -> Unit)? = null) {
        getRecentCalls(loadAll) {
            if (generation != refreshGeneration) return@getRecentCalls // superseded by an invalidating refresh, which owns the running flag
            allRecentCalls = it
            val showAll = searchQuery.isNullOrEmpty()
            // Only the full pass is worth persisting; the quick pass is a subset built from cached names.
            if (showAll && loadAll) context.config.saveRecentCallsCache(it)
            activity?.runOnUiThread {
                if (showAll) gotRecents(it) else updateSearchResult()
                callback?.invoke()
            }
        }
    }

    private fun refreshCallLogFromCache(cache: List<RecentCall>, callback: (() -> Unit)? = null) {
        gotRecents(cache)
        callback?.invoke()
    }

    private fun getRecentCalls(loadAll: Boolean, callback: (List<RecentCall>) -> Unit) {
        val queryCount = if (loadAll) context.config.queryLimitRecent else RecentsHelper.QUERY_LIMIT
        // The quick pass paints instantly from the call log's cached names, so the full pass
        // starts from scratch to re-resolve every row against the real contact book instead of
        // keeping possibly cached-name rows verbatim through the previous-entries merge.
        val existingRecentCalls = if (loadAll) emptyList() else allRecentCalls.filterIsInstance<RecentCall>()

        with(recentsHelper) {
            if (context.config.groupSubsequentCalls) {
                // updateCallsCache on the full pass keeps CACHED_NAME/CACHED_PHOTO_URI correct,
                // which the quick pass depends on for its instant first paint.
                getGroupedRecentCalls(existingRecentCalls, queryCount, updateCallsCache = loadAll, useCachedNames = !loadAll) {
                    if (loadAll) recentsHelper.updateRecentOutgoingNumbers(it)
                    finalizeCallLog(it, callback)
                }
            } else {
                getRecentCalls(existingRecentCalls, queryCount, updateCallsCache = loadAll, useCachedNames = !loadAll) { it ->
                    if (loadAll) recentsHelper.updateRecentOutgoingNumbers(it)
                    val calls = if (context.config.groupAllCalls) it.distinctBy { it.phoneNumber } else it
                    finalizeCallLog(calls, callback)
                }
            }
        }
    }

    // The helper already resolves rows against the contact book (full pass) or paints from the
    // call log's cached names (quick pass), so all that remains before display is the optional
    // private-calls filter — not another full contact-book load like prepareCallLog does.
    private fun finalizeCallLog(calls: List<RecentCall>, callback: (List<RecentCall>) -> Unit) {
        if (SMT_PRIVATE !in context.baseConfig.ignoredContactSources) {
            callback(calls)
            return
        }

        ensureBackgroundThread {
            callback(maybeFilterPrivateCalls(calls, getPrivateContacts()))
        }
    }

    private fun prepareCallLog(calls: List<RecentCall>, callback: (List<RecentCall>) -> Unit) {
        if (calls.isEmpty()) {
            callback(emptyList())
            return
        }

        ContactsRepository.ensureLoaded(context) { snapshot ->
            ensureBackgroundThread {
                val privateContacts = getPrivateContacts()
                callback(updateNamesIfEmpty(maybeFilterPrivateCalls(calls, privateContacts), snapshot, privateContacts))
            }
        }
    }

    private fun getPrivateContacts(): ArrayList<Contact> {
        return context.loadPrivateContacts(includeIgnored = true)
    }

    private fun maybeFilterPrivateCalls(calls: List<RecentCall>, privateContacts: List<Contact>): List<RecentCall> {
        val ignoredSources = context.baseConfig.ignoredContactSources
        return if (SMT_PRIVATE in ignoredSources) {
            val privateNumbers = privateContacts.flatMap { it.phoneNumbers }.map { it.value }
            calls.filterNot { it.phoneNumber in privateNumbers }
        } else {
            calls
        }
    }

    private fun updateNamesIfEmpty(
        calls: List<RecentCall>,
        snapshot: ContactsRepository.Snapshot,
        privateContacts: List<Contact>
    ): List<RecentCall> {
        // Misses are memoised too: repeated unknown numbers must not rescan the private contacts every row.
        val contactsByNumber = HashMap<String, Contact?>()
        return calls.map { call ->
            if (call.phoneNumber != call.name) return@map call
            val contact = if (contactsByNumber.containsKey(call.phoneNumber)) {
                contactsByNumber[call.phoneNumber]
            } else {
                (privateContacts.firstOrNull { it.doesContainPhoneNumber(call.phoneNumber) } ?: snapshot.find(call.phoneNumber))
                    .also { contactsByNumber[call.phoneNumber] = it }
            }
            if (contact == null) call else withUpdatedName(call = call, name = contact.getNameToDisplay())
        }
    }

    private fun withUpdatedName(call: RecentCall, name: String): RecentCall {
        return call.copy(
            name = name,
            groupedCalls = call.groupedCalls
                ?.map { it.copy(name = name) }
                ?.toMutableList()
                ?.ifEmpty { null }
        )
    }

//    private fun groupCallsByDate(recentCalls: List<RecentCall>): MutableList<CallLogItem> {
//        val callLog = mutableListOf<CallLogItem>()
//        var lastDayCode = ""
//        for (call in recentCalls) {
//            val currentDayCode = call.dayCode
//            if (currentDayCode != lastDayCode) {
//                callLog += CallLogItem.Date(timestamp = call.startTS, dayCode = currentDayCode)
//                lastDayCode = currentDayCode
//            }
//
//            callLog += call
//        }
//
//        return callLog
//    }

    private fun findContactByCall(recentCall: RecentCall): Contact? {
        return ContactsRepository.snapshot.find(recentCall.phoneNumber)
    }

    override fun myRecyclerView() = binding.recentsList

    private fun itemClickAction(action: Int, call: RecentCall) {
        when (action) {
            SWIPE_ACTION_MESSAGE -> actionSMS(call)
            SWIPE_ACTION_WHATSAPP -> actionWhatsApp(call)
            SWIPE_ACTION_CALL -> actionCall(call)
            SWIPE_ACTION_OPEN -> actionOpen(call)
            else -> {}
        }
    }

    private fun actionCall(call: RecentCall) {
        val recentCall = call
        if (context.config.showCallConfirmation) {
            CallConfirmationDialog(activity as SimpleActivity, recentCall.name) {
                callRecentNumber(recentCall)
            }
        } else {
            callRecentNumber(recentCall)
        }
    }

    private fun actionSMS(call: RecentCall) {
        activity?.launchSendSMSIntent(call.phoneNumber)
    }

    private fun actionWhatsApp(call: RecentCall) {
        activity?.launchSendWhatsAppIntent(call.phoneNumber)
    }

    private fun actionOpen(call: RecentCall) {
        val recentCalls = call.groupedCalls as ArrayList<RecentCall>? ?: arrayListOf(call)
        val contact = findContactByCall(call)
        Intent(activity, CallHistoryActivity::class.java).apply {
            putExtra(CURRENT_RECENT_CALL, call)
            putExtra(CURRENT_RECENT_CALL_LIST, recentCalls)
            putExtra(CONTACT_ID, call.contactID)
            if (contact != null) {
                putExtra(IS_PRIVATE, contact.isPrivate())
            }
            activity?.launchActivityIntent(this)
        }
    }
}
