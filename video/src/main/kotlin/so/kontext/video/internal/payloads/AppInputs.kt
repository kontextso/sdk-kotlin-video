package so.kontext.video.internal.payloads

import so.kontext.video.KontextVideo.Content
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

/** The user without what identifies the viewer (id, email, email hash), for a COPPA request; `null` when nothing else is left. */
internal val User.withoutIdentity: User?
    get() = keywords?.let { kept -> User { keywords = kept } }

/** Fields set to an empty string, which the request leaves out. */
internal val User.emptyFields: List<String>
    get() = listOf("id" to id, "email" to email, "emailHash" to emailHash).filter { it.second?.isEmpty() == true }.map { it.first }
