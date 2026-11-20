package com.mhw.phone.fragments

import android.content.Context
import android.util.AttributeSet
import android.widget.RelativeLayout
import com.goodwy.commons.adapters.MyRecyclerViewAdapter
import com.goodwy.commons.adapters.MyRecyclerViewListAdapter
import com.goodwy.commons.extensions.getProperAccentColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.helpers.SORT_BY_FIRST_NAME
import com.goodwy.commons.helpers.SORT_BY_SURNAME
import com.goodwy.commons.views.MyRecyclerView
import com.mhw.phone.activities.MainActivity
import com.mhw.phone.activities.SimpleActivity
import com.mhw.phone.databinding.FragmentLettersLayoutBinding
import com.mhw.phone.databinding.FragmentRecentsBinding
import com.mhw.phone.extensions.config
import com.mhw.phone.helpers.Config

abstract class MyViewPagerFragment<BINDING : MyViewPagerFragment.InnerBinding>(context: Context, attributeSet: AttributeSet) :
    RelativeLayout(context, attributeSet) {
    protected var activity: SimpleActivity? = null
    protected lateinit var innerBinding: BINDING
    private lateinit var config: Config

    fun setupFragment(activity: SimpleActivity) {
        config = activity.config
        if (this.activity == null) {
            this.activity = activity

            setupFragment()
            setupColors(
                textColor = activity.getProperTextColor(),
                primaryColor = activity.getProperPrimaryColor(),
                accentColor = activity.getProperAccentColor()
            )
        }
    }

    fun finishActMode() {
        (innerBinding.fragmentList?.adapter as? MyRecyclerViewAdapter)?.finishActMode()
        (innerBinding.recentsList?.adapter as? MyRecyclerViewListAdapter<*>)?.finishActMode()
    }

    // Drops the row adapter so it is rebuilt from the current display settings (the loaded data is kept).
    abstract fun rebuildAdapter()

    abstract fun setupFragment()

    abstract fun setupColors(textColor: Int, primaryColor: Int, accentColor: Int)

    abstract fun onSearchClosed()

    abstract fun onSearchQueryChanged(text: String)

    abstract fun myRecyclerView(): MyRecyclerView

    interface InnerBinding {
        val fragmentList: MyRecyclerView?
        val recentsList: MyRecyclerView?
    }

    class LettersInnerBinding(val binding: FragmentLettersLayoutBinding) : InnerBinding {
        override val fragmentList: MyRecyclerView = binding.fragmentList
        override val recentsList = null
    }

    class RecentsInnerBinding(val binding: FragmentRecentsBinding) : InnerBinding {
        override val fragmentList = null
        override val recentsList = binding.recentsList
    }
}
