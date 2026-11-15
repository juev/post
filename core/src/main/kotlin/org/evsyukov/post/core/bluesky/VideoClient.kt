package org.evsyukov.post.core.bluesky

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.buffer
import okio.source
import java.io.File

/** `app.bsky.video.getUploadLimits` output. */
data class UploadLimits(
    val canUpload: Boolean,
    val remainingDailyVideos: Long?,
    val remainingDailyBytes: Long?,
    val message: String?,
    val error: String?,
)

/** `app.bsky.video.defs#jobStatus`. */
data class JobStatus(
    val jobId: String,
    val state: String,
    val progress: Int?,
    /**
     * The processed video on the PDS. It may come with a failed job too: a video the service already
     * processed fails with `already_exists` and still returns the earlier blob (docs.bsky.app
     * tutorials/video, `bsky-docs` `fd3d7f8b`), so take the blob whenever it is present.
     */
    val blob: BlobRef?,
    val error: String?,
    val failureCode: String?,
    val message: String?,
) {
    val isCompleted: Boolean get() = state == STATE_COMPLETED
    val isFailed: Boolean get() = state == STATE_FAILED

    companion object {
        const val STATE_COMPLETED = "JOB_STATE_COMPLETED"
        const val STATE_FAILED = "JOB_STATE_FAILED"
    }
}

/** `app.bsky.video.startUpload` output. */
data class UploadSession(val jobId: String, val partSizeBytes: Long, val partCount: Int, val expiresAt: String)

/** `app.bsky.video.uploadPart` output. */
data class PartReceipt(val partNumber: Int, val sizeBytes: Long)

/** One part of the file: 1-based [partNumber], byte [offset] and [size]. */
data class PartPlan(val partNumber: Int, val offset: Long, val size: Long)

/** `app.bsky.video.getUploadStatus` output. */
data class UploadStatus(
    val jobId: String,
    val partSizeBytes: Long,
    val partCount: Int,
    val receivedParts: List<Int>,
    val expiresAt: String,
    /** `created`, `finishing`, `completed`, `failed`, `aborted` or `expired`. */
    val state: String,
    val completedJobId: String?,
    val jobStatus: JobStatus?,
    val failureReason: String?,
)

/** `app.bsky.video.finishUpload` output; poll [JobStatus] by [completedJobId], which may differ from the upload's job id. */
data class FinishedUpload(val completedJobId: String, val jobStatus: JobStatus)

/** `app.bsky.video.abortUpload` output. */
data class AbortedUpload(val state: String, val completedJobId: String?, val failureReason: String?)

/**
 * Client of the Bluesky video service (networks.md N20). Video and GIF (`image/gif`, sent without
 * conversion with `presentation: gif`, N21) are uploaded here instead of `uploadBlob`.
 *
 * VERIFIED PATH: the multipart upload, from the lexicons `app.bsky.video.{startUpload,uploadPart,
 * finishUpload,getUploadStatus,abortUpload,getJobStatus,getUploadLimits}` at `bluesky-social/bsky`
 * `416b0ad1d9` and the official client at `bluesky-social/social-app` `9747031e9b`
 * (`src/lib/media/video/multipart/{api,upload,uploadPart,planParts}.ts`,
 * `src/lib/media/video/upload.shared.ts`, `src/lib/constants.ts:209-210`), which uses only this path.
 * Auth, as in social-app:
 * - [getUploadLimits]: a service token with `aud` [LIMITS_AUDIENCE] and `lxm` [LIMITS_LXM];
 * - every multipart call: one token with `aud` = [uploadAudience] of the account's PDS, `lxm`
 *   [UPLOAD_LXM], valid [SERVICE_TOKEN_TTL_SECONDS]; the service later uploads the processed video to
 *   the PDS with the token given to `finishUpload`;
 * - [getJobStatus]: no auth.
 * Part sizes come from the server (`partSizeBytes`); the file is split like social-app's
 * `planParts`, and a plan whose length differs from `partCount` is rejected. The older single-request
 * `app.bsky.video.uploadVideo` is not implemented: social-app no longer uses it.
 *
 * Calls never follow redirects.
 */
