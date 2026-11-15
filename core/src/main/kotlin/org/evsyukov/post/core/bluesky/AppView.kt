package org.evsyukov.post.core.bluesky

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The public profile of an account (`app.bsky.actor.defs#profileViewDetailed`).
 *
 * @property displayName null when the account has none.
 * @property avatar the URL of the avatar on the CDN, or null.
 */
data class Profile(val did: String, val handle: String, val displayName: String?, val avatar: String?)

/**
 * Unauthenticated reads from the public AppView (`https://public.api.bsky.app`). No credentials ever
 * go there: the calls carry no `Authorization` header (product.md R4).
 *
 * Lexicon: `bluesky-social/atproto` `7dd086d4de`, `lexicons/app/bsky/actor/getProfile.json`.
 */
class AppViewClient(
    http: OkHttpClient,
    json: Json,
    base: HttpUrl = IdentityResolver.DEFAULT_APP_VIEW,
    clock: () -> Long = System::currentTimeMillis,
) {
    private val xrpc = Xrpc(base, http, json, clock, followRedirects = false)

    /**
     * `app.bsky.actor.getProfile` for [actor], a handle or a DID.
     *
     * @throws XrpcException for an error answer (400 `InvalidRequest` for an unknown actor) or an
     *   answer without `did` and `handle`
     * @throws java.io.IOException on a network failure
     */
    suspend fun getProfile(actor: String): Profile {
        val nsid = "app.bsky.actor.getProfile"
        val request = Request.Builder().url(xrpc.url(nsid, listOf("actor" to actor))).get().build()
        val fields = xrpc.call(request, nsid)
        return Profile(
            did = fields.string("did"),
            handle = fields.string("handle"),
            displayName = fields.obj.optString("displayName")?.takeIf { it.isNotBlank() },
            avatar = fields.obj.optString("avatar")?.takeIf { it.isNotBlank() },
        )
    }
}
