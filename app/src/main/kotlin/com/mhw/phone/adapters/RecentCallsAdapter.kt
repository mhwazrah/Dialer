package com.mhw.phone.adapters

import com.mhw.phone.extensions.placeCall
import com.mhw.phone.extensions.showSwipeActionsOnlyWhileSwiping
import com.mhw.phone.extensions.loadBrandedContactImage

import android.content.res.ColorStateList
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.provider.CallLog.Calls
import android.text.SpannableString
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.behaviorule.arturdumchev.library.pixels
import com.bumptech.glide.Glide
import com.goodwy.commons.adapters.MyRecyclerViewListAdapter
import com.mhw.phone.dialogs.CallConfirmDialog
import com.goodwy.commons.dialogs.ConfirmationAdvancedDialog
import com.goodwy.commons.extensions.addBlockedNumber
import com.goodwy.commons.extensions.adjustAlpha
import com.goodwy.commons.extensions.applyColorFilter
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.beVisible
import com.goodwy.commons.extensions.beVisibleIf
import com.goodwy.commons.extensions.performHapticFeedback
import com.goodwy.commons.extensions.copyToClipboard
import com.goodwy.commons.extensions.deleteBlockedNumber
import com.goodwy.commons.extensions.formatDateOrTime
import com.goodwy.commons.extensions.formatPhoneNumber
import com.goodwy.commons.extensions.formatSecondsToShortTimeString
import com.goodwy.commons.extensions.formatterUnicodeWrap
import com.goodwy.commons.extensions.getBlockedNumbers
import com.goodwy.commons.models.BlockedNumber
import com.goodwy.commons.extensions.getColoredDrawableWithColor
import com.goodwy.commons.extensions.getContrastColor
import com.goodwy.commons.extensions.getLetterBackgroundColors
import com.goodwy.commons.extensions.getPopupMenuTheme
import com.goodwy.commons.extensions.getTextSize
import com.goodwy.commons.extensions.highlightTextPart
import com.mhw.phone.extensions.isDefaultPhoneApp
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.isNumberBlocked
import com.goodwy.commons.extensions.isRTLLayout
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.extensions.launchActivityIntent
import com.goodwy.commons.extensions.launchInternetSearch
import com.goodwy.commons.extensions.setHeightAndWidth
import com.goodwy.commons.extensions.setupViewBackground
import com.goodwy.commons.extensions.slideLeft
import com.goodwy.commons.extensions.slideLeftReturn
import com.goodwy.commons.extensions.slideRight
import com.goodwy.commons.extensions.slideRightReturn
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.extensions.updateMarginWithBase
import com.goodwy.commons.helpers.CONTACT_ID
import com.goodwy.commons.helpers.ELLIPSIZE_MODE_END
import com.goodwy.commons.helpers.IS_PRIVATE
import com.goodwy.commons.helpers.IS_RIGHT_APP
import com.goodwy.commons.helpers.PERMISSION_READ_PHONE_STATE
import com.goodwy.commons.helpers.PERMISSION_WRITE_CALL_LOG
import com.goodwy.commons.helpers.SimpleContactsHelper
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.commons.views.MyRecyclerView
import com.mhw.phone.BuildConfig
import com.mhw.phone.R
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import com.mhw.phone.activities.CallHistoryActivity
import com.mhw.phone.helpers.startCallBackReminder
import com.mhw.phone.helpers.ContactsRepository
import com.mhw.phone.helpers.PlaceholderDrawableCache
import com.mhw.phone.activities.SimpleActivity
import com.mhw.phone.databinding.ItemRecentCallBinding
import com.mhw.phone.databinding.ItemRecentCallSwipeBinding
import com.mhw.phone.databinding.ItemRecentsDateBinding
import com.mhw.phone.extensions.areMultipleSIMsAvailable
import com.mhw.phone.extensions.getAvailableSIMCardLabels
import com.mhw.phone.extensions.recentCallSim
import com.mhw.phone.extensions.callContactWithSimWithConfirmationCheck
import com.mhw.phone.extensions.config
import com.mhw.phone.extensions.getCountryByNumber
import com.mhw.phone.extensions.getDayCode
import com.goodwy.commons.extensions.launchSendSMSIntent
import com.mhw.phone.extensions.launchSendWhatsAppIntent
import com.mhw.phone.extensions.setWidth
import com.mhw.phone.extensions.startAddNumberToContact
import com.mhw.phone.extensions.startCallWithConfirmationCheck
import com.mhw.phone.extensions.startSystemContactDetails
import com.mhw.phone.helpers.CURRENT_RECENT_CALL
import com.mhw.phone.helpers.CURRENT_RECENT_CALL_LIST
import com.mhw.phone.helpers.FILTER_RECENT_CALLS_ALL
import com.mhw.phone.helpers.BlockedCall
import com.mhw.phone.helpers.BlockedCallLog
import com.mhw.phone.helpers.blockReasonIn
import com.mhw.phone.helpers.blockReasonText
import com.mhw.phone.helpers.matchesRecentsFilter
import com.mhw.phone.helpers.RecentsHelper
import com.mhw.phone.helpers.highlightFoldedMatch
import com.mhw.phone.helpers.SWIPE_ACTION_BLOCK
import com.mhw.phone.helpers.SWIPE_ACTION_DELETE
import com.mhw.phone.helpers.SWIPE_ACTION_MESSAGE
import com.mhw.phone.helpers.SWIPE_ACTION_NONE
import com.mhw.phone.helpers.SWIPE_ACTION_WHATSAPP
import com.mhw.phone.interfaces.RefreshItemsListener
import com.mhw.phone.models.CallLogItem
import com.mhw.phone.models.RecentCall
import com.mhw.phone.models.SIMAccount
import me.thanel.swipeactionview.SwipeActionView
import me.thanel.swipeactionview.SwipeDirection
import me.thanel.swipeactionview.SwipeGestureListener
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import org.joda.time.DateTime
import kotlin.math.abs

private val REMIND_MINUTES = mapOf(R.id.cab_remind_10 to 10, R.id.cab_remind_30 to 30, R.id.cab_remind_60 to 60)

