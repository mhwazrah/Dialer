package com.mhw.phone.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.provider.CallLog.Calls
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.text.format.DateUtils
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.hasPermission
import com.goodwy.commons.extensions.normalizePhoneNumber
import com.goodwy.commons.helpers.PERMISSION_READ_CALL_LOG
import com.goodwy.commons.helpers.PERMISSION_READ_CONTACTS
import com.goodwy.commons.helpers.SMT_PRIVATE
import com.mhw.phone.R
import com.mhw.phone.activities.WidgetCallActivity
import com.mhw.phone.extensions.config
import com.mhw.phone.extensions.contactLetterBitmap
import com.mhw.phone.extensions.getNotificationBitmap
import com.mhw.phone.extensions.loadPrivateContacts
import com.mhw.phone.helpers.ContactsRepository
import com.mhw.phone.helpers.blockedNumberKey

private const val EXTRA_KIND = "widget_kind"
private const val KIND_FAVORITES = "favorites"
private const val KIND_RECENTS = "recents"
private const val MAX_FAVORITES = 12
private const val MAX_RECENTS = 20

/** One tappable person in a widget; an empty [number] (hidden caller) is shown but cannot be called. */
private data class WidgetEntry(val name: String, val number: String, val photoUri: String, val detail: String = "", val type: Int = 0)

class FavoritesWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        updateWidgets(context, manager, ids, KIND_FAVORITES, R.layout.widget_favorites)
}

class RecentsWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        updateWidgets(context, manager, ids, KIND_RECENTS, R.layout.widget_recents)
}

/** Contacts or the call log changed: both widgets reload their rows (no-op when none is placed). */
fun refreshCallWidgets(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    listOf(FavoritesWidgetProvider::class.java, RecentsWidgetProvider::class.java).forEach { provider ->
        val ids = manager.getAppWidgetIds(ComponentName(context, provider))
        if (ids.isNotEmpty()) manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
    }
}

