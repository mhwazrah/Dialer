package com.mhw.phone.activities

import android.Manifest
import android.annotation.SuppressLint
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.WallpaperManager
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.core.content.ContextCompat
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.*
import android.telecom.Call
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Property
import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.allViews
import androidx.core.view.children
import androidx.core.view.isVisible
import com.goodwy.commons.dialogs.ConfirmationAdvancedDialog
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.SimpleListItem
import com.mhw.phone.R
import com.mhw.phone.databinding.ActivityCallBinding
import com.mhw.phone.dialogs.ChangeTextDialog
import com.mhw.phone.extensions.*
import com.mhw.phone.helpers.*
import com.mhw.phone.views.CallBackdropDrawable
import com.mhw.phone.models.*
import com.mikhaellopez.rxanimation.*
import io.reactivex.rxjava3.disposables.CompositeDisposable
import com.mikhaellopez.rxanimation.fadeIn
import com.mikhaellopez.rxanimation.fadeOut
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.get
import androidx.core.view.size
import com.mikhaellopez.rxanimation.alpha
import com.mikhaellopez.rxanimation.height
import com.mikhaellopez.rxanimation.width


class CallActivity : SimpleActivity(), AlwaysDarkScreen {
    companion object {
        // Audio-route ints (CallAudioState.ROUTE_*) max out below 16; offset Bluetooth
        // per-device menu ids well above that so they can't collide.
        private const val BLUETOOTH_DEVICE_MENU_ID_BASE = 1000
        private const val BT_CONNECT_REQUEST_CODE = 1001

        fun getStartIntent(context: Context, needSelectSIM: Boolean = false): Intent {
            val openAppIntent = Intent(context, CallActivity::class.java)
            openAppIntent.putExtra(NEED_SELECT_SIM, needSelectSIM)
            //Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT --removed it, it can cause a full screen ringing instead of notifications
            openAppIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            return openAppIntent
        }

        // Whether the call screen is currently in the foreground. CallService uses this to
        // detect that its direct activity start was silently blocked (OEM background-activity
        // restrictions) so it can escalate the notification instead.
        var isActivityVisible = false
            private set
    }

    private val binding by viewBinding(ActivityCallBinding::inflate)
    private var callBackdrop: CallBackdropDrawable? = null

    private val isMicrophoneOff: Boolean get() = CallManager.isMicrophoneMuted
    private var appliedMicrophoneOff: Boolean? = null
    private var dragIconRes = 0
    private var wallpaperBlurRequested = false
    private var isCallEnded = false
    private var callContact: CallContact? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var bluetoothPermissionRequested = false
    private var callDuration = 0
    private val callContactAvatarHelper by lazy { CallContactAvatarHelper(this) }
    private val callDurationHandler = Handler(Looper.getMainLooper())
    private val arrowAnimators = HashMap<ImageView, ObjectAnimator>()
    private val arrowStarters = mutableListOf<() -> Unit>()
    private val animationDisposables = CompositeDisposable()
    private var dragDownX = 0f
    private var needSelectSIM = false //true - if the call is called from a third-party application not via ACTION_CALL, for example, this is how MIUI applications do it.
    private var simSelectionRequested = false
    private var audioRoutePopupMenu: PopupMenu? = null
    private var needHapticFeedback = true

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        addLockScreenFlags()
        super.onCreate(savedInstanceState)
        updateSystemBarsAppearance = false

        setContentView(binding.root)

        if (CallManager.getPhoneState() == NoCall) {
            finish()
            return
        }

        setupEdgeToEdge(
            padTopSystem = listOf(binding.callHolder),
            padBottomSystem = listOf(binding.callHolder),
            //moveTopSystem = listOf(binding.callerAvatar, binding.onHoldStatusHolder)
        )

        needSelectSIM = intent.getBooleanExtra(NEED_SELECT_SIM, false)
        if (needSelectSIM) CallManager.getPrimaryCall()?.details?.handle?.let { initOutgoingCall(it) }

        initButtons()

        CallManager.addListener(callCallback)

        if (config.backgroundCallScreen == TRANSPARENT_BACKGROUND) checkPermission()

        val configBackgroundCallScreen = config.backgroundCallScreen
        // The call screen is always dark: light bar icons and white content on every background
        window.setSystemBarsAppearance(Color.BLACK)
        binding.apply {
            callHolder.allViews.filterIsInstance<TextView>().forEach { it.setTextColor(Color.WHITE) }
            holdStatusLabel.setTextColor(getColor(R.color.red_missed)) // semantic red, as in the layout

            arrayOf(
                callToggleMicrophone, callToggleSpeaker, callDialpad, /*dialpadClose,*/ callSimImage, callDetails,
                callToggleHold, callAddContact, callAdd, callSwap, callMerge, callInfo, addCallerNote, imageView,
                dialpadInclude.dialpadAsterisk, dialpadInclude.dialpadHashtag, callMessage, callRemind
            ).forEach {
                it.applyColorFilter(Color.WHITE)
            }

            callSimId.setTextColor(Color.WHITE.getContrastColor())
        }

