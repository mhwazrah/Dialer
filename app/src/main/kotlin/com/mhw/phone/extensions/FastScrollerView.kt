package com.mhw.phone.extensions

import androidx.recyclerview.widget.RecyclerView
import com.reddit.indicatorfastscroll.FastScrollItemIndicator
import com.reddit.indicatorfastscroll.FastScrollerView
import com.mhw.phone.adapters.ContactsAdapter

/**
 * The index letters follow whatever the [ContactsAdapter] on [recyclerView] holds at the time, so call this once:
 * the scroller rebuilds itself when the adapter notifies a change or the adapter is swapped.
 */
fun FastScrollerView.setupWithContacts(recyclerView: RecyclerView) = setupWithRecyclerView(recyclerView, { position ->
    val contact = (recyclerView.adapter as? ContactsAdapter)?.contacts?.getOrNull(position)
    FastScrollItemIndicator.Text(contact?.getFirstLetter() ?: "")
})
