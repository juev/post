package org.evsyukov.post.send.bluesky

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import org.evsyukov.post.accounts.FakeServers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A Bluesky PDS on [FakeServers] at [host] with the repo [did]: `applyWrites` keeps records by collection
 * and key and answers a create at a taken key with a bare 500 as the reference PDS does (N25);
 * `getRecord`, `uploadBlob`, `refreshSession` and `getServiceAuth`. The access token is [accessJwt];
 * any other bearer gets 400 `ExpiredToken`.
 */
class FakePds(private val servers: FakeServers, val host: String = "pds.example", val did: String = "did:plc:abc") {
    private val json = Json { ignoreUnknownKeys = true }

    /** A record written by `applyWrites`. */
    class Record(val collection: String, val rkey: String, val value: JsonObject, val cid: String) {
        val text: String get() = value["text"]!!.jsonPrimitive.content
        val createdAt: String get() = value["createdAt"]!!.jsonPrimitive.content
    }

    /** Every `applyWrites` call that wrote, as its list of creates. */
    val commits = CopyOnWriteArrayList<List<Record>>()

    /** Every `applyWrites` call that deleted, as its list of "collection/rkey". */
    val deleteCommits = CopyOnWriteArrayList<List<String>>()
    val records = ConcurrentHashMap<String, Record>()
    val blobs = CopyOnWriteArrayList<FakeServers.Seen>()
    private val nextCid = AtomicInteger()

    var accessJwt = "a"
    var refreshJwt = "r"

    /** When set, `refreshSession` answers this instead of new tokens. */
    var refreshAnswer: MockResponse? = null

    /** Answers an `applyWrites` call before the server does; null lets it write. [call] counts from 1. */
    var writeAnswer: (call: Int, request: FakeServers.Seen) -> MockResponse? = { _, _ -> null }
    private val writeCalls = AtomicInteger()

    /** Whether `applyWrites` answers with `results`. */
    var withResults = true

    init {
        servers.route("POST", "$host/xrpc/com.atproto.repo.applyWrites") { request ->
            if (!authorized(request)) return@route expired()
            writeAnswer(writeCalls.incrementAndGet(), request)?.let { return@route it }
            val body = json.parseToJsonElement(request.body).jsonObject
            check(body["repo"]!!.jsonPrimitive.content == did)
            val all = body["writes"]!!.jsonArray.map { it.jsonObject }
            val deletes = all.filter { it["\$type"]!!.jsonPrimitive.content == "com.atproto.repo.applyWrites#delete" }
            if (deletes.isNotEmpty()) {
                check(deletes.size == all.size) { "creates and deletes in one call" }
                val keys = deletes.map { key(it["collection"]!!.jsonPrimitive.content, it["rkey"]!!.jsonPrimitive.content) }
                // A delete of a missing record fails the whole commit here, so the app must leave those out.
                if (keys.any { !records.containsKey(it) }) {
                    return@route FakeServers.json("""{"error":"InvalidRequest","message":"Could not find record"}""", 400)
                }
                keys.forEach { records.remove(it) }
                deleteCommits += keys
                return@route FakeServers.json("""{"commit":{"cid":"bafycommit","rev":"rev"},"results":[]}""")
            }
            val creates = all.map { write ->
                check(write["\$type"]!!.jsonPrimitive.content == "com.atproto.repo.applyWrites#create")
                Record(
                    write["collection"]!!.jsonPrimitive.content,
                    write["rkey"]!!.jsonPrimitive.content,
                    write["value"]!!.jsonObject,
                    "bafyrei${nextCid.incrementAndGet()}",
                )
            }
            if (creates.any { records.containsKey(key(it.collection, it.rkey)) }) {
                return@route FakeServers.json("""{"error":"InternalServerError","message":"Internal Server Error"}""", 500)
            }
            creates.forEach { records[key(it.collection, it.rkey)] = it }
            commits += creates
            if (!withResults) return@route FakeServers.json("{}")
            val results = creates.joinToString(",") {
                """{"${'$'}type":"com.atproto.repo.applyWrites#createResult","uri":"${uriOf(it)}","cid":"${it.cid}"}"""
            }
            FakeServers.json("""{"commit":{"cid":"bafycommit","rev":"rev"},"results":[$results]}""")
        }
        servers.route("GET", "$host/xrpc/com.atproto.repo.getRecord") { request ->
            val record = records[key(request.url.queryParameter("collection")!!, request.url.queryParameter("rkey")!!)]
            if (record == null) {
                FakeServers.json("""{"error":"RecordNotFound","message":"Could not locate record"}""", 400)
            } else {
                FakeServers.json("""{"uri":"${uriOf(record)}","cid":"${record.cid}","value":${record.value}}""")
            }
        }
        servers.route("POST", "$host/xrpc/com.atproto.repo.uploadBlob") { request ->
            if (!authorized(request)) return@route expired()
            blobs += request
            val mime = request.headers["Content-Type"]!!.substringBefore(';')
            val size = request.headers["Content-Length"]!!.toLong()
            FakeServers.json("""{"blob":${blobJson("bafkblob${blobs.size}", mime, size)}}""")
        }
        servers.route("POST", "$host/xrpc/com.atproto.server.refreshSession") { request ->
            refreshAnswer?.let { return@route it }
            if (request.headers["Authorization"] != "Bearer $refreshJwt") {
                return@route FakeServers.json("""{"error":"ExpiredToken","message":"Token has been revoked"}""", 400)
            }
            accessJwt = "$accessJwt+"
            refreshJwt = "$refreshJwt+"
            FakeServers.json("""{"did":"$did","handle":"alice.example.com","accessJwt":"$accessJwt","refreshJwt":"$refreshJwt"}""")
        }
        servers.route("GET", "$host/xrpc/com.atproto.server.getServiceAuth") { request ->
            if (!authorized(request)) return@route expired()
            serviceAuth += request
            FakeServers.json("""{"token":"svc-${request.url.queryParameter("lxm")}-${serviceAuth.size}"}""")
        }
    }

    val serviceAuth = CopyOnWriteArrayList<FakeServers.Seen>()

    /** The posts in the order they were written. */
    val posts: List<Record> get() = commits.flatten().filter { it.collection == "app.bsky.feed.post" }

    fun uriOf(record: Record) = "at://$did/${record.collection}/${record.rkey}"

    fun requests(nsid: String) = servers.requests("$host/xrpc/$nsid")

    private fun authorized(request: FakeServers.Seen) = request.headers["Authorization"] == "Bearer $accessJwt"

    private fun expired() = FakeServers.json("""{"error":"ExpiredToken","message":"Token has expired"}""", 400)

    private fun key(collection: String, rkey: String) = "$collection/$rkey"

    companion object {
        fun blobJson(cid: String, mime: String, size: Long) =
            """{"${'$'}type":"blob","ref":{"${'$'}link":"$cid"},"mimeType":"$mime","size":$size}"""

        fun error(code: Int, error: String, message: String, headers: Map<String, String> = emptyMap()): MockResponse =
            MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").apply {
                headers.forEach { (name, value) -> addHeader(name, value) }
            }.body("""{"error":"$error","message":"$message"}""").build()
    }
}

/** The public AppView resolving the handles of [dids]; any other handle is not found (N17). */
class FakeAppView(servers: FakeServers, dids: Map<String, String>) {
    val lookups = CopyOnWriteArrayList<String>()

    init {
        servers.route("GET", "public.api.bsky.app/xrpc/com.atproto.identity.resolveHandle") { request ->
            val handle = request.url.queryParameter("handle")!!
            lookups += handle
            val did = dids[handle]
            if (did == null) {
                FakeServers.json("""{"error":"HandleNotFound","message":"Unable to resolve handle"}""", 400)
            } else {
                FakeServers.json("""{"did":"$did"}""")
            }
        }
    }
}

/**
 * The video service at `video.bsky.app` (N20): limits, a one-part multipart upload as job `job<n>`, and
 * job status that completes with a blob after [pollsBeforeDone] polls.
 */
class FakeVideoService(private val servers: FakeServers) {
    var canUpload = true
    var limitsMessage: String? = null
    var pollsBeforeDone = 0
    var finishAnswer: (call: Int) -> MockResponse? = { null }
    var mime = "video/mp4"
    val polls = AtomicInteger()
    val started = CopyOnWriteArrayList<FakeServers.Seen>()
    val parts = CopyOnWriteArrayList<FakeServers.Seen>()
    private val finishCalls = AtomicInteger()
    private val uploads = ConcurrentHashMap<String, MutableSet<Int>>()

    init {
        val base = "video.bsky.app/xrpc/app.bsky.video"
        servers.route("GET", "$base.getUploadLimits") {
            val message = limitsMessage?.let { ",\"message\":\"$it\"" } ?: ""
            FakeServers.json("""{"canUpload":$canUpload,"remainingDailyVideos":${if (canUpload) 10 else 0}$message}""")
        }
        servers.route("POST", "$base.startUpload") { request ->
            started += request
            val job = "job${started.size}"
            uploads[job] = mutableSetOf()
            val size = Json.parseToJsonElement(request.body).jsonObject["sizeBytes"]!!.jsonPrimitive.content.toLong()
            FakeServers.json("""{"jobId":"$job","partSizeBytes":${size + 1},"partCount":1,"expiresAt":"2026-11-17T08:00:00Z"}""")
        }
        servers.route("POST", "$base.uploadPart") { request ->
            parts += request
            uploads[request.url.queryParameter("jobId")!!]!! += request.url.queryParameter("partNumber")!!.toInt()
            FakeServers.json("""{"partNumber":${request.url.queryParameter("partNumber")},"sizeBytes":${request.headers["Content-Length"]}}""")
        }
        servers.route("POST", "$base.finishUpload") { request ->
            finishAnswer(finishCalls.incrementAndGet())?.let { return@route it }
            val job = Json.parseToJsonElement(request.body).jsonObject["jobId"]!!.jsonPrimitive.content
            FakeServers.json("""{"completedJobId":"$job-done","jobStatus":{"jobId":"$job-done","state":"JOB_STATE_RUNNING","progress":10}}""")
        }
        servers.route("GET", "$base.getUploadStatus") { request ->
            val job = request.url.queryParameter("jobId")!!
            val received = uploads[job] ?: return@route FakePds.error(404, "UploadNotFound", "no such upload")
            FakeServers.json(
                """{"jobId":"$job","partSizeBytes":100000000,"partCount":1,"receivedParts":${JsonArray(received.map { kotlinx.serialization.json.JsonPrimitive(it) })},""" +
                    """"expiresAt":"2026-11-17T08:00:00Z","state":"created"}""",
            )
        }
        servers.route("GET", "$base.getJobStatus") { request ->
            val job = request.url.queryParameter("jobId")!!
            if (polls.incrementAndGet() <= pollsBeforeDone) {
                FakeServers.json("""{"jobStatus":{"jobId":"$job","state":"JOB_STATE_ENCODING","progress":50}}""")
            } else {
                FakeServers.json(
                    """{"jobStatus":{"jobId":"$job","state":"JOB_STATE_COMPLETED","blob":${FakePds.blobJson("bafkvideo", "video/mp4", 12345)}}}""",
                )
            }
        }
    }

    fun requests(name: String) = servers.requests("video.bsky.app/xrpc/app.bsky.video.$name")
}
