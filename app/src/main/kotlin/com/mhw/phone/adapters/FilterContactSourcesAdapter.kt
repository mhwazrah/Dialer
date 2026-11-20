package com.mhw.phone.adapters

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.helpers.SMT_PRIVATE
import com.goodwy.commons.models.contacts.ContactSource
import com.mhw.phone.activities.SimpleActivity
import com.mhw.phone.databinding.ItemFilterContactSourceBinding

class FilterContactSourcesAdapter(
    val activity: SimpleActivity,
    private val contactSources: List<ContactSource>,
    private val displayContactSources: ArrayList<String>
) : RecyclerView.Adapter<FilterContactSourcesAdapter.ViewHolder>() {

    private val selectedKeys = HashSet<Int>()
    private val textColor = activity.getProperTextColor()
    private val primaryColor = activity.getProperPrimaryColor()
    private val backgroundColor = activity.getProperBackgroundColor()

    init {
        contactSources.forEachIndexed { _, contactSource ->
            if (displayContactSources.contains(contactSource.name)) {
                selectedKeys.add(contactSource.hashCode())
            }

            if (contactSource.type == SMT_PRIVATE && displayContactSources.contains(SMT_PRIVATE)) {
                selectedKeys.add(contactSource.hashCode())
            }
        }
    }

    private fun toggleItemSelection(select: Boolean, contactSource: ContactSource, position: Int) {
        if (select) {
            selectedKeys.add(contactSource.hashCode())
        } else {
            selectedKeys.remove(contactSource.hashCode())
        }

        notifyItemChanged(position)
    }

    fun getSelectedContactSources() = contactSources.filter { selectedKeys.contains(it.hashCode()) }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFilterContactSourceBinding.inflate(activity.layoutInflater, parent, false)
        return ViewHolder(binding.root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contactSource = contactSources[position]
        holder.bindView(contactSource)
    }

    override fun getItemCount() = contactSources.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bindView(contactSource: ContactSource): View {
            ItemFilterContactSourceBinding.bind(itemView).apply {
                filterContactSourceCheckbox.isChecked = selectedKeys.contains(contactSource.hashCode())
                filterContactSourceCheckbox.setColors(textColor, primaryColor, backgroundColor)
                val countText = if (contactSource.count >= 0) " (${contactSource.count})" else ""
                filterContactSourceCheckbox.text = "${contactSource.publicName}$countText"
                filterContactSourceHolder.setOnClickListener { viewClicked(contactSource) }

                return root
            }
        }

        // Selection is read at click time, so a stale bind can never invert the toggle.
        private fun viewClicked(contactSource: ContactSource) {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return
            toggleItemSelection(!selectedKeys.contains(contactSource.hashCode()), contactSource, position)
        }
    }
}
