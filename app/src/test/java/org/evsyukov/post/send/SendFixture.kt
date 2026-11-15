package org.evsyukov.post.send

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import org.evsyukov.post.PublishTrigger
import org.evsyukov.post.accounts.AccountSessions
import org.evsyukov.post.accounts.FakeServers
import org.evsyukov.post.core.media.Conversion
import org.evsyukov.post.core.media.MediaKind
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.FakeCipher
import org.evsyukov.post.data.MutableClock
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.accounts.RoomAccountsRepository
import org.evsyukov.post.data.history.RoomHistoryRepository
import org.evsyukov.post.data.inMemoryDatabase
import org.evsyukov.post.data.posts.Attachment
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.FilesDirAttachmentFiles
import org.evsyukov.post.data.posts.Post
import org.evsyukov.post.data.posts.PostOptions
import org.evsyukov.post.data.posts.RoomPostsRepository
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.data.secrets.BlueskySecret
import org.evsyukov.post.data.secrets.MastodonSecret
import org.evsyukov.post.data.secrets.SecretStore
import org.evsyukov.post.data.settings.Settings
import org.evsyukov.post.media.convert.ConvertedFile
import org.evsyukov.post.media.convert.MediaConverter
import org.evsyukov.post.media.prepare.MediaPreparer
import org.evsyukov.post.text.AndroidTextSegmenter
import java.io.Closeable
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** The sender with its real repositories on an in-memory database, and every server on one MockWebServer. */
class SendFixture : Closeable {
    val context: android.content.Context = ApplicationProvider.getApplicationContext()
    val db = inMemoryDatabase()
    val clock = MutableClock(Instant.parse("2026-11-16T08:00:00Z"))
    val servers = FakeServers()
    var settings = Settings()
    val accounts = RoomAccountsRepository(db, SecretStore(FakeCipher()), clock)
    val filesDir: File = File(context.filesDir, "send-test-${System.nanoTime()}").apply { mkdirs() }
    val posts = RoomPostsRepository(db, FilesDirAttachmentFiles(filesDir), historyRetention = { settings.historyRetention }, clock = clock)
    val history = RoomHistoryRepository(db) { settings.historyRetention }
    val sessions = AccountSessions(accounts, servers.http)
    val notifier = RecordingNotifier()
    val retries = CopyOnWriteArrayList<Instant>()
    val triggers = AtomicInteger()
    val converter = FakeConverter()
    val preparer = MediaPreparer(db, filesDir, converter, clock)
    val foregrounds = CopyOnWriteArrayList<SendProgress>()

    var beforeSend: suspend (Instant) -> Unit = {}

    /** When it says so for a request, the server gets the request and answers, but the answer is lost. */
    var dropAnswer: (okhttp3.Request) -> Boolean = { false }

    private val dropping = servers.http.newBuilder().addInterceptor { chain ->
        val response = chain.proceed(chain.request())
        if (dropAnswer(chain.request())) {
            response.close()
            throw java.io.IOException("connection reset")
        }
        response
    }.build()

    val publisher = Publisher(
        posts = posts,
        accounts = accounts,
        settings = { settings },
        freezer = PlanFreezer(AndroidTextSegmenter()),
        executors = listOf(MastodonExecutor(dropping, sessions, accounts, clock, sleep = {})),
        preparer = preparer,
        filesDir = filesDir,
        notifier = notifier,
        scheduleRetry = { retries += it },
        trigger = PublishTrigger { triggers.incrementAndGet() },
        clock = clock,
        zone = { ZoneOffset.UTC },
        beforeSend = { beforeSend(it) },
    )

    fun run() = runBlocking { publisher.run { foregrounds += it } }

    fun mastodon(host: String = "mastodon.example", remoteId: String = "101", token: String = "token-$host"): Long = runBlocking {
        accounts.add(NewAccount(Network.MASTODON, "https://$host", "alice@$host", remoteId, "Alice", null), MastodonSecret(token))
    }

    fun bluesky(): Long = runBlocking {
        accounts.add(NewAccount(Network.BLUESKY, "https://pds.example", "alice.example.com", "did:plc:abc", "Alice", null), BlueskySecret("a", "r"))
    }

    fun account(id: Long): Account = runBlocking { accounts.get(id)!! }

    /** Queues a post due now, as Send now does. */
    fun queue(
        text: String,
        targets: List<Long>,
        options: PostOptions = PostOptions(),
        attachments: List<Attachment> = emptyList(),
    ): Long = runBlocking {
        posts.saveAndQueue(DraftInput(text = text, options = options, targets = targets, attachments = attachments), Schedule.Now(clock.instant()))
    }

    fun post(id: Long): Post? = runBlocking { posts.get(id) }

    /** A file under the files directory, as an imported attachment has; returns its relative path. */
    fun file(name: String, bytes: ByteArray = ByteArray(2048) { it.toByte() }): String {
        val dir = File(filesDir, "attachments").apply { mkdirs() }
        File(dir, name).writeBytes(bytes)
        return "attachments/$name"
    }

    fun image(name: String = "photo.jpg", alt: String = "a cat") = Attachment(
        kind = MediaKind.IMAGE,
        path = file(name),
        mime = "image/jpeg",
        sizeBytes = 2048,
        width = 800,
        height = 600,
        alt = alt,
    )

    override fun close() {
        servers.close()
        db.close()
        filesDir.deleteRecursively()
    }
}

