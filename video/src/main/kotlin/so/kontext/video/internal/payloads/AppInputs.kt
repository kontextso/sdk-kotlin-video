package so.kontext.video.internal.payloads

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import so.kontext.video.KontextVideo.Content
import so.kontext.video.KontextVideo.Regulatory
import so.kontext.video.KontextVideo.User

// How the app's Content, User and Regulatory go on the wire, as Swift's Payloads/AppInputs.swift: the public
// models are plain values, and the rules of the request (what is required, what is left out) live here, so a
// change to the wire is never a change to the public API.

/** Required fields left empty: every request would fail at the server. */
internal val Content.missingRequired: List<String>
    get() = listOfNotNull(if (id.isEmpty()) "id is empty" else null, if (language.isEmpty()) "language is empty" else null)

/** Optional metadata the request leaves out because the server would refuse it, one phrase each. */
internal val Content.leftOut: List<String>
    get() = listOfNotNull(
        durationMillis?.takeIf { it < 1 }?.let { "durationMillis $it left out" },
        episodeNumber?.takeIf { it < 1 }?.let { "episodeNumber $it left out" },
        series?.takeIf { it.id.isEmpty() }?.let { "series left out, its id is empty" },
        series?.episodeCount?.takeIf { it < 1 }?.let { "series.episodeCount $it left out" },
    )

// COPPA: a child's request carries no identifier for advertising, no advertising id, user id or email. The
// rule is here, in one place; the request sends what these return.

/** Whether the request is for a child: the publisher said COPPA applies (`regulatory.coppa`). */
internal val Regulatory?.isChild: Boolean
    get() = this?.coppa == true

/** The user the request sends: for a child only what does not identify them ([withoutIdentity]). */
internal fun Regulatory?.userToSend(user: User?): User? = if (isChild) user?.withoutIdentity else user

/** The advertising id the request sends: none for a child. */
internal fun Regulatory?.advertisingIdToSend(advertisingId: String?): String? = advertisingId?.takeUnless { isChild }

/** The user without what identifies the viewer (id, email, email hash), for a COPPA request; `null` when nothing else is left. */
internal val User.withoutIdentity: User?
    get() = keywords?.let { kept -> User { keywords = kept } }

/** Fields set to an empty string, which the request leaves out. */
internal val User.emptyFields: List<String>
    get() = listOf("id" to id, "email" to email, "emailHash" to emailHash).filter { it.second?.isEmpty() == true }.map { it.first }

/** The `video` object: absent fields left out, as are numbers the server's schema would refuse. */
internal fun Content.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("language", language)
    putIfNotNull("mediaRating", mediaRating?.name?.lowercase())
    putIfNotNull("url", url)
    putIfNotNull("title", title)
    putIfNotNull("categories", categories)
    putIfNotNull("description", description)
    putIfNotNull("duration", durationMillis?.takeIf { it >= 1 })
    putIfNotNull("episodeNumber", episodeNumber?.takeIf { it >= 1 })
    putIfNotNull("keywords", keywords)
    putIfNotNull("genre", genre)
    series?.takeIf { it.id.isNotEmpty() }?.let { put("series", it.toJson()) }
}

/** The `video.series` object: absent fields left out, an `episodeCount` below 1 too. */
internal fun Content.Series.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    putIfNotNull("title", title)
    putIfNotNull("description", description)
    putIfNotNull("episodeCount", episodeCount?.takeIf { it >= 1 })
}

/** The `user` object: empty strings are left out, an empty id, address or hash identifies nobody. */
internal fun User.toJson(): JsonObject = buildJsonObject {
    putIfNotNull("id", id?.takeIf { it.isNotEmpty() })
    putIfNotNull("email", email?.takeIf { it.isNotEmpty() })
    putIfNotNull("emailHash", emailHash?.takeIf { it.isNotEmpty() })
    putIfNotNull("keywords", keywords)
}

/** The `regulatory` object: the merged signals, absent fields left out. */
internal fun Regulatory.toJson(): JsonObject = buildJsonObject {
    putIfNotNull("gdpr", gdpr)
    putIfNotNull("gdprConsent", gdprConsent)
    putIfNotNull("coppa", coppa)
    putIfNotNull("gpp", gpp)
    putIfNotNull("gppSid", gppSid)
    putIfNotNull("usPrivacy", usPrivacy)
}
