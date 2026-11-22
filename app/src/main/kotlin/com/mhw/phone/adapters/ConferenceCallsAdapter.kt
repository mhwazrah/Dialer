package com.mhw.phone.adapters

import android.graphics.Color
import com.mhw.phone.extensions.AlwaysDarkScreen
import android.annotation.SuppressLint
import android.graphics.drawable.LayerDrawable
import android.telecom.Call
import android.view.Menu
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import com.bumptech.glide.Glide
import com.goodwy.commons.adapters.MyRecyclerViewAdapter
import com.goodwy.commons.extensions.*
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.helpers.LOWER_ALPHA
import com.goodwy.commons.helpers.SimpleContactsHelper
import com.goodwy.commons.views.MyRecyclerView
import com.mhw.phone.R
import com.mhw.phone.activities.SimpleActivity
import com.mhw.phone.databinding.ItemConferenceCallBinding
import com.mhw.phone.extensions.hasCapability
import com.mhw.phone.extensions.loadBrandedContactImage
import com.mhw.phone.helpers.getCallContact
import com.mhw.phone.models.CallContact
import kotlin.math.abs

class ConferenceCallsAdapter(
    activity: SimpleActivity, recyclerView: MyRecyclerView, val data: ArrayList<Call>, itemClick: (Any) -> Unit
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick) {
    // The conference screen is always dark, like the call screen
    private val contentColor by lazy { if (activity is AlwaysDarkScreen) Color.WHITE else textColor }


    override fun actionItemPressed(id: Int) {}

    override fun getActionMenuId(): Int = 0

    override fun getIsItemSelectable(position: Int): Boolean = false

    override fun getItemCount(): Int = data.size

    override fun getItemKeyPosition(key: Int): Int = -1

    override fun getItemSelectionKey(position: Int): Int? = null

    override fun getSelectableItemCount(): Int = data.size

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun prepareActionMode(menu: Menu) {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return createViewHolder(ItemConferenceCallBinding.inflate(layoutInflater, parent, false).root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val call = data[position]
        holder.bindView(call, allowSingleClick = false, allowLongClick = false) { itemView, _ ->
            ItemConferenceCallBinding.bind(itemView).apply {
                itemConferenceCallName.tag = call
                getCallContact(itemView.context, call) { callContact ->
                    root.post {
                        // Skip when the holder was rebound to another call, or when the lookup outlived the screen (it is
                        // relaunched right after opening, and Glide throws for a destroyed activity)
                        if (itemConferenceCallName.tag !== call || activity.isDestroyed) return@post
                        itemConferenceCallName.text = callContact.name.ifEmpty { itemView.context.getString(R.string.unknown_caller) }
                        bindContactImage(itemConferenceCallImage, callContact)
                    }
                }

                val canSeparate = call.hasCapability(Call.Details.CAPABILITY_SEPARATE_FROM_CONFERENCE)
                val canDisconnect = call.hasCapability(Call.Details.CAPABILITY_DISCONNECT_FROM_CONFERENCE)
                itemConferenceCallName.setTextColor(contentColor)
                itemConferenceCallSplit.applyColorFilter(contentColor)
                itemConferenceCallSplit.isEnabled = canSeparate
                itemConferenceCallSplit.alpha = if (canSeparate) 1.0f else LOWER_ALPHA
                itemConferenceCallSplit.setOnClickListener {
                    call.splitFromConference()
                    removeCall(call)
                }

                itemConferenceCallSplit.setOnLongClickListener {
                    if (!it.contentDescription.isNullOrEmpty()) {
                        root.context.toast(it.contentDescription.toString())
                    }
                    true
                }
                itemConferenceCallEnd.isEnabled = canDisconnect
                itemConferenceCallEnd.alpha = if (canDisconnect) 1.0f else LOWER_ALPHA
                itemConferenceCallEnd.setOnClickListener {
                    call.disconnect()
                    removeCall(call)
                }

                itemConferenceCallEnd.setOnLongClickListener {
                    if (!it.contentDescription.isNullOrEmpty()) {
                        root.context.toast(it.contentDescription.toString())
                    }
                    true
                }
            }
        }
        bindViewHolder(holder)
    }

    private fun bindContactImage(image: ImageView, callContact: CallContact) {
        if (callContact.number == callContact.name || callContact.isABusinessCall || callContact.isVoiceMail) {
            val drawable = when {
                callContact.isVoiceMail -> {
                    @SuppressLint("UseCompatLoadingForDrawables")
                    // mutate(): the tint below must not leak into the shared drawable state
                    val drawableVoicemail = resources.getDrawable(R.drawable.placeholder_voicemail, activity.theme).mutate()
                    if (baseConfig.useColoredContacts) {
                        val letterBackgroundColors = activity.getLetterBackgroundColors()
                        val color = letterBackgroundColors[abs(callContact.name.hashCode()) % letterBackgroundColors.size].toInt()
                        (drawableVoicemail as LayerDrawable).findDrawableByLayerId(R.id.placeholder_contact_background).applyColorFilter(color)
                    }
                    drawableVoicemail
                }
                callContact.isABusinessCall -> SimpleContactsHelper(activity).getColoredCompanyIcon(callContact.name)
                else -> SimpleContactsHelper(activity).getColoredContactIcon(callContact.name)
            }
            image.setImageDrawable(drawable)
        } else {
            image.loadBrandedContactImage(callContact.photoUri, callContact.name)
        }
    }

    // Resolve the row at click time: the position captured at bind time goes stale once other rows are removed.
    private fun removeCall(call: Call) {
        val index = data.indexOf(call)
        if (index == -1) return
        data.removeAt(index)
        notifyItemRemoved(index)
        if (data.size == 1) activity.finish()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateCalls(calls: List<Call>) {
        data.clear()
        data.addAll(calls)
        notifyDataSetChanged()
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (!activity.isDestroyed && !activity.isFinishing) {
            ItemConferenceCallBinding.bind(holder.itemView).apply {
                Glide.with(activity).clear(itemConferenceCallImage)
            }
        }
    }
}
