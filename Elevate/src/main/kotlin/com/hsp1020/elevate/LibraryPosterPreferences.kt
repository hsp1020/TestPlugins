package com.hsp1020.elevate

import android.content.Context
import android.net.Uri
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged

private class ApiKeyEyeIcon(private val iconColor: Int, private val visible: Boolean, private val iconSize: Int) : Drawable() {
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = iconColor
        }

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            val scale = minOf(bounds.width(), bounds.height()) / 24f
            val saved = canvas.save()
            canvas.translate(bounds.exactCenterX() - 12f * scale, bounds.exactCenterY() - 12f * scale)
            canvas.scale(scale, scale)
            val outline = Path().apply {
                moveTo(2f, 12f)
                cubicTo(5f, 8f, 8f, 6f, 12f, 6f)
                cubicTo(16f, 6f, 19f, 8f, 22f, 12f)
                cubicTo(19f, 16f, 16f, 18f, 12f, 18f)
                cubicTo(8f, 18f, 5f, 16f, 2f, 12f)
                close()
            }
            canvas.drawPath(outline, pen)
            canvas.drawCircle(12f, 12f, 3f, pen)
            if (!visible) canvas.drawLine(3f, 21f, 21f, 3f, pen)
            canvas.restoreToCount(saved)
        }

        override fun setAlpha(alpha: Int) {
            pen.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            pen.colorFilter = colorFilter
            invalidateSelf()
        }

        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = iconSize
        override fun getIntrinsicHeight(): Int = iconSize
    }

internal enum class PosterService(val title: String, val host: String) {
    RPDB("RPDB", "api.ratingposterdb.com"),
    XRDB("XRDB", "xrdb.elfhosted.com"),
    OPDB("OpenPosterDB", "openposterdb.com"),
    AIORATINGS("AIORatings", "api.aioratings.com"),
    TOP_POSTERS("TOP Posters", "api.top-posters.com"),
    EASY_RATINGS("EasyRatingsDB", "easyratingsdb.com")
}

internal data class PosterIds(
    val imdb: String? = null,
    val tmdb: String? = null,
    val mal: String? = null,
    val anilist: String? = null,
    val kitsu: String? = null
) {
    val available get() = imdb != null || tmdb != null ||
        mal != null || anilist != null || kitsu != null
}

internal data class PosterConfig(
    val service: PosterService,
    val key: String,
    val xrdbProfile: String,
    val xrdbBase: String,
    val xrdbRenderKey: String
) {
    val ready get() = if (service == PosterService.XRDB) xrdbBase.isNotBlank() else key.isNotBlank()

    fun image(ids: PosterIds, isMovie: Boolean): String? {
        if (!ready || !ids.available) return null
        val kind = if (isMovie) "movie" else "series"
        if (service == PosterService.XRDB) {
            val base = Uri.parse(xrdbBase.trim().trimEnd('/'))
            if (base.scheme != "https" || base.host.isNullOrBlank() ||
                !base.userInfo.isNullOrBlank() || !base.query.isNullOrBlank() ||
                !base.fragment.isNullOrBlank()) return null
            val artworkId = ids.imdb
                ?: ids.tmdb?.let { "tmdb:${kind}:${it}" }
                ?: ids.mal?.let { "mal:${it}" }
                ?: ids.anilist?.let { "anilist:${it}" }
                ?: ids.kitsu?.let { "kitsu:${it}" }
                ?: return null
            return base.buildUpon().appendPath("poster").appendPath(artworkId)
                .apply {
                    if (xrdbProfile.isNotBlank()) appendQueryParameter("config", xrdbProfile)
                    if (xrdbRenderKey.isNotBlank()) appendQueryParameter("key", xrdbRenderKey)
                }.build().toString()
        }
        if (ids.imdb == null && ids.tmdb == null) return null
        val type = if (ids.imdb != null) "imdb" else "tmdb"
        val id = ids.imdb ?: "${kind}-${ids.tmdb ?: return null}"
        val url = when (service) {
            PosterService.RPDB, PosterService.OPDB, PosterService.AIORATINGS ->
                "https://${service.host}/${Uri.encode(key)}/${type}/poster-default/${id}.jpg"
            PosterService.TOP_POSTERS ->
                "https://${service.host}/${Uri.encode(key)}/${type}/poster/${id}.jpg"
            PosterService.EASY_RATINGS -> {
                val externalId = ids.imdb ?: "tmdb:${ids.tmdb ?: return null}"
                "https://${service.host}/${Uri.encode(key)}/poster/${externalId}.jpg?type=${kind}"
            }
            PosterService.XRDB -> return null
        }
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        return url.takeIf { parsed.scheme == "https" && !parsed.host.isNullOrBlank() }
    }
}

internal object LibraryPosterPrefs {
    const val FILE = "ElevateLibraryPosters"
    private const val DEFAULT_XRDB_BASE = "https://xrdb.elfhosted.com"