private fun updateWidgets(context: Context, manager: AppWidgetManager, ids: IntArray, kind: String, layout: Int) {
    ids.forEach { id ->
        val serviceIntent = Intent(context, CallWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putExtra(EXTRA_KIND, kind)
            data = "widget://$kind/$id".toUri() // one factory per widget
        }
        // Explicit and non-exported: rows fill in only the number and name
        val callTemplate = PendingIntent.getActivity(
            context, kind.hashCode(), Intent(context, WidgetCallActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val views = RemoteViews(context.packageName, layout).apply {
            setRemoteAdapter(R.id.widget_list, serviceIntent)
            setEmptyView(R.id.widget_list, R.id.widget_empty)
            setPendingIntentTemplate(R.id.widget_list, callTemplate)
        }
        manager.updateAppWidget(id, views)
    }
    manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
}

class CallWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        CallWidgetFactory(applicationContext, intent.getStringExtra(EXTRA_KIND) == KIND_FAVORITES)
}

private class CallWidgetFactory(private val context: Context, private val favorites: Boolean) : RemoteViewsService.RemoteViewsFactory {
    private var entries: List<WidgetEntry> = emptyList()
    private val photos = HashMap<String, Bitmap?>()

    override fun onCreate() = Unit
    override fun onDestroy() = Unit
    override fun getCount() = entries.size
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount() = 1
    override fun getItemId(position: Int) = position.toLong()
    override fun hasStableIds() = false

    // Binder thread: blocking reads are fine here
    override fun onDataSetChanged() {
        photos.clear()
        entries = try {
            if (favorites) context.readFavorites() else context.readRecents()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    override fun getViewAt(position: Int): RemoteViews {
        val entry = entries.getOrNull(position) ?: return RemoteViews(context.packageName, R.layout.widget_item_recent)
        val layout = if (favorites) R.layout.widget_item_favorite else R.layout.widget_item_recent
        return RemoteViews(context.packageName, layout).apply {
            setTextViewText(R.id.widget_item_name, entry.name)
            setImageViewBitmap(R.id.widget_item_photo, photo(entry))
            if (!favorites) {
                setTextViewText(R.id.widget_item_detail, entry.detail)
                setImageViewResource(R.id.widget_item_type, typeIcon(entry.type))
                setInt(R.id.widget_item_type, "setColorFilter", ContextCompat.getColor(context, R.color.widget_text_secondary))
            }
            if (entry.number.isNotEmpty()) {
                setOnClickFillInIntent(R.id.widget_item, WidgetCallActivity.fillIn(entry.number, entry.name, entry.photoUri))
            }
        }
    }

    // Photos are decoded once per refresh and per number, at notification-avatar size
    private fun photo(entry: WidgetEntry): Bitmap? = photos.getOrPut(entry.photoUri + entry.name) {
        context.getNotificationBitmap(entry.photoUri) ?: context.contactLetterBitmap(entry.name)
    }
}

/**
 * Whether [number] is one the widgets show. A widget tap's number is filled in by the widget host, so it is checked
 * against our own lists before calling: another app hosting the widget cannot use it to call any number. Blocking.
 */
fun Context.isShownInWidgets(number: String): Boolean = try {
    (readFavorites() + readRecents()).any { it.number == number }
} catch (_: SecurityException) {
    false
}

private fun Context.readFavorites(): List<WidgetEntry> {
    if (!hasPermission(PERMISSION_READ_CONTACTS)) return emptyList()
    val projection = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.PHOTO_THUMBNAIL_URI)
    val byContact = LinkedHashMap<Long, WidgetEntry>()
    // The primary number first, so each favourite is called on it
    val order = "${Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC, ${Phone.IS_SUPER_PRIMARY} DESC"
    contentResolver.query(Phone.CONTENT_URI, projection, "${Phone.STARRED} = 1", null, order)?.use { cursor ->
        while (cursor.moveToNext()) {
            val number = cursor.getString(2) ?: continue
            byContact.getOrPut(cursor.getLong(0)) {
                WidgetEntry(cursor.getString(1).orEmpty().ifBlank { number }, number, cursor.getString(3).orEmpty())
            }
        }
    }
    return byContact.values.take(MAX_FAVORITES)
}

private fun Context.readRecents(): List<WidgetEntry> {
    if (!hasPermission(PERMISSION_READ_CALL_LOG)) return emptyList()
    // Hidden contacts and blocked callers stay hidden here too, as in Recents
    val privateNumbers = if (SMT_PRIVATE in baseConfig.ignoredContactSources) {
        loadPrivateContacts(includeIgnored = true).flatMap { it.phoneNumbers }.mapTo(HashSet()) { it.normalizedNumber }
    } else {
        emptySet()
    }
    val showBlocked = config.showBlockedNumbers
    val projection = arrayOf(Calls.NUMBER, Calls.CACHED_NAME, Calls.TYPE, Calls.DATE, Calls.CACHED_PHOTO_URI)
    val entries = ArrayList<WidgetEntry>()
    contentResolver.query(Calls.CONTENT_URI, projection, null, null, "${Calls.DATE} DESC")?.use { cursor ->
        while (cursor.moveToNext() && entries.size < MAX_RECENTS) {
            val hidden = cursor.getInt(2) == Calls.BLOCKED_TYPE && !showBlocked
            if (!hidden) recentEntry(cursor, privateNumbers)?.let(entries::add)
        }
    }
    return entries
}

/** The row under [cursor], or null for a hidden contact's number. */
private fun Context.recentEntry(cursor: Cursor, privateNumbers: Set<String>): WidgetEntry? {
    val number = cursor.getString(0).orEmpty().takeIf { blockedNumberKey(it).isNotEmpty() }.orEmpty()
    if (number.isNotEmpty() && number.normalizePhoneNumber() in privateNumbers) return null
    val cachedName = cursor.getString(1)?.takeIf { it.isNotBlank() }
    // The log's cached name is empty for a number saved after the call: look the contact up then
    val contact = if (cachedName == null && number.isNotEmpty()) ContactsRepository.findByNumber(this, number) else null
    val name = cachedName ?: contact?.getNameToDisplay() ?: number.ifEmpty { getString(R.string.unknown_caller) }
    val photoUri = cursor.getString(4)?.takeIf { it.isNotBlank() } ?: contact?.photoUri.orEmpty()
    val time = DateUtils.getRelativeTimeSpanString(cursor.getLong(3), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    return WidgetEntry(name, number, photoUri, time.toString(), cursor.getInt(2))
}

private fun typeIcon(type: Int) = when (type) {
    Calls.OUTGOING_TYPE -> R.drawable.ic_call_made_vector
    Calls.MISSED_TYPE, Calls.REJECTED_TYPE, Calls.BLOCKED_TYPE -> R.drawable.ic_call_missed_vector
    else -> R.drawable.ic_call_received_vector
}
