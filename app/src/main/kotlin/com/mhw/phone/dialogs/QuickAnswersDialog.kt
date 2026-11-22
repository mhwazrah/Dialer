package com.mhw.phone.dialogs

import android.content.DialogInterface.BUTTON_POSITIVE
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.goodwy.commons.extensions.beVisibleIf
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.setupDialogStuff
import com.goodwy.commons.views.MyTextView
import com.mhw.phone.R
import com.mhw.phone.activities.SimpleActivity
import com.mhw.phone.databinding.DialogQuickAnswersBinding
import com.mhw.phone.extensions.config

/** The decline-with-message replies: tap one to edit or delete it, Add for a new one. Any number, saved on every change. */
class QuickAnswersDialog(private val activity: SimpleActivity, private val onChanged: () -> Unit) {
    private val binding = DialogQuickAnswersBinding.inflate(activity.layoutInflater)

    init {
        showAnswers()
        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.add_quick_answer, null)
            .setNegativeButton(com.goodwy.commons.R.string.ok, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, titleText = activity.getString(R.string.quick_answers)) { dialog ->
                    // Adding keeps this dialog open, so several replies can be added in a row
                    dialog.getButton(BUTTON_POSITIVE).setOnClickListener {
                        ChangeTextDialog(activity, currentText = "") { text -> save { if (text.isNotBlank()) add(text) } }
                    }
                }
            }
    }

    private fun showAnswers() {
        val list = binding.quickAnswersList
        list.removeViews(1, list.childCount - 1)
        val answers = activity.config.quickAnswers
        binding.quickAnswersEmpty.beVisibleIf(answers.isEmpty())
        answers.forEachIndexed { index, answer ->
            list.addView(answerRow(answer) { editAnswer(index, answer) })
        }
    }

    private fun answerRow(answer: String, onClick: () -> Unit) = MyTextView(activity).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val padding = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.activity_margin)
        setPadding(padding, padding, padding, padding)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(com.goodwy.commons.R.dimen.bigger_text_size))
        setTextColor(activity.getProperTextColor())
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setBackgroundResource(R.drawable.ripple_all_corners_11dp)
        text = answer
        setOnClickListener { onClick() }
    }

    // ChangeTextDialog answers "" for its neutral button, here Delete
    private fun editAnswer(index: Int, answer: String) {
        val delete = com.goodwy.commons.R.string.delete
        ChangeTextDialog(activity, currentText = answer, showNeutralButton = true, neutralTextRes = delete) { text ->
            save { if (text.isBlank()) removeAt(index) else set(index, text) }
        }
    }

    private fun save(change: ArrayList<String>.() -> Unit) {
        activity.config.quickAnswers = activity.config.quickAnswers.apply(change)
        showAnswers()
        onChanged()
    }
}
