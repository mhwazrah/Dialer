package com.mhw.phone.activities

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.provider.ContactsContract
import android.text.SpannableString
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.graphics.drawable.GradientDrawable
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.updatePaddingRelative
import androidx.core.graphics.drawable.DrawableCompat
import com.goodwy.commons.dialogs.CallConfirmationDialog
import com.goodwy.commons.dialogs.ConfirmationAdvancedDialog
import com.goodwy.commons.dialogs.ConfirmationDialog
import com.goodwy.commons.dialogs.RadioGroupDialog
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.RadioItem
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.commons.models.contacts.ContactSource
import com.goodwy.commons.models.contacts.Event
import com.goodwy.commons.models.contacts.SocialAction
import com.mhw.phone.BuildConfig
import com.mhw.phone.R
import com.mhw.phone.adapters.CallHistoryAdapter
import com.mhw.phone.databinding.ActivityCallHistoryBinding
import com.mhw.phone.databinding.ItemViewEmailBinding
import com.mhw.phone.databinding.ItemViewEventBinding
import com.mhw.phone.databinding.ItemViewMessengersActionsBinding
import com.mhw.phone.dialogs.ChangeTextDialog
import com.mhw.phone.dialogs.ChooseSocialDialog
import com.mhw.phone.extensions.*
import com.mhw.phone.models.SIMAccount
import com.mhw.phone.databinding.ItemSimPopupBinding
import com.mhw.phone.helpers.*
import com.mhw.phone.models.RecentCall
import kotlin.collections.ArrayList
import kotlin.math.abs
import androidx.core.graphics.drawable.toDrawable
import com.goodwy.commons.dialogs.QrCodeDialog

class CallHistoryActivity : SimpleActivity() {
    // Call button's padding from the layout, before the dual-SIM strip widens it.
    private val callButtonPaddingEnd by lazy { binding.twoButton.paddingEnd }
    private val binding by viewBinding(ActivityCallHistoryBinding::inflate)

    private var allRecentCall = listOf<RecentCall>()
    // Written by the background contact loader, read on the UI thread: immutable snapshots only.
    @Volatile private var contact: Contact? = null
    @Volatile private var duplicateContacts: List<Contact> = emptyList()
    private var contactSources = ArrayList<ContactSource>()
    private var recentsAdapter: CallHistoryAdapter? = null
    private var currentRecentCall: RecentCall? = null
    private var currentRecentCallList: List<RecentCall>? = null
    private var recentsHelper = RecentsHelper(this)
    private var initShowAll = false
    private var showAll = false

    // The contact is loaded again only when it may have changed: first resume, after leaving to edit it, or when the
    // contact book changed.
    private var contactStale = true
    private var seenRepositoryVersion = ContactsRepository.version

    @SuppressLint("MissingSuperCall")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupEdgeToEdge(padBottomSystem = listOf(binding.callHistoryScrollview))

        currentRecentCall = intent.getSerializableExtra(CURRENT_RECENT_CALL) as? RecentCall
        if (currentRecentCall == null) {
            finish()
            return
        }

        initButtons()

        currentRecentCallList = intent.getSerializableExtra(CURRENT_RECENT_CALL_LIST) as? List<RecentCall>
        currentRecentCallList?.let {
            gotRecents(it)
            initShowAll()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.callHistoryPlaceholderContainer.beGone()
        updateTextColors(binding.callHistoryHolder)
        if (contactStale || seenRepositoryVersion != ContactsRepository.version) {
            contactStale = false
            seenRepositoryVersion = ContactsRepository.version
            ensureBackgroundThread {
                initContact()
            }
        }
        // Opened from a contact (no call list passed): load and show that number's calls, or the placeholder.
        if (!initShowAll) refreshCallLog(load = currentRecentCallList == null)
        updateButtons()
        setupMenu()
        setupCallerNotes()
    }

    private fun initButtons() {
        val properPrimaryColor = getProperPrimaryColor()

        var drawableSMS = AppCompatResources.getDrawable(this, R.drawable.ic_messages)
        drawableSMS = DrawableCompat.wrap(drawableSMS!!)
        DrawableCompat.setTint(drawableSMS, properPrimaryColor)
        DrawableCompat.setTintMode(drawableSMS, PorterDuff.Mode.SRC_IN)
        binding.oneButton.setCompoundDrawablesWithIntrinsicBounds(null, drawableSMS, null, null)

        var drawableCall = AppCompatResources.getDrawable(this, R.drawable.ic_phone_vector)
        drawableCall = DrawableCompat.wrap(drawableCall!!)
        DrawableCompat.setTint(drawableCall, properPrimaryColor)
        DrawableCompat.setTintMode(drawableCall, PorterDuff.Mode.SRC_IN)
        binding.twoButton.setCompoundDrawablesWithIntrinsicBounds(null, drawableCall, null, null)

        var drawableInfo = AppCompatResources.getDrawable(this, R.drawable.ic_videocam_vector)
        drawableInfo = DrawableCompat.wrap(drawableInfo!!)
        DrawableCompat.setTint(drawableInfo, properPrimaryColor)
        DrawableCompat.setTintMode(drawableInfo, PorterDuff.Mode.SRC_IN)
        binding.threeButton.apply {
            setCompoundDrawablesWithIntrinsicBounds(null, drawableInfo, null, null)
            alpha = 0.5f
            isEnabled = false
        }

        var drawableShare = AppCompatResources.getDrawable(this, R.drawable.ic_mail_vector)
        drawableShare = DrawableCompat.wrap(drawableShare!!)
        DrawableCompat.setTint(drawableShare, properPrimaryColor)
        DrawableCompat.setTintMode(drawableShare, PorterDuff.Mode.SRC_IN)
        binding.fourButton.apply {
            setCompoundDrawablesWithIntrinsicBounds(null, drawableShare, null, null)
            alpha = 0.5f
            isEnabled = false
        }

        binding.oneButton.setTextColor(properPrimaryColor)
        binding.twoButton.setTextColor(properPrimaryColor)
        binding.threeButton.setTextColor(properPrimaryColor)
        binding.fourButton.setTextColor(properPrimaryColor)
    }

