package org.evsyukov.post.core.bluesky

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * N20, N21: the multipart flow of the `app.bsky.video.*` lexicons at `bsky` `416b0ad1d9`, with the
 * auth and part planning of social-app `9747031e9b`.
 */
class VideoClientTest {
    @get:Rule val temp = TemporaryFolder()

    private val server = MockWebServer().apply { start() }
    private val video = VideoClient(OkHttpClient(), testJson, server.url("/"))
    private val blobJson = """{"${'$'}type":"blob","ref":{"${'$'}link":"bafkreivideo"},"mimeType":"video/mp4","size":2048}"""

    @After fun close() = server.close()

    private fun file(size: Int, name: String = "clip.mp4"): File =
        temp.newFile(name).apply { writeBytes(ByteArray(size) { (it * 7 % 256).toByte() }) }

    private fun job(id: String, state: String, extra: String = "") = """{"jobId":"$id","did":"did:plc:alice","state":"$state"$extra}"""

    private fun status(state: String, received: String = "[]", extra: String = "") =
        """{"jobId":"job-1","partSizeBytes":10,"partCount":3,"receivedParts":$received,"expiresAt":"2026-11-15T10:00:00Z","state":"$state"$extra}"""

    private fun started(partCount: Int = 3) = """{"jobId":"job-1","partSizeBytes":10,"partCount":$partCount,"expiresAt":"2026-11-15T10:00:00Z"}"""

    private fun finished(id: String = "job-1") = """{"completedJobId":"$id","jobStatus":${job(id, "JOB_STATE_CREATED")}}"""

    private fun part(n: Int, size: Int) = """{"partNumber":$n,"sizeBytes":$size}"""

    /** Token provider that names each token by its call: t1, t2, … and records whether it was forced. */
    private class Tokens {
        val forced = mutableListOf<Boolean>()
        val provider: suspend (Boolean) -> String = { fresh -> forced += fresh; "t${forced.size}" }
    }

    private fun RecordedRequest.nsid() = url.encodedPath.removePrefix("/xrpc/")

