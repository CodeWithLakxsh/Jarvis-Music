package com.laksh.jarvismusic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.palette.graphics.Palette
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

val Fragment.mainActivity: MainActivity?
    get() = activity as? MainActivity

fun Context.dp(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

/** Loads artwork with a neutral placeholder, matching Spotify's grey tiles. */
fun ImageView.loadArt(url: String?) {
    Glide.with(this)
        .load(url?.takeIf { it.isNotBlank() })
        .placeholder(R.drawable.placeholder_art)
        .error(R.drawable.placeholder_art)
        .fallback(R.drawable.placeholder_art)
        .into(this)
}

/** Adds the status-bar height on top of the view's existing top padding. */
fun View.applyStatusBarPadding() {
    val initialTop = paddingTop
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
        v.updatePadding(top = initialTop + top)
        insets
    }
    requestApplyInsetsWhenAttached()
}

/** Adds the navigation-bar height below the view's existing bottom padding. */
fun View.applyNavigationBarPadding() {
    val initialBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
        v.updatePadding(bottom = initialBottom + bottom)
        insets
    }
    requestApplyInsetsWhenAttached()
}

/** Adds the navigation-bar height to the view's bottom margin. */
fun View.applyNavigationBarMargin() {
    val initialBottom = (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: return
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
        v.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = initialBottom + bottom }
        insets
    }
    requestApplyInsetsWhenAttached()
}

fun View.requestApplyInsetsWhenAttached() {
    if (isAttachedToWindow) {
        ViewCompat.requestApplyInsets(this)
    } else {
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                ViewCompat.requestApplyInsets(v)
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }
}

object ArtColors {
    val DEFAULT = Color.parseColor("#535353")

    /**
     * Extracts a dark, saturated colour from the artwork (like Spotify's
     * now-playing background). [onColor] is called on the main thread.
     */
    fun extract(context: Context, url: String?, onColor: (Int) -> Unit) {
        if (url.isNullOrBlank()) {
            onColor(DEFAULT)
            return
        }
        Glide.with(context.applicationContext)
            .asBitmap()
            .load(url)
            .override(128, 128)
            .disallowHardwareConfig() // Palette needs to read the pixels
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    Palette.from(resource).generate { palette ->
                        val swatch = palette?.darkVibrantSwatch
                            ?: palette?.vibrantSwatch
                            ?: palette?.darkMutedSwatch
                            ?: palette?.dominantSwatch
                        onColor(swatch?.rgb?.let { toBackground(it) } ?: DEFAULT)
                    }
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    onColor(DEFAULT)
                }

                override fun onLoadCleared(placeholder: Drawable?) = Unit
            })
    }

    /** Keeps colours dark enough for white text on top. */
    fun toBackground(color: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[2] = hsl[2].coerceIn(0.18f, 0.32f)
        return ColorUtils.HSLToColor(hsl)
    }
}
