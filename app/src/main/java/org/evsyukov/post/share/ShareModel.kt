package org.evsyukov.post.share

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.evsyukov.post.AppContainer
import org.evsyukov.post.core.media.MediaKind
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.posts.Attachment
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.PostOptions
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.data.settings.Settings
import org.evsyukov.post.send.schedule.sendLaterSlots
import org.evsyukov.post.ui.composer.Blocker
import org.evsyukov.post.ui.composer.NetworkMedia
import org.evsyukov.post.ui.composer.PollLimits
import org.evsyukov.post.ui.composer.ProfileRender
import org.evsyukov.post.ui.composer.RenderInput
import org.evsyukov.post.ui.composer.blockers
import org.evsyukov.post.ui.composer.pieceCount
import org.evsyukov.post.ui.composer.planMedia
import org.evsyukov.post.ui.composer.renderTargets
import org.evsyukov.post.ui.composer.undecodableAttachments
import org.evsyukov.post.ui.composer.unreadableBlocker
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * The state of the Share sheet (product.md R19–R22, screens.md Share sheet): the shared text, the copied files,
 * the targets with the default ones selected, and what the selected accounts make of them. The shared files are
 * copied into app storage as soon as the model starts, while the share activity and its URI grant are alive
 * (R9, R19). Everything the user sees is kept in [saved], so the sheet comes back as it was after a rotation or
 * the death of the process, and the files are not copied twice.
 */