    @Test
    fun uploadLimitsUseTheServiceTokenAndReadAllFields() = runBlocking<Unit> {
        server.enqueue(jsonResponse("""{"canUpload":false,"remainingDailyVideos":0,"remainingDailyBytes":0,"message":"Daily limit reached","error":"DailyLimitExceeded"}"""))
        val limits = video.getUploadLimits("limits-token")
        val request = server.next()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.url.encodedPath).isEqualTo("/xrpc/app.bsky.video.getUploadLimits")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer limits-token")
        assertThat(limits).isEqualTo(UploadLimits(false, 0, 0, "Daily limit reached", "DailyLimitExceeded"))
        server.enqueue(jsonResponse("""{"canUpload":true}"""))
        assertThat(video.getUploadLimits("t")).isEqualTo(UploadLimits(true, null, null, null, null))
    }

    @Test
    fun startUploadDeclaresTheExactSizeAndTypeWithoutAFileName() = runBlocking<Unit> {
        server.enqueue(jsonResponse(started()))
        val session = video.startUpload("t", 25, "image/gif")
        val request = server.next()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/xrpc/app.bsky.video.startUpload")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer t")
        assertThat(request.bodyJson()).isEqualTo(JsonObject(mapOf("sizeBytes" to JsonPrimitive(25), "mimeType" to JsonPrimitive("image/gif"))))
        assertThat(session).isEqualTo(UploadSession("job-1", 10, 3, "2026-11-15T10:00:00Z"))

        server.enqueue(jsonResponse(started()))
        video.startUpload("t", 25, "video/mp4", durationMs = 61_000, width = 1920, height = 1080)
        val advisory = server.next().bodyJson()
        assertThat(advisory["durationMs"]!!.jsonPrimitive.long).isEqualTo(61_000)
        assertThat(advisory["width"]!!.jsonPrimitive.long).isEqualTo(1920)
        assertThat(advisory["height"]!!.jsonPrimitive.long).isEqualTo(1080)
    }

    @Test
    fun startUploadErrorsKeepTheirLexiconNames() = runBlocking<Unit> {
        server.enqueue(jsonResponse("""{"error":"DailyLimitExceeded","message":"Daily limit reached"}""", 400))
        val e = runCatching { video.startUpload("t", 25, "video/mp4") }.exceptionOrNull() as XrpcException
        assertThat(e.error).isEqualTo("DailyLimitExceeded")
        assertThat(e.isTransient).isFalse()
        server.enqueue(jsonResponse("""{"error":"ServiceOverloaded"}""", 503))
        assertThat((runCatching { video.startUpload("t", 25, "video/mp4") }.exceptionOrNull() as XrpcException).isTransient).isTrue()
    }

    @Test
    fun uploadPartSendsTheExactByteRangeAsOctetStream() = runBlocking<Unit> {
        val f = file(25)
        server.enqueue(jsonResponse(part(3, 5)))
        val receipt = video.uploadPart("t", "job-1", f, PartPlan(3, 20, 5))
        val request = server.next()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/xrpc/app.bsky.video.uploadPart")
        assertThat(request.url.queryParameter("jobId")).isEqualTo("job-1")
        assertThat(request.url.queryParameter("partNumber")).isEqualTo("3")
        assertThat(request.headers["Content-Type"]).isEqualTo("application/octet-stream")
        assertThat(request.headers["Content-Length"]).isEqualTo("5")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer t")
        assertThat(request.body!!.toByteArray()).isEqualTo(f.readBytes().copyOfRange(20, 25))
        assertThat(receipt).isEqualTo(PartReceipt(3, 5))
    }

    @Test
    fun finishStatusAbortAndJobStatusFollowTheLexicons() = runBlocking<Unit> {
        server.enqueue(jsonResponse(finished("job-9")))
        val finished = video.finishUpload("t", "job-1")
        val finishRequest = server.next()
        assertThat(finishRequest.nsid()).isEqualTo("app.bsky.video.finishUpload")
        assertThat(finishRequest.method).isEqualTo("POST")
        assertThat(finishRequest.bodyJson()).isEqualTo(JsonObject(mapOf("jobId" to JsonPrimitive("job-1"))))
        assertThat(finished.completedJobId).isEqualTo("job-9")
        assertThat(finished.jobStatus.jobId).isEqualTo("job-9")

        server.enqueue(jsonResponse(status("completed", "[1,2,3]", ""","completedJobId":"job-9","jobStatus":${job("job-9", "JOB_STATE_ENCODING", ""","progress":40""")}""")))
        val upload = video.getUploadStatus("t", "job-1")
        val statusRequest = server.next()
        assertThat(statusRequest.method).isEqualTo("GET")
        assertThat(statusRequest.nsid()).isEqualTo("app.bsky.video.getUploadStatus")
        assertThat(statusRequest.url.queryParameter("jobId")).isEqualTo("job-1")
        assertThat(statusRequest.headers["Authorization"]).isEqualTo("Bearer t")
        assertThat(upload.receivedParts).containsExactly(1, 2, 3).inOrder()
        assertThat(upload.state).isEqualTo("completed")
        assertThat(upload.completedJobId).isEqualTo("job-9")
        assertThat(upload.jobStatus!!.progress).isEqualTo(40)

        server.enqueue(jsonResponse("""{"state":"aborted"}"""))
        assertThat(video.abortUpload("t", "job-1")).isEqualTo(AbortedUpload("aborted", null, null))
        val abortRequest = server.next()
        assertThat(abortRequest.nsid()).isEqualTo("app.bsky.video.abortUpload")
        assertThat(abortRequest.method).isEqualTo("POST")
        assertThat(abortRequest.bodyJson()).isEqualTo(JsonObject(mapOf("jobId" to JsonPrimitive("job-1"))))
    }

    @Test
    fun jobStatusIsPublicAndCarriesTheBlob() = runBlocking<Unit> {
        server.enqueue(jsonResponse("""{"jobStatus":${job("job-9", "JOB_STATE_COMPLETED", ""","blob":$blobJson""")}}"""))
        val status = video.getJobStatus("job-9")
        val request = server.next()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.nsid()).isEqualTo("app.bsky.video.getJobStatus")
        assertThat(request.url.queryParameter("jobId")).isEqualTo("job-9")
        assertThat(request.headers["Authorization"]).isNull()
        assertThat(status.isCompleted).isTrue()
        assertThat(status.blob!!.json.toString()).isEqualTo(blobJson)
    }

    @Test
    fun alreadyProcessedVideoFailsButStillHasItsBlob() = runBlocking<Unit> {
        server.enqueue(jsonResponse("""{"jobStatus":${job("job-9", "JOB_STATE_FAILED", ""","error":"already_exists","message":"Video already processed","blob":$blobJson""")}}"""))
        val status = video.getJobStatus("job-9")
        assertThat(status.isFailed).isTrue()
        assertThat(status.error).isEqualTo("already_exists")
        assertThat(status.blob).isNotNull()
    }

    @Test
    fun awaitJobPollsUntilTheBlobArrives() = runBlocking<Unit> {
        server.enqueue(jsonResponse("""{"jobStatus":${job("job-9", "JOB_STATE_ENCODING", ""","progress":10""")}}"""))
        server.enqueue(jsonResponse("""{"jobStatus":${job("job-9", "JOB_STATE_UPLOADING")}}"""))
        server.enqueue(jsonResponse("""{"jobStatus":${job("job-9", "JOB_STATE_COMPLETED", ""","blob":$blobJson""")}}"""))
        val done = video.awaitJob("job-9", pollIntervalMillis = 1)
        assertThat(done.blob).isNotNull()
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun awaitJobStopsOnFailure() = runBlocking<Unit> {
        server.enqueue(jsonResponse("""{"jobStatus":${job("job-9", "JOB_STATE_FAILED", ""","failureCode":"encoding_failure"""")}}"""))
        val failed = video.awaitJob("job-9", pollIntervalMillis = 1)
        assertThat(failed.isFailed).isTrue()
        assertThat(failed.failureCode).isEqualTo("encoding_failure")
        assertThat(failed.blob).isNull()
    }

    @Test
    fun planPartsSplitsLikeSocialApp() {
        assertThat(VideoClient.planParts(25, 10)).containsExactly(PartPlan(1, 0, 10), PartPlan(2, 10, 10), PartPlan(3, 20, 5)).inOrder()
        assertThat(VideoClient.planParts(20, 10)).containsExactly(PartPlan(1, 0, 10), PartPlan(2, 10, 10)).inOrder()
        assertThat(VideoClient.planParts(3, 10)).containsExactly(PartPlan(1, 0, 3))
        assertThat(runCatching { VideoClient.planParts(3, 0) }.isFailure).isTrue()
    }

    @Test
    fun serviceTokenParametersMatchSocialApp() {
        assertThat(VideoClient.uploadAudience("https://morel.us-east.host.bsky.network".toHttpUrl())).isEqualTo("did:web:morel.us-east.host.bsky.network")
        assertThat(VideoClient.UPLOAD_LXM).isEqualTo("com.atproto.repo.uploadBlob")
        assertThat(VideoClient.LIMITS_AUDIENCE).isEqualTo("did:web:video.bsky.app")
        assertThat(VideoClient.LIMITS_LXM).isEqualTo("app.bsky.video.getUploadLimits")
        assertThat(VideoClient.uploadTokenExpiry(1_763_200_000_999L)).isEqualTo(1_763_200_000L + 1800)
        assertThat(VideoClient.DEFAULT_BASE.toString()).isEqualTo("https://video.bsky.app/")
    }

    @Test
    fun fullUploadStartsSendsEveryPartAndFinishesWithAFreshToken() = runBlocking<Unit> {
        val f = file(25, "clip.gif")
        val tokens = Tokens()
        val startedSessions = mutableListOf<UploadSession>()
        val progress = mutableListOf<Long>()
        server.enqueue(jsonResponse(started()))
        server.enqueue(jsonResponse(part(1, 10)))
        server.enqueue(jsonResponse(part(2, 10)))
        server.enqueue(jsonResponse(part(3, 5)))
        server.enqueue(jsonResponse(finished("job-9")))

        val result = video.upload(f, "image/gif", tokens.provider, onStarted = { startedSessions += it }, onProgress = { sent, _ -> progress += sent })

        val requests = List(5) { server.next() }
        assertThat(requests.map { it.nsid() }).containsExactly(
            "app.bsky.video.startUpload", "app.bsky.video.uploadPart", "app.bsky.video.uploadPart",
            "app.bsky.video.uploadPart", "app.bsky.video.finishUpload",
        ).inOrder()
        assertThat(requests[0].bodyJson().str("mimeType")).isEqualTo("image/gif")
        val bytes = f.readBytes()
        assertThat(requests[1].body!!.toByteArray()).isEqualTo(bytes.copyOfRange(0, 10))
        assertThat(requests[2].body!!.toByteArray()).isEqualTo(bytes.copyOfRange(10, 20))
        assertThat(requests[3].body!!.toByteArray()).isEqualTo(bytes.copyOfRange(20, 25))
        assertThat(requests.take(4).map { it.headers["Authorization"] }.toSet()).containsExactly("Bearer t1")
        assertThat(requests[4].headers["Authorization"]).isEqualTo("Bearer t2")
        assertThat(tokens.forced).containsExactly(false, true).inOrder()
        assertThat(startedSessions.single().jobId).isEqualTo("job-1")
        assertThat(progress).containsExactly(0L, 10L, 20L, 25L).inOrder()
        assertThat(result.completedJobId).isEqualTo("job-9")
    }

    @Test
    fun resumeSendsOnlyTheMissingParts() = runBlocking<Unit> {
        val f = file(25)
        server.enqueue(jsonResponse(status("created", "[1,3]")))
        server.enqueue(jsonResponse(part(2, 10)))
        server.enqueue(jsonResponse(finished()))
        var started = false
        video.upload(f, "video/mp4", Tokens().provider, resumeJobId = "job-1", onStarted = { started = true })
        val requests = List(3) { server.next() }
        assertThat(requests.map { it.nsid() }).containsExactly(
            "app.bsky.video.getUploadStatus", "app.bsky.video.uploadPart", "app.bsky.video.finishUpload",
        ).inOrder()
        assertThat(requests[1].url.queryParameter("partNumber")).isEqualTo("2")
        assertThat(requests[1].body!!.toByteArray()).isEqualTo(f.readBytes().copyOfRange(10, 20))
        assertThat(started).isFalse()
    }

    @Test
    fun resumeOfACompletedUploadReturnsItsJob() = runBlocking<Unit> {
        server.enqueue(jsonResponse(status("completed", "[1,2,3]", ""","completedJobId":"job-9","jobStatus":${job("job-9", "JOB_STATE_ENCODING")}""")))
        val result = video.upload(file(25), "video/mp4", Tokens().provider, resumeJobId = "job-1")
        assertThat(result.completedJobId).isEqualTo("job-9")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun resumeWhileFinishingOnlyFinishes() = runBlocking<Unit> {
        server.enqueue(jsonResponse(status("finishing", "[1,2,3]")))
        server.enqueue(jsonResponse(finished()))
        video.upload(file(25), "video/mp4", Tokens().provider, resumeJobId = "job-1")
        assertThat(List(2) { server.next().nsid() }).containsExactly("app.bsky.video.getUploadStatus", "app.bsky.video.finishUpload").inOrder()
    }

    @Test
    fun expiredOrUnknownUploadStartsAgain() = runBlocking<Unit> {
        for (answer in listOf(jsonResponse(status("expired")), jsonResponse("""{"error":"UploadNotFound"}""", 404))) {
            server.enqueue(answer)
            server.enqueue(jsonResponse(started(partCount = 1).replace("\"partSizeBytes\":10", "\"partSizeBytes\":100")))
            server.enqueue(jsonResponse(part(1, 25)))
            server.enqueue(jsonResponse(finished()))
            val sessions = mutableListOf<UploadSession>()
            video.upload(file(25, "v${server.requestCount}.mp4"), "video/mp4", Tokens().provider, resumeJobId = "old-job", onStarted = { sessions += it })
            assertThat(List(4) { server.next().nsid() }).containsExactly(
                "app.bsky.video.getUploadStatus", "app.bsky.video.startUpload", "app.bsky.video.uploadPart", "app.bsky.video.finishUpload",
            ).inOrder()
            assertThat(sessions).hasSize(1)
        }
    }

    @Test
    fun failedUploadIsReportedWithItsReason() = runBlocking<Unit> {
        server.enqueue(jsonResponse(status("failed", extra = ""","failureReason":"unsupported codec"""")))
        val e = runCatching { video.upload(file(25), "video/mp4", Tokens().provider, resumeJobId = "job-1") }.exceptionOrNull() as XrpcException
        assertThat(e.error).isEqualTo("UploadFailed")
        assertThat(e.message).isEqualTo("unsupported codec")
        assertThat(e.isTransient).isFalse()
    }

    @Test
    fun partPlanThatDisagreesWithTheServerIsRefused() = runBlocking<Unit> {
        server.enqueue(jsonResponse(started(partCount = 4)))
        val e = runCatching { video.upload(file(25), "video/mp4", Tokens().provider) }.exceptionOrNull() as XrpcException
        assertThat(e.error).isEqualTo(XrpcException.INVALID_RESPONSE)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun partRejectedWithAnExpiredTokenIsResentWithAFreshOne() = runBlocking<Unit> {
        val tokens = Tokens()
        server.enqueue(jsonResponse(started(partCount = 1).replace("\"partSizeBytes\":10", "\"partSizeBytes\":100")))
        server.enqueue(jsonResponse("""{"error":"AuthRequired","message":"jwt expired"}""", 401))
        server.enqueue(jsonResponse(part(1, 25)))
        server.enqueue(jsonResponse(finished()))
        video.upload(file(25), "video/mp4", tokens.provider)
        val requests = List(4) { server.next() }
        assertThat(requests[1].headers["Authorization"]).isEqualTo("Bearer t1")
        assertThat(requests[2].headers["Authorization"]).isEqualTo("Bearer t2")
        assertThat(requests[2].url.queryParameter("partNumber")).isEqualTo("1")
        assertThat(requests[3].headers["Authorization"]).isEqualTo("Bearer t3")
        assertThat(tokens.forced).containsExactly(false, true, true).inOrder()
    }

    @Test
    fun missingPartsAtFinishAreResent() = runBlocking<Unit> {
        val f = file(25)
        server.enqueue(jsonResponse(started()))
        repeat(3) { server.enqueue(jsonResponse(part(it + 1, 10))) }
        server.enqueue(jsonResponse("""{"error":"MissingParts","message":"missing parts: 2"}""", 400))
        server.enqueue(jsonResponse(status("created", "[1,3]")))
        server.enqueue(jsonResponse(part(2, 10)))
        server.enqueue(jsonResponse(finished()))
        val result = video.upload(f, "video/mp4", Tokens().provider)
        val nsids = List(8) { server.next() }
        assertThat(nsids.map { it.nsid() }.takeLast(4)).containsExactly(
            "app.bsky.video.finishUpload", "app.bsky.video.getUploadStatus", "app.bsky.video.uploadPart", "app.bsky.video.finishUpload",
        ).inOrder()
        assertThat(nsids[6].url.queryParameter("partNumber")).isEqualTo("2")
        assertThat(result.completedJobId).isEqualTo("job-1")
    }

    @Test
    fun otherFinishErrorsPropagate() = runBlocking<Unit> {
        server.enqueue(jsonResponse(started(partCount = 1).replace("\"partSizeBytes\":10", "\"partSizeBytes\":100")))
        server.enqueue(jsonResponse(part(1, 25)))
        server.enqueue(jsonResponse("""{"error":"UploadNotReady","message":"finish in progress"}""", 409))
        val e = runCatching { video.upload(file(25), "video/mp4", Tokens().provider) }.exceptionOrNull() as XrpcException
        assertThat(e.error).isEqualTo("UploadNotReady")
        assertThat(XrpcException.isTransient(e)).isTrue()
    }

    @Test
    fun redirectsAreNotFollowedWithTheServiceToken() = runBlocking<Unit> {
        server.enqueue(mockwebserver3.MockResponse.Builder().code(302).addHeader("Location", "https://elsewhere.example/").build())
        val e = runCatching { video.getUploadLimits("t") }.exceptionOrNull() as XrpcException
        assertThat(e.status).isEqualTo(302)
        assertThat(server.requestCount).isEqualTo(1)
    }
}