        if (configBackgroundCallScreen != THEME_BACKGROUND) {

            if (configBackgroundCallScreen == BLACK_BACKGROUND) {
                binding.callHolder.setBackgroundColor(Color.BLACK)
            } else if (configBackgroundCallScreen == TRANSPARENT_BACKGROUND) {
                if (!isTiramisuPlus()) {
                    if (hasPermission(PERMISSION_READ_STORAGE) && !wallpaperBlurRequested) {
                        wallpaperBlurRequested = true
                        // Decoding and blurring the wallpaper is slow: keep it off the main thread
                        ensureBackgroundThread {
                            try {
                                val wallpaperManager = WallpaperManager.getInstance(this)
                                @SuppressLint("MissingPermission")
                                val wallpaperBlur = BlurFactory.fileToBlurBitmap(wallpaperManager.drawable!!, this, 0.2f, 25f)
                                if (wallpaperBlur != null) runOnUiThread { if (!isDestroyed) applyWallpaperBlur(wallpaperBlur) }
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }
        } else {
            //THEME_BACKGROUND
            // The glass panels refract callHolder, so it carries the shared backdrop itself
            binding.callHolder.background = CallBackdropDrawable(this).also { callBackdrop = it }
            // Rings pulse out of the avatar and stay above the caller's name. Measured on call_holder's layout pass
            // (all children placed); the avatar and name may sit in nested holders (layout-small)
            binding.callHolder.addOnLayoutChangeListener { holder, _, _, _, _, _, _, _, _ ->
                val avatar = Rect().also { binding.callerAvatar.getDrawingRect(it) }
                val name = Rect().also { binding.callerNameLabel.getDrawingRect(it) }
                (holder as ViewGroup).offsetDescendantRectToMyCoords(binding.callerAvatar, avatar)
                holder.offsetDescendantRectToMyCoords(binding.callerNameLabel, name)
                callBackdrop?.setRings(avatar.exactCenterX(), avatar.exactCenterY(), avatar.width() / 2f, name.top.toFloat())
            }
            binding.dialpadInput.disableKeyboard()
        }
        // Keep an enlarged photo clear of the buttons (see fitEnlargedAvatar); posted, so it never resizes mid-layout
        binding.callHolder.addOnLayoutChangeListener { holder, _, _, _, _, _, _, _, _ -> holder.post { fitEnlargedAvatar() } }
        updateCallContactInfo(CallManager.getPrimaryCall())

        binding.apply {
            arrayOf(
                callToggleMicrophone, callToggleSpeaker, callToggleHold, onHoldStatusHolder,
                callRemind, callMessage,
                callDialpadHolder, callAddContactHolder, callAddHolder, callSwapHolder, callMergeHolder,
                callAcceptAndDecline
            ).forEach {
                it.background.applyColorFilter(Color.GRAY)
                it.background.alpha = 60
            }
            arrayOf(
                dialpadInclude.dialpad0Holder, dialpadInclude.dialpad1Holder, dialpadInclude.dialpad2Holder, dialpadInclude.dialpad3Holder,
                dialpadInclude.dialpad4Holder, dialpadInclude.dialpad5Holder, dialpadInclude.dialpad6Holder, dialpadInclude.dialpad7Holder,
                dialpadInclude.dialpad8Holder, dialpadInclude.dialpad9Holder, dialpadInclude.dialpadAsteriskHolder, dialpadInclude.dialpadHashtagHolder
            ).forEach {
                it.foreground.applyColorFilter(Color.GRAY)
                it.foreground.alpha = 60
            }


            if (config.hideDialpadLetters) {
                arrayOf(
                    dialpadInclude.dialpad1Letters,
                    dialpadInclude.dialpad2Letters,
                    dialpadInclude.dialpad3Letters,
                    dialpadInclude.dialpad4Letters,
                    dialpadInclude.dialpad5Letters,
                    dialpadInclude.dialpad6Letters,
                    dialpadInclude.dialpad7Letters,
                    dialpadInclude.dialpad8Letters,
                    dialpadInclude.dialpad9Letters
                ).forEach {
                    it.beGone()
                }
            }
        }

        val isSmallScreen =
            resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK == Configuration.SCREENLAYOUT_SIZE_SMALL
        if (config.callButtonStyle == IOS17 || isSmallScreen) {
            binding.callEndLabel.beVisible()
            binding.callAddContactHolder.beGone()

            val callAddHolderParams = binding.callAddHolder.layoutParams as ConstraintLayout.LayoutParams
            callAddHolderParams.topToTop = binding.callEnd.id
            callAddHolderParams.bottomToBottom = binding.callEnd.id
            callAddHolderParams.topMargin = 0
            binding.callAddHolder.requestLayout()
            val callSwapHolderParams = binding.callSwapHolder.layoutParams as ConstraintLayout.LayoutParams
            callSwapHolderParams.topToTop = binding.callEnd.id
            callSwapHolderParams.bottomToBottom = binding.callEnd.id
            callSwapHolderParams.topMargin = 0
            binding.callSwapHolder.requestLayout()

            val marginStartEnd = resources.getDimension(R.dimen.margin_button_horizontal).toInt()
            val callToggleHoldParams = binding.callToggleHold.layoutParams as ConstraintLayout.LayoutParams
            callToggleHoldParams.topToTop = binding.callEnd.id
            callToggleHoldParams.bottomToBottom = binding.callEnd.id
            callToggleHoldParams.leftToRight = binding.callEnd.id
            callToggleHoldParams.topMargin = 0
            callToggleHoldParams.marginStart = marginStartEnd
            binding.callToggleHold.requestLayout()
            val callMergeHolderParams = binding.callMergeHolder.layoutParams as ConstraintLayout.LayoutParams
            callMergeHolderParams.topToTop = binding.callEnd.id
            callMergeHolderParams.bottomToBottom = binding.callEnd.id
            callMergeHolderParams.leftToRight = binding.callEnd.id
            callMergeHolderParams.topMargin = 0
            callMergeHolderParams.marginStart = marginStartEnd
            binding.callMergeHolder.requestLayout()

            val marginBottom =
                if (isSmallScreen) resources.getDimension(R.dimen.call_button_row_margin_small).toInt()
                else resources.getDimension(R.dimen.call_button_row_margin).toInt()
            val callDialpadHolderParams = binding.callDialpadHolder.layoutParams as ConstraintLayout.LayoutParams
            callDialpadHolderParams.topToTop = -1
            callDialpadHolderParams.bottomToBottom = -1
            callDialpadHolderParams.bottomToTop = binding.callEnd.id
            callDialpadHolderParams.bottomMargin = marginBottom
            binding.callDialpadHolder.requestLayout()
        }
        if (isSmallScreen) {
            binding.apply {
                arrayOf(
                    callDeclineLabel, callAcceptLabel, callMessageLabel, callRemindLabel,
                    callToggleMicrophoneLabel, callDialpadLabel, callToggleSpeakerLabel, callAddLabel,
                    callSwapLabel, callMergeLabel, callToggleLabel, callAddContactLabel, callEndLabel
                ).forEach {
                    it.beGone()
                }
            }
        }

        if (config.flashForAlerts) {
            val phoneState = CallManager.getPhoneState()
            if (phoneState is SingleCall) {
                val call = phoneState.call
                val isDeviceLocked = !powerManager.isInteractive //|| keyguardManager.isDeviceLocked
                if (!call.isOutgoing() && isDeviceLocked) MyCameraImpl.newInstance(this).toggleSOS()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun initOutgoingCall(callNumber: Uri) {
        try {
            getHandleToUse(intent, callNumber.toString()) { handle ->
                if (handle != null) {
                    CallManager.getPrimaryCall()?.phoneAccountSelected(handle, false)
                }
            }
        } catch (e: Exception) {
            showErrorToast(e)
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        needSelectSIM = intent.getBooleanExtra(NEED_SELECT_SIM, false)
        if (needSelectSIM) CallManager.getPrimaryCall()?.details?.handle?.let { initOutgoingCall(it) }
        updateState()
    }

    override fun onResume() {
        super.onResume()
        isActivityVisible = true
        updateState()
    }

    override fun onPause() {
        super.onPause()
        isActivityVisible = false
    }

    // The backdrop animation runs only while the screen is visible and the call is ringing
    override fun onStart() {
        super.onStart()
        if (binding.incomingCallHolder.isVisible()) callBackdrop?.startRinging()
    }

    override fun onStop() {
        super.onStop()
        callBackdrop?.stopRinging()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingSuperCall", "Wakelock")
    override fun onDestroy() {
        super.onDestroy()
        CallManager.removeListener(callCallback)
        callDurationHandler.removeCallbacksAndMessages(null)
        cancelArrowAnimations()
        callBackdrop?.stopRinging()
        animationDisposables.dispose()
        disableProximitySensor()

        if (isOreoMr1Plus()) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        }

        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (config.flashForAlerts) MyCameraImpl.newInstance(this).stopSOS()
    }

    override fun onBackPressedCompat(): Boolean {
        if (binding.dialpadWrapper.isVisible()) {
            hideDialpad()
            return true
        }

        if (config.backPressedEndCall) {
            endCall()
            return true
        }

        // Allow minimizing active call - user can return via notification
        return false
    }

    private fun initButtons() = binding.apply {
        when (config.answerStyle) {
            ANSWER_SLIDER, ANSWER_SLIDER_OUTLINE -> {
                arrayOf(
                    callDecline, callDeclineLabel,
                    callAccept, callAcceptLabel,
                    callDraggableVertical, callUpArrow, callDownArrow
                ).forEach {
                    it.beGone()
                }
                handleSwipe()
            }
            ANSWER_SLIDER_VERTICAL -> {
                arrayOf(
                    callDecline, callDeclineLabel,
                    callAccept, callAcceptLabel,
                    callDraggable, callDraggableBackground,
                    callLeftArrow, callRightArrow
                ).forEach {
                    it.beGone()
                }
                handleSwipeVertical()
            }
            else -> {
                arrayOf(
                    callDraggable, callDraggableBackground, callDraggableVertical,
                    callLeftArrow, callRightArrow,
                    callUpArrow, callDownArrow
                ).forEach {
                    it.beGone()
                }

                callDecline.setOnClickListener {
                    endCall()
                }

                callAccept.setOnClickListener {
                    acceptCall()
                }
            }
        }

        callToggleMicrophone.setOnClickListener {
            toggleMicrophone()
            maybePerformDialpadHapticFeedback(it)
        }

        callToggleSpeaker.setOnClickListener {
            changeCallAudioRoute()
            maybePerformDialpadHapticFeedback(it)
        }

        callToggleSpeaker.setOnLongClickListener {
//            if (CallManager.getCallAudioRoute() == AudioRoute.BLUETOOTH) {
//                openBluetoothSettings()
            val supportAudioRoutes = CallManager.getSupportedAudioRoutes()
            if (supportAudioRoutes.size > 2) {
                CallManager.toggleSpeakerRoute()
            }
            else toast(callToggleSpeaker.contentDescription.toString())
            maybePerformDialpadHapticFeedback(it)
            true
        }

        callDialpadHolder.setOnClickListener {
            toggleDialpadVisibility()
            maybePerformDialpadHapticFeedback(it)
        }

        dialpadClose.setOnClickListener {
            hideDialpad()
            maybePerformDialpadHapticFeedback(it)
        }

        callAddHolder.setOnClickListener {
            Intent(applicationContext, DialpadActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                startActivity(this)
            }
            maybePerformDialpadHapticFeedback(it)
        }

        callSwapHolder.setOnClickListener {
            CallManager.swap()
            maybePerformDialpadHapticFeedback(it)
        }

        callMergeHolder.setOnClickListener {
            CallManager.merge()
            maybePerformDialpadHapticFeedback(it)
        }

        callInfo.setOnClickListener {
            startActivity(Intent(this@CallActivity, ConferenceActivity::class.java))
            maybePerformDialpadHapticFeedback(it)
        }

        callToggleHold.setOnClickListener {
            toggleHold()
            maybePerformDialpadHapticFeedback(it)
        }

        callAddContactHolder.setOnClickListener {
            addContact()
            maybePerformDialpadHapticFeedback(it)
        }

        callEnd.setOnClickListener {
            endCall()
        }

        dialpadInclude.apply {
            dialpad0Holder.setOnClickListener { dialpadPressed('0') }
            dialpad1Holder.setOnClickListener { dialpadPressed('1') }
            dialpad2Holder.setOnClickListener { dialpadPressed('2') }
            dialpad3Holder.setOnClickListener { dialpadPressed('3') }
            dialpad4Holder.setOnClickListener { dialpadPressed('4') }
            dialpad5Holder.setOnClickListener { dialpadPressed('5') }
            dialpad6Holder.setOnClickListener { dialpadPressed('6') }
            dialpad7Holder.setOnClickListener { dialpadPressed('7') }
            dialpad8Holder.setOnClickListener { dialpadPressed('8') }
            dialpad9Holder.setOnClickListener { dialpadPressed('9') }

            dialpad0Holder.setOnLongClickListener { dialpadPressed('+'); true }
            dialpadAsteriskHolder.setOnClickListener { dialpadPressed('*') }
            dialpadHashtagHolder.setOnClickListener { dialpadPressed('#') }
        }

        arrayOf(
            callToggleMicrophone, callDialpadHolder, callToggleHold,
            callAddHolder, callSwapHolder, callMergeHolder, callInfo, addCallerNote, callAddContactHolder
        ).forEach { imageView ->
            imageView.setOnLongClickListener {
                if (!imageView.contentDescription.isNullOrEmpty()) {
                    toast(imageView.contentDescription.toString())
                }
                true
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleSwipe() = binding.apply {
        var minDragX = 0f
        var maxDragX = 0f
        var initialDraggableX = 0f
        var leftArrowTranslation = 0f
        var rightArrowTranslation = 0f
        var initialBackgroundWidth = 0

        val isRtl = isRTLLayout
        callAccept.onGlobalLayout {
            minDragX = if (isRtl) callDraggableBackground.left.toFloat() + resources.getDimension(R.dimen.three_dp)
                        else callDraggableBackground.left.toFloat() - callDraggable.width.toFloat()
            maxDragX = if (isRtl) callDraggableBackground.right.toFloat() - 60f
                        else callDraggableBackground.right.toFloat() - callDraggable.width.toFloat() - resources.getDimension(R.dimen.three_dp) - 20f
            initialDraggableX = if (isRtl) callDraggableBackground.right.toFloat() - callDraggable.width.toFloat() else callDraggableBackground.left.toFloat() + resources.getDimension(R.dimen.three_dp)
            leftArrowTranslation = if (isRtl) 50f else -50f //-callDraggableBackground.x
            rightArrowTranslation = if (isRtl) -50f else 50f //callDraggableBackground.x
            initialBackgroundWidth = callDraggableBackground.width

            callLeftArrow.applyColorFilter(getColor(R.color.red_call))
            callRightArrow.applyColorFilter(getColor(R.color.green_call))

            val startArrows = {
                startArrowAnimation(callLeftArrow, View.TRANSLATION_X, leftArrowTranslation)
                startArrowAnimation(callRightArrow, View.TRANSLATION_X, rightArrowTranslation)
            }
            arrowStarters.add(startArrows)
            startArrows()
        }

        val configBackgroundCallScreen = config.backgroundCallScreen
        if (config.answerStyle == ANSWER_SLIDER_OUTLINE) {
            callDraggableBackground.background = AppCompatResources.getDrawable(this@CallActivity, R.drawable.call_draggable_background_stroke)
            callDraggableBackgroundIcon.background.mutate().setTint(Color.WHITE)
        } else {
            callDraggableBackground.background.alpha = 51 // 20%
        }
        callDraggableBackgroundIcon.drawable.mutate().setTint(getColor(R.color.green_call))
        callDraggableBackgroundIcon.background.mutate().setTint(Color.WHITE)
        callDraggableBackground.background.mutate().setTint(Color.WHITE)

        var lock = false
        callDraggable.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragDownX = event.x
                    //callDraggableBackground.animate().alpha(0f)
                    cancelArrowAnimations()
                    callLeftArrow.animate().alpha(0f)
                    callRightArrow.animate().alpha(0f)
                    lock = false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragDownX = 0f
//                    callDraggable.animate().x(initialDraggableX).withEndAction {
//                        callDraggableBackground.animate().alpha(0.2f)
//                    }
                    callDraggable.x(initialDraggableX)
                    setDragIcon(R.drawable.ic_phone_down_vector, tintGreen = true)
                    startArrowAnimation(callLeftArrow, View.TRANSLATION_X, leftArrowTranslation)
                    startArrowAnimation(callRightArrow, View.TRANSLATION_X, rightArrowTranslation)

                    setDragBackgroundWidth(initialBackgroundWidth)
                }

                MotionEvent.ACTION_MOVE -> {
                    callDraggable.x = min(maxDragX, max(minDragX, event.rawX - dragDownX))
                    setDragBackgroundWidth(
                        if (isRtl) (initialBackgroundWidth - (maxDragX + 60 - callDraggable.height.toFloat() - callDraggable.x)).toInt()
                        else (initialBackgroundWidth + (minDragX + resources.getDimension(R.dimen.three_dp) + callDraggable.width - callDraggable.x)).toInt()
                    )
                    //callerNameLabel.text = callDraggable.x.toString() + "   " + initialBackgroundWidth.toString() + "   " + (minDragX + callDraggable.width - callDraggable.x).toString()
                    when {
                        callDraggable.x >= maxDragX -> {
                            if (!lock) {
                                lock = true
                                if (isRtl) {
                                    endCall()
                                } else {
                                    acceptCall()
                                }
                            }
                        }

                        callDraggable.x <= minDragX + 50f -> {
                            if (!lock) {
                                lock = true
                                if (isRtl) {
                                    acceptCall()
                                } else {
                                    endCall()
                                }
                            }
                        }

                        callDraggable.x > initialDraggableX + 20f -> {
                            lock = false
                            val drawableRes = if (isRtl) {
                                R.drawable.ic_phone_down_red_vector
                            } else {
                                R.drawable.ic_phone_green_vector
                            }
                            setDragIcon(drawableRes, tintGreen = false)
                        }

                        callDraggable.x < initialDraggableX - 20f -> {
                            lock = false
                            val drawableRes = if (isRtl) {
                                R.drawable.ic_phone_green_vector
                            } else {
                                R.drawable.ic_phone_down_red_vector
                            }
                            setDragIcon(drawableRes, tintGreen = false)
                        }

                        callDraggable.x <= initialDraggableX + 20f || callDraggable.x >= initialDraggableX - 20f -> {
                            lock = false
                            setDragIcon(R.drawable.ic_phone_down_vector, tintGreen = true)
                        }
                    }
                }
            }
            true
        }
    }

    private fun applyWallpaperBlur(wallpaperBlur: Bitmap) {
        val drawable: Drawable = wallpaperBlur.toDrawable(resources)
        binding.callHolder.background = drawable
        binding.callHolder.background.alpha = 60
        if (isQPlus()) {
            binding.callHolder.background.colorFilter = BlendModeColorFilter(Color.DKGRAY, BlendMode.SOFT_LIGHT)
        } else {
            binding.callHolder.background.setColorFilter(Color.DKGRAY, PorterDuff.Mode.DARKEN)
        }
    }

    // The slider icon only changes between a few states; skip drawable lookups while dragging within one
    private fun setDragIcon(drawableRes: Int, tintGreen: Boolean) {
        if (dragIconRes == drawableRes) return
        dragIconRes = drawableRes
        binding.callDraggableBackgroundIcon.setImageDrawable(AppCompatResources.getDrawable(this, drawableRes))
        if (tintGreen) binding.callDraggableBackgroundIcon.drawable.mutate().setTint(getColor(R.color.green_call))
    }

    private fun setDragBackgroundWidth(width: Int) {
        val params = binding.callDraggableBackground.layoutParams
        if (params.width == width) return
        params.width = width
        binding.callDraggableBackground.layoutParams = params
    }

    // One infinite animator per arrow (restart repeats it), cancelled on touch, answer and destroy.
    private fun startArrowAnimation(arrow: ImageView, translationProperty: Property<View, Float>, translation: Float) {
        arrowAnimators.remove(arrow)?.cancel()
        arrow.animate().cancel()
        arrow.alpha = 1f
        arrow.scaleX = 1f
        arrow.scaleY = 1f
        arrow.translationX = 0f
        arrow.translationY = 0f
        arrowAnimators[arrow] = ObjectAnimator.ofPropertyValuesHolder(
            arrow,
            PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f),
            PropertyValuesHolder.ofFloat(translationProperty, 0f, translation),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.5f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.5f)
        ).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun cancelArrowAnimations() {
        arrowAnimators.values.forEach { it.cancel() }
        arrowAnimators.clear()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleSwipeVertical() = binding.apply {
        var minDragY = 0f
        var maxDragY = 0f
        var initialDraggableY = 0f
        var downArrowTranslation = 0f
        var upArrowTranslation = 0f

        callDraggableVertical.onGlobalLayout {
            minDragY = callDraggableVertical.top.toFloat() - callDraggableVertical.height.toFloat()
            maxDragY = callDraggableVertical.bottom.toFloat()
            initialDraggableY = callDraggableVertical.top.toFloat()
            downArrowTranslation = 50f
            upArrowTranslation = -50f

            callDownArrow.applyColorFilter(getColor(R.color.red_call))
            callUpArrow.applyColorFilter(getColor(R.color.green_call))

            val startArrows = {
                startArrowAnimation(callDownArrow, View.TRANSLATION_Y, downArrowTranslation)
                startArrowAnimation(callUpArrow, View.TRANSLATION_Y, upArrowTranslation)
            }
            arrowStarters.add(startArrows)
            startArrows()
        }

        val configBackgroundCallScreen = config.backgroundCallScreen
        callDraggableVertical.drawable.mutate().setTint(getColor(R.color.green_call))
        callDraggableVertical.background.mutate().setTint(Color.WHITE)
        //callDraggableVertical.background.alpha = 51 // 20%

        var lock = false
        callDraggableVertical.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
//                    dragDownX = event.y
                    //callDraggableBackground.animate().alpha(0f)
                    cancelArrowAnimations()
                    callDownArrow.animate().alpha(0f)
                    callUpArrow.animate().alpha(0f)
                    lock = false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragDownX = 0f
                    callDraggableVertical.animate().y(initialDraggableY)
                    callDraggableVertical.setImageDrawable(AppCompatResources.getDrawable(this@CallActivity, R.drawable.ic_phone_down_vector))
                    callDraggableVertical.drawable.mutate().setTint(getColor(R.color.green_call))
                    startArrowAnimation(callDownArrow, View.TRANSLATION_Y, downArrowTranslation)
                    startArrowAnimation(callUpArrow, View.TRANSLATION_Y, upArrowTranslation)
                }

                MotionEvent.ACTION_MOVE -> {
                    callDraggableVertical.y = min(maxDragY, max(minDragY, event.rawY - dragDownX - statusBarHeight))
                    //callerNameLabel.text = callDraggableVertical.y.toString() + "   " + statusBarHeight.toString() + "   " + callDraggableVertical.top.toFloat().toString()
                    when {
                        callDraggableVertical.y >= maxDragY -> {
                            if (!lock) {
                                lock = true
                                endCall()
                            }
                        }
                        callDraggableVertical.y <= minDragY -> {
                            if (!lock) {
                                lock = true
                                acceptCall()
                            }
                        }
                        callDraggableVertical.y > initialDraggableY + 20f -> {
                            lock = false
                            callDraggableVertical.setImageDrawable(AppCompatResources.getDrawable(this@CallActivity, R.drawable.ic_phone_down_red_vector))
                        }
                        callDraggableVertical.y < initialDraggableY - 20f -> {
                            lock = false
                            callDraggableVertical.setImageDrawable(AppCompatResources.getDrawable(this@CallActivity, R.drawable.ic_phone_green_vector))
                        }
                        callDraggableVertical.y <= initialDraggableY + 20f || callDraggableVertical.y >= initialDraggableY - 20f -> {
                            lock = false
                            callDraggableVertical.setImageDrawable(AppCompatResources.getDrawable(this@CallActivity, R.drawable.ic_phone_down_vector))
                            callDraggableVertical.drawable.mutate().setTint(getColor(R.color.green_call))
                        }
                    }
                }
            }
            true
        }
    }

    private fun dialpadPressed(char: Char) {
        CallManager.keypad(char)
        binding.dialpadInput.addCharacter(char)
        binding.dialpadInputGlass.beVisible() // glass card appears with the first digit, not as an empty bar
        maybePerformDialpadHapticFeedback(binding.dialpadInput)
    }

//    private fun openBluetoothSettings() {
//        try {
//            val storageSettingsIntent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
//            startActivity(storageSettingsIntent)
//        } catch (e: Exception) {
//            showErrorToast(e)
//        }
//    }

    private fun changeCallAudioRoute() {
        val supportAudioRoutes = CallManager.getSupportedAudioRoutes()
        if (supportAudioRoutes.size > 2) {
            createOrUpdateAudioRouteChooser(supportAudioRoutes)
        } else {
//            val isSpeakerOn = !isSpeakerOn
//            val newRoute = if (isSpeakerOn) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE
//            CallManager.setAudioRoute(newRoute)
            CallManager.toggleSpeakerRoute()
        }
    }

    private fun createOrUpdateAudioRouteChooser(routes: Array<AudioRoute>, create: Boolean = true) {
        // A route change only closes an open picker; nothing is built for it
        if (!create) {
            audioRoutePopupMenu?.dismiss()
            return
        }
        val callAudioRoute = CallManager.getCallAudioRoute()
        val btDevices = CallManager.getSupportedBluetoothDevices()
        val activeBtDevice = CallManager.getActiveBluetoothDevice()

        // Reading BluetoothDevice.name requires BLUETOOTH_CONNECT at runtime on Android 12+.
        // Ask for it once when the picker is opened with any Bluetooth device present, so
        // the next open shows the real device name instead of a generic fallback label.
        if (btDevices.isNotEmpty()) {
            requestBluetoothConnectPermissionIfNeeded()
        }

        // When at least one Bluetooth device is enumerable, replace the generic
        // "Bluetooth" row with the actual device name(s) so the user always sees
        // which device they are picking.
        val items = mutableListOf<SimpleListItem>()
        routes.sortedByDescending { it.route }.forEach { route ->
            if (route == AudioRoute.BLUETOOTH && btDevices.isNotEmpty()) {
                btDevices.forEachIndexed { index, device ->
                    items.add(
                        SimpleListItem(
                            id = BLUETOOTH_DEVICE_MENU_ID_BASE + index,
                            text = getBluetoothDeviceLabel(device, index),
                            imageRes = route.iconRes,
                            selected = device == activeBtDevice
                        )
                    )
                }
            } else {
                items.add(
                    SimpleListItem(
                        id = route.route,
                        textRes = route.stringRes,
                        imageRes = route.iconRes,
                        selected = route == callAudioRoute
                    )
                )
            }
        }

        if (audioRoutePopupMenu != null) {
            audioRoutePopupMenu?.dismiss()
        }

        if (create) {
            val wrapper: Context = ContextThemeWrapper(this@CallActivity, getPopupMenuTheme())
            audioRoutePopupMenu = PopupMenu(wrapper, binding.callToggleSpeaker, Gravity.END)

            items.forEach { item ->
                val title = item.text ?: getString(item.textRes ?: R.string.other)
                audioRoutePopupMenu?.menu?.add(
                    1,
                    item.id,
                    item.id,
                    title
                )?.setIcon(item.imageRes ?: R.drawable.ic_transparent)
            }

            audioRoutePopupMenu?.setOnMenuItemClickListener { item ->
                val deviceIndex = item.itemId - BLUETOOTH_DEVICE_MENU_ID_BASE
                if (deviceIndex in btDevices.indices) {
                    CallManager.setBluetoothDevice(btDevices[deviceIndex])
                } else {
                    CallManager.setAudioRoute(item.itemId)
                }
                true
            }

            if (isQPlus()) {
                audioRoutePopupMenu?.setForceShowIcon(true)
            }

            audioRoutePopupMenu?.show()

            val selected = items.firstOrNull { it.selected } ?: items.first()
            val primaryColor = getProperPrimaryColor() // popups follow the app theme (getPopupMenuTheme), not the dark call screen
            val textColor = getProperTextColor()
            // icon and text coloring
            audioRoutePopupMenu?.menu?.apply {
                for (index in 0 until this.size) {
                    val item = this[index]
                    val color = if (item.itemId == selected.id) primaryColor else textColor

                    //icon coloring
                    if (isQPlus()) {
                        item.icon!!.colorFilter = BlendModeColorFilter(
                            color, BlendMode.SRC_IN
                        )
                    } else {
                        item.icon!!.setColorFilter(color, PorterDuff.Mode.SRC_IN)
                    }

                    //text coloring
                    val spannableString = SpannableString(item.title)
                    spannableString.setSpan(
                        ForegroundColorSpan(color),
                        0,
                        spannableString.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    item.title = spannableString
                }
            }
        }
    }

    private fun getBluetoothDeviceLabel(device: BluetoothDevice, index: Int): String {
        return try {
            val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device.alias else null
            alias?.takeIf { it.isNotBlank() }
                ?: device.name?.takeIf { it.isNotBlank() }
                ?: "${getString(R.string.audio_route_bluetooth)} ${index + 1}"
        } catch (_: SecurityException) {
            "${getString(R.string.audio_route_bluetooth)} ${index + 1}"
        }
    }

    private fun requestBluetoothConnectPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted && !bluetoothPermissionRequested) {
            // Once per call screen: a denial must not re-prompt on every picker open
            bluetoothPermissionRequested = true
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), BT_CONNECT_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // The picker requests BLUETOOTH_CONNECT lazily the first time it is opened with a
        // Bluetooth device present. Device names only become readable once it is granted,
        // so rebuild and re-open the picker here instead of waiting for a second open.
        if (requestCode == BT_CONNECT_REQUEST_CODE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            val supportedAudioRoutes = CallManager.getSupportedAudioRoutes()
            if (supportedAudioRoutes.size > 2) {
                createOrUpdateAudioRouteChooser(supportedAudioRoutes, create = true)
            }
        }
    }

    private fun updateCallAudioState(route: AudioRoute?, changeProximitySensor: Boolean = true) {
        if (route != null) {
            updateMicrophoneButton()

            val isSpeakerOn = route == AudioRoute.SPEAKER
            val supportedAudioRoutes = CallManager.getSupportedAudioRoutes()
            binding.callToggleSpeaker.apply {
                val bluetoothConnected = supportedAudioRoutes.contains(AudioRoute.BLUETOOTH)
                contentDescription = if (bluetoothConnected) {
                    getString(R.string.choose_audio_route)
                } else {
                    getString(if (isSpeakerOn) R.string.turn_speaker_off else R.string.turn_speaker_on)
                }
                // show speaker icon when a headset is connected, a headset icon maybe confusing to some
                if (/*route == AudioRoute.WIRED_HEADSET || */route == AudioRoute.EARPIECE) {
                    setImageResource(R.drawable.ic_volume_down_vector)
                } else {
                    setImageResource(route.iconRes)
                }
            }
            val supportAudioRoutes = CallManager.getSupportedAudioRoutes()
            binding.callToggleSpeakerLabel.text =
                if (supportAudioRoutes.size == 2) getString(R.string.audio_route_speaker) else  getString(route.stringRes)
            toggleButtonColor(binding.callToggleSpeaker, enabled = route != AudioRoute.EARPIECE && route != AudioRoute.WIRED_HEADSET)
            createOrUpdateAudioRouteChooser(supportedAudioRoutes, create = false)

            if (changeProximitySensor) { // No need to turn on the sensor when a call has not yet been answered
                if (isSpeakerOn) {
                    disableProximitySensor()
                } else {
                    enableProximitySensor()
                }
            }
        }
    }

    private fun toggleMicrophone() {
        // The button and notification refresh when the system reports the new mute state (onMuteChanged)
        CallManager.setMuted(!isMicrophoneOff)
    }

    private fun updateMicrophoneButton() {
        if (appliedMicrophoneOff == isMicrophoneOff) return
        appliedMicrophoneOff = isMicrophoneOff
        binding.apply {
            val drawable = if (!isMicrophoneOff) R.drawable.ic_microphone_vector else R.drawable.ic_microphone_off_vector
            callToggleMicrophone.setImageDrawable(AppCompatResources.getDrawable(this@CallActivity, drawable))

            val configBackgroundCallScreen = config.backgroundCallScreen
            if (configBackgroundCallScreen == TRANSPARENT_BACKGROUND ||
                configBackgroundCallScreen == BLUR_AVATAR ||
                configBackgroundCallScreen == AVATAR
            ) {
                val color = if (isMicrophoneOff) Color.WHITE else Color.GRAY
                callToggleMicrophone.background.applyColorFilter(color)
                val colorIcon = if (isMicrophoneOff) Color.BLACK else Color.WHITE
                callToggleMicrophone.applyColorFilter(colorIcon)
            }
            callToggleMicrophone.background.alpha = if (isMicrophoneOff) 255 else 60
            callToggleMicrophone.contentDescription =
                getString(if (isMicrophoneOff) R.string.turn_microphone_on else R.string.turn_microphone_off)
//            callToggleMicrophoneLabel.text =
//                getString(if (isMicrophoneOff) R.string.turn_microphone_on else R.string.turn_microphone_off)
        }
    }

    private fun toggleDialpadVisibility() {
        if (binding.dialpadWrapper.isVisible()) hideDialpad() else showDialpad()
    }

    private fun showDialpad() {
        binding.apply {
            dialpadWrapper.beVisible()
            dialpadClose.beVisible()
            arrayOf(
                callerAvatar, callerNameLabel, callerDescription, callerNumber, callerNotes, callStatusLabel,
                callSimImage, callSimId, callToggleMicrophone, callDialpadHolder,
                callToggleSpeaker, callAddContactHolder, callInfo, addCallerNote
            ).forEach {
                it.beGone()
            }
            controlsSingleCall.beGone()
            controlsTwoCalls.beGone()

            RxAnimation.together(
                dialpadWrapper.scale(1f),
                dialpadWrapper.fadeIn(duration = 160),
                dialpadClose.fadeIn(duration = 160)
            ).doAfterTerminate {
            }.subscribe().also(animationDisposables::add)
        }
    }

    @SuppressLint("MissingPermission")
    private fun hideDialpad() {
        binding.apply {
            RxAnimation.together(
                dialpadWrapper.scale(0.7f),
                dialpadWrapper.fadeOut(duration = 160),
                dialpadClose.fadeOut(duration = 160)
            ).doAfterTerminate {
                dialpadWrapper.beGone()
                dialpadClose.beGone()
                arrayOf(
                    callerAvatar, callerNameLabel, callerNumber, callStatusLabel,
                    callToggleMicrophone, callDialpadHolder,
                    callToggleSpeaker
                ).forEach {
                    it.beVisible()
                }
                val isSmallScreen =
                    resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK == Configuration.SCREENLAYOUT_SIZE_SMALL
                callAddContactHolder.beVisibleIf(config.callButtonStyle == IOS16 && !isSmallScreen)
                callerDescription.beVisibleIf(callerDescription.text.isNotEmpty())
                callerNotes.beVisibleIf(callerNotes.text.isNotEmpty())
                val hasMultipleAccounts = try {
                    telecomManager.callCapablePhoneAccounts.size > 1
                } catch (_: SecurityException) {
                    false
                }
                callSimImage.beVisibleIf(hasMultipleAccounts)
                callSimId.beVisibleIf(hasMultipleAccounts)
                updateState()
            }.subscribe().also(animationDisposables::add)
        }
    }

    private fun toggleHold() {
        binding.apply {
            val isOnHold = CallManager.toggleHold()
            val drawable = if (isOnHold) R.drawable.ic_pause_crossed_vector else R.drawable.ic_pause_vector
            callToggleHold.setImageDrawable(AppCompatResources.getDrawable(this@CallActivity, drawable))
            val description = getString(if (isOnHold) R.string.resume_call else R.string.hold_call)
            callToggleLabel.text = description
            callToggleHold.contentDescription = description
            holdStatusLabel.beInvisibleIf(!isOnHold)
            RxAnimation.from(holdStatusLabel)
                .shake()
                .subscribe().also(animationDisposables::add)

            val configBackgroundCallScreen = config.backgroundCallScreen
            if (configBackgroundCallScreen == TRANSPARENT_BACKGROUND ||
                configBackgroundCallScreen == BLUR_AVATAR ||
                configBackgroundCallScreen == AVATAR
            ) {
                val color = if (isOnHold) Color.WHITE else Color.GRAY
                callToggleHold.background.applyColorFilter(color)
                val colorIcon = if (isOnHold) Color.BLACK else Color.WHITE
                callToggleHold.applyColorFilter(colorIcon)
            }
            callToggleHold.background.alpha = if (isOnHold) 255 else 60
        }
    }

    private fun addContact() {
        val number = callContact?.number?.ifEmpty { "" } ?: ""
        val formatNumber = if (config.formatPhoneNumbers) number.formatPhoneNumber() else number
        startAddNumberToContact(formatNumber)
    }

    @Suppress("DEPRECATION")
    private fun updateOtherPersonsInfo(avatarUri: String, isConference: Boolean) {
        if (callContact == null) {
            return
        }

        binding.apply {
            val (name, _, number, numberLabel, description, isABusinessCall, isVoiceMail) = callContact!!
            callerNameLabel.text =
                formatterUnicodeWrap(name.ifEmpty { getString(R.string.unknown_caller) })
            if (number.isNotEmpty() && number != name) {
                val numberText = formatterUnicodeWrap(number)
                if (numberLabel.isNotEmpty()) {
                    val numberLabelText = formatterUnicodeWrap(numberLabel)
                    callerNumber.text = numberLabelText
                    callerNumber.setOnClickListener {
                        if (callerNumber.text == numberLabelText) callerNumber.text = numberText
                        else callerNumber.text = numberLabelText
                        maybePerformDialpadHapticFeedback(it)
                    }
                } else {
                    callerNumber.text = numberText
                }

                if (description.isNotEmpty() && description != name) {
                    callerDescription.text = formatterUnicodeWrap(description)
                    callerDescription.beVisible()
                } else callerDescription.beGone()
            } else {
                callerDescription.beGone()
                val country = number.getCountryByNumber()
                if (country != "") {
                    callerNumber.text = formatterUnicodeWrap(country)
                } else callerNumber.beGone()
            }

            callerAvatar.apply {
                if (config.backgroundCallScreen == AVATAR) {
                    beGone()
                } else {
                    try {
                        if (number == name || ((isABusinessCall || isVoiceMail) && avatarUri == "") || isDestroyed || isFinishing) {
                            val drawable = when {
                                isVoiceMail -> {
                                    @SuppressLint("UseCompatLoadingForDrawables")
                                    val drawableVoicemail = resources.getDrawable(R.drawable.placeholder_voicemail, theme)
                                    if (baseConfig.useColoredContacts) {
                                        val letterBackgroundColors = getLetterBackgroundColors()
                                        val color = letterBackgroundColors[abs(name.hashCode()) % letterBackgroundColors.size].toInt()
                                        (drawableVoicemail as LayerDrawable).findDrawableByLayerId(R.id.placeholder_contact_background).applyColorFilter(color)
                                    }
                                    drawableVoicemail
                                }

                                isABusinessCall -> SimpleContactsHelper(this@CallActivity).getColoredCompanyIcon(name)
                                else -> SimpleContactsHelper(this@CallActivity).getColoredContactIcon(name)
                            }
                            setImageDrawable(drawable)
                        } else {
                            if (!isFinishing && !isDestroyed) {
                                if (isConference) {
                                    SimpleContactsHelper(this@CallActivity.applicationContext).loadContactImage(
                                        avatarUri,
                                        this,
                                        name,
                                        SimpleContactsHelper(this@CallActivity).getColoredGroupIcon(name)
                                    )
                                } else {
                                    loadBrandedContactImage(avatarUri, name)
                                }
                            }
                        }
                    } catch (_: Exception) {
                        @SuppressLint("UseCompatLoadingForDrawables")
                        val drawable = resources.getDrawable(R.drawable.placeholder_contact, theme)
                        setImageDrawable(drawable)
                    }
                }
            }

            callMessage.apply {
                setOnClickListener {
                    val wrapper: Context = ContextThemeWrapper(this@CallActivity, getPopupMenuTheme())
                    val popupMenu = PopupMenu(wrapper, callMessage, Gravity.END)
                    val quickAnswers = config.quickAnswers
                    popupMenu.menu.add(1, 1, 1, R.string.other).setIcon(R.drawable.ic_transparent)
                    if (quickAnswers.size == 3) {
                        popupMenu.menu.add(1, 2, 2, quickAnswers[0]).setIcon(R.drawable.ic_clock_vector)
                        popupMenu.menu.add(1, 3, 3, quickAnswers[1]).setIcon(R.drawable.ic_run)
                        popupMenu.menu.add(1, 4, 4, quickAnswers[2]).setIcon(R.drawable.ic_microphone_off_vector)
                    }
                    popupMenu.setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            1 -> {
                                sendSMS(callContact!!.number)
                                endCall()
                            }

                            else -> {
                                endCall(rejectWithMessage = true, textMessage = item.title.toString())
                            }
                        }
                        true
                    }
                    if (isQPlus()) {
                        popupMenu.setForceShowIcon(true)
                    }
                    popupMenu.show()
                    // icon coloring
                    popupMenu.menu.apply {
                        for (index in 0 until this.size) {
                            val item = this[index]

                            if (isQPlus()) {
                                item.icon!!.colorFilter = BlendModeColorFilter(
                                    getProperTextColor(), BlendMode.SRC_IN
                                )
                            } else {
                                item.icon!!.setColorFilter(getProperTextColor(), PorterDuff.Mode.SRC_IN)
                            }
                        }
                    }

                    //sendSMS(callContact!!.number, "textMessage")
                }
                setOnLongClickListener { toast(R.string.send_sms); true; }
            }

            callRemind.apply {
                setOnClickListener {
                    this@CallActivity.handleNotificationPermission { permission ->
                        if (permission) {
                            val wrapper: Context = ContextThemeWrapper(this@CallActivity, getPopupMenuTheme())
                            val popupMenu = PopupMenu(wrapper, callRemind, Gravity.START)
                            popupMenu.menu.add(1, 1, 1, String.format(resources.getQuantityString(R.plurals.minutes, 10, 10)))
                            popupMenu.menu.add(1, 2, 2, String.format(resources.getQuantityString(R.plurals.minutes, 30, 30)))
                            popupMenu.menu.add(1, 3, 3, String.format(resources.getQuantityString(R.plurals.minutes, 60, 60)))
                            popupMenu.setOnMenuItemClickListener { item ->
                                when (item.itemId) {
                                    1 -> {
                                        startTimer(600)
                                        endCall()
                                    }

                                    2 -> {
                                        startTimer(1800)
                                        endCall()
                                    }

                                    else -> {
                                        startTimer(3600)
                                        endCall()
                                    }
                                }
                                true
                            }
                            popupMenu.show()
                        } else {
                            toast(R.string.allow_notifications_reminders)
                        }
                    }
                }
                setOnLongClickListener { toast(R.string.remind_me); true; }
            }

            val callNote = callerNotesHelper.getCallerNotes(number)
            callerNotes.apply {
                beVisibleIf(callNote != null && !isConference)
                if (callNote != null) {
                    text = callNote.note
                }
                setOnClickListener {
                    changeNoteDialog(number, name)
                }
            }

            addCallerNote.apply {
                setOnClickListener {
                    changeNoteDialog(number, name)
                }
            }
        }
    }

    private fun changeNoteDialog(number: String, name: String) {
        val callerNote = callerNotesHelper.getCallerNotes(number)
        ChangeTextDialog(
            activity = this@CallActivity,
            title = name,
            currentText = callerNote?.note,
            maxLength = CALLER_NOTES_MAX_LENGTH,
            showNeutralButton = true,
            neutralTextRes = R.string.delete
        ) {
            if (it != "") {
                callerNotesHelper.addCallerNotes(number, it, callerNote) {
                    binding.callerNotes.text = it
                    binding.callerNotes.beVisible()
                }
            } else {
                callerNotesHelper.deleteCallerNotes(callerNote) {
                    binding.callerNotes.text = it
                    binding.callerNotes.beGone()
                }
            }
        }
    }

    private fun startTimer(duration: Int) {
        // Row 1 is replaced in place and Start restarts the countdown itself; Start reads the row, so it follows the insert.
        val newTimer = createNewTimer()
        newTimer.id = 1
        newTimer.title = callContact!!.name
        newTimer.label = callContact!!.number
        newTimer.seconds = duration
        timerHelper.insertOrUpdateTimer(newTimer) {
            EventBus.getDefault().post(TimerEvent.Start(1, duration.secondsToMillis))
        }
    }

    private val Int.secondsToMillis get() = TimeUnit.SECONDS.toMillis(this.toLong())

    private fun sendSMS(number: String, text: String = " ") {
        Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.fromParts("smsto", number, null)
            putExtra("sms_body", text)
            launchActivityIntent(this)
        }
    }

