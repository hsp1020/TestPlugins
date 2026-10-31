package com.hsp1020.shared

import android.os.Build
import android.view.View
import android.widget.ImageView
import android.widget.TextView

/** Shared native card sizing/restoration. Compiled into both standalone plugins. */
internal class NativePosterCardState(
    private val title: TextView,
    private val card: View,
    private val image: ImageView
) {
    private val max = title.maxLines
    private val min = title.minLines
    val originalCardWidth = card.layoutParams.width
    private val originalCardHeight = card.layoutParams.height
    private val originalImageHeight = image.layoutParams.height
    private val originalTitleWidth = title.layoutParams.width
    private var changedTitleWidth = false
    private val horizontal = Build.VERSION.SDK_INT >= 29 && title.isSingleLine
    private var changedCard = false
    private var changedImage = false

    fun setTitleWidth(width: Int) {
        val params = title.layoutParams
        if (params.width != width) {
            params.width = width
            title.layoutParams = params
        }
        changedTitleWidth = true
    }

    fun setCardSize(width: Int, height: Int) {
        val params = card.layoutParams
        if (params.width != width || params.height != height) {
            params.width = width
            params.height = height
            card.layoutParams = params
        }
        changedCard = true
    }

    fun setCardWidth(width: Int) {
        val params = card.layoutParams
        if (params.width != width) {
            params.width = width
            card.layoutParams = params
        }
        changedCard = true
    }

    fun setImageHeight(height: Int) {
        val params = image.layoutParams
        if (params.height != height) {
            params.height = height
            image.layoutParams = params
        }
        changedImage = true
    }

    fun restoreSize() {
        if (changedTitleWidth) {
            val params = title.layoutParams
            if (params.width != originalTitleWidth) {
                params.width = originalTitleWidth
                title.layoutParams = params
            }
            changedTitleWidth = false
        }
        if (changedCard) {
            val params = card.layoutParams
            if (params.width != originalCardWidth || params.height != originalCardHeight) {
                params.width = originalCardWidth
                params.height = originalCardHeight
                card.layoutParams = params
            }
            changedCard = false
        }
        if (changedImage) {
            val params = image.layoutParams
            if (params.height != originalImageHeight) {
                params.height = originalImageHeight
                image.layoutParams = params
            }
            changedImage = false
        }
    }

    fun restore() {
        title.maxLines = max
        title.minLines = min
        if (horizontal) title.setSingleLine(true)
        restoreSize()
    }
}