class RecentCallsAdapter(
    activity: SimpleActivity,
    recyclerView: MyRecyclerView,
    private val refreshItemsListener: RefreshItemsListener?,
    private val showOverflowMenu: Boolean = false,
    private val showCallIcon: Boolean = false,
    private val hideTimeAtOtherDays: Boolean = false,
    private val isDialpad: Boolean = false,
    private val itemDelete: (List<RecentCall>) -> Unit = {},
    itemClick: (Any) -> Unit,
    val profileInfoClick: ((RecentCall) -> Unit)? = null,
    val profileIconClick: ((Any) -> Unit)? = null
) : MyRecyclerViewListAdapter<CallLogItem>(activity, recyclerView, RecentCallsDiffCallback(), itemClick) {

    private lateinit var outgoingCallIcon: Drawable
    private lateinit var incomingCallIcon: Drawable
    private lateinit var incomingMissedCallIcon: Drawable
    private lateinit var blockedCallIcon: Drawable
    private var blockRecords: List<BlockedCall> = emptyList()
    var fontSize: Float = activity.getTextSize()
    private var ellipsizeMode = activity.config.ellipsizeMode
    private val areMultipleSIMsAvailable = activity.areMultipleSIMsAvailable()
    private val simAccounts by lazy { activity.getAvailableSIMCardLabels() }
    private val missedCallColor = resources.getColor(R.color.red_missed, activity.theme)
    private var secondaryTextColor = textColor.adjustAlpha(0.6f)
    private var textToHighlight = ""
    @Volatile private var blockedNumbers = ArrayList<BlockedNumber>()
    private var todayCode = ""
    private var yesterdayCode = ""
    private val marginNormal = resources.getDimension(com.goodwy.commons.R.dimen.normal_margin).toInt()
    private val marginTen = resources.getDimension(com.goodwy.commons.R.dimen.ten_dpi).toInt()

    private val voiceMail = resources.getString(R.string.voicemail)
    private val colorCache = mutableMapOf<String, Int>()
    private val placeholderDrawables = PlaceholderDrawableCache(activity)

    companion object {
        private const val VIEW_TYPE_DATE = 0
        private const val VIEW_TYPE_CALL = 1
        private const val VIEW_TYPE_CALL_SWIPE = 2
    }

    init {
        refreshBlockedNumbers()
        initDrawables()
        setupDragListener(true)
        setHasStableIds(true)
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    override fun getActionMenuId() = R.menu.cab_recent_calls

    override fun prepareActionMode(menu: Menu) {
        val hasMultipleSIMs = activity.areMultipleSIMsAvailable()
        val selectedItems = getSelectedItems()
        val isOneItemSelected = selectedItems.size == 1
        val selectedNumber = "tel:${getSelectedPhoneNumber()}".replace("+","%2B")
        val isAllBlockedNumbers = isAllBlockedNumbers()
        val isAllUnblockedNumbers = isAllUnblockedNumbers()

        menu.apply {
            findItem(R.id.cab_call_sim_1).isVisible = hasMultipleSIMs && isOneItemSelected
            findItem(R.id.cab_call_sim_2).isVisible = hasMultipleSIMs && isOneItemSelected
            findItem(R.id.cab_remove_default_sim).isVisible = isOneItemSelected && (activity.config.getCustomSIM(selectedNumber) ?: "") != ""

            findItem(R.id.cab_block_number).title = if (isOneItemSelected) resources.getString(R.string.block_number) else resources.getString(R.string.block_numbers)
            findItem(R.id.cab_block_number).isVisible = isAllUnblockedNumbers && !isAllBlockedNumbers
            findItem(R.id.cab_unblock_number).title = if (isOneItemSelected) resources.getString(R.string.unblock_number) else resources.getString(R.string.unblock_numbers)
            findItem(R.id.cab_unblock_number).isVisible = isAllBlockedNumbers && !isAllUnblockedNumbers
            findItem(R.id.cab_add_number).isVisible = isOneItemSelected
            findItem(R.id.cab_show_call_details).isVisible = isOneItemSelected
            findItem(R.id.cab_copy_number).isVisible = isOneItemSelected
            findItem(R.id.web_search).isVisible = isOneItemSelected
            findItem(R.id.cab_view_details)?.isVisible = isOneItemSelected && findContactByCall(selectedItems.first()) != null
            findItem(R.id.cab_remind).isVisible = isOneItemSelected && !selectedItems.first().isUnknownNumber
            setRemindTitles(this)
        }
    }

    private fun isAllBlockedNumbers(): Boolean {
        getSelectedItems().map { it.phoneNumber }.forEach { number ->
            if (activity.isNumberBlocked(number, blockedNumbers)) return true
        }
        return false
    }

    private fun isAllUnblockedNumbers(): Boolean {
        getSelectedItems().map { it.phoneNumber }.forEach { number ->
            if (!activity.isNumberBlocked(number, blockedNumbers)) return true
        }
        return false
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            in REMIND_MINUTES -> {
                getSelectedItems().firstOrNull()?.let { remindToCallBack(it, REMIND_MINUTES.getValue(id)) }
                finishActMode()
            }
            R.id.cab_call_sim_1 -> callContact(true)
            R.id.cab_call_sim_2 -> callContact(false)
            R.id.cab_remove_default_sim -> removeDefaultSIM()
            R.id.cab_block_number -> askConfirmBlock()
            R.id.cab_unblock_number -> askConfirmUnblock()
            R.id.cab_add_number -> addNumberToContact()
            R.id.cab_send_sms -> sendSMS()
            R.id.cab_show_call_details -> showCallDetails()
            R.id.cab_copy_number -> copyNumber()
            R.id.web_search -> webSearch()
            R.id.cab_remove -> askConfirmRemove()
            R.id.cab_select_all -> selectAll()
            R.id.cab_view_details -> {
                val selectItems = getSelectedItems().firstOrNull() ?: return
                launchContactDetailsIntent(findContactByCall(selectItems))
            }
        }
    }

    override fun getItemId(position: Int) = currentList[position].getItemId().toLong()

    override fun getSelectableItemCount() = currentList.filterIsInstance<RecentCall>().size

    override fun getIsItemSelectable(position: Int) = currentList.getOrNull(position) is RecentCall

    override fun getItemSelectionKey(position: Int) = currentList.getOrNull(position)?.getItemId()

    override fun getItemKeyPosition(key: Int) = currentList.indexOfFirst { it.getItemId() == key }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun getItemViewType(position: Int): Int {
        return when (currentList[position]) {
            is CallLogItem.Date -> VIEW_TYPE_DATE
            is RecentCall -> if (activity.config.useSwipeToAction) VIEW_TYPE_CALL_SWIPE else VIEW_TYPE_CALL
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val viewHolder = when (viewType) {
            VIEW_TYPE_DATE -> RecentCallDateViewHolder(
                ItemRecentsDateBinding.inflate(layoutInflater, parent, false)
            )

            VIEW_TYPE_CALL -> RecentCallViewHolder(
                ItemRecentCallBinding.inflate(layoutInflater, parent, false)
            )

            VIEW_TYPE_CALL_SWIPE -> RecentCallSwipeViewHolder(
                ItemRecentCallSwipeBinding.inflate(layoutInflater, parent, false)
            )

            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }

        return viewHolder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val callRecord = currentList[position]
        when (holder) {
            is RecentCallDateViewHolder -> holder.bind(callRecord as CallLogItem.Date)
            is RecentCallViewHolder -> holder.bind(callRecord as RecentCall)
            is RecentCallSwipeViewHolder -> holder.bind(callRecord as RecentCall)
        }

        bindViewHolder(holder)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (!activity.isDestroyed && !activity.isFinishing) {
            if (activity.config.useSwipeToAction) {
                if (holder is RecentCallSwipeViewHolder) {
                    Glide.with(activity).clear(holder.binding.itemRecentsImage)
                }
            } else {
                if (holder is RecentCallViewHolder) {
                    Glide.with(activity).clear(holder.binding.itemRecentsImage)
                }
            }
        }
    }

    private fun loadBlockedNumbers(): ArrayList<BlockedNumber> = try {
        activity.getBlockedNumbers()
    } catch (_: SecurityException) {
        ArrayList()
    }

    private fun refreshBlockedNumbers() {
        ensureBackgroundThread { blockedNumbers = loadBlockedNumbers() }
    }

    override fun submitList(list: List<CallLogItem>?) = submitFiltered(list)

    // Formatted strings are memoised per submitted list (rows rebind on every scroll, the text does not change).
    private val dateTexts = HashMap<Long, CharSequence>()
    private val durationTexts = HashMap<Int, String>()
    private var dateFormatKey = ""

    private fun dateText(timestamp: Long): CharSequence {
        val formatKey = "${activity.config.useRelativeDate}|$hideTimeAtOtherDays"
        if (formatKey != dateFormatKey) {
            dateTexts.clear()
            dateFormatKey = formatKey
        }
        return dateTexts.getOrPut(timestamp) {
            if (activity.config.useRelativeDate) {
                DateUtils.getRelativeDateTimeString(
                    activity,
                    timestamp,
                    1.minutes.inWholeMilliseconds,
                    2.days.inWholeMilliseconds,
                    0,
                )
            } else {
                timestamp.formatDateOrTime(activity, hideTimeOnOtherDays = hideTimeAtOtherDays, false)
            }
        }
    }

    private fun submitFiltered(list: List<CallLogItem>?, onCommitted: () -> Unit = {}) {
        dateTexts.clear()
        durationTexts.clear()
        val layoutManager = recyclerView.layoutManager!!
        val recyclerViewState = layoutManager.onSaveInstanceState()
        val now = DateTime.now()
        todayCode = now.millis.getDayCode()
        yesterdayCode = now.minusDays(1).millis.getDayCode()
        blockRecords = BlockedCallLog.read(activity)
        val filter = activity.config.filterRecentCalls
        val listFilter =
            if (filter == FILTER_RECENT_CALLS_ALL) list
            else list?.filterIsInstance<RecentCall>()?.filter { it.matchesRecentsFilter(filter, blockRecords) }
        super.submitList(listFilter) {
            // Do not fight a user who is already scrolling while the diff was computed.
            if (recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE) layoutManager.onRestoreInstanceState(recyclerViewState)
            onCommitted()
        }
    }

    fun initDrawables(newColor: Int = textColor) {
        secondaryTextColor = textColor.adjustAlpha(0.6f)
        outgoingCallIcon = resources.getColoredDrawableWithColor(R.drawable.ic_call_made_vector, newColor)
        incomingCallIcon = resources.getColoredDrawableWithColor(R.drawable.ic_call_received_vector, newColor)
        incomingMissedCallIcon = resources.getColoredDrawableWithColor(R.drawable.ic_call_missed_vector, newColor)
        blockedCallIcon = resources.getColoredDrawableWithColor(R.drawable.ic_block_vector, newColor)
    }

    private fun callContact(useSimOne: Boolean) {
        val phoneNumber = getSelectedPhoneNumber() ?: return
        val name = getSelectedName() ?: return

        activity.callContactWithSimWithConfirmationCheck(phoneNumber, name, useSimOne)
    }

    private fun callContact(prefix: String = "") {
        val phoneNumber = getSelectedPhoneNumber() ?: return
        val name = getSelectedName() ?: return

        (activity as SimpleActivity).startCallWithConfirmationCheck("$prefix$phoneNumber", name)
    }

    private fun removeDefaultSIM() {
        val phoneNumber = getSelectedPhoneNumber()?.replace("+","%2B") ?: return
        activity.config.removeCustomSIM(phoneNumber)
        finishActMode()
        // SIM pickers and swipe colours show the saved SIM.
        notifyItemRangeChanged(0, itemCount)
    }

    private fun askConfirmBlock() {
        if (activity.isDefaultPhoneApp()) {
            val numbers = TextUtils.join(", ", getSelectedItems().distinctBy { it.phoneNumber }.map { it.phoneNumber })
            val baseString = R.string.block_confirmation
            val question = String.format(resources.getString(baseString), numbers)

            ConfirmationAdvancedDialog(activity, question, cancelOnTouchOutside = false) {
                if (it) blockNumbers()
                else selectedKeys.clear()
            }
        } else {
            selectedKeys.clear()
            activity.toast(R.string.default_phone_app_prompt, Toast.LENGTH_LONG)
        }
    }

    private fun blockNumbers() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val callsToBlock = getSelectedItems()

        ensureBackgroundThread {
            val newlyBlocked = callsToBlock.map { it.phoneNumber }.distinct().filter { runCatching { activity.addBlockedNumber(it) }.getOrDefault(false) }.toSet()
            blockedNumbers = loadBlockedNumbers()

            activity.runOnUiThread {
                if (!activity.config.showBlockedNumbers && newlyBlocked.isNotEmpty()) {
                    submitList(currentList.withoutCalls { it.phoneNumber in newlyBlocked })
                }
                finishActMode()
                selectedKeys.clear()
            }
        }
    }

    private fun askConfirmUnblock() {
        if (activity.isDefaultPhoneApp()) {
            val numbers = TextUtils.join(", ", getSelectedItems().distinctBy { it.phoneNumber }.map { it.phoneNumber })
            val baseString = R.string.unblock_confirmation
            val question = String.format(resources.getString(baseString), numbers)

            ConfirmationAdvancedDialog(activity, question, cancelOnTouchOutside = false) {
                if (it) unblockNumbers()
                else selectedKeys.clear()
            }
        } else {
            selectedKeys.clear()
            activity.toast(R.string.default_phone_app_prompt, Toast.LENGTH_LONG)
        }
    }

    private fun unblockNumbers() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val callsToBlock = getSelectedItems()
        //val positions = getSelectedItemPositions()
        //recentCalls.removeAll(callsToBlock.toSet())

        ensureBackgroundThread {
            callsToBlock.map { it.phoneNumber }.forEach { number ->
                try {
                    activity.deleteBlockedNumber(number)
                } catch (_: SecurityException) {
                }
            }
            blockedNumbers = loadBlockedNumbers()

            activity.runOnUiThread {
                //removeSelectedItems(positions)
                finishActMode()
                selectedKeys.clear()
            }
        }
    }

    private fun addNumberToContact() {
        val phoneNumber = getSelectedPhoneNumber() ?: return
        val formatPhoneNumber = if (activity.config.formatPhoneNumbers) phoneNumber.formatPhoneNumber() else phoneNumber
        activity.startAddNumberToContact(formatPhoneNumber)
    }

    private fun sendSMS() {
        val numbers = getSelectedItems().map { it.phoneNumber }
        val recipient = TextUtils.join(";", numbers)
        activity.launchSendSMSIntent(recipient)
    }

    private fun showCallDetails() {
        val recentCall = getSelectedItems().firstOrNull() ?: return
        showCallHistory(recentCall)
    }

    private fun copyNumber() {
        val recentCall = getSelectedItems().firstOrNull() ?: return
        activity.copyToClipboard(recentCall.phoneNumber)
        finishActMode()
    }

    private fun editNumberBeforeCall() {
        val recentCall = getSelectedItems().firstOrNull() ?: return
        Intent(Intent.ACTION_DIAL).apply {
            data = Uri.fromParts("tel", recentCall.phoneNumber, null)
            putExtra(IS_RIGHT_APP, BuildConfig.RIGHT_APP_KEY)
            activity.launchActivityIntent(this)
        }
        finishActMode()
    }

    private fun webSearch() {
        val recentCall = getSelectedItems().firstOrNull() ?: return
        activity.launchInternetSearch(recentCall.phoneNumber)
        finishActMode()
    }

    private fun askConfirmRemove() {
        ConfirmationAdvancedDialog(activity, resources.getString(R.string.remove_confirmation), cancelOnTouchOutside = false) { result ->
            if (result) removeRecentsWithPermission() else selectedKeys.clear()
        }
    }

    private fun removeRecentsWithPermission() {
        activity.handlePermission(PERMISSION_WRITE_CALL_LOG) { granted ->
            if (granted) removeRecents() else {
                selectedKeys.clear()
                activity.toast(com.goodwy.commons.R.string.no_permission)
            }
        }
    }

    private fun removeRecents() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val callsToRemove = getSelectedItems()
        val idsToRemove = ArrayList<Int>()
        callsToRemove.forEach {
            idsToRemove.add(it.id)
            it.groupedCalls?.mapTo(idsToRemove) { call -> call.id }
        }

        RecentsHelper(activity).removeRecentCalls(idsToRemove) {
            itemDelete(callsToRemove)
            activity.runOnUiThread {
                refreshItemsListener?.refreshItems(needUpdate = true)
                submitList(currentList.withoutCalls { it in callsToRemove })
                finishActMode()
            }
        }
    }

    private fun findContactByCall(recentCall: RecentCall): Contact? = ContactsRepository.snapshot.find(recentCall.phoneNumber)

    private fun launchContactDetailsIntent(contact: Contact?) {
        // "View contact details" next to "Show call details": the full contact in the contacts app.
        if (contact != null) {
            activity.startSystemContactDetails(contact)
        }
    }

    fun updateItems(newItems: List<CallLogItem>, highlightText: String = "") {
        refreshBlockedNumbers()
        if (textToHighlight == highlightText) {
            submitList(newItems)
            return
        }
        // The highlight is not part of the row data: rebind the rows once the new list is in.
        textToHighlight = highlightText
        submitFiltered(newItems) { notifyItemRangeChanged(0, itemCount) }
        finishActMode()
    }

    // Removes the matching calls and any day header left without calls under it.
    private fun List<CallLogItem>.withoutCalls(predicate: (RecentCall) -> Boolean): List<CallLogItem> {
        val kept = filterNot { it is RecentCall && predicate(it) }
        return kept.filterIndexed { index, item ->
            item !is CallLogItem.Date || kept.getOrNull(index + 1) is RecentCall
        }
    }

    private fun getSelectedItems() = currentList.filterIsInstance<RecentCall>()
        .filter { selectedKeys.contains(it.getItemId()) }

    private fun getSelectedPhoneNumber() = getSelectedItems().firstOrNull()?.phoneNumber

    private fun getSelectedName() = getSelectedItems().firstOrNull()?.name

    private fun setRemindTitles(menu: Menu) = REMIND_MINUTES.forEach { (id, minutes) ->
        menu.findItem(id)?.title = resources.getQuantityString(R.plurals.minutes, minutes, minutes)
    }

    private fun remindToCallBack(call: RecentCall, minutes: Int) {
        activity.handleNotificationPermission { granted ->
            if (granted) {
                activity.startCallBackReminder(call.name, call.phoneNumber, minutes * 60)
                activity.toast(resources.getString(R.string.reminder_set, resources.getQuantityString(R.plurals.minutes, minutes, minutes)))
            } else {
                activity.toast(R.string.allow_notifications_reminders)
            }
        }
    }

    private fun showPopupMenu(view: View, call: RecentCall) {
        finishActMode()
        val theme = activity.getPopupMenuTheme()
        val contextTheme = ContextThemeWrapper(activity, theme)
        val contact = findContactByCall(call)
        val selectedNumber = "tel:${call.phoneNumber}".replace("+","%2B")

        PopupMenu(contextTheme, view, Gravity.END).apply {
            inflate(R.menu.menu_recent_item_options)
            menu.apply {
                val areMultipleSIMsAvailable = activity.areMultipleSIMsAvailable()
                findItem(R.id.cab_call).isVisible = !areMultipleSIMsAvailable && !call.isUnknownNumber
                findItem(R.id.cab_call_sim_1).isVisible = areMultipleSIMsAvailable && !call.isUnknownNumber
                findItem(R.id.cab_call_sim_2).isVisible = areMultipleSIMsAvailable && !call.isUnknownNumber
                findItem(R.id.cab_send_sms).isVisible = !call.isUnknownNumber
                findItem(R.id.cab_view_details).isVisible = contact != null && !call.isUnknownNumber
                findItem(R.id.cab_add_number).isVisible = !call.isUnknownNumber
                findItem(R.id.cab_copy_number).isVisible = !call.isUnknownNumber
                findItem(R.id.cab_edit_number_before_call).isVisible = !call.isUnknownNumber
                findItem(R.id.web_search).isVisible = !call.isUnknownNumber
                findItem(R.id.cab_show_call_details).isVisible = !call.isUnknownNumber
                findItem(R.id.cab_block_number).isVisible = !call.isUnknownNumber && !activity.isNumberBlocked(call.phoneNumber, blockedNumbers)
                findItem(R.id.cab_unblock_number).isVisible = !call.isUnknownNumber && activity.isNumberBlocked(call.phoneNumber, blockedNumbers)
                findItem(R.id.cab_remove_default_sim).isVisible = (activity.config.getCustomSIM(selectedNumber) ?: "") != "" && !call.isUnknownNumber
                findItem(R.id.cab_remind).isVisible = !call.isUnknownNumber
                setRemindTitles(this)
            }
            setOnMenuItemClickListener { item ->
                val callId = call.id
                REMIND_MINUTES[item.itemId]?.let { minutes ->
                    remindToCallBack(call, minutes)
                    return@setOnMenuItemClickListener true
                }
                when (item.itemId) {
                    R.id.cab_call -> {
                        executeItemMenuOperation(callId) {
                            callContact()
                        }
                    }

                    R.id.cab_call_anonymously -> {
                        if (activity.config.showWarningAnonymousCall) {
                            var phoneNumber = ""
                            executeItemMenuOperation(callId) {
                                phoneNumber = getSelectedPhoneNumber() ?: "+1 234 567 8910"
                            }
                            val text = String.format(resources.getString(R.string.call_anonymously_warning), phoneNumber)
                            ConfirmationAdvancedDialog(
                                activity,
                                text,
                                R.string.call_anonymously_warning,
                                com.goodwy.commons.R.string.ok,
                                com.goodwy.commons.R.string.do_not_show_again,
                                fromHtml = true
                            ) {
                                if (it) {
                                    executeItemMenuOperation(callId) {
                                        callContact("#31#")
                                    }
                                } else {
                                    activity.config.showWarningAnonymousCall = false
                                    executeItemMenuOperation(callId) {
                                        callContact("#31#")
                                    }
                                }
                            }
                        } else {
                            executeItemMenuOperation(callId) {
                                callContact("#31#")
                            }
                        }
                    }

                    R.id.cab_call_sim_1 -> {
                        executeItemMenuOperation(callId) {
                            callContact(true)
                        }
                    }

                    R.id.cab_call_sim_2 -> {
                        executeItemMenuOperation(callId) {
                            callContact(false)
                        }
                    }

                    R.id.cab_send_sms -> {
                        executeItemMenuOperation(callId) {
                            sendSMS()
                        }
                    }

                    R.id.cab_view_details -> {
                        executeItemMenuOperation(callId) {
                            launchContactDetailsIntent(contact)
                        }
                    }

                    R.id.cab_add_number -> {
                        executeItemMenuOperation(callId) {
                            addNumberToContact()
                        }
                    }

                    R.id.cab_show_call_details -> {
                        executeItemMenuOperation(callId) {
                            showCallDetails()
                        }
                    }

                    R.id.cab_block_number -> {
                        selectedKeys.add(callId)
                        askConfirmBlock()
                    }

                    R.id.cab_unblock_number -> {
                        selectedKeys.add(callId)
                        askConfirmUnblock()
                    }

                    R.id.cab_remove -> {
                        selectedKeys.add(callId)
                        askConfirmRemove()
                    }

                    R.id.cab_copy_number -> {
                        executeItemMenuOperation(callId) {
                            copyNumber()
                        }
                    }

                    R.id.cab_edit_number_before_call -> {
                        executeItemMenuOperation(callId) {
                            editNumberBeforeCall()
                        }
                    }

                    R.id.web_search -> {
                        executeItemMenuOperation(callId) {
                            webSearch()
                        }
                    }

                    R.id.cab_remove_default_sim -> {
                        executeItemMenuOperation(callId) {
                            removeDefaultSIM()
                        }
                    }
                }
                true
            }
            show()
        }
    }

    private fun executeItemMenuOperation(callId: Int, callback: () -> Unit) {
        selectedKeys.add(callId)
        callback()
        selectedKeys.remove(callId)
    }

    private class CallRowViews(
        val divider: View,
        val name: TextView,
        val number: TextView,
        val dateTime: TextView,
        val duration: TextView,
        val simImage: ImageView,
        val simId: TextView,
        val image: ImageView,
        val type: ImageView,
        val info: ImageView,
        val infoHolder: View,
        val overflowAnchor: View,
    )

    // Fields shared by the plain and the swipeable row layouts.
    private fun bindCallRow(call: RecentCall, v: CallRowViews, holder: ViewHolder) {
            val currentFontSize = fontSize

            v.divider.setBackgroundColor(textColor)
            if (currentList.lastOrNull() === call || !activity.config.useDividers) v.divider.visibility = View.INVISIBLE else v.divider.visibility = View.VISIBLE

//                val matchingContact = findContactByCall(call)
            val name = call.name //matchingContact?.getNameToDisplay() ?: call.name
            val formatPhoneNumbers = activity.config.formatPhoneNumbers
            var nameToShow = if (name == call.phoneNumber && formatPhoneNumbers) {
                SpannableString(name.formatPhoneNumber())
            } else {
                SpannableString(formatterUnicodeWrap(name))
            }

            if (call.groupedCalls != null) {
                nameToShow = SpannableString("$nameToShow (${call.groupedCalls.size})")
            }

            if (textToHighlight.isNotEmpty() && nameToShow.contains(textToHighlight, true)) {
                nameToShow = SpannableString(nameToShow.toString().highlightTextPart(textToHighlight, properPrimaryColor))
            } else if (textToHighlight.isNotEmpty()) {
                nameToShow.toString().highlightFoldedMatch(textToHighlight, properPrimaryColor)?.let { nameToShow = it }
            }

            v.name.apply {
                text = nameToShow
                setTextColor(if (call.type == Calls.MISSED_TYPE) missedCallColor else textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, currentFontSize)
                isSelected = true
                val ellipsizeConfig =
                    if (ellipsizeMode == ELLIPSIZE_MODE_END) TextUtils.TruncateAt.END else TextUtils.TruncateAt.MARQUEE
                ellipsize = ellipsizeConfig
            }

            var numberToShow =
                if (formatPhoneNumbers) SpannableString(call.phoneNumber.formatPhoneNumber()) else SpannableString(call.phoneNumber)
            if (textToHighlight.isNotEmpty() && numberToShow.contains(textToHighlight, true)) {
                numberToShow = SpannableString(numberToShow.toString().highlightTextPart(textToHighlight, properPrimaryColor))
            }

            v.number.apply {
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, currentFontSize * 0.8f)
                val recentsNumber = if (call.phoneNumber == call.name) {
                    if (call.isVoiceMail) voiceMail else call.phoneNumber.getCountryByNumber()
                } else {
                    if (call.specificType.isNotEmpty() && call.specificNumber.isNotEmpty()) call.specificType
                    else {
                        if (formatPhoneNumbers) call.phoneNumber.formatPhoneNumber() else call.phoneNumber
                    }
                }
                text = if (name != call.phoneNumber && textToHighlight.isNotEmpty()) numberToShow else formatterUnicodeWrap(recentsNumber)
                // Blocked calls say why: block list, not a contact, hidden number
                call.blockReasonIn(blockRecords)?.let {
                    val reason = resources.getString(blockReasonText(it))
                    if (text.isNullOrBlank()) text = reason else append(" · $reason")
                }
            }

            v.dateTime.apply {
                text = dateText(call.startTS)
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, currentFontSize * 0.8f)
                if (showCallIcon) updateMarginWithBase(marginNormal,0,marginNormal,0)
            }

            v.duration.apply {
                text = durationTexts.getOrPut(call.duration) { context.formatSecondsToShortTimeString(call.duration) }
                setTextColor(textColor)
                beVisibleIf(call.type != Calls.MISSED_TYPE && call.type != Calls.REJECTED_TYPE && call.duration > 0)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, currentFontSize * 0.8f)
            }

            v.simImage.beVisibleIf(areMultipleSIMsAvailable)
            v.simId.beVisibleIf(areMultipleSIMsAvailable)
            if (areMultipleSIMsAvailable) {
                val colorSimIcons = activity.config.colorSimIcons
                val simColor = if (!colorSimIcons) textColor
                            else call.simColor
                v.simImage.applyColorFilter(simColor)
                v.simImage.alpha = if (!colorSimIcons) 0.6f else 1f
                v.simId.setTextColor(simColor.getContrastColor())
                v.simId.text = if (call.simID == -1) "?" else call.simID.toString()
            }

            val showContactThumbnails = activity.config.showContactThumbnails
            v.image.beVisibleIf(showContactThumbnails)
            if (showContactThumbnails) {
                val size = (v.image.context.pixels(R.dimen.normal_icon_size) * contactThumbnailsSize).toInt()
                v.image.setHeightAndWidth(size)
                if (call.phoneNumber == call.name || ((call.isABusinessCall() || call.isVoiceMail) && call.photoUri == "")) {
                    val placeholderRes = when {
                        call.isABusinessCall() -> R.drawable.placeholder_company
                        call.isVoiceMail -> R.drawable.placeholder_voicemail
                        else -> R.drawable.placeholder_contact
                    }

                    val drawable = placeholderDrawables.newDrawable(placeholderRes)
                    if (baseConfig.useColoredContacts && drawable is LayerDrawable) {
                        val color = getCachedColorForName(call.name)
                        val backgroundId = when {
                            call.isABusinessCall() -> R.id.placeholder_company_background
                            call.isVoiceMail -> R.id.placeholder_contact_background
                            else -> R.id.placeholder_contact_background
                        }

                        // Use mutate() to create a separate copy, otherwise the colors might get mixed up
                        val backgroundDrawable = drawable.findDrawableByLayerId(backgroundId)?.mutate()
                        backgroundDrawable?.applyColorFilter(color)
                    } else if (drawable is LayerDrawable) {
                        // Brand look: placeholder circle in the theme's primary instead of grey.
                        drawable.findDrawableByLayerId(
                            if (call.isABusinessCall()) R.id.placeholder_company_background else R.id.placeholder_contact_background
                        )?.mutate()?.applyColorFilter(properPrimaryColor)
                    }
                    v.image.setImageDrawable(drawable)
                } else {
                    v.image.loadBrandedContactImage(call.photoUri, call.name)
                }

                v.image.apply {
                    if (profileIconClick != null) {
                        setOnClickListener {
                            if (!actModeCallback.isSelectable) {
                                profileIconClick.invoke(call)
                            } else {
                                holder.viewClicked(call)
                            }
                        }
                        setOnLongClickListener {
                            holder.viewLongClicked()
                            true
                        }
                    }
                }
            }

            val drawable = when {
                (call.blockReason ?: 0) != 0 -> blockedCallIcon
                call.type == Calls.OUTGOING_TYPE -> outgoingCallIcon
                call.type == Calls.MISSED_TYPE -> incomingMissedCallIcon
                else -> incomingCallIcon
            }
            v.type.setImageDrawable(drawable)

            v.info.apply {
                beVisibleIf(showOverflowMenu)
                if (showCallIcon) {
                    setImageResource(R.drawable.ic_phone_vector)
                    setBackgroundResource(R.drawable.circle_background)
                    if (activity.isDynamicTheme() && !activity.isSystemInDarkMode()) {
                        background.setTint(backgroundColor)
                    } else background.setTint(surfaceColor)
                    updateMarginWithBase(marginTen,0,marginTen,0)

                    if (profileInfoClick != null) {
                        setOnClickListener {
                            if (!actModeCallback.isSelectable) {
                                swipedCall(call)
                            } else {
                                holder.viewClicked(call)
                            }
                        }
                    }
                    contentDescription = resources.getString(R.string.call)
                } else {
                    if (profileInfoClick != null) {
                        setOnClickListener {
                            if (!actModeCallback.isSelectable) {
                                profileInfoClick.invoke(call)
                            } else {
                                holder.viewClicked(call)
                            }
                        }
                    }
                }
                applyColorFilter(accentColor)
                setOnLongClickListener {
                    showPopupMenu(v.overflowAnchor, call)
                    true
                }
            }
            //In order not to miss the icon item_recents_info
            v.infoHolder.apply {
                if (showCallIcon) {
                    if (profileInfoClick != null) {
                        setOnClickListener {
                            if (!actModeCallback.isSelectable) {
                                swipedCall(call)
                            } else {
                                holder.viewClicked(call)
                            }
                        }
                    }
                } else {
                    if (profileInfoClick != null) {
                        setOnClickListener {
                            if (!actModeCallback.isSelectable) {
                                profileInfoClick.invoke(call)
                            } else {
                                holder.viewClicked(call)
                            }
                        }
                    }
                }
                setOnLongClickListener {
                    showPopupMenu(v.overflowAnchor, call)
                    true
                }
            }
    }

    private inner class RecentCallViewHolder(val binding: ItemRecentCallBinding) : ViewHolder(binding.root) {
        private val rowViews = binding.run {
            CallRowViews(divider, itemRecentsName, itemRecentsNumber, itemRecentsDateTime, itemRecentsDuration,
                itemRecentsSimImage, itemRecentsSimId, itemRecentsImage, itemRecentsType, itemRecentsInfo,
                itemRecentsInfoHolder, overflowMenuAnchor)
        }

        @SuppressLint("SetTextI18n")
        fun bind(call: RecentCall) = bindView(
            item = call,
            allowSingleClick = (refreshItemsListener != null || isDialpad) && !call.isUnknownNumber,
            allowLongClick = (refreshItemsListener != null || isDialpad) && !call.isUnknownNumber
        ) { _, _ ->
            binding.apply {
                itemRecentsHolder.isSelected = selectedKeys.contains(call.id)
                itemRecentsHolder.setupViewBackground(activity)
                bindCallRow(call, rowViews, this@RecentCallViewHolder)
            }
        }
    }

    private inner class RecentCallSwipeViewHolder(val binding: ItemRecentCallSwipeBinding) : ViewHolder(binding.root) {
        private val rowViews = binding.run {
            CallRowViews(divider, itemRecentsName, itemRecentsNumber, itemRecentsDateTime, itemRecentsDuration,
                itemRecentsSimImage, itemRecentsSimId, itemRecentsImage, itemRecentsType, itemRecentsInfo,
                itemRecentsInfoHolder, overflowMenuAnchor)
        }

        @SuppressLint("SetTextI18n")
        fun bind(call: RecentCall) = bindView(
            item = call,
            allowSingleClick = (refreshItemsListener != null || isDialpad) && !call.isUnknownNumber,
            allowLongClick = (refreshItemsListener != null || isDialpad) && !call.isUnknownNumber
        ) { _, _ ->
            binding.apply {
                itemRecentsFrameSelect.setupViewBackground(activity)
                itemRecentsHolder.isSelected = selectedKeys.contains(call.id)
                bindCallRow(call, rowViews, this@RecentCallSwipeViewHolder)
                val isRTL = activity.isRTLLayout
                val swipeLeftAction = if (isRTL) activity.config.swipeRightAction else activity.config.swipeLeftAction
                val swipeRightAction = if (isRTL) activity.config.swipeLeftAction else activity.config.swipeRightAction
                // Resolved once per bind, and only when a swipe can call: it drives the picker, the call colour and the call.
                val swipeCalls = swipeActionCalls(swipeLeftAction) || swipeActionCalls(swipeRightAction)
                val callSim = if (areMultipleSIMsAvailable && swipeCalls) activity.recentCallSim(call, simAccounts) else null
                // When a swipe can call, the call button is redundant: two SIMs get the SIM picker in its place, one SIM
                // gets nothing. Without a calling swipe the call button stays, as it is then the only way to call.
                val replaceCallButton = showCallIcon && showOverflowMenu && swipeCalls && !call.isUnknownNumber
                val showSimPicker = replaceCallButton && areMultipleSIMsAvailable && simAccounts.size > 1
                itemRecentsSimPicker.beVisibleIf(showSimPicker)
                // The picker is wider than the call button: tighten the second line's gaps so the number type still fits.
                // Set in both branches, as picker rows and call-button rows recycle in the same list.
                val gap = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.medium_margin)
                itemRecentsNumber.updatePaddingRelative(end = if (showSimPicker) gap / 2 else gap)
                if (showSimPicker) itemRecentsDateTime.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    marginStart = 0
                    marginEnd = gap / 2
                }
                if (replaceCallButton) {
                    // The wide tap area behind the call button would place a call on a near miss.
                    itemRecentsInfo.beGone()
                    itemRecentsInfoHolder.beGone()
                    if (showSimPicker) bindSimPicker(call, callSim, this@RecentCallSwipeViewHolder)
                } else {
                    itemRecentsInfoHolder.beVisible()
                }
                //swipe
                swipeLeftIcon.setImageResource(swipeActionImageResource(swipeLeftAction))
                swipeLeftIcon.setColorFilter(properPrimaryColor.getContrastColor())
                swipeLeftIconHolder.backgroundTintList = ColorStateList.valueOf(swipeActionColor(call, swipeLeftAction, callSim))

                swipeRightIcon.setImageResource(swipeActionImageResource(swipeRightAction))
                swipeRightIcon.setColorFilter(properPrimaryColor.getContrastColor())
                swipeRightIconHolder.backgroundTintList = ColorStateList.valueOf(swipeActionColor(call, swipeRightAction, callSim))

                itemRecentsHolder.setDirectionEnabled(SwipeDirection.Left, swipeLeftAction != SWIPE_ACTION_NONE)
                itemRecentsHolder.setDirectionEnabled(SwipeDirection.Right, swipeRightAction != SWIPE_ACTION_NONE)
                itemRecentsHolder.showSwipeActionsOnlyWhileSwiping(itemRecentsFrame, swipeLeftIconHolder, swipeRightIconHolder)

                val halfScreenWidth = activity.resources.displayMetrics.widthPixels / activity.config.swipeToActionWidth
                val swipeWidth = activity.resources.getDimension(com.goodwy.commons.R.dimen.swipe_width)
                if (swipeWidth > halfScreenWidth) {
                    swipeRightIconHolder.setWidth(halfScreenWidth)
                    swipeLeftIconHolder.setWidth(halfScreenWidth)
                }

                if (activity.config.swipeRipple) {
                    itemRecentsHolder.setRippleColor(SwipeDirection.Left, swipeActionColor(call, swipeLeftAction, callSim))
                    itemRecentsHolder.setRippleColor(SwipeDirection.Right, swipeActionColor(call, swipeRightAction, callSim))
                }

                itemRecentsHolder.useHapticFeedback = activity.config.swipeVibration
                itemRecentsHolder.swipeGestureListener = object : SwipeGestureListener {
                    override fun onSwipedLeft(swipeActionView: SwipeActionView): Boolean {
                        finishActMode()
                        val swipeLeftOrRightAction =
                            if (activity.isRTLLayout) activity.config.swipeRightAction else activity.config.swipeLeftAction
                        swipeAction(swipeLeftOrRightAction, call)
                        swipeLeftIcon.slideLeftReturn(swipeLeftIconHolder)
                        return true
                    }

                    override fun onSwipedRight(swipeActionView: SwipeActionView): Boolean {
                        finishActMode()
                        val swipeRightOrLeftAction =
                            if (activity.isRTLLayout) activity.config.swipeLeftAction else activity.config.swipeRightAction
                        swipeAction(swipeRightOrLeftAction, call)
                        swipeRightIcon.slideRightReturn(swipeRightIconHolder)
                        return true
                    }

                    override fun onSwipedActivated(swipedRight: Boolean) {
                        if (swipedRight) swipeRightIcon.slideRight(swipeRightIconHolder)
                        else swipeLeftIcon.slideLeft()
                    }

                    override fun onSwipedDeactivated(swipedRight: Boolean) {
                        if (swipedRight) swipeRightIcon.slideRightReturn(swipeRightIconHolder)
                        else swipeLeftIcon.slideLeftReturn(swipeLeftIconHolder)
                    }
                }
            }
        }
    }

    // Tapping a SIM saves it for the number (same setting as the profile's "Always use this SIM"); it never calls.
    private fun ItemRecentCallSwipeBinding.bindSimPicker(call: RecentCall, selected: SIMAccount?, holder: ViewHolder) {
        val buttons = listOf(
            Triple(itemRecentsSim1Button, itemRecentsSim1Icon, itemRecentsSim1Id),
            Triple(itemRecentsSim2Button, itemRecentsSim2Icon, itemRecentsSim2Id),
        )
        buttons.forEachIndexed { index, (button, icon, id) ->
            val sim = simAccounts[index]
            val isSelected = sim.handle == selected?.handle
            icon.background.mutate().setTint(if (isSelected) sim.color else textColor)
            icon.background.alpha = if (isSelected) 255 else 70
            icon.setColorFilter(if (isSelected) Color.WHITE else textColor.adjustAlpha(0.6f))
            id.setTextColor(if (isSelected) sim.color else textColor.adjustAlpha(0.6f).getContrastColor())
            button.isSelected = isSelected
            button.contentDescription = sim.label
            button.setOnClickListener {
                if (actModeCallback.isSelectable) {
                    holder.viewClicked(call)
                    return@setOnClickListener
                }
                if (activity.config.getCustomSIM(call.phoneNumber) == sim.handle) return@setOnClickListener
                activity.config.saveCustomSIM(call.phoneNumber, sim.handle)
                button.performHapticFeedback()
                // Rows of the same number and the swipe colours depend on the saved SIM.
                notifyItemRangeChanged(0, itemCount)
            }
            button.setOnLongClickListener {
                showPopupMenu(overflowMenuAnchor, call)
                true
            }
        }
    }

    private inner class RecentCallDateViewHolder(val binding: ItemRecentsDateBinding) : ViewHolder(binding.root) {
        fun bind(date: CallLogItem.Date) {
            binding.dateTextView.apply {
                setTextColor(secondaryTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize * 0.76f)

                text = when (date.dayCode) {
                    todayCode -> resources.getString(R.string.today)
                    yesterdayCode -> resources.getString(R.string.yesterday)
                    else -> date.timestamp.formatDateOrTime(
                        context = activity,
                        hideTimeOnOtherDays = true,
                        showCurrentYear = false
                    )
                }
            }
        }
    }

    private fun showCallHistory(call: RecentCall) {
//        val callIdList : ArrayList<Int> = arrayListOf()
//        for (i in getCallList(call)){ callIdList.add(i.id) } // add all the individual records
//        for (n in getCallList(call)){ callIdList.addAll(n.neighbourIDs) } // add all grouped records
        val recentCalls = call.groupedCalls as ArrayList<RecentCall>? ?: arrayListOf(call)
        val matchingContact = findContactByCall(call)
        Intent(activity, CallHistoryActivity::class.java).apply {
            putExtra(CURRENT_RECENT_CALL, call)
            putExtra(CURRENT_RECENT_CALL_LIST, recentCalls)
            putExtra(CONTACT_ID, call.contactID)
            if (matchingContact != null) {
                putExtra(IS_PRIVATE, matchingContact.isPrivate())
            }
            activity.launchActivityIntent(this)
        }
    }

    private fun swipeActionImageResource(swipeAction: Int): Int {
        return when (swipeAction) {
            SWIPE_ACTION_DELETE -> com.goodwy.commons.R.drawable.ic_delete_outline
            SWIPE_ACTION_MESSAGE -> R.drawable.ic_messages
            SWIPE_ACTION_WHATSAPP -> R.drawable.ic_whatsapp_mono_vector
            SWIPE_ACTION_BLOCK -> R.drawable.ic_block_vector
            else -> R.drawable.ic_phone_vector
        }
    }

    // Mirrors swipeAction(): anything but these actions calls.
    private fun swipeActionCalls(swipeAction: Int): Boolean = swipeAction !in
        listOf(SWIPE_ACTION_NONE, SWIPE_ACTION_DELETE, SWIPE_ACTION_MESSAGE, SWIPE_ACTION_WHATSAPP, SWIPE_ACTION_BLOCK)

    private fun swipeActionColor(call: RecentCall, swipeAction: Int, callSim: SIMAccount?): Int {
        val defaultSim = if (activity.config.currentSIMCardIndex == 0) 1 else 2
        // if we're calling with the same SIM that was used to call us then highlight using that SIMs color
        // but only if we have the sim ID
        val simIndex: Int = if (call.simID > 0 && call.simID < 5 && activity.config.callUsingSameSim) call.simID else defaultSim
        val simColor = callSim?.color ?: activity.config.simIconsColors[simIndex]
        return when (swipeAction) {
            SWIPE_ACTION_DELETE -> resources.getColor(R.color.red_call, activity.theme)
            SWIPE_ACTION_MESSAGE -> resources.getColor(R.color.ic_messages, activity.theme)
            SWIPE_ACTION_WHATSAPP -> resources.getColor(R.color.whatsapp_green, activity.theme)
            SWIPE_ACTION_BLOCK -> resources.getColor(R.color.swipe_purple, activity.theme)
            else -> simColor
        }
    }

    private fun swipeAction(swipeAction: Int, call: RecentCall) {
        when (swipeAction) {
            SWIPE_ACTION_DELETE -> swipedDelete(call)
            SWIPE_ACTION_MESSAGE -> swipedSMS(call)
            SWIPE_ACTION_WHATSAPP -> swipedWhatsApp(call)
            SWIPE_ACTION_BLOCK -> swipedBlock(call)
            else -> swipedCall(call)
        }
    }

    private fun swipedDelete(call: RecentCall) {
        selectedKeys.add(call.id)
        if (activity.config.skipDeleteConfirmation) removeRecentsWithPermission() else askConfirmRemove()
    }

    private fun swipedSMS(call: RecentCall) {
        activity.launchSendSMSIntent(call.phoneNumber)
    }

    private fun swipedWhatsApp(call: RecentCall) {
        activity.launchSendWhatsAppIntent(call.phoneNumber)
    }

    private fun swipedBlock(call: RecentCall) {
        if (call.isUnknownNumber) return
        selectedKeys.add(call.id)
        if (!activity.isNumberBlocked(call.phoneNumber, blockedNumbers)) askConfirmBlock() else askConfirmUnblock()
    }

    private fun swipedCall(call: RecentCall) {
        if (activity.config.showCallConfirmation) {
            CallConfirmDialog(activity, call.name, call.phoneNumber, call.photoUri) {
                callRecentNumber(call)
            }
        } else {
            callRecentNumber(call)
        }
    }

    private fun callRecentNumber(recentCall: RecentCall) {
        if (!areMultipleSIMsAvailable) {
            activity.placeCall(recentCall.phoneNumber)
            return
        }
        // Same resolver as the dialpad SIM picker's highlight, so a swipe calls with the highlighted SIM.
        activity.handlePermission(PERMISSION_READ_PHONE_STATE) { granted ->
            val handle = if (granted) activity.recentCallSim(recentCall, simAccounts)?.handle else null
            activity.placeCall(recentCall.phoneNumber, handle)
        }
    }

    private fun getCachedColorForName(name: String): Int {
        return colorCache.getOrPut(name) {
            val letterBackgroundColors = activity.getLetterBackgroundColors()
            letterBackgroundColors[abs(name.hashCode()) % letterBackgroundColors.size].toInt()
        }
    }

//    fun clearColorCache() {
//        colorCache.clear()
//    }
}

class RecentCallsDiffCallback : DiffUtil.ItemCallback<CallLogItem>() {

    override fun areItemsTheSame(oldItem: CallLogItem, newItem: CallLogItem) = oldItem.getItemId() == newItem.getItemId()

    override fun areContentsTheSame(oldItem: CallLogItem, newItem: CallLogItem): Boolean {
        return when {
            oldItem is CallLogItem.Date && newItem is CallLogItem.Date -> oldItem.timestamp == newItem.timestamp && oldItem.dayCode == newItem.dayCode
            // RecentCall is a data class: equality covers every field the row shows, including the group.
            oldItem is RecentCall && newItem is RecentCall -> oldItem == newItem

            else -> false
        }
    }
}