    private fun getContactNameOrNumber(contact: CallContact): String {
        return contact.name.ifEmpty {
            contact.number.ifEmpty {
                getString(R.string.unknown_caller)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkCalledSIMCard() {
        try {
            val simLabels = getAvailableSIMCardLabels()
            if (simLabels.size > 1) {
                simLabels.forEachIndexed { index, sim ->
                    if (sim.handle == CallManager.getPrimaryCall()?.details?.accountHandle) {
                        binding.apply {
                            callSimId.text = sim.id.toString()
                            callSimId.beVisible()
                            callSimImage.beVisible()
                            val simColor = sim.color
                            callSimId.setTextColor(simColor.getContrastColor())
                            callSimImage.applyColorFilter(simColor)
                        }

                        val acceptDrawableId = when (index) {
                            0 -> R.drawable.ic_phone_one_vector
                            1 -> R.drawable.ic_phone_two_vector
                            else -> R.drawable.ic_phone_vector
                        }
                        val acceptDrawable = AppCompatResources.getDrawable(this@CallActivity, acceptDrawableId)

                        val rippleBg = AppCompatResources.getDrawable(this@CallActivity, R.drawable.ic_call_accept) as RippleDrawable
                        val layerDrawable = rippleBg.findDrawableByLayerId(R.id.accept_call_background_holder) as LayerDrawable
                        layerDrawable.setDrawableByLayerId(R.id.accept_call_icon, acceptDrawable)
                        binding.callAccept.setImageDrawable(rippleBg)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun updateCallState(call: Call) {
        val isBusy = call.details.connectTimeMillis == 0L

        val state = call.getStateCompat()
        enlargeAvatar()
        when (state) {
            Call.STATE_RINGING -> callRinging()
            Call.STATE_ACTIVE -> callStarted()
            Call.STATE_DISCONNECTED -> endCall(isBusy = isBusy)
            Call.STATE_CONNECTING, Call.STATE_DIALING -> initOutgoingCallUI()
            Call.STATE_SELECT_PHONE_ACCOUNT -> showPhoneAccountPicker()
        }

        val statusTextId = when (state) {
            Call.STATE_RINGING -> R.string.is_calling
            Call.STATE_CONNECTING, Call.STATE_DIALING -> R.string.dialing
            Call.STATE_DISCONNECTED -> if (isBusy) R.string.busy else 0
            else -> 0
        }

        binding.apply {
            if (statusTextId != 0) {
                callStatusLabel.text = getString(statusTextId)
            }

            callInfo.beVisibleIf(!isCallEnded && call.hasCapability(Call.Details.CAPABILITY_MANAGE_CONFERENCE))
            addCallerNote.beVisibleIf(!callInfo.isVisible)
            if (dialpadWrapper.isGone()) {
                setActionButtonEnabled(callSwapHolder, enabled = !isCallEnded && state == Call.STATE_ACTIVE)
                setActionButtonEnabled(callMergeHolder, enabled = !isCallEnded && state == Call.STATE_ACTIVE)
            }
        }

        updateCallNetworkType(call, state)
    }

    // Shows a Wi-Fi-calling or mobile-network indicator (top-left, mirroring the note icon) while a
    // call is connected, refreshed live on state/details changes (e.g. a Wi-Fi<->cellular hand-over).
    private fun updateCallNetworkType(call: Call?, state: Int) {
        val showNetwork = state == Call.STATE_ACTIVE || state == Call.STATE_DIALING ||
            state == Call.STATE_CONNECTING || state == Call.STATE_HOLDING
        binding.callNetworkType.beVisibleIf(showNetwork)
        if (!showNetwork) return
        val isWifi = call.isWifiCall()
        binding.callNetworkType.apply {
            setImageResource(if (isWifi) R.drawable.ic_wifi_calling_vector else R.drawable.ic_cellular_vector)
            contentDescription = getString(if (isWifi) R.string.wifi_calling else R.string.mobile_network_call)
            applyColorFilter(Color.WHITE)
        }
    }

    private fun updateState() {
        val phoneState = CallManager.getPhoneState()
        var changeProximitySensor = true
        if (phoneState is SingleCall) {
            updateCallState(phoneState.call)
            updateCallOnHoldState(null)
            val state = phoneState.call.getStateCompat()
            val isSingleCallActionsEnabled = !isCallEnded && (state == Call.STATE_ACTIVE || state == Call.STATE_DISCONNECTED
                || state == Call.STATE_DISCONNECTING || state == Call.STATE_HOLDING)
            if (binding.dialpadWrapper.isGone()) {
                setActionImageViewEnabled(binding.callToggleHold, isSingleCallActionsEnabled)
                setActionButtonEnabled(binding.callAddHolder, isSingleCallActionsEnabled)
            }
            if (state == Call.STATE_DIALING) changeProximitySensor = false
        } else if (phoneState is TwoCalls) {
            updateCallState(phoneState.active)
            updateCallOnHoldState(phoneState.onHold, phoneState.active)
            // Keep the screen on while a 2nd call is ringing (call waiting).
            if (phoneState.active.getStateCompat() == Call.STATE_RINGING) changeProximitySensor = false
        }

        runOnUiThread {
            updateCallAudioState(CallManager.getCallAudioRoute(), changeProximitySensor)
            updateMicrophoneButton()
        }
    }

    private fun updateCallOnHoldState(call: Call?, callActive: Call? = null) {
        val hasCallOnHold = call != null
        // Call waiting: the primary/active call is actually a 2nd call that is still ringing.
        // The standard incoming UI (shown via callRinging) presents the NEW caller. We force plain
        // Accept/Decline buttons here instead of the answer-style slider: the slider's drag bounds
        // and arrow base positions are captured once by a one-shot layout listener when the call
        // screen is first created (with the incoming UI hidden), so they are invalid on this
        // re-shown call-waiting screen — the arrows would otherwise fly across the display.
        val isCallWaiting = hasCallOnHold && callActive?.getStateCompat() == Call.STATE_RINGING

        if (hasCallOnHold) {
            getCallContact(applicationContext, call) { contact ->
                runOnUiThread {
                    binding.onHoldCallerName.text = getContactNameOrNumber(contact)
                }
            }
        }

        if (isCallWaiting) {
            binding.apply {
                arrayOf(
                    callDraggable, callDraggableBackground, callDraggableVertical,
                    callLeftArrow, callRightArrow, callUpArrow, callDownArrow
                ).forEach { it.beGone() }

                callDecline.beVisible()
                callDecline.setOnClickListener { endCall() }

                callAccept.beVisible()
                callAccept.setOnClickListener { acceptCall() }

                callAcceptAndDecline.apply {
                    beVisible()
                    setText(R.string.answer_end_other_call)
                    setOnClickListener {
                        acceptCall()
                        call?.disconnect()
                    }
                }
            }
        } else if (!hasCallOnHold && config.callBlockButton) {
            binding.callAcceptAndDecline.apply {
                beVisible()
                setText(R.string.block_number)
                setOnClickListener {
                    if (callContact != null) {
                        val number = callContact!!.number
                        val baseString = R.string.block_confirmation
                        val question = String.format(resources.getString(baseString), number)

                        ConfirmationAdvancedDialog(this@CallActivity, question, cancelOnTouchOutside = false) {
                            if (it) {
                                blockNumbers(number.normalizePhoneNumber())
                            }
                        }
                    }
                }
            }
        }

        binding.apply {
            onHoldStatusHolder.beVisibleIf(hasCallOnHold)
            controlsSingleCall.beVisibleIf(!hasCallOnHold && dialpadWrapper.isGone())
            // While a 2nd call is ringing the incoming UI is shown, so keep the two-call
            // controls hidden until that waiting call is actually answered.
            controlsTwoCalls.beVisibleIf(hasCallOnHold && !isCallWaiting && dialpadWrapper.isGone())
        }
    }

    private fun blockNumbers(number: String) {
        config.needRestart = true
        ensureBackgroundThread {
            val blocked = try {
                addBlockedNumber(number)
            } catch (_: SecurityException) {
                false
            }
            if (blocked) runOnUiThread { endCall() }
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("UseCompatLoadingForDrawables")
    private fun updateCallContactInfo(call: Call?) {
        binding.callDetails.beVisibleIf(call.isHD())
        // Size the photo before it loads: the image loader decodes at the view's size, so a photo loaded small and
        // then enlarged would be blurry
        enlargeAvatar()
        getCallContact(applicationContext, call) { contact ->
            if (isDestroyed || call != CallManager.getPrimaryCall()) {
                return@getCallContact
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                callContact = contact

                // The call may already have been waiting for SIM selection before the contact
                // resolved (showPhoneAccountPicker falls back to callContact when the call has
                // no handle URI) — retry now; the one-shot flag makes this a no-op otherwise.
                if (call.getStateCompat() == Call.STATE_SELECT_PHONE_ACCOUNT) showPhoneAccountPicker()
            }

            val configBackgroundCallScreen = config.backgroundCallScreen
            val isConference = call.isConference()

            var drawable: Drawable? = null
            if (configBackgroundCallScreen == BLUR_AVATAR || configBackgroundCallScreen == AVATAR) {
                val windowHeight = binding.callHolder.height //window.decorView.height
                val windowWidth = binding.callHolder.width //window.decorView.width
                val avatar =
                    if (!isConference) {
                        callContactAvatarHelper.getCallContactAvatar(contact.photoUri, false, windowWidth, windowHeight)
                    } else null
                if (avatar != null) {
                    val bg = when (configBackgroundCallScreen) {
                        BLUR_AVATAR -> BlurFactory.fileToBlurBitmap(avatar, this, 0.6f, 5f)
                        AVATAR -> avatar
                        else -> null
                    }
                    if (bg != null && windowWidth != 0 && windowHeight != 0) {
                        val cropWidth = (bg.height * windowWidth / windowHeight.toFloat()).toInt().coerceIn(1, bg.width)
                        drawable = bg.cropCenter(cropWidth, bg.height)?.toDrawable(resources)
                    }
                }
            }

            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (drawable != null) {
                    binding.callHolder.background = drawable
                    binding.callHolder.background.alpha = if (configBackgroundCallScreen == AVATAR) 120 else 60
                    if (isQPlus()) {
                        binding.callHolder.background.colorFilter = BlendModeColorFilter(Color.DKGRAY, BlendMode.SOFT_LIGHT)
                    } else {
                        binding.callHolder.background.setColorFilter(Color.DKGRAY, PorterDuff.Mode.DARKEN)
                    }
                }

                val avatarRound = if (!isConference) contact.photoUri else ""
                updateOtherPersonsInfo(avatarRound, isConference)
                checkCalledSIMCard()
            }
        }
    }

    private fun acceptCall() {
        CallManager.accept()
    }

    private fun initOutgoingCallUI() {
        enableProximitySensor()
        binding.incomingCallHolder.beGone()
        callBackdrop?.stopRinging()
        cancelArrowAnimations()
        binding.ongoingCallHolder.beVisible()
        binding.callEnd.beVisible()
        needHapticFeedback = true
    }

    // The caller's photo is twice the layout's size in every call state (ringing, dialing and connected).
    // On short screens it grows only up to a quarter of the screen height, so the status stays clear of the buttons.
    private var avatarBaseSize = 0

    // Largest enlarged size that left the status line clear of the buttons on this screen (found by fitEnlargedAvatar)
    private var avatarFitLimit = Int.MAX_VALUE

    // The dialing and connected layouts have their buttons much higher than the incoming one, so on a short screen the
    // enlarged photo can push the name and status under them: shrink it by the overlap (never below the layout's size)
    // and remember the limit.
    private fun fitEnlargedAvatar() {
        val overlap = enlargedAvatarOverlap()
        val params = binding.callerAvatar.layoutParams
        if (overlap <= 0 || params == null) return
        avatarFitLimit = maxOf(avatarBaseSize, params.width - overlap)
        if (avatarFitLimit != params.width) {
            params.width = avatarFitLimit
            params.height = avatarFitLimit
            binding.callerAvatar.layoutParams = params
        }
    }

    // How far the status line reaches into the first row of buttons while the photo is enlarged; 0 when clear
    private fun enlargedAvatarOverlap(): Int {
        val width = binding.callerAvatar.layoutParams?.width ?: 0
        val firstButtons = if (binding.incomingCallHolder.isVisible()) binding.callRemind else binding.callToggleMicrophone
        val applies = !isDestroyed && avatarBaseSize > 0 && width > avatarBaseSize &&
            firstButtons.isVisible() && binding.callStatusLabel.isVisible()
        if (!applies) return 0
        val window = IntArray(2)
        binding.callStatusLabel.getLocationInWindow(window)
        val statusBottom = window[1] + binding.callStatusLabel.height
        firstButtons.getLocationInWindow(window)
        return statusBottom + resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.medium_margin) - window[1]
    }

    private fun enlargeAvatar() {
        val params = binding.callerAvatar.layoutParams ?: return
        if (avatarBaseSize == 0) avatarBaseSize = params.width
        val size = minOf(ringingAvatarSize(avatarBaseSize, resources.displayMetrics.heightPixels), avatarFitLimit)
        if (params.width == size) return
        params.width = size
        params.height = size
        binding.callerAvatar.layoutParams = params
    }

    private fun callRinging() {
        binding.incomingCallHolder.beVisible()
        callBackdrop?.startRinging()
        if (arrowAnimators.isEmpty()) arrowStarters.forEach { it() }
        // Hide the in-call controls so a 2nd (waiting) call fully takes over with the
        // incoming UI; for a first incoming call the ongoing holder is already hidden.
        binding.ongoingCallHolder.beGone()
    }

    private fun callStarted() {
        enableProximitySensor()
        binding.incomingCallHolder.beGone()
        callBackdrop?.stopRinging()
        cancelArrowAnimations()
        binding.ongoingCallHolder.beVisible()
        binding.callEnd.beVisible()
        callDurationHandler.removeCallbacks(updateCallDurationTask)
        callDurationHandler.post(updateCallDurationTask)
        if (needHapticFeedback) maybePerformCallHapticFeedback(binding.callerNameLabel)
//        if (config.flashForAlerts) MyCameraImpl.newInstance(this).toggleSOS()
    }

    private fun showPhoneAccountPicker() {
        // Use the call's own number instead of callContact, which resolves asynchronously — a
        // call already waiting in SELECT_PHONE_ACCOUNT when this screen opens (e.g. from the
        // quiet outgoing-call notification) would otherwise never get its SIM picker and stay
        // stuck on "Dialing". needSelectSIM calls are handled by initOutgoingCall; the one-shot
        // flag prevents a second dialog when the state is dispatched again.
        if (needSelectSIM || simSelectionRequested) return
        val number = CallManager.getPrimaryCall()?.details?.handle?.schemeSpecificPart
            ?: callContact?.number
            ?: return
        simSelectionRequested = true
        getHandleToUse(intent, number) { handle ->
            if (handle != null) CallManager.getPrimaryCall()?.phoneAccountSelected(handle, false)
        }
    }

    private fun endCall(rejectWithMessage: Boolean = false, textMessage: String? = null, isBusy: Boolean = false) {
        CallManager.reject(rejectWithMessage, textMessage)

        if (isBusy) toast(R.string.busy)

        if (isCallEnded) {
            safeFinishAndRemoveTask()
            return
        }

        isCallEnded = true
        runOnUiThread {
            val phoneState = CallManager.getPhoneState()
            if (callDuration > 0) {
                disableAllActionButtons()
                @SuppressLint("SetTextI18n")
                val label = "${callDuration.getFormattedDuration()} (${getString(R.string.call_ended)})"
                binding.callStatusLabel.text = label
                safeFinishAndRemoveTask()
                if (phoneState is TwoCalls) startActivity(Intent(this, CallActivity::class.java))
            } else {
                disableAllActionButtons()
                binding.callStatusLabel.text = getString(R.string.call_ended)
                if (phoneState is TwoCalls) {
                    safeFinishAndRemoveTask()
                    startActivity(Intent(this, CallActivity::class.java))
                } else finish()
            }
            if (phoneState is SingleCall) disableProximitySensor()
        }
        maybePerformCallHapticFeedback(binding.callerNameLabel)
    }

    private fun safeFinishAndRemoveTask() {
        try {
            if (intent != null) {
                finishAndRemoveTask()
            } else {
                finish()
            }
        } catch (_: Exception) {
            finish()
        }
    }

    private val callCallback = object : CallManagerListener {
        override fun onStateChanged() {
            updateState()
        }

        override fun onAudioStateChanged(audioState: AudioRoute) {
            updateCallAudioState(audioState)
        }

        override fun onPrimaryCallChanged(call: Call) {
            callDurationHandler.removeCallbacks(updateCallDurationTask)
            updateCallContactInfo(call)
            updateState()
        }

        override fun onMuteChanged(isMuted: Boolean) {
            runOnUiThread { updateMicrophoneButton() }
        }
    }

    private val updateCallDurationTask = object : Runnable {
        override fun run() {
            val call = CallManager.getPrimaryCall() ?: return
            callDuration = call.getCallDuration()
            if (!isCallEnded && call.getStateCompat() != Call.STATE_DIALING) {
                binding.callStatusLabel.text = callDuration.getFormattedDuration()
                callDurationHandler.postDelayed(this, 1000)
            }
        }
    }

    /**
     * Shows the call over the lock screen and wakes the screen. The keyguard is deliberately not
     * dismissed here: answering works on top of it, and the system asks for the PIN only when an
     * action launches another (non-lock-screen) activity.
     */
    @Suppress("DEPRECATION")
    private fun addLockScreenFlags() {
        if (isOreoMr1Plus()) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun enableProximitySensor() {
        if (!config.disableProximitySensor && (proximityWakeLock == null || proximityWakeLock?.isHeld == false)) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            proximityWakeLock = powerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "com.mhw.phone:wake_lock")
            // Held until disableProximitySensor() (call end / destroy), so long calls keep the screen-off behaviour
            proximityWakeLock!!.acquire()
        }
    }

    private fun disableProximitySensor() {
        if (proximityWakeLock?.isHeld == true) {
            proximityWakeLock!!.release()
        }
    }

    private fun disableAllActionButtons() {
        (binding.ongoingCallHolder.children + binding.callEnd)
            .filter { it is ImageView && it.isVisible() }
            .forEach { view ->
                setActionButtonEnabled(button = view as ImageView, enabled = false)
            }
        (binding.ongoingCallHolder.children)
            .filter { it is LinearLayout && it.isVisible() }
            .forEach { view ->
                setActionButtonEnabled(button = view as LinearLayout, enabled = false)
            }
    }

    private fun setActionButtonEnabled(button: LinearLayout, enabled: Boolean) {
        button.apply {
            isEnabled = enabled
            alpha = if (enabled) 1.0f else LOWER_ALPHA
        }
    }

    private fun setActionButtonEnabled(button: ImageView, enabled: Boolean) {
        button.apply {
            isEnabled = enabled
            alpha = if (enabled) 1.0f else LOWER_ALPHA
        }
    }

    private fun setActionImageViewEnabled(button: ImageView, enabled: Boolean) {
        button.apply {
            isEnabled = enabled
            alpha = if (enabled) 1.0f else LOWER_ALPHA
        }
    }

    private fun toggleButtonColor(view: ImageView, enabled: Boolean) {
        // The call screen is always dark: "on" is a white button with a black icon
        view.background.applyColorFilter(if (enabled) Color.WHITE else Color.GRAY)
        view.applyColorFilter(if (enabled) Color.BLACK else Color.WHITE)
        view.background.alpha = if (enabled) 255 else 60
    }

    private fun checkPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(PERMISSION_READ_STORAGE)) {
                config.backgroundCallScreen = BLUR_AVATAR
                toast(R.string.no_storage_permissions)
            }
        }
    }

    private fun maybePerformDialpadHapticFeedback(view: View?) {
        if (config.callVibration) {
            view?.performHapticFeedback()
        }
    }

    private fun maybePerformCallHapticFeedback(view: View?) {
        needHapticFeedback = false
        if (config.callStartEndVibration) {
            view?.performHapticFeedback()
        }
    }
}