/** Remembers what the sender told the user. */
class RecordingNotifier : SendNotifier {
    val failures = CopyOnWriteArrayList<Triple<Long, Long, String>>()
    val signIns = CopyOnWriteArrayList<Long>()
    val successes = CopyOnWriteArrayList<Pair<Long, Int>>()

    override fun deliveryFailed(account: Account, postId: Long, message: String) {
        failures += Triple(account.id, postId, message)
    }

    override fun needsSignIn(account: Account) {
        signIns += account.id
    }

    override fun posted(postId: Long, accounts: Int) {
        successes += postId to accounts
    }
}

/** Writes a copy of the source as the variant; [onConvert] runs first and can suspend or fail. */
class FakeConverter : MediaConverter {
    val conversions = CopyOnWriteArrayList<Conversion>()
    var onConvert: suspend (Conversion, (Int) -> Unit) -> Unit = { _, _ -> }

    override suspend fun convert(source: File, conversion: Conversion, output: File, onProgress: (Int) -> Unit): ConvertedFile {
        conversions += conversion
        onConvert(conversion, onProgress)
        source.copyTo(output, overwrite = true)
        val mime = when (conversion) {
            is Conversion.ConvertImage -> conversion.targetMime
            is Conversion.TranscodeVideo -> conversion.targetMime
            else -> error("no variant for $conversion")
        }
        return ConvertedFile(mime, output.length(), 800, 600)
    }
}

/**
 * A Mastodon server on [FakeServers]: statuses with ids s1, s2, …, an idempotency cache by key as Mastodon
 * keeps it for an hour, media uploads with ids m1, m2, … Each answer can be overridden by [statusAnswer].
 */
class FakeMastodon(private val servers: FakeServers, val host: String = "mastodon.example", private val clock: () -> Instant) {
    private val json = Json { ignoreUnknownKeys = true }

    /** A created status: its id, the request as JSON and the key it came with. */
    class Created(val id: String, val body: JsonObject, val key: String?, val at: Instant) {
        val text: String get() = body["status"]!!.jsonPrimitive.content
        val inReplyTo: String? get() = (body["in_reply_to_id"] as? JsonPrimitive)?.content
        val visibility: String get() = body["visibility"]!!.jsonPrimitive.content
        val mediaIds: List<String> get() = (body["media_ids"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
    }

    val statuses = CopyOnWriteArrayList<Created>()
    val keys = CopyOnWriteArrayList<String?>()
    val uploads = CopyOnWriteArrayList<FakeServers.Seen>()
    private val byKey = java.util.concurrent.ConcurrentHashMap<String, Created>()
    private var nextMedia = 0

    /** Answers a status request before the normal one; null lets the server create the status. */
    var statusAnswer: (attempt: Int, request: FakeServers.Seen) -> MockResponse? = { _, _ -> null }

    /** Called after a status is created and before the answer goes out. */
    var afterCreate: (Created) -> Unit = {}

    /** How the server answers a media upload: 200 (processed) or 202 (processing). */
    var uploadCode = 200

    /** The answers of `GET /api/v1/media/:id` while the media is processing, before it is ready. */
    var processingPolls = 0

    /** When set, processing of every upload fails with this message (N7: 422). */
    var processingError: String? = null
    val polls = AtomicInteger()

    /** Whether a created status is also given in `accountStatuses`. */
    var listCreated = true

    init {
        servers.route("POST", "$host/api/v1/statuses") { request ->
            val key = request.headers["Idempotency-Key"]
            keys += key
            statusAnswer(keys.size, request)?.let { return@route it }
            val cached = key?.let { byKey[it] }
            val created = cached ?: Created("s${statuses.size + 1}", json.parseToJsonElement(request.body).jsonObject, key, clock()).also {
                statuses += it
                if (key != null) byKey[key] = it
            }
            afterCreate(created)
            FakeServers.json(statusJson(created))
        }
        servers.route("POST", "$host/api/v2/media") { request ->
            uploads += request
            val id = "m${++nextMedia}"
            servers.route("GET", "$host/api/v1/media/$id") {
                val count = polls.incrementAndGet()
                val failure = processingError
                if (failure != null) {
                    error(422, failure)
                } else if (count <= processingPolls) {
                    FakeServers.json("""{"id":"$id","type":"image","url":null}""", 206)
                } else {
                    FakeServers.json("""{"id":"$id","type":"image","url":"https://$host/media/$id"}""")
                }
            }
            FakeServers.json("""{"id":"$id","type":"image","url":null}""", uploadCode)
        }
    }

    fun statusJson(created: Created): String {
        val reply = created.inReplyTo?.let { "\"$it\"" } ?: "null"
        val content = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive("<p>${created.text}</p>"))
        return """{"id":"${created.id}","uri":"https://$host/users/alice/statuses/${created.id}","url":"https://$host/@alice/${created.id}",""" +
            """"created_at":"${created.at}","in_reply_to_id":$reply,"visibility":"${created.visibility}","content":$content}"""
    }

    /** `GET /api/v1/accounts/:id/statuses` lists the statuses made, newest first. */
    fun listStatuses(accountId: String) {
        servers.route("GET", "$host/api/v1/accounts/$accountId/statuses") {
            FakeServers.json(statuses.reversed().joinToString(",", "[", "]") { statusJson(it) })
        }
    }

    fun requests(path: String) = servers.requests("$host$path")

    companion object {
        fun error(code: Int, message: String, headers: Map<String, String> = emptyMap()): MockResponse =
            MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").apply {
                headers.forEach { (name, value) -> addHeader(name, value) }
            }.body("""{"error":"$message"}""").build()
    }
}

fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }
