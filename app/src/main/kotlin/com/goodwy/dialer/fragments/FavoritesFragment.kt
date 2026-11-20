package com.goodwy.dialer.fragments

import android.graphics.Color
import android.content.Context
import android.util.AttributeSet
import com.goodwy.dialer.helpers.gson
import com.goodwy.commons.adapters.MyRecyclerViewAdapter
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.commons.views.MyGridLayoutManager
import com.goodwy.commons.views.MyLinearLayoutManager
import com.goodwy.dialer.R
import com.goodwy.dialer.activities.MainActivity
import com.goodwy.dialer.activities.SimpleActivity
import com.goodwy.dialer.adapters.ContactsAdapter
import com.goodwy.dialer.databinding.FragmentFavoritesBinding
import com.goodwy.dialer.databinding.FragmentLettersLayoutBinding
import com.goodwy.dialer.extensions.config
import com.goodwy.dialer.extensions.listedContacts
import com.goodwy.dialer.extensions.launchSendWhatsAppIntent
import com.goodwy.dialer.extensions.setupWithContacts
import com.goodwy.dialer.extensions.toSearchQuery
import com.goodwy.dialer.extensions.startCallWithConfirmationCheck
import com.goodwy.dialer.extensions.startContactDetailsIntent
import com.goodwy.dialer.extensions.startContactEdit
import com.google.gson.reflect.TypeToken
import com.goodwy.dialer.helpers.ContactsRepository
import com.goodwy.dialer.helpers.SWIPE_ACTION_CALL
import com.goodwy.dialer.helpers.SWIPE_ACTION_EDIT
import com.goodwy.dialer.helpers.SWIPE_ACTION_MESSAGE
import com.goodwy.dialer.helpers.SWIPE_ACTION_OPEN
import com.goodwy.dialer.helpers.SWIPE_ACTION_WHATSAPP
import com.goodwy.dialer.interfaces.RefreshItemsListener

class FavoritesFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment<MyViewPagerFragment.LettersInnerBinding>(context, attributeSet),
    RefreshItemsListener {
    private lateinit var binding: FragmentLettersLayoutBinding
    private var allContacts = ArrayList<Contact>()
    private var letterFastScrollerReady = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentLettersLayoutBinding.bind(FragmentFavoritesBinding.bind(this).favoritesFragment)
        innerBinding = LettersInnerBinding(binding)
    }

    override fun setupFragment() {
        binding.root.setBackgroundColor(Color.TRANSPARENT) // let the liquid backdrop show through

        val placeholderResId = if (context.hasPermission(PERMISSION_READ_CONTACTS)) {
            R.string.no_contacts_found
        } else {
            R.string.could_not_access_contacts
        }

        binding.fragmentPlaceholder.text = context.getString(placeholderResId)
        binding.fragmentPlaceholder2.beGone()
        binding.letterFastscrollerThumb.beGone()
        binding.letterFastscroller.beGone()
    }

    override fun setupColors(textColor: Int, primaryColor: Int, accentColor: Int) {
        binding.apply {
            fragmentPlaceholder.setTextColor(textColor)
            (fragmentList.adapter as? MyRecyclerViewAdapter)?.apply {
                updateTextColor(textColor)
                updatePrimaryColor()
                updateBackgroundColor(context.getProperBackgroundColor())
            }

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
                val favorites = context.listedContacts(snapshot.favorites)
                allContacts = activity?.sortFavoritesByCustomOrder(favorites) ?: return@ensureBackgroundThread
                activity?.runOnUiThread {
                    gotContacts(allContacts)
                    callback?.invoke()
                }
            }
        }
    }

    private fun gotContacts(contacts: ArrayList<Contact>) {
        setupLetterFastScroller()
        binding.apply {
            if (contacts.isEmpty()) {
                fragmentPlaceholder.beVisible()
                fragmentList.beGone()
                // The last favourite was removed: the adapter and the dynamic shortcuts must not keep it.
                (fragmentList.adapter as? ContactsAdapter)?.updateItems(contacts)
                (activity as MainActivity).cacheFavorites(contacts)
            } else {
                fragmentPlaceholder.beGone()
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

                updateListAdapter()
            }
        }
    }

    private fun updateListAdapter() {
        val viewType = context.config.viewType
        setViewType(viewType, allContacts.size)

        val currAdapter = binding.fragmentList.adapter as ContactsAdapter?
        if (currAdapter == null) {
            ContactsAdapter(
                activity = activity as SimpleActivity,
                contacts = allContacts,
                recyclerView = binding.fragmentList,
                refreshItemsListener = this,
                showIcon = context.config.onFavoriteClick != SWIPE_ACTION_OPEN,
                viewType = viewType,
                showDeleteButton = false,
                enableDrag = true,
                showNumber = context.baseConfig.showPhoneNumbers,
                itemClick = {
                    itemClickAction(context.config.onFavoriteClick, it as Contact)
                },
                profileIconClick = {
                    activity?.startContactDetailsIntent(it as Contact)
                }).apply {
                binding.fragmentList.adapter = this

                onDragEndListener = {
                    val adapter = binding.fragmentList.adapter
                    if (adapter is ContactsAdapter) {
                        val items = adapter.contacts
                        saveCustomOrderToPrefs(items)
                        (activity as MainActivity).cacheFavorites(items)
                        activity?.findViewById<ContactsFragment>(R.id.contacts_fragment)?.updateFavoritesStrip()
                    }
                }

                onSpanCountListener = { newSpanCount ->
                    context.config.contactsGridColumnCount = newSpanCount
                }
            }

            if (context.areSystemAnimationsEnabled) {
                binding.fragmentList.scheduleLayoutAnimation()
            }
            (activity as MainActivity).cacheFavorites(allContacts)
        } else {
            currAdapter.viewType = viewType
            currAdapter.updateItems(allContacts)
            (activity as MainActivity).cacheFavorites(allContacts)
        }
    }

    fun columnCountChanged() {
        if (binding.fragmentList.layoutManager is MyGridLayoutManager) (binding.fragmentList.layoutManager as MyGridLayoutManager).spanCount = context!!.config.contactsGridColumnCount
        binding.fragmentList.adapter?.apply {
            notifyItemRangeChanged(0, allContacts.size)
        }
    }

    private fun saveCustomOrderToPrefs(items: List<Contact>) {
        activity?.apply {
            val orderIds = items.map { it.contactId }
            val orderGsonString = gson.toJson(orderIds)
            config.favoritesContactsOrder = orderGsonString
            allContacts = ArrayList(items)
        }
    }

    // Once is enough: the scroller follows the adapter's list (see setupWithContacts).
    private fun setupLetterFastScroller() {
        if (letterFastScrollerReady) return
        letterFastScrollerReady = true
        binding.letterFastscroller.setupWithContacts(binding.fragmentList)
    }

    override fun rebuildAdapter() {
        if (binding.fragmentList.adapter == null) return
        binding.fragmentList.adapter = null
        gotContacts(allContacts)
    }

    override fun onSearchClosed() {
        binding.fragmentPlaceholder.beVisibleIf(allContacts.isEmpty())
        (binding.fragmentList.adapter as? ContactsAdapter)?.updateItems(allContacts)
    }

    override fun onSearchQueryChanged(text: String) {
        val fixedText = text.toSearchQuery()
        val shouldNormalize = fixedText.normalizeString() == fixedText
        val snapshot = ContactsRepository.snapshot
        val normalizedNumberQuery = if (fixedText.toLongOrNull() != null) fixedText.normalizePhoneNumber() else ""
        val contacts = allContacts.filter { contact ->
            getProperText(contact.getNameToDisplay(), shouldNormalize).contains(fixedText, true) ||
                (normalizedNumberQuery.isNotEmpty() && contact.phoneNumbers.any { it.normalizedNumber.contains(normalizedNumberQuery, true) }) ||
                snapshot.listSearchKeys(contact).matchesSecondaryFields(contact, fixedText, shouldNormalize)
        }.sortedByDescending {
            it.name.startsWith(fixedText, true)
        }.toMutableList() as ArrayList<Contact>

        binding.fragmentPlaceholder.beVisibleIf(contacts.isEmpty())
        (binding.fragmentList.adapter as? ContactsAdapter)?.updateItems(contacts, fixedText)
    }

    private fun setViewType(viewType: Int, size: Int = 0) {
        val spanCount = context.config.contactsGridColumnCount
        val current = binding.fragmentList.layoutManager
        // Keep the layout manager (and the scroll position) unless the view type changed
        if (current != null && (current is MyGridLayoutManager) == (viewType == VIEW_TYPE_GRID)) {
            (current as? MyGridLayoutManager)?.spanCount = spanCount
            return
        }

        val layoutManager = if (viewType == VIEW_TYPE_GRID) {
            binding.letterFastscroller.beGone()
            MyGridLayoutManager(context, spanCount)
        } else {
            binding.letterFastscroller.beGone()
//            binding.letterFastscroller.beVisibleIf(size > 10)
//            if (size > 50) binding.letterFastscroller.textAppearanceRes = R.style.DialpadLetterStyleTiny
//            else if (size > 30) binding.letterFastscroller.textAppearanceRes = R.style.DialpadLetterStyleSmall
            MyLinearLayoutManager(context)
        }
        binding.fragmentList.layoutManager = layoutManager
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
}

/** Favourites in the user's custom order when one is selected (shared by the Favourites tab and the Contacts strip). */
fun Context.sortFavoritesByCustomOrder(favorites: List<Contact>): ArrayList<Contact> {
    val favoritesOrder = config.favoritesContactsOrder
    if (!config.isCustomOrderSelected || favoritesOrder.isEmpty()) return ArrayList(favorites)

    val orderList = try {
        gson.fromJson<List<String>>(favoritesOrder, object : TypeToken<List<String>>() {}.type)
    } catch (_: Exception) {
        null
    } ?: return ArrayList(favorites)
    val map = orderList.withIndex().associate { it.value to it.index }
    return ArrayList(favorites.sortedBy { map[it.contactId.toString()] })
}