class ShareModel(
    private val container: AppContainer,
    private val saved: SavedStateHandle,
    shared: SharedContent,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {
    /** The draft text; the sheet edits it. */
    var text by mutableStateOf(saved.get<String>(KEY_TEXT) ?: shared.text)
        private set

    /** The selected accounts; null until the default targets are read (R16). */
    var targets by mutableStateOf(saved.get<LongArray>(KEY_TARGETS)?.toList())
        private set

    /** The copied files in the order they were shared; each has a negative id of its own until it is saved. */
    var attachments by mutableStateOf(saved.get<String>(KEY_FILES)?.let(::decode).orEmpty())
        private set

    /** Shared files that could not be added: unreadable, or not an image, video or audio file. */
    var failedFiles by mutableStateOf(saved.get<Int>(KEY_FAILED) ?: 0)
        private set

    /** Copied files that did not come back after process death because their copy is gone from app storage (media.md A1). */
    var missingFiles by mutableStateOf(saved.get<Int>(KEY_MISSING) ?: 0)
        private set

    /** The shared files are still being copied; nothing can be sent or opened until they are in. */
    var importing by mutableStateOf(saved.get<Boolean>(KEY_IMPORTED) != true && shared.uris.isNotEmpty())
        private set

    /** An action runs; another tap does nothing. */
    var busy by mutableStateOf(false)
        private set

    /**
     * The files the last send found unreadable on this device (media.md A4); the sheet shows the problem
     * until they are removed or replaced or a send finds them readable.
     */
    private var unreadable by mutableStateOf<List<Long>>(emptyList())

    /** The post went into the database (queued or as a draft); its files belong to it from now on. */
    private var handedOver = false

    val accounts: StateFlow<List<Account>?> = container.accounts.observe().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val settings: StateFlow<Settings?> = container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // The copies on screen stay while the sheet lives (media.md A1).
        container.filesInUse.hold(this) { attachments.map { it.path } }
        if (attachments.isNotEmpty()) dropMissingFiles()
        if (importing) viewModelScope.launch { importFiles(shared) }
        if (targets == null) {
            viewModelScope.launch {
                val existing = accounts.filterNotNull().first().map { it.id }.toSet()
                // R16: the default targets that still exist; an account removed since is dropped.
                if (targets == null) updateTargets(settings.filterNotNull().first().defaultTargets.filter { it in existing })
            }
        }
    }

    /**
     * After process death the copies come from the saved state; one the sweep deleted meanwhile is dropped. It
     * runs before the sheet shows anything, so it checks the few files right here rather than off the main thread.
     */
    private fun dropMissingFiles() {
        val present = attachments.filter { container.attachmentImporter.file(it.path).isFile }
        if (present.size == attachments.size) return
        missingFiles += attachments.size - present.size
        attachments = present
        saved[KEY_FILES] = encode(present)
        saved[KEY_MISSING] = missingFiles
    }

    private suspend fun importFiles(shared: SharedContent) {
        val result = container.attachmentImporter.import(shared.uris, pieceIndex = 0)
        attachments = result.attachments.mapIndexed { index, attachment -> attachment.copy(id = -1L - index) }
        failedFiles = result.failures.size
        saved[KEY_FILES] = encode(attachments)
        saved[KEY_FAILED] = failedFiles
        saved[KEY_IMPORTED] = true
        importing = false
    }

    fun editText(value: String) {
        text = value
        saved[KEY_TEXT] = value
    }

    /** A tap on a target chip selects or clears the account (R7). */
    fun toggleTarget(id: Long) {
        val current = targets.orEmpty()
        updateTargets(if (id in current) current - id else current + id)
    }

    private fun updateTargets(value: List<Long>) {
        targets = value
        saved[KEY_TARGETS] = value.toLongArray()
    }

    /** What the selected accounts make of the share: the counters, the media plans and what blocks sending. */
    data class Check(val renders: List<ProfileRender>, val media: List<NetworkMedia>, val blockers: List<Blocker>)

    /** [Check] of the sheet as it is now, with [accounts] and [settings]. */
    fun check(accounts: List<Account>, settings: Settings): Check {
        val byId = accounts.associateBy { it.id }
        val selected = targets.orEmpty().mapNotNull { byId[it] }
        val ids = selected.map { it.id }
        val current = text
        val files = attachments
        // A12: the default fixes of the settings apply; the sheet chooses no fix of its own (A13).
        val media = planMedia(files, pieceCount(current), selected, settings, emptyMap(), hasPoll = false)
        val input = RenderInput(current, spoilerText(selected), language(selected), ids, files)
        val renders = renderTargets(input, accounts, settings, container.textSegmenter, media)
        val blocked = blockers(ids, current, renders, null, PollLimits.of(selected), files, media, settings.requireAltText, importing) +
            unreadableBlocker(unreadable, files)
        return Check(renders, media, blocked)
    }

    /** [check] with the accounts and settings as they are now; empty until both are read. */
    fun currentCheck(): Check? {
        val list = accounts.value ?: return null
        val settings = settings.value ?: return null
        return check(list, settings)
    }

    /** The content warning a new draft takes from the first selected Mastodon account (R17). */
    private fun spoilerText(selected: List<Account>): String =
        selected.firstOrNull { it.network == Network.MASTODON }?.defaults?.contentWarning.orEmpty()

    private fun language(selected: List<Account>): String =
        selected.firstNotNullOfOrNull { it.defaults.languages.firstOrNull() } ?: Locale.getDefault().language

    private fun draftInput(selected: List<Account>) = DraftInput(
        text = text,
        options = PostOptions(language = language(selected), spoilerText = spoilerText(selected)),
        targets = selected.map { it.id },
        attachments = attachments.map { it.copy(id = 0) },
    )

    /** The outcome of an action of the sheet. */
    sealed interface Outcome {
        /** The post was queued for [accounts] accounts at [schedule]; the sheet closes (R21). */
        data class Queued(val accounts: Int, val schedule: Schedule) : Outcome

        /** The post was saved as draft [id] for the composer. */
        data class Draft(val id: Long) : Outcome

        /** Something blocks sending (R22); nothing was saved. */
        data class Blocked(val blockers: List<Blocker>) : Outcome

        /** Send later found no free slot: there is no schedule, or it is full; a time has to be picked. */
        data object NeedsTime : Outcome

        /** Another action was running, or the files are still being copied; nothing was done. */
        data object Ignored : Outcome
    }

    /**
     * Send now when [schedule] is null, else queues at [schedule] (R21): checks the limits of the selected accounts
     * again, and when nothing blocks, saves the post as queued with its files in one transaction before anything
     * goes to the network (R27), then wakes the sender.
     */
    suspend fun send(schedule: Schedule?): Outcome = act {
        val list = accounts.filterNotNull().first()
        val settings = settings.filterNotNull().first()
        val check = check(list, settings)
        if (check.blockers.isNotEmpty()) return@act Outcome.Blocked(check.blockers)
        // A4: a file this device cannot decode is caught here, while the sheet is on screen, not at send time.
        val problems = withContext(Dispatchers.IO) {
            undecodableAttachments(check.media, attachments, container.mediaDecodeCheck, container.attachmentImporter::file)
        }
        unreadable = problems
        if (problems.isNotEmpty()) return@act Outcome.Blocked(listOf(Blocker.MediaUnreadable(problems)))
        val byId = list.associateBy { it.id }
        val selected = targets.orEmpty().mapNotNull { byId[it] }
        val time = schedule ?: Schedule.Now(clock.instant())
        container.posts.saveAndQueue(draftInput(selected), time)
        handedOver = true
        container.prepareTrigger.prepareNow()
        container.publishTrigger.publishNow()
        Outcome.Queued(selected.size, time)
    }

    /** Send later by a tap (screens.md, Share sheet): the next free slot at once, or [Outcome.NeedsTime] without one. */
    suspend fun sendLater(): Outcome {
        if (busy || importing) return Outcome.Ignored
        val slot = nextFreeSlot() ?: return Outcome.NeedsTime
        return send(Schedule.Slot(slot))
    }

    /** The next free slot of the schedule (R24), or null when there is no schedule or no free slot. */
    suspend fun nextFreeSlot(): LocalDateTime? =
        sendLaterSlots(container.posts.observeQueue().first(), container.schedule.get(), zone(), clock.instant()).firstOrNull()

    /** Open in composer: saves the share as a draft, whatever its conflicts (A13), for the composer to open. */
    suspend fun saveDraft(): Outcome = act {
        val byId = accounts.filterNotNull().first().associateBy { it.id }
        val id = container.posts.saveDraft(draftInput(targets.orEmpty().mapNotNull { byId[it] }))
        handedOver = true
        Outcome.Draft(id)
    }

    private suspend fun act(block: suspend () -> Outcome): Outcome {
        if (busy || importing || handedOver) return Outcome.Ignored
        busy = true
        try {
            return block()
        } finally {
            busy = false
        }
    }

    /**
     * The sheet is gone for good. Files of a share that was dismissed belong to nothing and are deleted; those of
     * a saved post stay with it.
     */
    override fun onCleared() {
        container.filesInUse.release(this)
        if (handedOver) return
        val paths = attachments.map { it.path }
        if (paths.isNotEmpty()) container.scope.launch(Dispatchers.IO) { container.attachmentFiles.delete(paths) }
    }

    /** What of an attachment the saved state keeps: everything the importer read. */
    @Serializable
    private data class SavedFile(
        val id: Long,
        val kind: MediaKind,
        val path: String,
        val mime: String,
        val sizeBytes: Long,
        val width: Int? = null,
        val height: Int? = null,
        val durationMs: Long? = null,
        val frameRate: Double? = null,
        val animated: Boolean = false,
        val hasTransparency: Boolean = false,
        val videoCodec: String? = null,
    )

    companion object {
        private const val KEY_TEXT = "share.text"
        private const val KEY_TARGETS = "share.targets"
        private const val KEY_FILES = "share.files"
        private const val KEY_FAILED = "share.failed"
        private const val KEY_IMPORTED = "share.imported"
        private const val KEY_MISSING = "share.missing"

        private val serializer = ListSerializer(SavedFile.serializer())

        private fun encode(list: List<Attachment>): String = Json.encodeToString(
            serializer,
            list.map {
                SavedFile(it.id, it.kind, it.path, it.mime, it.sizeBytes, it.width, it.height, it.durationMs, it.frameRate, it.animated, it.hasTransparency, it.videoCodec)
            },
        )

        private fun decode(value: String): List<Attachment> = Json.decodeFromString(serializer, value).map {
            Attachment(
                id = it.id, kind = it.kind, path = it.path, mime = it.mime, sizeBytes = it.sizeBytes, width = it.width,
                height = it.height, durationMs = it.durationMs, frameRate = it.frameRate, animated = it.animated,
                hasTransparency = it.hasTransparency, videoCodec = it.videoCodec,
            )
        }
    }
}
