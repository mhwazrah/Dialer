package com.goodwy.dialer.helpers

import android.content.Context
import android.graphics.*
import android.util.Size
import com.goodwy.commons.helpers.isQPlus
import com.goodwy.dialer.R
import androidx.core.net.toUri
import androidx.core.graphics.createBitmap

class CallContactAvatarHelper(private val context: Context) {
    fun getCallContactAvatar(photoUri: String?, round: Boolean = true, width: Int = 0, height: Int = 0): Bitmap? {
        if (photoUri.isNullOrEmpty()) return null
        val photoUriParse = photoUri.toUri()
        return try {
            val contentResolver = context.contentResolver
            val defaultSize = context.resources.getDimension(R.dimen.list_avatar_size).toInt()
            val size = if (round || width <= 0 || height <= 0) Size(defaultSize, defaultSize) else Size(width, height)
            val bitmap = if (isQPlus()) {
                contentResolver.loadThumbnail(photoUriParse, size, null)
            } else {
                contentResolver.openInputStream(photoUriParse)?.use { BitmapFactory.decodeStream(it) }
            } ?: return null
            if (round) getCircularBitmap(bitmap).also { bitmap.recycle() } else bitmap
        } catch (_: Exception) {
            null
        }
    }

    /** Centre-crops to a square, then clips to a circle, so non-square photos are not distorted. */
    fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val diameter = minOf(bitmap.width, bitmap.height)
        val output = createBitmap(diameter, diameter)
        val canvas = Canvas(output)
        val paint = Paint()
        val source = Rect((bitmap.width - diameter) / 2, (bitmap.height - diameter) / 2, (bitmap.width + diameter) / 2, (bitmap.height + diameter) / 2)
        val target = Rect(0, 0, diameter, diameter)
        val radius = diameter / 2f

        paint.isAntiAlias = true
        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawCircle(radius, radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, source, target, paint)
        return output
    }
}