    private fun initShowAll() {
        val phoneNumber = currentRecentCall?.phoneNumber ?: return
        ensureBackgroundThread {
            try {
                val currentRecentCalls = config.parseRecentCallsCache().filter { RecentsHelper.isSameNumber(it.phoneNumber, phoneNumber) }
                val visibleCount = currentRecentCallList?.size ?: return@ensureBackgroundThread
                if (currentRecentCalls.size > visibleCount) {
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        binding.callHistoryShowAll.beVisible()
                        initShowAll = true
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun showAll() {
        if (showAll) {
            // Action hide
            binding.callHistoryShowAll.beInvisible()
            binding.progressIndicator.show()
            currentRecentCallList?.let {
                gotRecents(it) {
                    showAll = false
                    binding.callHistoryShowAll.apply {
                        text = getString(R.string.all_g)
                        beVisible()
                    }
                }
            }
        } else {
            // Action show all
            binding.callHistoryShowAll.beInvisible()
            binding.progressIndicator.show()
            refreshCallLog(load = true) {
                runOnUiThread {
                    showAll = true
                    binding.callHistoryShowAll.apply {
                        text = getString(R.string.hide)
                        beVisible()
                    }
                }
            }
        }
    }

    private fun updateButtons() {
        val properPrimaryColor = getProperPrimaryColor()

        val phoneNumber = if (config.formatPhoneNumbers) currentRecentCall!!.phoneNumber.formatPhoneNumber() else currentRecentCall!!.phoneNumber

        binding.apply {
            callHistoryShowAll.apply {
//                val drawable = AppCompatResources.getDrawable(context, R.drawable.ic_chevron_down_vector)
//                drawable?.applyColorFilter(properPrimaryColor)
//                setDrawablesRelativeWithIntrinsicBounds(end = drawable)
                setTextColor(properPrimaryColor)
                setOnClickListener { showAll() }
            }

            callHistoryNumberType.beGone()
            callHistoryNumberType.setTextColor(getProperTextColor())
            callHistoryNumberContainer.setOnClickListener {
                val call = currentRecentCall
                if (call != null) {
                    makeCall(call)
                }
            }
            callHistoryNumberContainer.setOnLongClickListener {
                copyToClipboard(callHistoryNumber.text.toString())
                true
            }
            callHistoryNumber.text = formatterUnicodeWrap(phoneNumber)
            callHistoryNumber.setTextColor(properPrimaryColor)

            supportActionBar?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            contactActionsHolder.setBackgroundColor(Color.TRANSPARENT)
            collapsingToolbar.setBackgroundColor(Color.TRANSPARENT)

            updateBlockButton()

            val typeface = FontHelper.getTypeface(this@CallHistoryActivity)
            arrayOf(
                binding.oneButton, binding.twoButton, binding.threeButton, binding.fourButton, blockButton
            ).forEach {
                it.setTypeface(typeface)
            }
        }
    }

    private fun setupCallerNotes() {
        val callerNote = callerNotesHelper.getCallerNotes(currentRecentCall!!.phoneNumber)
        val note = callerNote?.note
        binding.apply {
            callerNotesIcon.setColorFilter(getProperTextColor())
            callerNotesText(note)

            callerNotesHolder.setOnClickListener {
                changeNoteDialog(currentRecentCall!!.phoneNumber)
            }
            callerNotesHolder.setOnLongClickListener {
                val text = callerNotesHelper.getCallerNotes(currentRecentCall!!.phoneNumber)?.note
                text?.let { note -> copyToClipboard(note) }
                true
            }
        }
    }

    private fun callerNotesText(note: String?) {
        binding.apply {
            val empty = note == null || note == ""
            callerNotes.text = if (empty) getString(R.string.add_notes) else note
                callerNotes.alpha = if (empty) 0.6f else 1f
        }
    }

    private fun changeNoteDialog(number: String) {
        val callerNote = callerNotesHelper.getCallerNotes(number)
        ChangeTextDialog(
            activity = this@CallHistoryActivity,
            title = getString(R.string.add_notes) + " ($number)",
            currentText = callerNote?.note,
            maxLength = CALLER_NOTES_MAX_LENGTH,
            showNeutralButton = true,
            neutralTextRes = R.string.delete
        ) {
            if (it != "") {
                callerNotesHelper.addCallerNotes(number, it, callerNote) {
                    callerNotesText(it)
                }
            } else {
                callerNotesHelper.deleteCallerNotes(callerNote) {
                    callerNotesText(it)
                }
            }
        }
    }

    private fun setupMenu() {
        binding.callHistoryToolbar.menu.apply {
            updateMenuItemColors(this)

            findItem(R.id.delete).setOnMenuItemClickListener {
                askConfirmRemove()
                true
            }

            findItem(R.id.qr_code).setOnMenuItemClickListener {
                val qrContact = contact
                if (qrContact != null) {
                    showContactQrCode(qrContact)
                } else {
                    val exporter = VcfExporter()
                    val vCard =
                        exporter.generateSimpleVCard(currentRecentCall!!.name, currentRecentCall!!.phoneNumber)

                    QrCodeDialog(
                        activity = this@CallHistoryActivity,
                        message = currentRecentCall!!.name,
                        content = vCard,
                        dialogTitle = getString(com.goodwy.commons.R.string.qr_code)
                    ) {}
                }
                true
            }

            findItem(R.id.share).setOnMenuItemClickListener {
                launchShare()
                true
            }

            findItem(R.id.call_anonymously).setOnMenuItemClickListener {
                if (currentRecentCall != null) {
                    if (config.showWarningAnonymousCall) {
                        val text = String.format(getString(R.string.call_anonymously_warning), currentRecentCall!!.phoneNumber)
                        ConfirmationAdvancedDialog(
                            this@CallHistoryActivity,
                            text,
                            R.string.call_anonymously_warning,
                            R.string.ok,
                            R.string.do_not_show_again,
                            fromHtml = true
                        ) {
                            if (it) {
                                makeCall(currentRecentCall!!, "#31#")
                            } else {
                                config.showWarningAnonymousCall = false
                                makeCall(currentRecentCall!!, "#31#")
                            }
                        }
                    } else {
                        makeCall(currentRecentCall!!, "#31#")
                    }
                }
                true
            }
        }

        val properBackgroundColor = getProperBackgroundColor()
        val contrastColor = properBackgroundColor.getContrastColor()
        val itemColor = if (baseConfig.topAppBarColorIcon) getProperPrimaryColor() else contrastColor
        binding.callHistoryToolbar.apply {
            overflowIcon = resources.getColoredDrawableWithColor(R.drawable.ic_three_dots_vector, itemColor)
            setNavigationIconTint(itemColor)
            setNavigationOnClickListener {
                finish()
            }
        }
    }

    private fun getStarDrawable(on: Boolean) = AppCompatResources.getDrawable(this, if (on) R.drawable.ic_star_vector else R.drawable.ic_star_outline_vector)

    private fun refreshCallLog(load: Boolean, callback: (() -> Unit)? = null) {
        getRecentCalls { currentRecentCalls ->
            allRecentCall = currentRecentCalls

            if (load) {
                runOnUiThread {
                    if (currentRecentCalls.isEmpty()) {
                        binding.apply {
                            progressIndicator.hide()
                            callHistoryListContainer.beGone()
                            callHistoryPlaceholderContainer.beVisible()
                            callHistoryToolbar.menu.findItem(R.id.delete).isVisible = false
                        }
                    } else {
                        binding.apply {
                            callHistoryListContainer.beVisible()
                            callHistoryPlaceholderContainer.beGone()
                            callHistoryToolbar.menu.findItem(R.id.delete).isVisible = true
                        }
                        gotRecents(currentRecentCalls)
                    }
                }
            } else {
                if (!initShowAll) {
                    if (currentRecentCallList != null) {
                        if (currentRecentCalls.size > currentRecentCallList!!.size) runOnUiThread { binding.callHistoryShowAll.beVisible() }
                    }
                }
            }

            callback?.invoke()
        }
    }

    private fun getRecentCalls(callback: (List<RecentCall>) -> Unit) {
        val phoneNumber = currentRecentCall?.phoneNumber ?: return
        recentsHelper.getRecentCallsOfNumber(phoneNumber, config.queryLimitRecent, callback)
    }

    private fun gotRecents(recents: List<RecentCall>, callback: (() -> Unit)? = null) {
        val currAdapter = binding.callHistoryList.adapter
        if (currAdapter == null) {
            recentsAdapter = CallHistoryAdapter(
                activity = this as SimpleActivity,
                recyclerView = binding.callHistoryList,
                refreshItemsListener = null,
                hideTimeAtOtherDays = false,
                itemDelete = { deleted ->
                    val deletedSet = deleted.toHashSet()
                    allRecentCall = allRecentCall.filter { it !in deletedSet }
                },
                itemClick = {}
            )

            binding.callHistoryList.adapter = recentsAdapter
            recentsAdapter?.updateItems(recents)
            setupCallHistoryListCount(recents.size)

            binding.progressIndicator.hide()
            if (this.areSystemAnimationsEnabled) {
                binding.callHistoryList.scheduleLayoutAnimation()
            }
        } else {
            recentsAdapter?.updateItems(recents)
            setupCallHistoryListCount(recents.size)
            binding.progressIndicator.hide()
        }

        callback?.invoke()
    }

    private fun setupCallHistoryListCount(count: Int) {
        binding.callHistoryListCount.apply {
            beVisibleIf(count > 6)
            text = String.format(getString(R.string.total_g), count.toString())
        }
    }

    private fun initContact() {
        var wasLookupKeyUsed = false
        var contactId: Int
        try {
            contactId = intent.getIntExtra(CONTACT_ID, 0)
        } catch (_: Exception) {
            return
        }
        if (contactId == 0 ) {
            val data = intent.data
            if (data != null) {
                val rawId = if (data.path?.contains("lookup") == true) {
                    val lookupKey = getLookupKeyFromUri(data)
                    if (lookupKey != null) {
                        contact = ContactsHelper(this).getContactWithLookupKey(lookupKey)
                        wasLookupKeyUsed = true
                    }

                    getLookupUriRawId(data)
                } else {
                    getContactUriRawId(data)
                }

                if (rawId != -1) {
                    contactId = rawId
                }
            }
        }

        if (contactId != 0 && !wasLookupKeyUsed) {

            handlePermission(PERMISSION_READ_CONTACTS) { granted ->
                val isPrivate = intent.getBooleanExtra(IS_PRIVATE, false)
                if (granted) contact =
                    ContactsHelper(this).getContactWithId(contactId, isPrivate)
            }

            if (contact == null && intent.getBooleanExtra(IS_PRIVATE, false)) {
                contact = loadPrivateContacts().firstOrNull { it.id == contactId }
            }

            if (contact == null) {
                runOnUiThread {
                    updateButton()
                }
            } else {
                getDuplicateContacts {
                    ContactsHelper(this).getContactSources {
                        contactSources = it
                        bindContactActions { setupMenuForContact() }
                    }
                }
            }
        } else {
            if (contact == null) {
                runOnUiThread {
                    setupMenuForNoContact()
                    updateButton()
                }
            } else {
                getDuplicateContacts {
                    bindContactActions {}
                }
            }
        }
    }

    private fun setupMenuForNoContact() {
        val contrastColor = getProperBackgroundColor().getContrastColor()
        val itemColor = if (baseConfig.topAppBarColorIcon) getProperPrimaryColor() else contrastColor

        val editMenu = binding.callHistoryToolbar.menu.findItem(R.id.edit)
        editMenu.isVisible = true
        val editIcon = resources.getColoredDrawableWithColor(R.drawable.ic_add_person_vector, itemColor)
        editMenu.icon = editIcon
        editMenu.setTitle(R.string.add_contact)
        editMenu.setOnMenuItemClickListener {
            addContact()
            true
        }
    }

    private fun addContact() {
        val phoneNumber = if (config.formatPhoneNumbers) currentRecentCall!!.phoneNumber.formatPhoneNumber() else currentRecentCall!!.phoneNumber
        contactStale = true
        startAddNumberToContact(phoneNumber)
    }

    private fun setupMenuForContact() {
        val currentContact = contact ?: return
        val contrastColor = getProperBackgroundColor().getContrastColor()
        val itemColor = if (baseConfig.topAppBarColorIcon) getProperPrimaryColor() else contrastColor

        val favoriteMenu = binding.callHistoryToolbar.menu.findItem(R.id.favorite)
        favoriteMenu.isVisible = true
        val favoriteIcon = getStarDrawable(currentContact.starred == 1)
        favoriteIcon!!.setTint(itemColor)
        favoriteMenu.icon = favoriteIcon
        favoriteMenu.setOnMenuItemClickListener {
            val newIsStarred = if (currentContact.starred == 1) 0 else 1
            ensureBackgroundThread {
                val contacts = arrayListOf(currentContact)
                if (newIsStarred == 1) {
                    ContactsHelper(this@CallHistoryActivity).addFavorites(contacts)
                } else {
                    ContactsHelper(this@CallHistoryActivity).removeFavorites(contacts)
                }
            }
            currentContact.starred = newIsStarred
            val favoriteIconNew = getStarDrawable(currentContact.starred == 1)
            favoriteIconNew!!.setTint(itemColor)
            favoriteMenu.icon = favoriteIconNew
            true
        }

        val editMenu = binding.callHistoryToolbar.menu.findItem(R.id.edit)
        editMenu.isVisible = true
        editMenu.setOnMenuItemClickListener {
            contactStale = true
            startContactEdit(currentContact)
            true
        }

        val openWithMenu = binding.callHistoryToolbar.menu.findItem(R.id.open_with)
        openWithMenu.isVisible = true
        openWithMenu.setOnMenuItemClickListener {
            contactStale = true
            openWith()
            true
        }
    }

    private fun getDuplicateContacts(callback: () -> Unit) {
        val baseContact = contact ?: run { callback(); return }
        ContactsHelper(this).getDuplicatesOfContact(baseContact, false) { contacts ->
            ensureBackgroundThread {
                val displayContactSources = getVisibleContactSources()
                duplicateContacts = contacts.filter { displayContactSources.contains(it.source) }
                    .mapNotNull { ContactsHelper(this).getContactWithId(it.id, it.isPrivate()) }

                runOnUiThread {
                    callback()
                }
            }
        }
    }

    // The contact and its duplicates with their public source names, ordered by name.
    private fun contactSourceNames(): Map<Contact, String> {
        val currentContact = contact ?: return emptyMap()
        val sources = LinkedHashMap<Contact, String>()
        sources[currentContact] = getPublicContactSourceSync(currentContact.source, contactSources)
        duplicateContacts.forEach { sources[it] = getPublicContactSourceSync(it.source, contactSources) }
        return sources.entries.sortedBy { it.value.lowercase() }.associate { it.key to it.value }
    }

    // One query per messenger source, on a background thread; the video and messenger rows share the result.
    private fun loadSocialActions(sources: Map<Contact, String>): Map<Contact, List<SocialAction>> =
        sources.filterValues { it.lowercase() in MESSENGER_SOURCES }.mapValues { getSocialActions(it.key.id) }

    private fun bindContactActions(bindMenu: () -> Unit) {
        ensureBackgroundThread {
            val sources = contactSourceNames()
            val socialActions = loadSocialActions(sources)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                bindMenu()
                setupVideoCallActions(socialActions)
                setupMessengersActions(sources, socialActions)
                setupEmails()
                setupEvents()
                updateButton()
            }
        }
    }

    private fun setupVideoCallActions(socialActions: Map<Contact, List<SocialAction>>) {
        if (contact == null) return
        val videoActions = socialActions.values.flatMap { actions -> actions.filter { it.type == 1 } }
        binding.threeButton.apply {
            alpha = if (videoActions.isNotEmpty()) 1f else 0.5f
            isEnabled = videoActions.isNotEmpty()
            setOnLongClickListener { toast(R.string.video_call); true; }
            if (videoActions.isNotEmpty()) setOnClickListener { showVideoCallAction(ArrayList(videoActions)) }
        }
    }

    private fun showVideoCallAction(actions: ArrayList<SocialAction>) {
        if (isDestroyed || isFinishing) return
        ChooseSocialDialog(this@CallHistoryActivity, actions) { action -> openSocialAction(action) }
    }

    private fun setupMessengersActions(sources: Map<Contact, String>, socialActions: Map<Contact, List<SocialAction>>) {
        val currentContact = contact
        binding.contactMessengersActionsHolder.removeAllViews()
        if (currentContact == null) {
            binding.contactMessengersActionsHolder.beGone()
            return
        }

        val properTextColor = getProperTextColor()
        val properPrimaryColor = getProperPrimaryColor()
        val lastSource = sources.keys.last()
        for ((key, value) in sources) {
            ItemViewMessengersActionsBinding.inflate(layoutInflater, binding.contactMessengersActionsHolder, false).apply {
                contactMessengerActionName.text = if (value == "") getString(R.string.phone_storage) else value
                val text = " (ID:" + key.source + ")"
                contactMessengerActionAccount.text = text
                contactMessengerActionName.setTextColor(properTextColor)
                contactMessengerActionAccount.setTextColor(properTextColor)
                contactMessengerActionHolder.setOnClickListener {
                    if (contactMessengerActionAccount.isVisible()) contactMessengerActionAccount.beGone()
                    else contactMessengerActionAccount.beVisible()
                }
                contactMessengerActionNumber.setTextColor(properPrimaryColor)
                binding.contactMessengersActionsHolder.addView(root)

                arrayOf(
                    contactMessengerActionMessageIcon, contactMessengerActionCallIcon, contactMessengerActionVideoIcon,
                ).forEach {
                    it.background.setTint(properTextColor)
                    it.background.alpha = 40
                    it.setColorFilter(properPrimaryColor)
                }

                dividerContactMessengerAction.setBackgroundColor(properTextColor)
                dividerContactMessengerAction.beGoneIf(lastSource == key)

                val actions = socialActions[key].orEmpty()
                val firstAction = actions.firstOrNull() ?: return@apply
                val plus = if (firstAction.label.contains("+", ignoreCase = true)) "+" else ""
                val number = plus + firstAction.label.filter { it.isDigit() }
                contactMessengerActionNumber.text = number
                root.copyOnLongClick(number)
                binding.contactMessengersActionsHolder.beVisible()
                contactMessengerActionHolder.beVisible()
                val callActions = actions.filter { it.type == 0 }
                val videoActions = actions.filter { it.type == 1 }
                val messageActions = actions.filter { it.type == 2 }
                if (value.lowercase() == VIBER) {
                    contactMessengerActionNumber.beGoneIf(currentContact.phoneNumbers.size > 1 && messageActions.isEmpty())
                }
                if (messageActions.isNotEmpty()) contactMessengerActionMessage.apply {
                    beVisible()
                    setOnClickListener { showMessengerAction(messageActions) }
                }
                if (callActions.isNotEmpty()) contactMessengerActionCall.apply {
                    beVisible()
                    setOnClickListener { showMessengerAction(callActions) }
                }
                if (videoActions.isNotEmpty()) contactMessengerActionVideo.apply {
                    beVisible()
                    setOnClickListener { showMessengerAction(videoActions) }
                }
            }
        }
    }

    private fun showMessengerAction(actions: List<SocialAction>) {
        if (isDestroyed || isFinishing) return
        val action = actions.singleOrNull() ?: return
        openSocialAction(action)
    }

    private fun openSocialAction(action: SocialAction) {
        Intent(Intent.ACTION_VIEW).apply {
            val uri = ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, action.dataId)
            setDataAndType(uri, action.mimetype)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TASK
            try {
                startActivity(this)
            } catch (_: SecurityException) {
                handlePermission(PERMISSION_CALL_PHONE) { success ->
                    if (success) {
                        startActivity(this)
                    } else {
                        toast(R.string.no_phone_call_permission)
                    }
                }
            } catch (_: ActivityNotFoundException) {
                toast(R.string.no_app_found)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }

    // a contact cannot have different emails per contact source. Such contacts are handled as separate ones, not duplicates of each other
    private fun setupEmails() {
        val currentContact = contact ?: return
        binding.contactEmailsHolder.removeAllViews()
        val emails = currentContact.emails
        if (emails.isNotEmpty()) {

            binding.fourButton.apply {
                alpha = 1f
                isEnabled = true
                setOnClickListener {
                    if (emails.size == 1) sendEmailIntent(emails.first().value)
                    else {
                        val items = java.util.ArrayList<RadioItem>()
                        emails.forEachIndexed { index, email ->
                            items.add(RadioItem(index, email.value))
                        }

                        RadioGroupDialog(this@CallHistoryActivity, items, R.string.email) {
                            sendEmailIntent(emails[it as Int].value)
                        }
                    }
                }
                setOnLongClickListener { toast(R.string.email); true; }
            }

            val isFirstItem = emails.first()
            val isLastItem = emails.last()
            emails.forEach {
                ItemViewEmailBinding.inflate(layoutInflater, binding.contactEmailsHolder, false).apply {
                    val email = it
                    binding.contactEmailsHolder.addView(root)
                    contactEmail.text = email.value
                    contactEmailType.text = getEmailTypeText(email.type, email.label)
                    val properTextColor = getProperTextColor()
                    contactEmailType.setTextColor(properTextColor)
                    root.copyOnLongClick(email.value)

                    root.setOnClickListener {
                        sendEmailIntent(email.value)
                    }

                    contactEmailIcon.beVisibleIf(isFirstItem == email)
                    contactEmailIcon.setColorFilter(properTextColor)
                    dividerContactEmail.setBackgroundColor(properTextColor)
                    dividerContactEmail.beGoneIf(isLastItem == email)
                    contactEmail.setTextColor(getProperPrimaryColor())
                }
            }
            binding.contactEmailsHolder.beVisible()
        } else {
            binding.contactEmailsHolder.beGone()
        }
    }

    private fun getEmailTypeText(type: Int, label: String): String {
        return if (type == ContactsContract.CommonDataKinds.BaseTypes.TYPE_CUSTOM) {
            label
        } else {
            getString(
                when (type) {
                    ContactsContract.CommonDataKinds.Email.TYPE_HOME -> R.string.home
                    ContactsContract.CommonDataKinds.Email.TYPE_WORK -> R.string.work
                    ContactsContract.CommonDataKinds.Email.TYPE_MOBILE -> R.string.mobile
                    else -> R.string.other
                }
            )
        }
    }

    private fun setupEvents() {
        val currentContact = contact
        if (currentContact != null) {
            var events = currentContact.events.toMutableSet() as LinkedHashSet<Event>

            duplicateContacts.forEach {
                events.addAll(it.events)
            }

            events = events.sortedBy { it.type }.toMutableSet() as LinkedHashSet<Event>
            binding.contactEventsHolder.removeAllViews()

            if (events.isNotEmpty()) {
                val isFirstItem = events.first()
                val isLastItem = events.last()
                events.forEach {
                    ItemViewEventBinding.inflate(layoutInflater, binding.contactEventsHolder, false).apply {
                        val event = it
                        binding.contactEventsHolder.addView(root)
                        it.value.getDateTimeFromDateString(true, contactEvent)
                        contactEventType.setText(getEventTextId(it.type))
                        val properTextColor = getProperTextColor()
                        contactEventType.setTextColor(properTextColor)
                        root.copyOnLongClick(it.value.getDateFormattedFromDateString(true))

                        contactEventIcon.beVisibleIf(isFirstItem == event)
                        contactEventIcon.setColorFilter(properTextColor)
                        dividerContactEvent.setBackgroundColor(properTextColor)
                        dividerContactEvent.beGoneIf(isLastItem == event)
                        contactEvent.setTextColor(getProperPrimaryColor())
                    }
                }
                binding.contactEventsHolder.beVisible()
            } else {
                binding.contactEventsHolder.beGone()
            }
        }
    }

    private fun getEventTextId(type: Int) = when (type) {
        ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY -> R.string.anniversary
        ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY -> R.string.birthday
        else -> R.string.other
    }

    private fun updateButton() {
        val call = currentRecentCall
        if (call != null) {
            if (call.phoneNumber == call.name
                || ((call.isABusinessCall() || call.isVoiceMail) && call.photoUri == "")
                || isDestroyed || isFinishing
            ) {
                val drawable = when {
                    call.isVoiceMail -> {
                        @SuppressLint("UseCompatLoadingForDrawables")
                        val drawableVoicemail = resources.getDrawable( R.drawable.placeholder_voicemail, theme)
                        if (baseConfig.useColoredContacts) {
                            val letterBackgroundColors = getLetterBackgroundColors()
                            val color = letterBackgroundColors[abs(call.name.hashCode()) % letterBackgroundColors.size].toInt()
                            (drawableVoicemail as LayerDrawable).findDrawableByLayerId(R.id.placeholder_contact_background).applyColorFilter(color)
                        }
                        drawableVoicemail
                    }
                    call.isABusinessCall() -> SimpleContactsHelper(this@CallHistoryActivity).getColoredCompanyIcon(call.name)
                    else -> SimpleContactsHelper(this@CallHistoryActivity).getColoredContactIcon(call.name)
                }
                binding.topDetails.callHistoryImage.setImageDrawable(drawable)
            } else {
                if (!isFinishing && !isDestroyed) binding.topDetails.callHistoryImage.loadBrandedContactImage(call.photoUri, call.name)
            }

            val currentContact = contact
            if (currentContact != null) {
                val contactPhoneNumber = currentContact.phoneNumbers
                    .firstOrNull { it.normalizedNumber == currentRecentCall!!.phoneNumber }
                if (contactPhoneNumber != null) {
                    binding.callHistoryNumberTypeContainer.beVisible()
                    binding.callHistoryNumberType.apply {
                        beVisible()
                        val phoneNumberType = contactPhoneNumber.type
                        val phoneNumberLabel = contactPhoneNumber.label
                        text = getPhoneNumberTypeText(phoneNumberType, phoneNumberLabel)
                    }
                    binding.callHistoryFavoriteIcon.apply {
                        beVisibleIf(contactPhoneNumber.isPrimary)
                        applyColorFilter(getProperTextColor())
                    }
                }

                arrayOf(
                    binding.topDetails.callHistoryImage, binding.topDetails.callHistoryName
                ).forEach {
                    it.setOnClickListener { viewContactInfo(currentContact) }
                }
                binding.topDetails.callHistoryImage.apply {
                    setOnLongClickListener { toast(R.string.contact_details); true; }
                    contentDescription = getString(R.string.contact_details)
                }
            } else {
                val countryOrVoiceMail =
                    if (currentRecentCall!!.isVoiceMail) getString(R.string.voicemail)
                    else currentRecentCall!!.phoneNumber.getCountryByNumber()
                if (countryOrVoiceMail != "") {
                    binding.callHistoryNumberTypeContainer.beVisible()
                    binding.callHistoryNumberType.apply {
                        beVisible()
                        text = countryOrVoiceMail
                    }
                }

                arrayOf(
                    binding.topDetails.callHistoryImage, binding.topDetails.callHistoryName
                ).forEach {
                    it.setOnClickListener {
                        addContact()
                    }
                }
                binding.topDetails.callHistoryImage.apply {
                    setOnLongClickListener { toast(R.string.add_contact); true; }
                    contentDescription = getString(R.string.add_contact)
                }
            }

            binding.callHistoryPlaceholderContainer.beGone()

            val name = call.name
            val formatPhoneNumbers = config.formatPhoneNumbers
            val nameToShow = if (name == call.phoneNumber && formatPhoneNumbers) {
                SpannableString(name.formatPhoneNumber())
            } else {
                SpannableString(name)
            }
            binding.topDetails.callHistoryName.apply {
                text = formatterUnicodeWrap(nameToShow.toString())
                setTextColor(getProperTextColor())
                setOnLongClickListener {
                    copyToClipboard(nameToShow.toString())
                    true
                }
            }

            binding.topDetails.callHistoryCompany.apply {
                val company = formatterUnicodeWrap(call.company)
                beVisibleIf(company != "" && !call.isABusinessCall())
                text = company
                setTextColor(getProperTextColor())
                setOnLongClickListener {
                    copyToClipboard(company)
                    true
                }
            }

            binding.topDetails.callHistoryJobPosition.apply {
                val jobPosition = formatterUnicodeWrap(call.jobPosition)
                beVisibleIf(jobPosition != "" && !call.isABusinessCall())
                text = jobPosition
                setTextColor(getProperTextColor())
                setOnLongClickListener {
                    copyToClipboard(jobPosition)
                    true
                }
            }

            binding.oneButton.apply {
                setOnClickListener {
                    launchSendSMSIntent(call.phoneNumber)
                }
                setOnLongClickListener { toast(R.string.send_sms); true; }
            }

            binding.twoButton.apply {
                setOnClickListener {
                    makeCall(call)
                }
                setOnLongClickListener { toast(R.string.call); true; }
            }

            val callSims = getAvailableSIMCardLabels()
            val showCallSimDropdown = areMultipleSIMsAvailable() && callSims.size > 1 && !call.isUnknownNumber
            binding.callSimDropdown.apply {
                beVisibleIf(showCallSimDropdown)
                applyColorFilter(getProperPrimaryColor())
                setOnClickListener { showCallSimMenu(call, callSims) }
            }
            binding.callSimDivider.apply {
                beVisibleIf(showCallSimDropdown)
                setBackgroundColor(getProperTextColor())
            }
            // Centre the phone icon and label in the part left of the divider.
            val dropdownWidth = if (showCallSimDropdown) resources.getDimensionPixelSize(R.dimen.call_sim_dropdown_width) else 0
            binding.twoButton.updatePaddingRelative(end = callButtonPaddingEnd + dropdownWidth)

            if (areMultipleSIMsAvailable() && !call.isUnknownNumber) {
                binding.defaultSimButtonContainer.beVisible()
                updateDefaultSIMButton(call)
                val phoneNumber = call.phoneNumber.replace("+", "%2B")
                val simList = getAvailableSIMCardLabels()
                binding.defaultSim1Button.setOnClickListener {
                    val sim1 = simList.firstOrNull() ?: return@setOnClickListener
                    if ((config.getCustomSIM("tel:$phoneNumber") ?: "") == sim1.handle) {
                        removeDefaultSIM()
                    } else {
                        config.saveCustomSIM("tel:$phoneNumber", sim1.handle)
                        toast(sim1.label)
                    }
                    updateDefaultSIMButton(call)
                    binding.defaultSim1Button.performHapticFeedback()
                }
                if (simList.size > 1) {
                    binding.defaultSim2Button.setOnClickListener {
                        val sim2 = simList[1]
                        if ((config.getCustomSIM("tel:$phoneNumber") ?: "") == sim2.handle) {
                            removeDefaultSIM()
                        } else {
                            config.saveCustomSIM("tel:$phoneNumber", sim2.handle)
                            toast(sim2.label)
                        }
                        updateDefaultSIMButton(call)
                        binding.defaultSim2Button.performHapticFeedback()
                    }
                }  else binding.defaultSimButtonContainer.beGone()
            } else binding.defaultSimButtonContainer.beGone()

            binding.blockButton.apply {
                setOnClickListener {
                    askConfirmBlock()
                }
            }
        } else {
            binding.callHistoryListContainer.beGone()
            binding.callHistoryPlaceholderContainer.beVisible()
            binding.callHistoryToolbar.menu.findItem(R.id.delete).isVisible = false
        }
    }

    private fun updateDefaultSIMButton(call: RecentCall) {
        val background = getProperTextColor()
        val phoneNumber = call.phoneNumber.replace("+","%2B")
        val simList = getAvailableSIMCardLabels()
        binding.apply {
            defaultSim1Icon.background.setTint(background)
            defaultSim1Icon.background.alpha = 40
            defaultSim1Icon.setColorFilter(background.adjustAlpha(0.60f))
            defaultSim1Id.setTextColor(Color.BLACK)

            defaultSim2Icon.background.setTint(background)
            defaultSim2Icon.background.alpha = 40
            defaultSim2Icon.setColorFilter(background.adjustAlpha(0.60f))
            defaultSim2Id.setTextColor(Color.BLACK)

            if (simList.size > 1) {
                val sim1 = simList[0].color
                val sim2 = simList[1].color

                if ((config.getCustomSIM("tel:$phoneNumber") ?: "") == simList[0].handle && !call.isUnknownNumber) {
                    defaultSim1Icon.background.setTint(sim1)
                    defaultSim1Icon.background.alpha = 255
                    defaultSim1Icon.setColorFilter(Color.WHITE)
                    defaultSim1Id.setTextColor(sim1)
                }

                if ((config.getCustomSIM("tel:$phoneNumber") ?: "") == simList[1].handle && !call.isUnknownNumber) {
                    defaultSim2Icon.background.setTint(sim2)
                    defaultSim2Icon.background.alpha = 255
                    defaultSim2Icon.setColorFilter(Color.WHITE)
                    defaultSim2Id.setTextColor(sim2)
                }
            }
        }
    }

    private fun makeCall(call: RecentCall, prefix: String = "") {
        val phoneNumber = call.phoneNumber
        if (config.showCallConfirmation) {
            CallConfirmationDialog(this as SimpleActivity, call.name) {
                launchCallIntent("$prefix$phoneNumber", key = BuildConfig.RIGHT_APP_KEY)
            }
        } else {
            launchCallIntent("$prefix$phoneNumber", key = BuildConfig.RIGHT_APP_KEY)
        }
    }

    // One-off call with a chosen SIM; the number's saved SIM is left as it is. A custom glass popup, not PopupMenu:
    // the commons popup theme renders dark with square corners and can't follow the app's frosted style.
    private fun showCallSimMenu(call: RecentCall, sims: List<SIMAccount>) {
        val density = resources.displayMetrics.density
        val textColor = getProperTextColor()
        val isLight = isLiquidLightTheme()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = (240 * density).toInt()
            val pad = (6 * density).toInt()
            setPadding(0, pad, 0, pad)
            background = GradientDrawable().apply {
                cornerRadius = resources.getDimension(R.dimen.glass_card_radius)
                setColor(if (isLight) 0xFFF7FAFD.toInt() else 0xFF001123.toInt())
                setStroke(density.toInt().coerceAtLeast(1), if (isLight) 0x1A000000 else 0x40FFFFFF)
            }
            elevation = 8 * density
            clipToOutline = true
        }
        val popup = PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = 8 * density
        }
        val titles = listOf(com.goodwy.commons.R.string.call_from_sim_1, com.goodwy.commons.R.string.call_from_sim_2)
        sims.take(2).forEachIndexed { index, sim ->
            val row = ItemSimPopupBinding.inflate(layoutInflater, content, false)
            row.simPopupIcon.background.mutate().setTint(sim.color)
            row.simPopupIcon.setColorFilter(Color.WHITE)
            row.simPopupId.text = sim.id.toString()
            row.simPopupId.setTextColor(sim.color)
            row.simPopupTitle.text = getString(titles[index])
            row.simPopupTitle.setTextColor(textColor)
            row.simPopupLabel.text = sim.label
            row.simPopupLabel.setTextColor(textColor.adjustAlpha(0.65f))
            row.root.contentDescription = "${getString(titles[index])}, ${sim.label}"
            row.root.setOnClickListener {
                popup.dismiss()
                callContactWithSimWithConfirmationCheck(call.phoneNumber, call.name, index == 0)
            }
            content.addView(row.root)
        }
        // Measured up front: PopupWindow places and clamps itself to the screen only when it knows its size.
        val maxWidth = minOf((300 * density).toInt(), resources.displayMetrics.widthPixels - (32 * density).toInt())
        content.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        popup.width = content.measuredWidth
        popup.height = content.measuredHeight
        popup.showAsDropDown(binding.twoButton, 0, (4 * density).toInt(), Gravity.START)
    }

    private fun removeDefaultSIM() {
        val phoneNumber = currentRecentCall!!.phoneNumber.replace("+","%2B")
        config.removeCustomSIM("tel:$phoneNumber")
    }

    /** Blocked-number provider reads must not run on the main thread. */
    private fun isCurrentNumberBlocked(): Boolean = try {
        isNumberBlocked(currentRecentCall!!.phoneNumber, getBlockedNumbers())
    } catch (_: SecurityException) {
        false
    }

    private fun updateBlockButton() {
        ensureBackgroundThread {
            val isBlocked = isCurrentNumberBlocked()
            runOnUiThread { showBlockButton(isBlocked) }
        }
    }

    private fun showBlockButton(isBlocked: Boolean) {
        if (isDestroyed) return
        if (isBlocked) {
            binding.blockButton.text = getString(R.string.unblock_number)
            binding.blockButton.setTextColor(getProperPrimaryColor())
        } else {
            binding.blockButton.text = getString(R.string.block_number)
            binding.blockButton.setTextColor(resources.getColor(R.color.red_missed, theme))
        }
    }

    private fun askConfirmBlock() {
        if (!isDefaultDialer()) {
            toast(R.string.default_phone_app_prompt, Toast.LENGTH_LONG)
            return
        }
        ensureBackgroundThread {
            val isBlocked = isCurrentNumberBlocked()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val baseString = if (isBlocked) R.string.unblock_confirmation else R.string.block_confirmation
                val question = String.format(resources.getString(baseString), currentRecentCall!!.phoneNumber)
                ConfirmationDialog(this, question) {
                    blockNumbers()
                }
            }
        }
    }

    private fun blockNumbers() {
        config.needUpdateRecents = true
        val phoneNumber = currentRecentCall!!.phoneNumber
        ensureBackgroundThread {
            val isBlocked = try {
                if (isNumberBlocked(phoneNumber, getBlockedNumbers())) {
                    deleteBlockedNumber(phoneNumber)
                    false
                } else {
                    addBlockedNumber(phoneNumber)
                    true
                }
            } catch (_: SecurityException) {
                isCurrentNumberBlocked()
            }
            runOnUiThread { showBlockButton(isBlocked) }
        }
    }

    private fun askConfirmRemove() {
        val message =
            if (showAll) getString(R.string.clear_history_confirmation)
            else getString(R.string.remove_confirmation)
        ConfirmationDialog(this, message) {
            handlePermission(PERMISSION_WRITE_CALL_LOG) { granted ->
                if (granted) removeRecents() else toast(com.goodwy.commons.R.string.no_permission)
            }
        }
    }

    private fun removeRecents() {
        val number = currentRecentCall?.phoneNumber
        if (number.isNullOrEmpty()) {
            return
        }
        config.needUpdateRecents = true

        val callsToRemove =
            if (showAll) allRecentCall
            else currentRecentCallList

        if (callsToRemove == null) {
            return
        }

        val idsToRemove = ArrayList<Int>()
        callsToRemove.forEach {
            idsToRemove.add(it.id)
            it.groupedCalls?.mapTo(idsToRemove) { call -> call.id }
        }

        RecentsHelper(this).removeRecentCalls(idsToRemove) {
            // Only the "clear all history" path removes every call of this number, so only then drop its note.
            if (showAll) callerNotesHelper.deleteCallerNotesForNumber(number)

            runOnUiThread {
                if (!isFinishing) finish()
            }
        }
    }

//    private fun finishActMode() {
//        recentsAdapter?.finishActMode()
//    }

    private fun viewContactInfo(contact: Contact) {
        // Already on the in-app contact page: "view contact" goes to the phone's contacts app.
        this.startSystemContactDetails(contact)
    }

    private fun launchShare() {
        val text = currentRecentCall!!.phoneNumber
        Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_SUBJECT, text)
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
            try {
                startActivity(Intent.createChooser(this, getString(R.string.invite_via)))
            } catch (e: ActivityNotFoundException) {
                showErrorToast(e)
            }
        }
    }

    private fun View.copyOnLongClick(value: String) {
        setOnLongClickListener {
            copyToClipboard(value)
            true
        }
    }

    private fun openWith() {
        val currentContact = contact
        if (currentContact != null) {
            val uri = getContactPublicUri(currentContact)
            launchViewContactIntent(uri)
        }
    }
}

