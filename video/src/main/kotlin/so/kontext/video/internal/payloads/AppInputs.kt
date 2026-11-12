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

// COPPA: a child's request carries no identifier for advertising, no advertising id, email or email hash. The
// user id is still sent: the server's privacy decision keeps it from buyers, and its caps and analytics key on
// it. The rule is here, in one place; the request sends what these return.

/** Whether the request is for a child: the publisher said COPPA applies (`regulatory.coppa`). */
internal val Regulatory?.isChild: Boolean
    get() = this?.coppa == true

/** The user the request sends: for a child without the email and its hash ([withoutContact]). */
internal fun Regulatory?.userToSend(user: User): User = if (isChild) user.withoutContact else user

/** The advertising id the request sends: none for a child. */
internal fun Regulatory?.advertisingIdToSend(advertisingId: String?): String? = advertisingId?.takeUnless { isChild }

/** The user without the email and its hash, for a COPPA request: the id and the keywords stay. */
internal val User.withoutContact: User
    get() = User(id) { keywords = this@withoutContact.keywords }

/** The user a request may carry: `null` when none is set or its id is empty, and then no request is sent. */
internal val User?.usable: User?
    get() = this?.takeIf { it.id.isNotEmpty() }

/** Fields set to an empty string, which the request leaves out. */
internal val User.emptyFields: List<String>
    get() = listOf<Pair<String, String?>>("id" to id, "email" to email, "emailHash" to emailHash).filter { it.second?.isEmpty() == true }.map { it.first }

/** The longest `section` or `variantId` the server takes. */
internal const val MAX_NAME_LENGTH = 128

/** A `section` or `variantId` as sent: empty or longer than [MAX_NAME_LENGTH], it would fail the request, so it is left out. */
internal val String?.sendable: String?
    get() = this?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }

/** What is wrong with a `section` or `variantId`, or `null`: what [sendable] leaves out. */
internal fun String?.nameProblem(): String? = when {
    this == null -> null
    isEmpty() -> "is empty"
    length > MAX_NAME_LENGTH -> "is longer than $MAX_NAME_LENGTH characters"
    else -> null
}

/** The `video` object: absent fields left out, as are numbers the server's schema would refuse. */
internal fun Content.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("language", language)
    putIfNotNull("mediaRating", mediaRating?.name?.lowercase())
    putIfNotNull("url", url)
    putIfNotNull("title", title)
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

/** The `user` object: the id always (a request is never made without one), empty optional strings left out. */
internal fun User.toJson(): JsonObject = buildJsonObject {
    put("id", id)
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
