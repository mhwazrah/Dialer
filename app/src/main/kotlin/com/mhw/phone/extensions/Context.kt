package com.mhw.phone.extensions

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Context.KEYGUARD_SERVICE
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioManager.STREAM_ALARM
import android.media.RingtoneManager
import android.net.Uri
import android.os.PowerManager
import android.telecom.TelecomManager
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.goodwy.commons.dialogs.RadioGroupDialog
import com.goodwy.commons.extensions.launchActivityIntent
import com.goodwy.commons.helpers.KEY_PHONE
import com.goodwy.commons.models.RadioItem
import com.mhw.phone.R
import com.mhw.phone.models.SIMAccount
import com.mhw.phone.BuildConfig
import com.mhw.phone.activities.SplashActivity
import com.mhw.phone.databases.AppDatabase
import com.mhw.phone.helpers.*
import com.mhw.phone.interfaces.TimerDao
import com.mhw.phone.models.Timer
import com.mhw.phone.models.TimerState
import com.mhw.phone.receivers.TimerReceiver
import me.leolin.shortcutbadger.ShortcutBadger
import androidx.core.net.toUri
import androidx.core.graphics.drawable.toDrawable
import com.goodwy.commons.extensions.getDefaultAlarmSound
import com.goodwy.commons.extensions.getLaunchIntent
import com.goodwy.commons.extensions.getMyContactsCursor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.grantReadUriPermission
import com.goodwy.commons.extensions.notificationManager
import com.goodwy.commons.extensions.sendSMSPendingIntent
import com.goodwy.commons.extensions.setText
import com.goodwy.commons.extensions.startCallPendingIntent
import com.goodwy.commons.extensions.telecomManager
import android.database.Cursor
import java.util.concurrent.TimeUnit
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.helpers.ContactLookupResult
import com.goodwy.commons.helpers.IS_RIGHT_APP
import com.goodwy.commons.helpers.SMT_PRIVATE
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.commons.helpers.MyContactsContentProvider
import com.goodwy.commons.helpers.SIGNAL_PACKAGE
import com.goodwy.commons.helpers.SILENT
import com.goodwy.commons.helpers.SimpleContactsHelper
import com.goodwy.commons.helpers.TELEGRAM_PACKAGE
import com.goodwy.commons.helpers.VIBER_PACKAGE
import com.goodwy.commons.helpers.WHATSAPP_PACKAGE
import com.goodwy.commons.models.SimpleContact
import com.goodwy.commons.extensions.getVisibleContactSources
import java.util.Locale

val Context.config: Config get() = Config.getInstance(applicationContext)

/** Commons' isDefaultDialer() answers true for any package outside goodwy's, so the app never asked for the role. */
fun Context.isDefaultPhoneApp(): Boolean = telecomManager.defaultDialerPackage == packageName

val Context.audioManager: AudioManager
    get() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

val Context.powerManager: PowerManager
    get() = getSystemService(Context.POWER_SERVICE) as PowerManager

val Context.keyguardManager: KeyguardManager
    get() = getSystemService(KEYGUARD_SERVICE) as KeyguardManager

@SuppressLint("MissingPermission")
fun Context.getAvailableSIMCardLabels(): List<SIMAccount> {
    val simAccounts = mutableListOf<SIMAccount>()
    try {
        telecomManager.callCapablePhoneAccounts.forEachIndexed { index, account ->
            val phoneAccount = telecomManager.getPhoneAccount(account) ?: return@forEachIndexed
            var label = phoneAccount.label.toString()
            var address = phoneAccount.address.toString()
            if (address.startsWith("tel:") && address.substringAfter("tel:").isNotEmpty()) {
                address = Uri.decode(address.substringAfter("tel:"))
                label += " ($address)"
            }

            val simColor = try {
                config.simIconsColors[index + 1]
            } catch (_: Exception) {
                phoneAccount.highlightColor
            }
            simAccounts.add(
                SIMAccount(
                    id = index + 1,
                    handle = phoneAccount.accountHandle,
                    label = label,
                    phoneNumber = address.substringAfter("tel:"),
                    color = simColor
                )
            )
        }
    } catch (_: Exception) {
    }

    return simAccounts
}

@SuppressLint("MissingPermission")
fun Context.areMultipleSIMsAvailable(): Boolean {
    return try {
        telecomManager.callCapablePhoneAccounts.size > 1
    } catch (_: Exception) {
        false
    }
}

