package com.mhw.phone.dialogs

import android.telecom.PhoneAccountHandle
import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.*
import com.mhw.phone.R
import com.mhw.phone.databinding.DialogSelectSimButtonBinding
import com.mhw.phone.extensions.config
import com.mhw.phone.extensions.getAvailableSIMCardLabels

class SelectSimButtonDialog(
    val activity: BaseSimpleActivity,
    val phoneNumber: String,
    onDismiss: () -> Unit = {},
    val callback: (handle: PhoneAccountHandle?, label: String?) -> Unit
) {
    private var dialog: AlertDialog? = null
    private val binding by activity.viewBinding(DialogSelectSimButtonBinding::inflate)

    init {

        val sim1Color = activity.baseConfig.simIconsColors[1]
        val sim2Color = activity.baseConfig.simIconsColors[2]
        val cancelColor = 0xFFEB5545.toInt()
        val padding = (2 * activity.resources.displayMetrics.density).toInt()

        activity.getAvailableSIMCardLabels().forEachIndexed { index, SIMAccount ->
            val indexText = index + 1
            if (indexText == 1) {
                binding.sim1Button.apply {
                    val drawable = resources.getColoredDrawableWithColor(activity, R.drawable.button_gray_bg, sim1Color)
                    background = drawable
                    setPadding(padding, padding, padding, padding)
                    setTextColor(sim1Color.getContrastColor())
                    setOnClickListener { selectedSIM(SIMAccount.handle, SIMAccount.label) }
                }
            } else if (indexText == 2) {
                binding.sim2Button.apply {
                    val drawable = resources.getColoredDrawableWithColor(activity, R.drawable.button_gray_bg, sim2Color)
                    background = drawable
                    setPadding(padding, padding, padding, padding)
                    setTextColor(sim2Color.getContrastColor())
                    setOnClickListener { selectedSIM(SIMAccount.handle, SIMAccount.label) }
                }
            }
        }

        activity.getAlertDialogBuilder()
            .apply {
                val title = activity.getString(R.string.select_sim)
                activity.setupDialogStuff(binding.root, this, titleText = title) { alertDialog ->
                    dialog = alertDialog
                    alertDialog.setOnDismissListener { onDismiss() }
                }
            }

        binding.cancelButton.apply {
            val drawable = resources.getColoredDrawableWithColor(activity, R.drawable.button_gray_bg, cancelColor)
            background = drawable
            setPadding(padding, padding, padding, padding)
            setTextColor(cancelColor.getContrastColor())
            setOnClickListener {
                dialog?.dismiss()
            }
        }

        binding.selectSimRememberHolder.setOnClickListener {
            binding.selectSimRemember.toggle()
        }
    }

    private fun selectedSIM(handle: PhoneAccountHandle, label: String) {
        if (binding.selectSimRemember.isChecked) {
            activity.config.saveCustomSIM(phoneNumber, handle)
        }

        callback(handle, label)
        dialog?.dismiss()
    }
}
