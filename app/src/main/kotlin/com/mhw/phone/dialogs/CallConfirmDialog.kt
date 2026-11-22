package com.mhw.phone.dialogs

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.text.BidiFormatter
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.beVisible
import com.goodwy.commons.extensions.formatPhoneNumber
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.setupDialogStuff
import com.mhw.phone.R
import com.goodwy.commons.activities.BaseSimpleActivity
import com.mhw.phone.databinding.DialogCallConfirmBinding
import com.mhw.phone.extensions.areMultipleSIMsAvailable
import com.mhw.phone.extensions.config
import com.mhw.phone.extensions.getAvailableSIMCardLabels
import com.mhw.phone.extensions.loadBrandedContactImage
import com.mhw.phone.helpers.ContactsRepository

/**
 * "Call this person?": their photo (or letter), name, number and saved SIM, with Cancel and a filled Call button.
 * A soft ring pulses around the photo while it is open. Without a name only the number is shown, large.
 */
class CallConfirmDialog(
    private val activity: BaseSimpleActivity,
    name: String,
    number: String,
    photoUri: String? = null,
    private val onCancel: () -> Unit = {},
    private val onConfirm: () -> Unit,
) {
    private val binding = DialogCallConfirmBinding.inflate(activity.layoutInflater)
    private var dialog: AlertDialog? = null
    private var pulse: ObjectAnimator? = null
    private var confirmed = false

    init {
        val hasName = name.isNotBlank() && name != number
        val photo = photoUri ?: ContactsRepository.snapshot.find(number)?.photoUri.orEmpty()
        binding.apply {
            callConfirmPhoto.outlineProvider = ViewOutlineProvider.BACKGROUND
            callConfirmPhoto.clipToOutline = true
            if (hasName) {
                callConfirmPhoto.loadBrandedContactImage(photo, name)
                callConfirmName.text = name
            } else {
                callConfirmName.beGone()
                callConfirmNumber.textSize = 28f
                callConfirmNumber.alpha = 1f
                callConfirmPhoto.setImageResource(R.drawable.ic_phone_vector)
                callConfirmPhoto.setColorFilter(activity.getProperTextColor())
                val padding = activity.resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.big_margin)
                callConfirmPhoto.setPadding(padding, padding, padding, padding)
            }
            val shownNumber = if (activity.config.formatPhoneNumbers) number.formatPhoneNumber() else number
            callConfirmNumber.text = BidiFormatter.getInstance().unicodeWrap(shownNumber)
            if (number.isEmpty()) callConfirmNumber.beGone() // a contact with several numbers: picked next
            savedSimLabel(number)?.let {
                callConfirmSim.text = it
                callConfirmSim.beVisible()
            }
            callConfirmCancel.setTextColor(activity.getProperTextColor())
            callConfirmCancel.setOnClickListener { dialog?.dismiss() }
            callConfirmCall.setOnClickListener {
                confirmed = true
                dialog?.dismiss()
                onConfirm()
            }
        }

        activity.getAlertDialogBuilder().apply {
            activity.setupDialogStuff(binding.root, this) { alertDialog ->
                dialog = alertDialog
                // The library tints dialog text with the theme colours; the name and number follow them
                listOf(binding.callConfirmName, binding.callConfirmNumber, binding.callConfirmSim)
                    .forEach { it.setTextColor(activity.getProperTextColor()) }
                alertDialog.setOnDismissListener {
                    pulse?.cancel()
                    if (!confirmed) onCancel()
                }
                startPulse(binding.callConfirmRing)
            }
        }
    }

    /** The SIM this number is set to always use, when there is a choice of SIMs. */
    private fun savedSimLabel(number: String): String? {
        val handle = activity.config.getCustomSIM(number).takeIf { activity.areMultipleSIMsAvailable() } ?: return null
        return activity.getAvailableSIMCardLabels().firstOrNull { it.handle == handle }?.label?.substringBefore(" (")
    }

    private fun startPulse(ring: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            ring.beGone()
            return
        }
        pulse = ObjectAnimator.ofPropertyValuesHolder(
            ring,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.3f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.3f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0f),
        ).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            start()
        }
    }
}
