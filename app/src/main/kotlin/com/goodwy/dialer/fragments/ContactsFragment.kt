package com.goodwy.dialer.fragments

import android.graphics.Color
import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.goodwy.commons.adapters.MyRecyclerViewAdapter
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.dialer.R
import com.goodwy.dialer.activities.SimpleActivity
import com.goodwy.dialer.adapters.ContactsAdapter
import com.goodwy.dialer.databinding.FragmentContactsBinding
import com.goodwy.dialer.databinding.FragmentLettersLayoutBinding
import com.goodwy.dialer.databinding.ItemFavoriteStripBinding
import com.goodwy.dialer.extensions.config
import com.goodwy.dialer.extensions.listedContacts
import com.goodwy.dialer.extensions.launchCreateNewContactIntent
import com.goodwy.dialer.extensions.launchSendWhatsAppIntent
import com.goodwy.dialer.extensions.loadBrandedContactImage
import com.goodwy.dialer.extensions.firstCodePointOrNull
import com.goodwy.dialer.extensions.setupWithContacts
import com.goodwy.dialer.extensions.toSearchQuery
import com.goodwy.dialer.extensions.startCallWithConfirmationCheck
import com.goodwy.dialer.extensions.startContactDetailsIntent
import com.goodwy.dialer.extensions.startContactEdit
import com.goodwy.dialer.helpers.ContactsRepository
import com.goodwy.dialer.helpers.SWIPE_ACTION_CALL
import com.goodwy.dialer.helpers.SWIPE_ACTION_EDIT
import com.goodwy.dialer.helpers.SWIPE_ACTION_MESSAGE
import com.goodwy.dialer.helpers.SWIPE_ACTION_OPEN
import com.goodwy.dialer.helpers.SWIPE_ACTION_WHATSAPP
import com.goodwy.dialer.interfaces.RefreshItemsListener

class ContactsFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment<MyViewPagerFragment.LettersInnerBinding>(context, attributeSet),
    RefreshItemsListener {
    private lateinit var binding: FragmentLettersLayoutBinding
    private lateinit var contactsBinding: FragmentContactsBinding
    private var allContacts = ArrayList<Contact>()
    private var letterFastScrollerReady = false
    private val favoritesAdapter = FavoritesStripAdapter()
    private var searchQuery = ""
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var forwardingToList = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        contactsBinding = FragmentContactsBinding.bind(this)
        binding = FragmentLettersLayoutBinding.bind(contactsBinding.contactsFragment)
        innerBinding = LettersInnerBinding(binding)
        contactsBinding.contactsFavoritesList.adapter = favoritesAdapter
        contactsBinding.contactsFavoritesTitle.setOnClickListener {
            context.config.contactsFavoritesExpanded = !context.config.contactsFavoritesExpanded
            applyFavoritesExpanded()
        }
        applyFavoritesExpanded()
        // MainActivity pads the list by the strip's height; keep the strip glued to the first row.
        binding.fragmentList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = syncFavoritesStrip()
        })
    }

    override fun setupFragment() {
        binding.root.setBackgroundColor(Color.TRANSPARENT) // let the liquid backdrop show through
        //binding.contactsFragment.setBackgroundColor(context.getProperBackgroundColor())

        val placeholderResId = if (context.hasPermission(PERMISSION_READ_CONTACTS)) {
            R.string.no_contacts_found
        } else {
            R.string.could_not_access_contacts
        }

        binding.fragmentPlaceholder.text = context.getString(placeholderResId)

        val placeholderActionResId = if (context.hasPermission(PERMISSION_READ_CONTACTS)) {
            R.string.create_new_contact
        } else {
            R.string.request_access
        }

        binding.fragmentPlaceholder2.apply {
            text = context.getString(placeholderActionResId)
            underlineText()
            setOnClickListener {
                if (context.hasPermission(PERMISSION_READ_CONTACTS)) {
                    activity?.launchCreateNewContactIntent()
                } else {
                    requestReadContactsPermission()
                }
            }
        }
    }

    override fun setupColors(textColor: Int, primaryColor: Int, accentColor: Int) {
        binding.apply {
            (fragmentList.adapter as? MyRecyclerViewAdapter)?.apply {
                updateTextColor(textColor)
                updatePrimaryColor()
                updateBackgroundColor(context.getProperBackgroundColor())
            }
            fragmentPlaceholder.setTextColor(textColor)
            fragmentPlaceholder2.setTextColor(accentColor)
            contactsBinding.contactsFavoritesTitle.setTextColor(accentColor)
            applyFavoritesExpanded() // re-tints the arrow to the new title colour
            favoritesAdapter.notifyDataSetChanged()

            letterFastscroller.textColor = textColor.getColorStateList()
            letterFastscroller.pressedTextColor = accentColor
            letterFastscrollerThumb.setupWithFastScroller(letterFastscroller)
            letterFastscrollerThumb.textColor = accentColor.getContrastColor()
            letterFastscrollerThumb.thumbColor = accentColor.getColorStateList()
        }
    }

    override fun refreshItems(invalidate: Boolean, needUpdate: Boolean, callback: (() -> Unit)?) {
        ContactsRepository.ensureLoaded(context) { snapshot ->
            ensureBackgroundThread {
                val contacts = context.listedContacts(snapshot.contacts)
                allContacts = contacts
                activity?.runOnUiThread {
                    gotContacts(contacts)
                    callback?.invoke()
                }
            }
        }
    }

    private fun gotContacts(contacts: ArrayList<Contact>) {
        setupLetterFastScroller()
        updateFavoritesStrip()
        if (contacts.isEmpty()) {
            binding.apply {
                fragmentPlaceholder.beVisible()
                fragmentPlaceholder2.beVisible()
                fragmentList.beGone()
            }
        } else {
            binding.apply {
                fragmentPlaceholder.beGone()
                fragmentPlaceholder2.beGone()
                fragmentList.beVisible()

//                fragmentList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
//                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
//                        super.onScrollStateChanged(recyclerView, newState)
//                        activity?.hideKeyboard()
//                    }
//                })
                fragmentList.setOnTouchListener { _, _ ->
                    activity?.hideKeyboard()
                    false
                }
            }

            if (binding.fragmentList.adapter == null) {
                ContactsAdapter(
                    activity = activity as SimpleActivity,
                    contacts = contacts,
                    recyclerView = binding.fragmentList,
                    refreshItemsListener = this,
                    showIcon = context.config.onContactClick != SWIPE_ACTION_OPEN,
                    showNumber = context.baseConfig.showPhoneNumbers,
                    itemClick = {
                        itemClickAction(context.config.onContactClick, it as Contact)
                    },
                    profileIconClick = {
                        activity?.startContactDetailsIntent(it as Contact)
                    }
                ).apply {
                    binding.fragmentList.adapter = this
                }

                if (context.areSystemAnimationsEnabled) {
                    binding.fragmentList.scheduleLayoutAnimation()
                }
            } else {
                (binding.fragmentList.adapter as ContactsAdapter).updateItems(contacts)
            }

            try {
                //Decrease the font size based on the number of letters in the letter scroller
                val sizeUnique = contacts.mapNotNullTo(HashSet()) { it.getNameToDisplay().firstCodePointOrNull() }.size
                if (isHighScreenSize()) {
                    if (sizeUnique > 48) binding.letterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleTooTiny
                    else if (sizeUnique > 37) binding.letterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleTiny
                    else binding.letterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleSmall
                } else {
                    if (sizeUnique > 36) binding.letterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleTooTiny
                    else if (sizeUnique > 30) binding.letterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleTiny
                    else binding.letterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleSmall
                }
            } catch (_: Exception) { }
        }
    }

    private fun isHighScreenSize(): Boolean {
        return when (resources.configuration.screenLayout
            and Configuration.SCREENLAYOUT_LONG_MASK) {
            Configuration.SCREENLAYOUT_LONG_NO -> false
            else -> true
        }
    }

    // Once is enough: the scroller follows the adapter's list (see setupWithContacts).
    private fun setupLetterFastScroller() {
        if (letterFastScrollerReady) return
        letterFastScrollerReady = true
        binding.letterFastscroller.setupWithContacts(binding.fragmentList)
    }

    /** Re-reads favourites (and their custom order) from the loaded contacts. */
    fun updateFavoritesStrip() {
        favoritesAdapter.items = context.sortFavoritesByCustomOrder(allContacts.filter { it.starred == 1 })
        favoritesAdapter.notifyDataSetChanged()
        showFavoritesStrip(searchQuery.isEmpty())
    }

    private fun showFavoritesStrip(show: Boolean) {
        contactsBinding.contactsFavoritesHeader.beVisibleIf(show && favoritesAdapter.itemCount > 0)
    }

    private fun applyFavoritesExpanded() {
        val expanded = context.config.contactsFavoritesExpanded
        contactsBinding.contactsFavoritesList.beVisibleIf(expanded)
        contactsBinding.contactsFavoritesTitle.apply {
            val arrow = context.getDrawable(if (expanded) R.drawable.ic_chevron_up_vector else R.drawable.ic_chevron_down_vector)?.mutate()
            arrow?.setTint(currentTextColor)
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, arrow, null)
        }
    }

    private fun syncFavoritesStrip() {
        val list = binding.fragmentList
        val header = contactsBinding.contactsFavoritesHeader
        val first = list.getChildAt(0)
        header.translationY = when {
            first == null -> 0f
            list.getChildAdapterPosition(first) == 0 -> (first.top - list.paddingTop).toFloat()
            else -> -header.bottom.toFloat() // first row scrolled away: strip is off-screen too
        }
    }

    // The strip floats over the list rather than being one of its rows, so a vertical drag that starts on
    // an avatar would otherwise go nowhere. Take it from the strip and hand it to the list.
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                forwardingToList = false
            }
            MotionEvent.ACTION_MOVE -> {
                val header = contactsBinding.contactsFavoritesHeader
                val startedOnStrip = header.visibility == View.VISIBLE && downY >= header.top + header.translationY &&
                    downY <= header.bottom + header.translationY
                val dy = Math.abs(ev.y - downY)
                if (startedOnStrip && dy > touchSlop && dy > Math.abs(ev.x - downX)) {
                    forwardingToList = true
                    forwardToList(MotionEvent.obtain(ev.downTime, ev.eventTime, MotionEvent.ACTION_DOWN, downX, downY, 0))
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!forwardingToList) return super.onTouchEvent(ev)
        forwardToList(MotionEvent.obtain(ev))
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) forwardingToList = false
        return true
    }

    private fun forwardToList(event: MotionEvent) {
        val list = binding.fragmentList
        var view: View = list
        while (view !== this) {
            event.offsetLocation(-view.left.toFloat(), -view.top.toFloat())
            view = view.parent as View
        }
        list.dispatchTouchEvent(event)
        event.recycle()
    }

    override fun rebuildAdapter() {
        if (binding.fragmentList.adapter == null) return
        binding.fragmentList.adapter = null
        gotContacts(allContacts)
    }

    override fun onSearchClosed() {
        searchQuery = ""
        showFavoritesStrip(true)
        binding.fragmentPlaceholder.beVisibleIf(allContacts.isEmpty())
        (binding.fragmentList.adapter as? ContactsAdapter)?.updateItems(allContacts)
    }

    override fun onSearchQueryChanged(text: String) {
        val fixedText = text.toSearchQuery()
        searchQuery = fixedText
        showFavoritesStrip(fixedText.isEmpty())
        val shouldNormalize = fixedText.normalizeString() == fixedText
        val snapshot = ContactsRepository.snapshot
        val isNumberQuery = fixedText.toLongOrNull() != null
        val filtered = allContacts.filter { contact ->
            getProperText(contact.getNameToDisplay(), shouldNormalize).contains(fixedText, true) ||
                (isNumberQuery && contact.doesContainPhoneNumber(fixedText, true)) ||
                contact.relations.any { it.name.contains(fixedText, true) } ||
                snapshot.listSearchKeys(contact).matchesSecondaryFields(contact, fixedText, shouldNormalize)
        } as ArrayList

        filtered.sortBy {
            val nameToDisplay = it.getNameToDisplay()
            !getProperText(nameToDisplay, shouldNormalize).startsWith(fixedText, true) && !nameToDisplay.contains(fixedText, true)
        }

        binding.fragmentPlaceholder.beVisibleIf(filtered.isEmpty())
        (binding.fragmentList.adapter as? ContactsAdapter)?.updateItems(filtered, fixedText)
    }

    private fun requestReadContactsPermission() {
        activity?.handlePermission(PERMISSION_READ_CONTACTS) {
            if (it) {
                binding.fragmentPlaceholder.text = context.getString(R.string.no_contacts_found)
                binding.fragmentPlaceholder2.text = context.getString(R.string.create_new_contact)
                // Marks the repository dirty; MainActivity's listener then refreshes every list from one load.
                ContactsRepository.onPermissionGranted(context)
            }
        }
    }

    override fun myRecyclerView() = binding.fragmentList

    private fun itemClickAction(action: Int, contact: Contact) {
        when (action) {
            SWIPE_ACTION_MESSAGE -> actionSMS(contact)
            SWIPE_ACTION_WHATSAPP -> actionWhatsApp(contact)
            SWIPE_ACTION_CALL -> actionCall(contact)
            SWIPE_ACTION_OPEN -> actionOpen(contact)
            SWIPE_ACTION_EDIT -> activity?.startContactEdit(contact)
            else -> {}
        }
    }

    private fun actionCall(contact: Contact) {
        activity?.startCallWithConfirmationCheck(contact)
    }

    private fun actionSMS(contact: Contact) {
        activity?.initiateCall(contact) { activity?.launchSendSMSIntent(it) }
    }

    private fun actionWhatsApp(contact: Contact) {
        activity?.initiateCall(contact) { activity?.launchSendWhatsAppIntent(it) }
    }

    private fun actionOpen(contact: Contact) {
        activity?.startContactDetailsIntent(contact)
    }

    private inner class FavoritesStripAdapter : RecyclerView.Adapter<FavoritesStripAdapter.Holder>() {
        var items: List<Contact> = emptyList()

        inner class Holder(val binding: ItemFavoriteStripBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemFavoriteStripBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val contact = items[position]
            val name = contact.getNameToDisplay()
            holder.binding.apply {
                favoriteStripImage.loadBrandedContactImage(contact.photoUri, name)
                favoriteStripName.text = name
                favoriteStripName.setTextColor(context.getProperTextColor())
                root.setOnClickListener { itemClickAction(context.config.onFavoriteClick, contact) }
                root.setOnLongClickListener { actionOpen(contact); true }
            }
        }
    }
}
