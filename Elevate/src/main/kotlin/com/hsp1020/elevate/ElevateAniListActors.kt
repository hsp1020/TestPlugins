package com.hsp1020.elevate

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ActorRole
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal enum class ElevateActorSource { TMDB, ANILIST_STAFF, ANILIST_CHARACTER }

/** AniList IDs are kept separate from TMDB IDs, including the secondary voice actor. */
internal object ElevateAniListActors {
    private const val PERSON = "id name { userPreferred full native } image { large medium }"
    private const val MEDIA = "id type format title { userPreferred romaji english native } coverImage { large medium } startDate { year month day } popularity"
    private const val STAFF = "pageInfo { hasNextPage } edges { role node { $PERSON } }"

    private fun JSONObject.string(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONObject.name(): String? = optJSONObject("name")?.let {
        it.string("userPreferred") ?: it.string("full") ?: it.string("native")
    }

    private fun JSONObject.photo(): String? = optJSONObject("image")?.let {
        it.string("large") ?: it.string("medium")
    }

    private suspend fun query(query: String): JSONObject {
        val response = app.post("https://graphql.anilist.co", data = mapOf("query" to query),
            cacheTime = 0, timeout = 15L)
        check(response.code in 200..299) { "AniList HTTP ${response.code}" }
        val root = JSONObject(response.text)
        return root.optJSONObject("data") ?: error(
            root.optJSONArray("errors")?.optJSONObject(0)?.string("message") ?: "AniList data unavailable")
    }

    private fun JSONObject.ref(source: ElevateActorSource, crew: Boolean = false): ElevateActorPersonRef? {
        val id = optInt("id").takeIf { it > 0 } ?: return null
        return ElevateActorPersonRef(id, name() ?: return null, photo(), crew, source)
    }

    suspend fun cast(id: Int): List<Pair<ActorData, ElevateActorPersonRef>> {
        val media = query("{ Media(id: $id, type: ANIME) { characters(sort: ROLE, page: 1, perPage: 20) { edges { role node { $PERSON } voiceActors { $PERSON } } } staff(page: 1, perPage: 25) { $STAFF } } }")
            .optJSONObject("Media") ?: return emptyList()
        val characters = media.optJSONObject("characters")?.optJSONArray("edges").objects().mapNotNull { edge ->
            val ref = edge.optJSONObject("node")?.ref(ElevateActorSource.ANILIST_CHARACTER)
                ?: return@mapNotNull null
            val voice = edge.optJSONArray("voiceActors").objects().firstNotNullOfOrNull {
                it.ref(ElevateActorSource.ANILIST_STAFF)
            }
            val role = when (edge.string("role")) {
                "MAIN" -> ActorRole.Main
                "SUPPORTING" -> ActorRole.Supporting
                "BACKGROUND" -> ActorRole.Background
                else -> null
            }
            ActorData(Actor(ref.name, ref.photo), role = role,
                voiceActor = voice?.let { Actor(it.name, it.photo) }) to ref.copy(voiceId = voice?.id)
        }
        val crew = linkedMapOf<Int, Pair<ElevateActorPersonRef, MutableSet<String>>>()
        var staff = media.optJSONObject("staff")
        var page = 1
        while (staff != null) {
            for (edge in staff.optJSONArray("edges").objects()) {
                val role = edge.string("role") ?: continue
                if (role != "Original Creator" && role != "Director") continue
                val ref = edge.optJSONObject("node")?.ref(ElevateActorSource.ANILIST_STAFF, true) ?: continue
                crew.getOrPut(ref.id ?: continue) { ref to linkedSetOf() }.second.add(role)
            }
            if (staff.optJSONObject("pageInfo")?.optBoolean("hasNextPage") != true) break
            page++
            staff = try {
                query("{ Media(id: $id, type: ANIME) { staff(page: $page, perPage: 25) { $STAFF } } }")
                    .optJSONObject("Media")?.optJSONObject("staff")
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { break }
        }
        val creators = crew.values.sortedBy { (_, roles) ->
            when { roles.size > 1 -> 0; "Original Creator" in roles -> 1; else -> 2 }
        }.map { (ref, roles) ->
            ActorData(Actor(ref.name, ref.photo),
                roleString = listOf("Original Creator", "Director").filter { it in roles }.joinToString(", ")) to ref
        }
        return creators + characters
    }

    private fun date(obj: JSONObject?): String? {
        if (obj == null) return null
        val y = obj.optInt("year").takeIf { it > 0 }
        val m = obj.optInt("month").takeIf { it in 1..12 }
        val d = obj.optInt("day").takeIf { it in 1..31 }
        return when {
            y != null && m != null && d != null -> "%04d-%02d-%02d".format(java.util.Locale.US, y, m, d)
            y != null && m != null -> "%04d-%02d".format(java.util.Locale.US, y, m)
            y != null -> y.toString()
            m != null && d != null -> "%02d-%02d".format(java.util.Locale.US, m, d)
            else -> null
        }
    }

    private fun root(source: ElevateActorSource) =
        if (source == ElevateActorSource.ANILIST_STAFF) "Staff" else "Character"

    suspend fun profile(id: Int, source: ElevateActorSource): JSONObject {
        val root = root(source)
        val extra = if (source == ElevateActorSource.ANILIST_STAFF)
            "dateOfDeath { year month day } homeTown primaryOccupations yearsActive" else ""
        val node = query("{ $root(id: $id) { $PERSON description(asHtml: false) dateOfBirth { year month day } age bloodType $extra } }")
            .optJSONObject(root) ?: error("AniList profile unavailable")
        val jobs = node.optJSONArray("primaryOccupations")?.let { a ->
            (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() && s != "null" } }
                .joinToString(", ")
        }
        val years = node.optJSONArray("yearsActive")?.let { a ->
            (0 until a.length()).mapNotNull { a.optInt(it).takeIf { y -> y > 0 } }.joinToString("–")
        }
        return JSONObject().apply {
            put("name", node.name())
            put("biography", node.string("description"))
            put("birthday", date(node.optJSONObject("dateOfBirth")))
            put("deathday", date(node.optJSONObject("dateOfDeath")))
            put("place_of_birth", node.string("homeTown"))
            put("known_for_department", jobs?.takeIf(String::isNotBlank))
            put("age", node.string("age"))
            put("blood_type", node.string("bloodType"))
            put("years_active", years?.takeIf(String::isNotBlank))
        }
    }

    /** Fetch one page of each ordering; the dialog requests later pages only on demand. */
    suspend fun credits(id: Int, source: ElevateActorSource, page: Int): JSONObject {
        val root = root(source)
        val fields = if (source == ElevateActorSource.ANILIST_STAFF)
            listOf("staffMedia" to "staffRole", "characterMedia" to "characters { name { userPreferred full native } }")
        else listOf("media" to "characterRole")
        val selections = fields.flatMapIndexed { index, (field, edgeFields) ->
            listOf("POPULARITY_DESC", "START_DATE_DESC").mapIndexed { order, sort ->
                val type = if (field == "characterMedia") "" else ", type: ANIME"
                "c${index}_$order: $field(page: $page, perPage: 25, sort: $sort$type) { pageInfo { hasNextPage } edges { $edgeFields node { $MEDIA } } }"
            }
        }
        val node = query("{ $root(id: $id) { ${selections.joinToString(" ")} } }")
            .optJSONObject(root) ?: error("AniList credits unavailable")
        val items = JSONArray()
        var more = false
        for (index in fields.indices) for (order in 0..1) {
            val connection = node.optJSONObject("c${index}_$order") ?: continue
            more = more || connection.optJSONObject("pageInfo")?.optBoolean("hasNextPage") == true
            for (edge in connection.optJSONArray("edges").objects()) {
                val media = edge.optJSONObject("node") ?: continue
                if (media.string("type") != "ANIME") continue
                val title = media.optJSONObject("title")?.let {
                    it.string("userPreferred") ?: it.string("romaji") ?: it.string("english") ?: it.string("native")
                } ?: continue
                val movie = media.string("format") == "MOVIE"
                val role = edge.string("staffRole") ?: edge.string("characterRole")
                    ?: edge.optJSONArray("characters").objects().mapNotNull { it.name() }
                        .distinct().joinToString(", ").takeIf(String::isNotBlank)
                items.put(JSONObject().apply {
                    put("id", media.optInt("id"))
                    put("media_type", if (movie) "movie" else "tv")
                    put(if (movie) "title" else "name", title)
                    put("poster_path", media.optJSONObject("coverImage")?.let {
                        it.string("large") ?: it.string("medium")
                    })
                    put(if (movie) "release_date" else "first_air_date", date(media.optJSONObject("startDate")))
                    put("popularity", media.optInt("popularity"))
                    put("credit_role", role)
                })
            }
        }
        return JSONObject().put("cast", items).put("hasNextPage", more)
    }
}
