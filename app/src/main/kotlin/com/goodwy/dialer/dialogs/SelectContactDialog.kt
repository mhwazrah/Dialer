package com.goodwy.dialer.dialogs

import android.content.res.Configuration
import android.graphics.Color
import android.view.inputmethod.EditorInfo
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.getProperText
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.commons.views.MySearchMenuTop
import com.goodwy.dialer.R
import com.goodwy.dialer.activities.SimpleActivity
import com.goodwy.dialer.adapters.ContactsAdapter
import com.goodwy.dialer.databinding.DialogSelectContactBinding
import com.goodwy.dialer.extensions.firstCodePointOrNull
import com.goodwy.dialer.extensions.setupWithContacts
import com.goodwy.dialer.extensions.toSearchQuery
import com.goodwy.dialer.helpers.ContactsRepository
import com.goodwy.dialer.helpers.foldArabic

class SelectContactDialog(val activity: SimpleActivity, val contacts: List<Contact>, val callback: (selectedContact: Contact) -> Unit) {
    private val binding by activity.viewBinding(DialogSelectContactBinding::inflate)

    private var dialog: AlertDialog? = null

    init {
        binding.apply {
            letterFastscroller.textColor = activity.getProperTextColor().getColorStateList()
            letterFastscrollerThumb.setupWithFastScroller(letterFastscroller)
            letterFastscrollerThumb.textColor = activity.getProperPrimaryColor().getContrastColor()
            letterFastscrollerThumb.thumbColor = activity.getProperPrimaryColor().getColorStateList()

            letterFastscroller.setupWithContacts(selectContactList) // follows the adapter's list from here on
            updateLetterFastScrollerSize(contacts)
            configureSearchView()

            selectContactList.adapter = ContactsAdapter(
                activity = activity,
                contacts = contacts.toMutableList(),
                recyclerView = selectContactList,
                showIcon = false,
                allowLongClick = false,
                itemClick = {
                    callback(it as Contact)
                    dialog?.dismiss()
                }
            )
        }

        activity.getAlertDialogBuilder()
            .setNegativeButton(R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.choose_contact) { alertDialog ->
                    dialog = alertDialog
                    alertDialog.onBackPressedDispatcher.addCallback(alertDialog) {
                        if (binding.contactSearchView.isSearchOpen) {
                            binding.contactSearchView.closeSearch()
                        } else {
                            isEnabled = false
                            alertDialog.onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            }
    }

    private fun updateLetterFastScrollerSize(contacts: List<Contact>) {
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

    private fun isHighScreenSize(): Boolean {
        return when (activity.resources.configuration.screenLayout
            and Configuration.SCREENLAYOUT_LONG_MASK) {
            Configuration.SCREENLAYOUT_LONG_NO -> false
            else -> true
        }
    }

    private fun configureSearchView() = with(binding.contactSearchView) {
        updateHintText(context.getString(R.string.search_contacts))
        binding.topToolbarSearch.imeOptions = EditorInfo.IME_ACTION_DONE

        toggleHideOnScroll(true)
        setupMenu()
        setSearchViewListeners()
        updateSearchViewUi()
    }

    private fun MySearchMenuTop.updateSearchViewUi() {
        requireToolbar().beInvisible()
        updateColors()
        setBackgroundColor(Color.TRANSPARENT)
        binding.searchBarContainer.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun MySearchMenuTop.setSearchViewListeners() {
        onSearchOpenListener = {
            //updateSearchViewLeftIcon(R.drawable.ic_cross_vector)
        }
        onSearchClosedListener = {
            binding.topToolbarSearch.clearFocus()
            activity.hideKeyboard(binding.topToolbarSearch)
            //updateSearchViewLeftIcon(R.drawable.ic_search_vector)
        }

        onSearchTextChangedListener = { text ->
            filterContactListBySearchQuery(text)
            clearSearch()
        }
    }

//    private fun updateSearchViewLeftIcon(iconResId: Int) = with(binding.root.findViewById<ImageView>(R.id.top_toolbar_search_icon)) {
//        post {
//            setImageResource(iconResId)
//        }
//    }

    private fun filterContactListBySearchQuery(query: String) {
        val adapter = binding.selectContactList.adapter as? ContactsAdapter
        var contactsToShow = contacts
        val fixedText = query.toSearchQuery()
        val searchText = fixedText.foldArabic()
        val shouldNormalize = searchText.normalizeString() == searchText
        if (query.isNotEmpty()) {
            val snapshot = ContactsRepository.snapshot
            val normalizedNumberQuery = if (fixedText.toIntOrNull() != null) fixedText.normalizePhoneNumber() else ""
            contactsToShow = contacts.filter { contact ->
                getProperText(contact.getNameToDisplay(), shouldNormalize).foldArabic().contains(searchText, true) ||
                    (normalizedNumberQuery.isNotEmpty() && contact.phoneNumbers.any { it.normalizedNumber.contains(normalizedNumberQuery, true) }) ||
                    snapshot.listSearchKeys(contact).matchesSecondaryFields(contact, searchText, shouldNormalize)
            }
        }
        checkPlaceholderVisibility(contactsToShow)

        if (adapter?.contacts != contactsToShow) {
            adapter?.updateItems(contactsToShow)
            updateLetterFastScrollerSize(contactsToShow)

            binding.selectContactList.apply {
                post {
                    scrollToPosition(0)
                }
            }
        }
    }

    private fun checkPlaceholderVisibility(contacts: List<Contact>) = with(binding) {
        contactsEmptyPlaceholder.beVisibleIf(contacts.isEmpty())

        if (contactSearchView.isSearchOpen) {
            contactsEmptyPlaceholder.text = activity.getString(R.string.no_items_found)
        }

        letterFastscroller.beVisibleIf(contactsEmptyPlaceholder.isGone())
        letterFastscrollerThumb.beVisibleIf(contactsEmptyPlaceholder.isGone())
    }
}
