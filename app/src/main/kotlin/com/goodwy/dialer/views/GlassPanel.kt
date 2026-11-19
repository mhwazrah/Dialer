package com.goodwy.dialer.views

import android.content.Context
import android.util.AttributeSet
import android.view.View
import com.example.liquidglass.LiquidGlassView
import com.goodwy.dialer.extensions.applyLiquidGlass

/**
 * A [LiquidGlassView] pre-configured with the app's glass style, so layouts can declare glass
 * with just the tag. Corner radius comes from `app:cornerRadius` (library default: pill);
 * `app:backdropSourceId` points it at a specific backdrop. Without one (or when that id is not in
 * this screen's tree) it refracts the whole activity content, which carries the screen backdrop:
 * the library default — the direct parent — is usually a transparent container whose capture is
 * empty, which renders the glass dark.
 */
class GlassPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LiquidGlassView(context, attrs, defStyleAttr) {
    init {
        applyLiquidGlass(pressable = isClickable)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow() // resolves app:backdropSourceId
        if (backdropSource == null) {
            backdropSource = rootView.findViewById<View>(android.R.id.content)
        }
    }
}
