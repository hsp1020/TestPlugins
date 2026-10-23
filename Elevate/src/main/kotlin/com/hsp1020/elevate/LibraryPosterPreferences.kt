package com.hsp1020.elevate

import android.content.Context
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

internal enum class PosterService(val title: String, val host: String) {
    RPDB("RPDB", "api.ratingposterdb.com"),
    XRDB("XRDB", "xrdb.elfhosted.com"),
    OPDB("OpenPosterDB", "openposterdb.com"),
    AIORATINGS("AIORatings", "api.aioratings.com"),
    TOP_POSTERS("TOP Posters", "api.top-posters.com"),
    EASY_RATINGS("EasyRatingsDB", "easyratingsdb.com")
}

internal data class PosterIds(val imdb: String? = null, val tmdb: String? = null) {
    val available get() = imdb != null || tmdb != null
}

internal data class PosterConfig(
    val service: PosterService, val key: String, val template: String
) {
    val ready get() = key.isNotBlank() && (service != PosterService.XRDB || template.isNotBlank())

    fun image(ids: PosterIds, isMovie: Boolean): String? {
        if (!ready || !ids.available) return null
        val kind = if (isMovie) "movie" else "series"
        val type = if (ids.imdb != null) "imdb" else "tmdb"
        val id = ids.imdb ?: "${kind}-${ids.tmdb ?: return null}"
        val url = if (template.isNotBlank()) {
            val params = mapOf(
                "{key}" to Uri.encode(key), "{id}" to id, "{imdb}" to ids.imdb.orEmpty(),
                "{tmdb}" to ids.tmdb.orEmpty(), "{type}" to kind,
                "{id_type}" to type, "{tmdb_type}" to "${kind}-${ids.tmdb.orEmpty()}"
            )
            var result = template
            for ((name, value) in params) result = result.replace(name, value)
            if (result.contains('{') || result.contains('}') ||
                (ids.imdb == null && template.contains("{imdb}")) ||
                (ids.tmdb == null && (template.contains("{tmdb}") || template.contains("{tmdb_type}")))) return null
            result
        } else when (service) {
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
    fun get(context: Context): PosterConfig {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return PosterConfig(
            PosterService.entries.firstOrNull { it.name == prefs.getString("service", null) }
                ?: PosterService.RPDB,
            prefs.getString("key", "").orEmpty().trim(),
            prefs.getString("template", "").orEmpty().trim()
        )
    }

    fun controls(context: Context, content: LinearLayout, dp: (Int) -> Int): () -> Unit {
        val saved = get(context)
        fun title(text: String, smaller: Boolean = false) {
            content.addView(TextView(context).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (smaller) 12f else 15f)
                if (smaller) alpha = 0.75f
                setPadding(0, dp(8), 0, dp(4))
            })
        }
        title("Library Poster Service")
        val service = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                PosterService.entries.map { it.title })
            setSelection(saved.service.ordinal)
        }
        content.addView(service)
        title("Service API Key / Token")
        val key = EditText(context).apply {
            hint = "API Key"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(saved.key)
        }
        content.addView(key, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        title("Poster URL Template (optional; required for XRDB)")
        val template = EditText(context).apply {
            hint = "https://.../{key}/.../{id}.jpg"
            maxLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(saved.template)
        }
        content.addView(template, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        title("Tokens: {key}, {id}, {imdb}, {tmdb}, {type}, {id_type}, {tmdb_type}. " +
            "For XRDB, paste your saved profile poster URL and replace its title ID with a token. " +
            "Leave blank for the other services' standard poster URL.", true)
        return {
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("service", PosterService.entries[service.selectedItemPosition].name)
                .putString("key", key.text.toString().trim())
                .putString("template", template.text.toString().trim()).apply()
        }
    }
}
