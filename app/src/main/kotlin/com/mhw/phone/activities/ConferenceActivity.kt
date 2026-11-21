package com.mhw.phone.activities

import android.graphics.Color
import android.os.Bundle
import android.telecom.Call
import androidx.core.view.WindowCompat
import com.goodwy.commons.extensions.applyColorFilter
import com.goodwy.commons.extensions.viewBinding
import com.goodwy.commons.helpers.NavigationIcon
import com.mhw.phone.adapters.ConferenceCallsAdapter
import com.mhw.phone.databinding.ActivityConferenceBinding
import com.mhw.phone.extensions.AlwaysDarkScreen
import com.mhw.phone.helpers.CallManager
import com.mhw.phone.helpers.CallManagerListener
import com.mhw.phone.models.AudioRoute
import com.mhw.phone.views.CallBackdropDrawable

class ConferenceActivity : SimpleActivity(), AlwaysDarkScreen {
    private val binding by viewBinding(ActivityConferenceBinding::inflate)
    private lateinit var adapter: ConferenceCallsAdapter

    private val callListener = object : CallManagerListener {
        override fun onStateChanged() = refreshParticipants()

        override fun onAudioStateChanged(audioState: AudioRoute) {}

        override fun onPrimaryCallChanged(call: Call) = refreshParticipants()

        override fun onMuteChanged(isMuted: Boolean) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(conferenceList))
            adapter = ConferenceCallsAdapter(this@ConferenceActivity, conferenceList, ArrayList(CallManager.getConferenceCalls())) {}
            conferenceList.adapter = adapter
        }
        CallManager.addListener(callListener)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.conferenceAppbar, NavigationIcon.Arrow)
        binding.conferenceAppbar.setBackgroundColor(Color.TRANSPARENT)
        binding.conferenceToolbar.setBackgroundColor(Color.TRANSPARENT)
        // Always dark, like the call screen it belongs to: the same navy backdrop and white content
        binding.root.background = CallBackdropDrawable(this)
        binding.conferenceToolbar.setTitleTextColor(Color.WHITE)
        // Commons colours the arrow with a colour filter (a tint would be ignored); mutate so other screens keep theirs
        binding.conferenceToolbar.navigationIcon = binding.conferenceToolbar.navigationIcon?.mutate()?.apply {
            applyColorFilter(Color.WHITE)
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        CallManager.removeListener(callListener)
    }

    // A conference needs at least two participants: when it ends or shrinks below that, this screen has nothing to show.
    private fun refreshParticipants() {
        runOnUiThread {
            if (isDestroyed || isFinishing) return@runOnUiThread
            val participants = CallManager.getConferenceCalls()
            if (participants.size < 2) finish() else adapter.updateCalls(participants)
        }
    }

    override fun onBackPressedCompat(): Boolean {
        finish()
        return true
    }
}
