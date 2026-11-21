package com.mhw.phone.activities

import android.graphics.Color
import android.os.Bundle
import android.telecom.Call
import com.goodwy.commons.extensions.viewBinding
import com.goodwy.commons.helpers.NavigationIcon
import com.mhw.phone.adapters.ConferenceCallsAdapter
import com.mhw.phone.databinding.ActivityConferenceBinding
import com.mhw.phone.helpers.CallManager
import com.mhw.phone.helpers.CallManagerListener
import com.mhw.phone.models.AudioRoute

class ConferenceActivity : SimpleActivity() {
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
