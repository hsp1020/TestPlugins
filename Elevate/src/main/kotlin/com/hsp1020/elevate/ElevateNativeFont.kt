package com.hsp1020.elevate

import android.graphics.Typeface
import android.os.Build
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.lagradost.cloudstream3.R

/** Use CloudStream's default font family without changing each view's weight or layout. */
internal fun TextView.applyElevateNativeFont(
    reference: Typeface? = typeface,
    bold: Boolean = reference?.isBold == true
) {
    val family = ResourcesCompat.getFont(context, R.font.google_sans) ?: return
    typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Typeface.create(family, if (bold) 700 else reference?.weight ?: 400, reference?.isItalic == true)
    } else {
        val italic = (reference?.style ?: Typeface.NORMAL) and Typeface.ITALIC
        Typeface.create(family, italic or if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
}