@SuppressLint("MissingPermission")
fun Context.clearMissedCalls() {
    try {
        // notification cancellation triggers MissedCallNotifier.clearMissedCalls() which, in turn,
        // should update the database and reset the cached missed call count in MissedCallNotifier.java
        // https://android.googlesource.com/platform/packages/services/Telecomm/+/master/src/com/android/server/telecom/ui/MissedCallNotifierImpl.java#170
//        telecomManager.cancelMissedCallsNotification()

//        notificationManager.cancelAll()
        updateUnreadCountBadge(0)
        notificationManager.activeNotifications
            .filter { it.notification.channelId == MISSED_CALL_CHANNEL_ID }
            .forEach { notificationManager.cancel(it.id) }
        RecentsHelper(this).markMissedCallsAsRead()
    } catch (_: Exception) {
    }
}

/** Marks one number's missed calls read ("" = hidden numbers), drops its notification and refreshes badge/group. */
fun Context.clearMissedCallsOf(numberKey: String, callback: () -> Unit = {}) {
    notificationManager.cancel(numberKey.hashCode())
    RecentsHelper(this).markMissedCallsAsRead(numberKey) {
        try {
            syncMissedCallsState()
        } finally {
            callback()
        }
    }
}

/** Re-derives the launcher badge and the missed-call group from the call log. Blocking: call off the main thread. */
fun Context.syncMissedCallsState() {
    val total = RecentsHelper(this).getNewMissedCallCounts().values.sum()
    updateUnreadCountBadge(total)
    if (total == 0) notificationManager.cancel(MISSED_CALLS.hashCode())
}

fun Context.canLaunchAccountsConfiguration(): Boolean {
    return Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
        .resolveActivity(packageManager) != null
}

fun Context.launchAccountsConfiguration() {
    startActivity(Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS))
}

fun Activity.startAddContactIntent(phoneNumber: String) {
    Intent().apply {
        action = Intent.ACTION_INSERT_OR_EDIT
        type = "vnd.android.cursor.item/contact"
        putExtra(KEY_PHONE, phoneNumber)
        launchActivityIntent(this)
    }
}

// Opens a blank new-contact form with the number pre-filled. ACTION_INSERT always
// creates a new contact, unlike ACTION_INSERT_OR_EDIT which some OEM contacts apps
// (e.g. Samsung) treat as add-to-existing only.
fun Activity.startCreateNewContactIntent(phoneNumber: String) {
    Intent().apply {
        action = Intent.ACTION_INSERT
        type = android.provider.ContactsContract.Contacts.CONTENT_TYPE
        putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, phoneNumber)
        launchActivityIntent(this)
    }
}

// Single entry point for "add unsaved number to contacts": lets the user choose
// between creating a new contact and adding to an existing one, so every add-contact
// button behaves the same (and works on OEMs that otherwise force add-to-existing).
fun Activity.startAddNumberToContact(phoneNumber: String) {
    val createNew = 0
    val addExisting = 1
    val items = arrayListOf(
        RadioItem(createNew, getString(R.string.create_new_contact)),
        RadioItem(addExisting, getString(R.string.add_to_existing_contact))
    )
    RadioGroupDialog(this, items) {
        when (it as Int) {
            createNew -> startCreateNewContactIntent(phoneNumber)
            addExisting -> startAddContactIntent(phoneNumber)
        }
    }
}

//Goodwy
fun Context.updateUnreadCountBadge(count: Int) {
    if (count == 0) {
        ShortcutBadger.removeCount(this)
    } else {
        ShortcutBadger.applyCount(this, count)
    }
}

@SuppressLint("UseCompatLoadingForDrawables")
fun Context.getPackageDrawable(packageName: String): Drawable {
    return resources.getDrawable(
        when (packageName) {
            TELEGRAM_PACKAGE -> R.drawable.ic_telegram_vector
            SIGNAL_PACKAGE -> R.drawable.ic_signal_vector
            WHATSAPP_PACKAGE -> R.drawable.ic_whatsapp_vector
            VIBER_PACKAGE -> R.drawable.ic_viber_vector
            else -> R.drawable.ic_threema_vector
        }, theme
    )
}

// Upper bound for the blocking Glide loads below (they run on worker threads)
private const val GLIDE_BLOCKING_TIMEOUT_SECONDS = 5L