    fun get(context: Context): PosterConfig {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val legacyUrl = prefs.getString("template", "").orEmpty().trim()
        val legacy = runCatching { Uri.parse(legacyUrl).takeIf { it.scheme == "https" } }.getOrNull()
        val legacyBase = legacy?.host?.takeIf { it.isNotBlank() }?.let {
            "https://${it}${legacy.encodedPath?.substringBefore("/poster/").orEmpty()}"
        }
        return PosterConfig(
            PosterService.entries.firstOrNull { it.name == prefs.getString("service", null) }
                ?: PosterService.RPDB,
            prefs.getString("key", "").orEmpty().trim(),
            prefs.getString("xrdb_profile", null) ?: legacy?.getQueryParameter("config").orEmpty(),
            prefs.getString("xrdb_base", null) ?: legacyBase ?: DEFAULT_XRDB_BASE,
            prefs.getString("xrdb_render_key", null) ?: legacy?.getQueryParameter("key").orEmpty()
        )
    }

    fun controls(context: Context, content: LinearLayout, dp: (Int) -> Int): () -> Unit {
        val saved = get(context)
        fun title(parent: LinearLayout, label: String, smaller: Boolean = false) {
            parent.addView(TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (smaller) 12f else 15f)
                if (smaller) alpha = 0.75f
                setPadding(0, dp(8), 0, dp(4))
            })
        }
        fun field(value: String, hintText: String, secret: Boolean = false) =
            EditText(context).apply {
                setSingleLine(true)
                hint = hintText
                inputType = if (secret) InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setText(value)
            }
        fun addField(parent: LinearLayout, field: EditText, secret: Boolean = false) {
            if (!secret) {
                parent.addView(field, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                return
            }
            val row = FrameLayout(context)
            row.addView(field, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            field.setPadding(field.paddingLeft, field.paddingTop,
                field.paddingRight + dp(48), field.paddingBottom)
            var revealed = false
            val toggle = ImageButton(context).apply {
                val ripple = TypedValue()
                if (context.theme.resolveAttribute(
                        android.R.attr.selectableItemBackgroundBorderless, ripple, true
                    ) && ripple.resourceId != 0) {
                    setBackgroundResource(ripple.resourceId)
                } else {
                    background = null
                }
                scaleType = ImageView.ScaleType.CENTER
                isFocusable = true
                isFocusableInTouchMode = false
                contentDescription = "Show API key"
                setImageDrawable(ApiKeyEyeIcon(field.currentTextColor, false, dp(24)))
                setOnClickListener {
                    val start = field.selectionStart.coerceAtLeast(0)
                    val end = field.selectionEnd.coerceAtLeast(0)
                    revealed = !revealed
                    field.transformationMethod =
                        if (revealed) null else PasswordTransformationMethod.getInstance()
                    val length = field.text?.length ?: 0
                    field.setSelection(start.coerceAtMost(length), end.coerceAtMost(length))
                    setImageDrawable(ApiKeyEyeIcon(field.currentTextColor, revealed, dp(24)))
                    contentDescription = if (revealed) "Hide API key" else "Show API key"
                }
            }
            row.addView(toggle, FrameLayout.LayoutParams(dp(48), dp(48),
                Gravity.END or Gravity.CENTER_VERTICAL))
            parent.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        title(content, "Library Poster Service")
        val service = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                PosterService.entries.map { it.title })
            setSelection(saved.service.ordinal)
        }
        content.addView(service)

        val keyGroup = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        title(keyGroup, "Service API Key / Token")
        val key = field(saved.key, "API Key", secret = true)
        addField(keyGroup, key, secret = true)
        content.addView(keyGroup)

        val xrdbGroup = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        title(xrdbGroup, "XRDB Profile UUID / Alias")
        val profile = field(saved.xrdbProfile, "Saved profile UUID or alias")
        addField(xrdbGroup, profile)
        title(xrdbGroup, "XRDB Server URL")
        val base = field(saved.xrdbBase, DEFAULT_XRDB_BASE)
        addField(xrdbGroup, base)
        title(xrdbGroup, "Profile is optional for the server's default appearance. " +
            "The public XRDB instance does not need an API key.", true)
        val privateKeyGroup = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        title(privateKeyGroup, "Instance API Key (only for protected self-hosted XRDB)")
        val renderKey = field(saved.xrdbRenderKey, "XRDB_API_KEY", secret = true)
        addField(privateKeyGroup, renderKey, secret = true)
        xrdbGroup.addView(privateKeyGroup)
        content.addView(xrdbGroup)

        fun updateFields() {
            val xrdb = PosterService.entries[service.selectedItemPosition] == PosterService.XRDB
            keyGroup.visibility = if (xrdb) View.GONE else View.VISIBLE
            xrdbGroup.visibility = if (xrdb) View.VISIBLE else View.GONE
            val host = runCatching { Uri.parse(base.text.toString().trim()).host?.lowercase() }.getOrNull()
            privateKeyGroup.visibility = if (xrdb && host != null &&
                host !in setOf("xrdb.elfhosted.com", "extendedratings.com", "www.extendedratings.com")
            ) View.VISIBLE else View.GONE
        }
        service.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) = updateFields()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        base.doAfterTextChanged { updateFields() }
        updateFields()

        return {
            val isPublic = runCatching {
                Uri.parse(base.text.toString().trim()).host?.lowercase() in
                    setOf("xrdb.elfhosted.com", "extendedratings.com", "www.extendedratings.com")
            }.getOrDefault(false)
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("service", PosterService.entries[service.selectedItemPosition].name)
                .putString("key", key.text.toString().trim())
                .putString("xrdb_profile", profile.text.toString().trim())
                .putString("xrdb_base", base.text.toString().trim())
                .putString("xrdb_render_key", if (isPublic) "" else renderKey.text.toString().trim())
                .remove("template")
                .apply()
        }
    }
}