// hide private contacts from recent calls
//private fun List<RecentCall>.hidePrivateContacts(privateContacts: List<Contact>, shouldHide: Boolean): List<RecentCall> {
//    return if (shouldHide) {
//        filterNot { recent ->
//            val privateNumbers = privateContacts.flatMap { it.phoneNumbers }.map { it.value }
//            recent.phoneNumber in privateNumbers
//        }
//    } else {
//        this
//    }
//}

//private fun List<RecentCall>.setNamesIfEmpty(contacts: List<Contact>, privateContacts: List<Contact>): ArrayList<RecentCall> {
//    val contactsWithNumbers = contacts.filter { it.phoneNumbers.isNotEmpty() }
//    return map { recent ->
//        if (recent.phoneNumber == recent.name) {
//            val privateContact = privateContacts.firstOrNull { it.doesContainPhoneNumber(recent.phoneNumber) }
//            val contact = contactsWithNumbers.firstOrNull { it.phoneNumbers.first().normalizedNumber == recent.phoneNumber }
//
//            when {
//                privateContact != null -> recent.copy(name = privateContact.getNameToDisplay())
//                contact != null -> recent.copy(name = contact.getNameToDisplay())
//                else -> recent
//            }
//        } else {
//            recent
//        }
//    } as ArrayList
//}

// Contact sources whose social actions (message / call / video rows) the history screen shows.
private val MESSENGER_SOURCES = setOf(WHATSAPP, SIGNAL, VIBER, TELEGRAM, THREEMA)