// You need to run it in ensureBackgroundThread {}
fun Context.getShortcutImageNeedBackground(path: String, placeholderName: String, callback: (image: Bitmap) -> Unit) {
    val placeholder = SimpleContactsHelper(this).getContactLetterIcon(placeholderName).toDrawable(resources)
    val bitmap = try {
        val options = RequestOptions()
            .format(DecodeFormat.PREFER_ARGB_8888)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .error(placeholder)
            .centerCrop()

        val size = resources.getDimension(R.dimen.shortcut_size).toInt()
        val target = Glide.with(this).asBitmap()
            .load(path)
            //.placeholder(placeholder)
            .apply(options)
            .apply(RequestOptions.circleCropTransform())
            .into(size, size)
        try {
            target.get(GLIDE_BLOCKING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            Glide.with(this).clear(target)
        }
    } catch (_: Exception) {
        null
    }
    // Outside the try: a throwing callback must not be invoked a second time with the placeholder
    callback(bitmap ?: placeholder.bitmap)
}

//Timer
val Context.timerDb: TimerDao
    get() = AppDatabase.getInstance(applicationContext).TimerDao()

val Context.timerHelper: TimerHelper
    get() = TimerHelper(this)

val Context.callerNotesHelper: CallerNotesHelper
    get() = CallerNotesHelper.getInstance(applicationContext)

fun Context.getOpenTimerTabIntent(timerId: Int): PendingIntent {
    val intent = getLaunchIntent() ?: Intent(this, SplashActivity::class.java)
    return PendingIntent.getActivity(
        this,
        timerId,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

fun Context.hideNotification(id: Int) {
    notificationManager.cancel(id)
}

fun Context.getTimerNotification(timer: Timer, pendingIntent: PendingIntent): Notification {
    var soundUri = timer.soundUri
    if (soundUri == SILENT) {
        soundUri = ""
    } else {
        grantReadUriPermission(soundUri)
    }

    // One channel per (sound, vibrate): channel settings are fixed once created, so a change gets a new id.
    val channelId = "right_dialer_timer_channel_${soundUri}_${timer.vibrate}"
    val timerId = timer.id ?: 0

    val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setLegacyStreamType(STREAM_ALARM)
        .build()

    val name = getString(R.string.timer)
    val importance = NotificationManager.IMPORTANCE_HIGH
    if (notificationManager.getNotificationChannel(channelId) == null) {
        NotificationChannel(channelId, name, importance).apply {
            setBypassDnd(true)
            enableLights(true)
            lightColor = getProperPrimaryColor()
            setSound(soundUri.toUri(), audioAttributes)

            if (!timer.vibrate) {
                vibrationPattern = longArrayOf(0L)
            }

            enableVibration(timer.vibrate)
            notificationManager.createNotificationChannel(this)
        }
    }

    val restart = Intent(this, TimerReceiver::class.java).apply {
//        action = TIMER_RESTART
        putExtra(TIMER_ID, timerId)
    }
    val cancelIntent = PendingIntent.getBroadcast(
        this, timerId, restart, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val collapsedView = RemoteViews(this.packageName, R.layout.timer_notification).apply {
        setText(R.id.timer_title, getString(R.string.remind_me))
        setText(R.id.timer_content, String.format(getString(R.string.call_back_person_g), timer.title))
        setOnClickPendingIntent(R.id.timer_repeat, cancelIntent)
    }

    val builder = NotificationCompat.Builder(this, channelId)
//        .setContentTitle(getString(R.string.remind_me))
//        .setContentText(String.format(getString(R.string.call_back_person_g), timer.title))
        .setCategory(Notification.CATEGORY_REMINDER)
        .setCustomContentView(collapsedView)
        .setSmallIcon(R.drawable.ic_remind_call)
        .setContentIntent(pendingIntent)
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .setDefaults(Notification.DEFAULT_LIGHTS)
        .setSound(soundUri.toUri(), STREAM_ALARM)
        .setChannelId(channelId)
        .setStyle(NotificationCompat.DecoratedCustomViewStyle())
        .addAction(
            com.goodwy.commons.R.drawable.ic_cross_vector,
            getString(com.goodwy.commons.R.string.dismiss),
            getHideTimerPendingIntent(timerId)
        )
        .addAction(
            R.drawable.ic_messages,
            getString(R.string.message),
            sendSMSPendingIntent(timer.label)
        )
        .addAction(
            R.drawable.ic_phone_vector,
            getString(R.string.call_back_g),
            startCallPendingIntent(timer.label, BuildConfig.RIGHT_APP_KEY)
        )

    builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

    if (timer.vibrate) {
        val vibrateArray = LongArray(2) { 500 }
        builder.setVibrate(vibrateArray)
    }

    val notification = builder.build()
    notification.flags = notification.flags or Notification.FLAG_INSISTENT
    return notification
}

fun Context.getHideTimerPendingIntent(timerId: Int): PendingIntent {
    val intent = Intent(this, TimerReceiver::class.java)
    intent.action = TIMER_HIDE
    intent.putExtra(TIMER_ID, timerId)
    return PendingIntent.getBroadcast(
        this,
        timerId,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

fun Context.startCallPendingIntentUpdateCurrent(recipient: String): PendingIntent {
    return PendingIntent.getActivity(
        this,
        0,
        Intent(Intent.ACTION_CALL, Uri.fromParts("tel", recipient, null))
            .putExtra(IS_RIGHT_APP, BuildConfig.RIGHT_APP_KEY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

fun Context.sendSMSPendingIntentUpdateCurrent(recipient: String): PendingIntent {
    return PendingIntent.getActivity(
        this,
        0,
        Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", recipient, null)),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

fun Context.hideTimerNotification(timerId: Int) = hideNotification(timerId)

fun Context.createNewTimer(): Timer {
    return Timer(
        id = 1,
        seconds = 600,
        state = TimerState.Idle,
        vibrate = config.callVibration,
        soundUri = getDefaultAlarmSound(RingtoneManager.TYPE_ALARM).uri,
        soundTitle = "",
        title = "Timer",
        label = "",
        description = "",
        createdAt = System.currentTimeMillis(),
        channelId = "right_dialer_timer_channel",
    )
}

fun Context.getNotificationBitmap(photoUri: String): Bitmap? {
    val size = resources.getDimension(R.dimen.contact_photo_size).toInt()
    if (photoUri.isEmpty()) {
        return null
    }

    val options = RequestOptions()
        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
        .centerCrop()

    return try {
        val target = Glide.with(this)
            .asBitmap()
            .load(photoUri)
            .apply(options)
            .apply(RequestOptions.circleCropTransform())
            .into(size, size)
        try {
            target.get(GLIDE_BLOCKING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            Glide.with(this).clear(target)
        }
    } catch (_: Exception) {
        null
    }
}

val Context.statusBarHeight: Int
    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    get() {
        var statusBarHeight = 0
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            statusBarHeight = resources.getDimensionPixelSize(resourceId)
        }
        return statusBarHeight
    }

// Private contacts live in another app's provider: query only from a background thread. `includeIgnored` is for
// hiding calls of private contacts when that source is disabled (needs the numbers); otherwise a disabled source is not queried.
private fun Context.privateContactsCursor(favoritesOnly: Boolean, includeIgnored: Boolean): Cursor? {
    if (!includeIgnored && SMT_PRIVATE in baseConfig.ignoredContactSources) return null
    return getMyContactsCursor(favoritesOnly = favoritesOnly, withPhoneNumbersOnly = true)
}

fun Context.loadPrivateContacts(favoritesOnly: Boolean = false, includeIgnored: Boolean = false): ArrayList<Contact> =
    MyContactsContentProvider.getContacts(this, privateContactsCursor(favoritesOnly, includeIgnored))

// System contacts first; the private-contacts provider is queried (and its cursor opened) only when needed.
fun Context.lookupContactSync(number: String): ContactLookupResult {
    val result = SimpleContactsHelper(this).existsSync(number)
    if (result == ContactLookupResult.Found) return result
    val privateContacts = MyContactsContentProvider.getSimpleContacts(this, privateContactsCursor(false, false))
    return if (privateContacts.any { it.doesHavePhoneNumber(number) }) ContactLookupResult.Found else result
}

/**
 * The settings the list rows are built from (adapters read them when they are created or bound). A change means the
 * visible adapters must be rebuilt; the lists' data and the open tab stay as they are.
 */
fun Context.displaySettingsKey(): String = with(config) {
    listOf(
        fontSize, useDividers, showContactThumbnails, baseConfig.contactThumbnailsSize, ellipsizeMode,
        baseConfig.useColoredContacts, contactColorList, colorSimIcons, simIconsColors.joinToString(","),
        formatPhoneNumbers, useRelativeDate, baseConfig.dateFormat, baseConfig.use24HourFormat,
        useSwipeToAction, swipeToActionWidth, swipeVibration, swipeRipple, swipeLeftAction, swipeRightAction,
        onFavoriteClick, onRecentClick, onContactClick
    ).joinToString("|")
}

/**
 * The contacts the lists show, from the repository's unfiltered [all]: without the sources hidden in the source
 * filter, duplicates merged when that setting is on, sorted by the current sort settings.
 * Queries the contact sources: background thread only.
 */
fun Context.listedContacts(all: List<Contact>): ArrayList<Contact> {
    val visibleSources = getVisibleContactSources().toHashSet()
    var contacts = all.filter { it.source in visibleSources }
    if (baseConfig.mergeDuplicateContacts) {
        contacts = contacts.groupBy { it.getNameToDisplay().lowercase(Locale.getDefault()) }.values.map { duplicates ->
            val longestFirst = duplicates.sortedByDescending { it.getStringToCompare().length }
            longestFirst.firstOrNull { it.phoneNumbers.isNotEmpty() } ?: longestFirst.first()
        }
    }
    Contact.sorting = baseConfig.sorting
    Contact.startWithSurname = baseConfig.startNameWithSurname
    Contact.showNicknameInsteadNames = baseConfig.showNicknameInsteadNames
    Contact.sortingSymbolsFirst = baseConfig.sortingSymbolsFirst
    return ArrayList(contacts.sorted())
}