class VideoClient(
    http: OkHttpClient,
    json: Json,
    base: HttpUrl = DEFAULT_BASE,
    clock: () -> Long = System::currentTimeMillis,
) {
    private val xrpc = Xrpc(base, http, json, clock, followRedirects = false)

    suspend fun getUploadLimits(serviceToken: String): UploadLimits {
        val nsid = "app.bsky.video.getUploadLimits"
        val request = Request.Builder().url(xrpc.url(nsid)).header("Authorization", "Bearer $serviceToken").get().build()
        val fields = xrpc.call(request, nsid, listOf(serviceToken))
        return UploadLimits(
            canUpload = fields.boolean("canUpload"),
            remainingDailyVideos = fields.obj.optLong("remainingDailyVideos"),
            remainingDailyBytes = fields.obj.optLong("remainingDailyBytes"),
            message = fields.obj.optString("message"),
            error = fields.obj.optString("error"),
        )
    }

    /**
     * `startUpload` with the exact size. [durationMs], [width] and [height] are advisory, used by the
     * service only to fail early. No file name is sent: it is optional and may be private.
     */
    suspend fun startUpload(
        serviceToken: String,
        sizeBytes: Long,
        mimeType: String,
        durationMs: Long? = null,
        width: Int? = null,
        height: Int? = null,
    ): UploadSession {
        require(sizeBytes >= 1) { "empty video" }
        val nsid = "app.bsky.video.startUpload"
        val body = buildJsonObject {
            put("sizeBytes", sizeBytes)
            put("mimeType", mimeType)
            if (durationMs != null) put("durationMs", durationMs)
            if (width != null) put("width", width)
            if (height != null) put("height", height)
        }
        val request = authorized(nsid, serviceToken).post(xrpc.jsonBody(body)).build()
        val fields = xrpc.call(request, nsid, listOf(serviceToken))
        val session = UploadSession(
            jobId = fields.string("jobId"),
            partSizeBytes = fields.long("partSizeBytes"),
            partCount = fields.long("partCount").toInt(),
            expiresAt = fields.string("expiresAt"),
        )
        if (session.partSizeBytes <= 0) throw fields.invalid("'partSizeBytes' is not positive")
        return session
    }

    /**
     * `uploadPart`: bytes `[part.offset, part.offset + part.size)` of [file] as
     * `application/octet-stream` with an exact `Content-Length`. Idempotent, may be repeated.
     */
    suspend fun uploadPart(serviceToken: String, jobId: String, file: File, part: PartPlan): PartReceipt {
        val nsid = "app.bsky.video.uploadPart"
        val url = xrpc.url(nsid, listOf("jobId" to jobId, "partNumber" to part.partNumber.toString()))
        val request = Request.Builder().url(url).header("Authorization", "Bearer $serviceToken")
            .post(FilePartBody(file, part.offset, part.size, OCTET_STREAM)).build()
        val fields = xrpc.call(request, nsid, listOf(serviceToken))
        return PartReceipt(fields.obj.optLong("partNumber")?.toInt() ?: part.partNumber, fields.obj.optLong("sizeBytes") ?: part.size)
    }

    /** `finishUpload`, idempotent. Missing parts fail with error `MissingParts`. */
    suspend fun finishUpload(serviceToken: String, jobId: String): FinishedUpload {
        val nsid = "app.bsky.video.finishUpload"
        val request = authorized(nsid, serviceToken).post(xrpc.jsonBody(buildJsonObject { put("jobId", jobId) })).build()
        val fields = xrpc.call(request, nsid, listOf(serviceToken))
        return FinishedUpload(fields.string("completedJobId"), jobStatus(fields, fields.obj("jobStatus")))
    }

    /** `getUploadStatus`: the parts received so far, to resume an upload. */
    suspend fun getUploadStatus(serviceToken: String, jobId: String): UploadStatus {
        val nsid = "app.bsky.video.getUploadStatus"
        val request = Request.Builder().url(xrpc.url(nsid, listOf("jobId" to jobId)))
            .header("Authorization", "Bearer $serviceToken").get().build()
        val fields = xrpc.call(request, nsid, listOf(serviceToken))
        val received = fields.obj.optArray("receivedParts") ?: throw fields.invalid("missing array field 'receivedParts'")
        return UploadStatus(
            jobId = fields.string("jobId"),
            partSizeBytes = fields.long("partSizeBytes"),
            partCount = fields.long("partCount").toInt(),
            receivedParts = received.mapNotNull { (it as? JsonPrimitive)?.longOrNull?.toInt() },
            expiresAt = fields.string("expiresAt"),
            state = fields.string("state"),
            completedJobId = fields.obj.optString("completedJobId"),
            jobStatus = fields.obj.optObject("jobStatus")?.let { jobStatus(fields, it) },
            failureReason = fields.obj.optString("failureReason"),
        )
    }

    /** `abortUpload`: releases the quota of an upload still in `created`; terminal uploads report their outcome. */
    suspend fun abortUpload(serviceToken: String, jobId: String): AbortedUpload {
        val nsid = "app.bsky.video.abortUpload"
        val request = authorized(nsid, serviceToken).post(xrpc.jsonBody(buildJsonObject { put("jobId", jobId) })).build()
        val fields = xrpc.call(request, nsid, listOf(serviceToken))
        return AbortedUpload(fields.string("state"), fields.obj.optString("completedJobId"), fields.obj.optString("failureReason"))
    }

    /** `getJobStatus`, without auth (social-app polls it with a tokenless client). */
    suspend fun getJobStatus(jobId: String): JobStatus {
        val nsid = "app.bsky.video.getJobStatus"
        val request = Request.Builder().url(xrpc.url(nsid, listOf("jobId" to jobId))).get().build()
        val fields = xrpc.call(request, nsid)
        return jobStatus(fields, fields.obj("jobStatus"))
    }

    /**
     * Polls [getJobStatus] until the job has a blob, completes or fails, and returns that status.
     * Wrap in a timeout; network errors propagate.
     */
    suspend fun awaitJob(jobId: String, pollIntervalMillis: Long = DEFAULT_POLL_MILLIS): JobStatus {
        while (true) {
            val status = getJobStatus(jobId)
            if (status.blob != null || status.isCompleted || status.isFailed) return status
            delay(pollIntervalMillis)
        }
    }

    /**
     * The whole multipart upload of [file], resumable across attempts.
     *
     * With [resumeJobId] (saved from [onStarted] by an earlier attempt), the upload status decides:
     * `completed` returns its job; `created` sends only the parts not in `receivedParts`; `finishing`
     * goes straight to `finishUpload`; `failed` throws; `aborted`, `expired` or an unknown job start a
     * new upload. A part rejected with 401 is sent once more with a fresh token. After the parts,
     * `finishUpload` gets a fresh token, since the service keeps it to upload the result to the PDS
     * (social-app `multipart/upload.ts:130-133`). If finishing reports missing parts, they are sent
     * again, up to [MAX_FINISH_ROUNDS] times.
     *
     * Network and transient failures propagate: the caller retries later with the same job id.
     *
     * @param serviceToken returns a token for the multipart calls (see the class description); `true`
     *   asks for a new one
     */
    suspend fun upload(
        file: File,
        mimeType: String,
        serviceToken: suspend (fresh: Boolean) -> String,
        resumeJobId: String? = null,
        onStarted: suspend (UploadSession) -> Unit = {},
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): FinishedUpload {
        val size = file.length()
        require(size > 0) { "empty video" }
        var token = serviceToken(false)

        var jobId: String? = null
        var parts: List<PartPlan> = emptyList()
        var received: Set<Int> = emptySet()
        if (resumeJobId != null) {
            val status = try {
                getUploadStatus(token, resumeJobId)
            } catch (e: XrpcException) {
                if (e.error != "UploadNotFound") throw e
                null
            }
            when (status?.state) {
                "completed" -> return completed(status)
                "created", "finishing" -> {
                    jobId = resumeJobId
                    parts = checkedPlan(size, status.partSizeBytes, status.partCount, "getUploadStatus")
                    received = if (status.state == "finishing") parts.map { it.partNumber }.toSet() else status.receivedParts.toSet()
                }
                "failed" -> throw XrpcException(200, "UploadFailed", status.failureReason, null)
            }
        }
        if (jobId == null) {
            val session = startUpload(token, size, mimeType)
            parts = checkedPlan(size, session.partSizeBytes, session.partCount, "startUpload")
            jobId = session.jobId
            onStarted(session)
        }

        sendParts(token, serviceToken, jobId, file, parts, received, size, onProgress)
        token = serviceToken(true)
        repeat(MAX_FINISH_ROUNDS) {
            try {
                return finishUpload(token, jobId)
            } catch (e: XrpcException) {
                if (e.error != "MissingParts") throw e
                val status = getUploadStatus(token, jobId)
                if (status.state == "completed") return completed(status)
                token = sendParts(token, serviceToken, jobId, file, parts, status.receivedParts.toSet(), size, onProgress)
            }
        }
        return finishUpload(token, jobId)
    }

    private suspend fun sendParts(
        initialToken: String,
        serviceToken: suspend (Boolean) -> String,
        jobId: String,
        file: File,
        parts: List<PartPlan>,
        received: Set<Int>,
        size: Long,
        onProgress: (Long, Long) -> Unit,
    ): String {
        var token = initialToken
        var sent = parts.filter { it.partNumber in received }.sumOf { it.size }
        onProgress(sent, size)
        for (part in parts) {
            if (part.partNumber in received) continue
            try {
                uploadPart(token, jobId, file, part)
            } catch (e: XrpcException) {
                if (e.status != 401) throw e
                token = serviceToken(true)
                uploadPart(token, jobId, file, part)
            }
            sent += part.size
            onProgress(sent, size)
        }
        return token
    }

    private fun completed(status: UploadStatus): FinishedUpload {
        val job = status.jobStatus ?: throw XrpcException(200, XrpcException.INVALID_RESPONSE, "completed upload without a job status", null)
        return FinishedUpload(status.completedJobId ?: job.jobId, job)
    }

    private fun checkedPlan(size: Long, partSize: Long, partCount: Int, nsid: String): List<PartPlan> {
        if (partSize <= 0) throw XrpcException.invalidResponse(200, nsid, "'partSizeBytes' is not positive")
        val plan = planParts(size, partSize)
        if (plan.size != partCount) throw XrpcException.invalidResponse(200, nsid, "'partCount' does not match the file size")
        return plan
    }

    private fun authorized(nsid: String, token: String) = Request.Builder().url(xrpc.url(nsid)).header("Authorization", "Bearer $token")

    /** `app.bsky.video.defs#jobStatus`, nested in the answer described by [fields]. */
    private fun jobStatus(fields: Fields, obj: JsonObject): JobStatus {
        fun required(key: String) = obj.optString(key)?.takeIf { it.isNotEmpty() } ?: throw fields.invalid("jobStatus lacks '$key'")
        return JobStatus(
            jobId = required("jobId"),
            state = required("state"),
            progress = obj.optLong("progress")?.toInt(),
            blob = obj.optObject("blob")?.let { BlobRef(it) },
            error = obj.optString("error"),
            failureCode = obj.optString("failureCode"),
            message = obj.optString("message"),
        )
    }

    /** Sends a byte range of a file; repeatable, so OkHttp may resend it. */
    private class FilePartBody(
        private val file: File,
        private val offset: Long,
        private val size: Long,
        private val type: MediaType,
    ) : RequestBody() {
        override fun contentType(): MediaType = type
        override fun contentLength(): Long = size
        override fun writeTo(sink: BufferedSink) {
            file.source().buffer().use { source ->
                source.skip(offset)
                sink.write(source, size)
            }
        }
    }

    companion object {
        /** social-app `src/lib/constants.ts:209`. */
        val DEFAULT_BASE: HttpUrl = "https://video.bsky.app/".toHttpUrl()

        /** social-app `src/lib/constants.ts:210`, the `aud` of the limits token. */
        const val LIMITS_AUDIENCE = "did:web:video.bsky.app"
        const val LIMITS_LXM = "app.bsky.video.getUploadLimits"

        /** `lxm` of the multipart token (social-app `multipart/upload.ts:302`). */
        const val UPLOAD_LXM = "com.atproto.repo.uploadBlob"

        /** 30 minutes (social-app `upload.shared.ts:61`). */
        const val SERVICE_TOKEN_TTL_SECONDS = 30L * 60

        const val MAX_FINISH_ROUNDS = 3
        const val DEFAULT_POLL_MILLIS = 1500L

        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        /** `did:web:<PDS host>`, the `aud` of the multipart token (social-app `url-helpers.ts:431-437`). */
        fun uploadAudience(pds: HttpUrl): String = "did:web:${pds.host}"

        /** `exp` for a multipart token requested at [nowMillis]. */
        fun uploadTokenExpiry(nowMillis: Long): Long = nowMillis / 1000 + SERVICE_TOKEN_TTL_SECONDS

        /** Splits [totalSize] bytes into parts of [partSize]; the last one takes the rest (social-app `planParts.ts`). */
        fun planParts(totalSize: Long, partSize: Long): List<PartPlan> {
            require(partSize > 0) { "part size must be positive" }
            val parts = mutableListOf<PartPlan>()
            var offset = 0L
            var number = 1
            while (offset < totalSize) {
                val size = minOf(partSize, totalSize - offset)
                parts += PartPlan(number++, offset, size)
                offset += size
            }
            return parts
        }
    }
}
