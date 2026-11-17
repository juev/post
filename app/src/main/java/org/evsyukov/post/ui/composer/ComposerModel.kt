package org.evsyukov.post.ui.composer

import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.evsyukov.post.AppContainer
import org.evsyukov.post.accounts.canEdit
import org.evsyukov.post.core.media.ConflictKind
import org.evsyukov.post.core.media.FixKind
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.posts.AccountPostOptions
import org.evsyukov.post.data.posts.Attachment
import org.evsyukov.post.data.db.PostKind
import org.evsyukov.post.data.history.HistoryRecord
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.KeptMedia
import org.evsyukov.post.data.posts.PollDraft
import org.evsyukov.post.data.posts.PostNotEditable
import org.evsyukov.post.data.posts.PostNotFound
import org.evsyukov.post.data.posts.PostOptions
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.data.settings.Settings
import java.time.Instant
import java.util.Locale

/** Everything the composer saves, to tell whether something changed since the last save (R15). */
data class ComposerContent(
    val text: String,
    val spoilerText: String?,
    val language: String,
    val targets: List<Long>,
    val accountOptions: Map<Long, AccountPostOptions>,
    val poll: PollDraft?,
    val attachments: List<SavedAttachment> = emptyList(),
    val chosenFixes: ChosenFixes = emptyMap(),
    val keptMedia: List<KeptMedia> = emptyList(),
)

/** What of an attachment the composer saves; the file path stands for the file, whatever its id. */
data class SavedAttachment(val path: String, val pieceIndex: Int, val alt: String, val focusX: Float?, val focusY: Float?)

/**
 * The state of the composer: the draft being edited, its targets and options, and the counters of the
 * selected accounts (screens.md, Composer). It outlives configuration changes; the text fields are
 * [TextFieldState]s the editor writes into directly.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ComposerModel(
    private val container: AppContainer,
    initialDraftId: Long?,
    /** Opens the composer in edit mode for this history record (R40). */
    private val editHistoryId: Long? = null,
    /** New post from this (R31): a new draft with the text and options of this history record. */
    private val fromHistoryId: Long? = null,
    /** The saved state of the screen: what the composer shows comes back from it after process death; null keeps nothing. */
    private val savedState: SavedStateHandle? = null,
) : ViewModel() {
    val text = TextFieldState()
    val spoiler = TextFieldState()

    /** The content warning field is shown; its text goes out only while it is. */
    var spoilerShown by mutableStateOf(false)
        private set
    var language by mutableStateOf(Locale.getDefault().language)
        private set
    var targets by mutableStateOf<List<Long>>(emptyList())
        private set
    var accountOptions by mutableStateOf<Map<Long, AccountPostOptions>>(emptyMap())
        private set
    var poll by mutableStateOf<PollDraft?>(null)
        private set

    /**
     * The attachments of the draft in order (R9). A saved one has its database id; one added since the last
     * save has a negative id of its own, which the planner and the strip use as its key and which is saved
     * as a new row.
     */
    var attachments by mutableStateOf<List<Attachment>>(emptyList())
        private set

    /** The fixes the user chose for media conflicts, per network (media.md A10). */
    var chosenFixes by mutableStateOf<ChosenFixes>(emptyMap())
        private set

    /** Imports running now; the strip shows progress while any runs. */
    var importing by mutableStateOf(0)
        private set

    /**
     * Unsaved attachments that did not come back after process death because their copy is gone from app storage
     * (media.md A1); the screen says so once and calls [missingFilesShown].
     */
    var missingFiles by mutableStateOf(0)
        private set

    private var nextNewId = -1L

    /**
     * Changes whenever the composer starts another draft or reloads one (sent, cleared, discarded); an import
     * that started before belongs to a draft that is gone, so its files are deleted instead of added.
     */
    private var draftGeneration = 0

    /** The post being edited, or null for a new one that was never saved. */
    var draftId by mutableStateOf(initialDraftId)
        private set

    /** False until the draft or the defaults of a new one are in place. */
    var loaded by mutableStateOf(false)
        private set

    /** The counters of the current text, or null before the first render. */
    var renders by mutableStateOf<Pair<RenderInput, List<ProfileRender>>?>(null)
        private set

    // State, so that whatever reads [hasUnsavedChanges] in a derived state sees a save.
    private var saved by mutableStateOf<ComposerContent?>(null)

    /** A send is running: Send and Send later do nothing until it ends, so a double tap queues one post. */
    var sending by mutableStateOf(false)
        private set

    val accounts: StateFlow<List<Account>?> = container.accounts.observe().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val settings: StateFlow<Settings?> = container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** A queued post is being edited: Send saves it and keeps its time (R25, Q2). */
    var editingQueued by mutableStateOf(false)
        private set

    /**
     * The published post being edited (R40, screens.md "Editing a published post"), or null. Its targets are
     * the accounts holding it; a Bluesky account becomes a target only for "delete and post again" (N32).
     */
    var editing by mutableStateOf<HistoryRecord?>(null)
        private set

    /** Edit mode was asked for, but the record is gone (R37): the screen says so and saves nothing. */
    var editUnavailable by mutableStateOf(false)
        private set

    /** The published media of the post being edited, with the alt text and removal chosen here (N30). */
    var keptMedia by mutableStateOf<List<KeptMedia>>(emptyList())
        private set

    /** The accounts that hold the post being edited, which are the only targets an edit can have (R40). */
    val holders: List<Long> get() = editing?.items?.map { it.accountId }.orEmpty()

    /** Posts in the Late group, for the warning badge on Posts (scheduling.md Q7). */
    val lateCount: StateFlow<Int> = container.posts.observeQueue()
        .map { list -> list.count { it.state == org.evsyukov.post.data.db.PostState.HELD } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Posts waiting in the queue, for the badge on Posts. */
    val queueCount: StateFlow<Int> = container.posts.observeQueue().map { it.size }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    init {
        // The copies on screen stay while the composer lives, saved or not (media.md A1).
        container.filesInUse.hold(this) { attachments.map { it.path } }
        viewModelScope.launch {
            // After process death the draft comes back as it was on screen, saved or not (R15, R27).
            val state = savedState
            val snapshot = ComposerSnapshot.decode(state?.get<String>(KEY_SNAPSHOT))
            load(snapshot?.draftId ?: initialDraftId)
            if (snapshot != null) restore(snapshot)
            if (state != null) {
                snapshotFlow { if (loaded) toSnapshot() else null }.filterNotNull().collect { state[KEY_SNAPSHOT] = it.encode() }
            }
        }
        viewModelScope.launch {
            snapshotFlow { renderInput() }
                .combine(accounts.filterNotNull()) { input, list -> input to list }
                .combine(settings.filterNotNull()) { (input, list), settings -> Triple(input, list, settings) }
                .debounce(RENDER_DEBOUNCE_MS)
                .mapLatest { (input, list, settings) -> input to render(input, list, settings) }
                .collect { renders = it }
        }
    }

    fun renderInput() = RenderInput(
        text = text.text.toString(),
        spoilerText = if (spoilerShown) spoiler.text.toString() else "",
        language = language,
        targets = targets,
        attachments = attachments,
        chosenFixes = chosenFixes,
        hasPoll = poll != null,
    )

    private suspend fun render(input: RenderInput, accounts: List<Account>, settings: Settings): List<ProfileRender> =
        withContext(Dispatchers.Default) {
            renderTargets(input, accounts, settings, container.textSegmenter, mediaOf(input, accounts, settings))
        }

    /** The media plan of each selected network for [input] (media.md A2, A3). */
    private fun mediaOf(input: RenderInput, accounts: List<Account>, settings: Settings): List<NetworkMedia> {
        val byId = accounts.associateBy { it.id }
        return planMedia(input.attachments, pieceCount(input.text), input.targets.mapNotNull { byId[it] }, settings, input.chosenFixes, input.hasPoll)
    }

    /** The media plans of the selected networks for the draft as it is now. */
    fun currentMedia(): List<NetworkMedia> {
        val settings = settings.value ?: return emptyList()
        return mediaOf(renderInput().copy(targets = liveTargets()), accounts.value.orEmpty(), settings)
    }

    /** The selected accounts in the order of the targets. */
    fun selectedAccounts(): List<Account> {
        val byId = accounts.value.orEmpty().associateBy { it.id }
        return targets.mapNotNull { byId[it] }
    }

    fun pollLimits(): PollLimits = PollLimits.of(selectedAccounts())

    /** What keeps Send disabled for the counters shown now. */
    fun currentBlockers(): List<Blocker> = blockers(
        liveTargets(), text.text.toString(), renders?.second.orEmpty(), poll, pollLimits(),
        attachments, currentMedia(), settings.value?.requireAltText == true, importing > 0,
    )

    /** The targets that still exist: an account removed while selected is dropped. */
    private fun liveTargets(): List<Long> {
        val existing = accounts.value?.map { it.id }?.toSet() ?: return targets
        return targets.filter { it in existing }
    }

    // Loading and drafts (R15, R16, R17)

    private suspend fun load(id: Long?) {
        draftGeneration++
        val accounts = accounts.filterNotNull().first()
        val settings = settings.filterNotNull().first()
        val post = id?.let { container.posts.get(it) }
        val existing = accounts.map { it.id }.toSet()
        if (post == null && editHistoryId != null && id == null) {
            val record = container.history.get(editHistoryId)
            newDraft(accounts, settings)
            if (record == null) {
                editUnavailable = true
            } else {
                fill(record.text, record.options, accounts, emptyList())
                editing = record
                // The record's own options may hold the edits of an earlier edit, by places that have moved since.
                keptMedia = keptMediaOf(record, emptyList())
                // R40: a Mastodon copy is edited in place where the server can; a copy that cannot be edited,
                // Bluesky or an old Mastodon server, is left unchanged by default (N31, N32).
                targets = record.items.filter { item ->
                    item.network == Network.MASTODON && item.accountId in existing &&
                        accounts.firstOrNull { it.id == item.accountId }?.canEdit() == true
                }.map { it.accountId }
            }
        } else if (post == null && fromHistoryId != null && id == null) {
            val record = container.history.get(fromHistoryId)
            newDraft(accounts, settings)
            if (record != null) {
                fill(record.text, record.options.copy(keptMedia = emptyList()), accounts, emptyList())
                targets = record.items.map { it.accountId }.filter { it in existing }.distinct()
            }
        } else if (post == null) {
            newDraft(accounts, settings)
        } else {
            draftId = post.id
            editingQueued = post.state == org.evsyukov.post.data.db.PostState.QUEUED || post.state == org.evsyukov.post.data.db.PostState.HELD
            text.setTextAndPlaceCursorAtEnd(post.text)
            spoiler.setTextAndPlaceCursorAtEnd(post.options.spoilerText)
            spoilerShown = post.options.spoilerText.isNotEmpty()
            language = post.options.language ?: defaultLanguage(accounts.filter { it.id in post.targets })
            targets = post.targets
            accountOptions = post.options.accounts
            poll = post.options.poll
            attachments = post.attachments
            chosenFixes = post.options.chosenFixes
            if (post.kind == PostKind.EDIT) {
                val record = post.historyId?.let { container.history.get(it) }
                editing = record
                editUnavailable = record == null
                keptMedia = record?.let { keptMediaOf(it, post.options.keptMedia) }.orEmpty()
            }
        }
        // A new post from a sent one is not saved yet: leaving asks whether to keep it (R15).
        saved = if (fromHistoryId != null && id == null) null else content()
        loaded = true
    }

    /** What the screen shows, for the saved state. */
    private fun toSnapshot() = ComposerSnapshot(
        draftId = draftId,
        text = text.text.toString(),
        spoilerText = spoiler.text.toString(),
        spoilerShown = spoilerShown,
        language = language,
        targets = targets,
        accountOptions = accountOptions,
        poll = poll,
        attachments = attachments.map(SnapshotFile::of),
        chosenFixes = chosenFixes,
        keptMedia = keptMedia,
    )

    /**
     * Puts back what the screen showed before process death over the draft [load] read. A saved attachment
     * comes from the database with its variants and takes the edits of the snapshot; one added since the last
     * save comes back while its copy is still in app storage, and [missingFiles] counts those that do not.
     */
    private suspend fun restore(snapshot: ComposerSnapshot) {
        text.setTextAndPlaceCursorAtEnd(snapshot.text)
        spoiler.setTextAndPlaceCursorAtEnd(snapshot.spoilerText)
        spoilerShown = snapshot.spoilerShown
        language = snapshot.language
        targets = snapshot.targets
        accountOptions = snapshot.accountOptions
        poll = snapshot.poll
        chosenFixes = snapshot.chosenFixes
        if (editing != null) keptMedia = snapshot.keptMedia
        val stored = attachments.associateBy { it.id }
        var missing = 0
        val restored = withContext(Dispatchers.IO) {
            snapshot.attachments.mapNotNull { file ->
                if (file.id >= 0) {
                    stored[file.id]?.copy(pieceIndex = file.pieceIndex, alt = file.alt, focusX = file.focusX, focusY = file.focusY)
                } else {
                    // The sweep deletes a copy no row refers to once it is a day old.
                    file.toAttachment().takeIf { container.attachmentImporter.file(it.path).isFile } ?: run {
                        missing++
                        null
                    }
                }
            }
        }
        attachments = restored
        missingFiles = missing
        nextNewId = minOf(-1L, (restored.minOfOrNull { it.id } ?: 0L) - 1)
    }

    /** The composer filled with [text] and [options], as a saved draft or a history record gives them. */
    private fun fill(text: String, options: PostOptions, accounts: List<Account>, targets: List<Long>) {
        this.text.setTextAndPlaceCursorAtEnd(text)
        spoiler.setTextAndPlaceCursorAtEnd(options.spoilerText)
        spoilerShown = options.spoilerText.isNotEmpty()
        language = options.language ?: defaultLanguage(accounts.filter { it.id in targets })
        this.targets = targets
        accountOptions = options.accounts
        poll = options.poll
        chosenFixes = options.chosenFixes
    }

    /** Every published file of [record] by place, with the edits of [edits] applied (N30). */
    private fun keptMediaOf(record: HistoryRecord, edits: List<KeptMedia>): List<KeptMedia> {
        val item = record.items.firstOrNull { it.network == Network.MASTODON } ?: record.items.firstOrNull() ?: return emptyList()
        val byPlace = edits.associateBy { it.part to it.index }
        var ordinal = 0
        return item.parts.flatMapIndexed { part, published ->
            published.media.mapIndexed { index, media ->
                // The place among all files names the same file in every Mastodon copy, whatever its split.
                (byPlace[part to index] ?: KeptMedia(part, index, media.alt)).copy(ordinal = ordinal++)
            }
        }
    }

    fun setKeptMediaAlt(part: Int, index: Int, alt: String) {
        keptMedia = keptMedia.map { if (it.part == part && it.index == index) it.copy(alt = alt) else it }
    }

    fun toggleKeptMedia(part: Int, index: Int) {
        keptMedia = keptMedia.map { if (it.part == part && it.index == index) it.copy(removed = !it.removed) else it }
    }

    /** An empty draft with the default targets (R16) and the defaults of the first of them (R17). */
    private fun newDraft(accounts: List<Account>, settings: Settings) {
        draftGeneration++
        editingQueued = false
        editing = null
        editUnavailable = false
        keptMedia = emptyList()
        val existing = accounts.associateBy { it.id }
        val defaults = settings.defaultTargets.filter { it in existing }
        val selected = defaults.mapNotNull { existing[it] }
        draftId = null
        text.clearText()
        val warning = selected.firstOrNull { it.network == Network.MASTODON }?.defaults?.contentWarning.orEmpty()
        spoiler.setTextAndPlaceCursorAtEnd(warning)
        spoilerShown = warning.isNotEmpty()
        language = defaultLanguage(selected)
        targets = defaults
        accountOptions = emptyMap()
        poll = null
        attachments = emptyList()
        chosenFixes = emptyMap()
    }

    private fun defaultLanguage(selected: List<Account>): String =
        selected.firstNotNullOfOrNull { it.defaults.languages.firstOrNull() } ?: Locale.getDefault().language

    fun content() = ComposerContent(
        text = text.text.toString(),
        spoilerText = if (spoilerShown) spoiler.text.toString() else null,
        language = language,
        targets = targets,
        accountOptions = accountOptions,
        poll = poll,
        attachments = attachments.map { SavedAttachment(it.path, it.pieceIndex, it.alt, it.focusX, it.focusY) },
        chosenFixes = chosenFixes,
        keptMedia = keptMedia,
    )

    /**
     * Whether leaving would lose something (R15): the content differs from the last save, and there is
     * something worth keeping. Choosing other targets for an empty new draft is not worth a question.
     */
    fun hasUnsavedChanges(): Boolean {
        if (!loaded) return false
        val now = content()
        if (now == saved) return false
        return draftId != null || now.text.isNotBlank() || !now.spoilerText.isNullOrBlank() || now.poll != null ||
            now.attachments.isNotEmpty()
    }

    private fun options() = PostOptions(
        language = language,
        spoilerText = if (spoilerShown) spoiler.text.toString() else "",
        accounts = accountOptions.filterKeys { it in targets },
        poll = poll,
        chosenFixes = chosenFixes,
        keptMedia = if (editing != null) keptMedia else emptyList(),
    )

    private fun draftInput() = DraftInput(
        id = draftId,
        text = text.text.toString(),
        options = options(),
        targets = liveTargets(),
        // A new attachment is saved as a new row (id 0); a saved one keeps its id.
        attachments = attachments.map { if (it.id < 0) it.copy(id = 0) else it },
        kind = if (editing != null) PostKind.EDIT else PostKind.NEW,
        historyId = editing?.id,
    )

    /**
     * Saves the draft and remembers what was saved; the composer keeps editing it. The attachments are read
     * back so that the new ones carry their database ids from now on. The save suspends, so an import or an
     * edit can land meanwhile: the read-back only gives ids to the saved files and keeps everything else as it
     * is now, and what came in meanwhile stays unsaved.
     */
    suspend fun saveDraft(): Long = keepDraft().id

    /**
     * The outcome of a draft save: the id, and whether the edits went into a new draft (see [keepDraft]). With
     * [blocked] nothing was saved: a queued post stays in the queue only as something that can be sent.
     */
    data class DraftSave(val id: Long, val keptAsNew: Boolean, val blocked: List<Blocker> = emptyList())

    /**
     * [saveDraft] for Save draft and for keeping the draft on leave. When the post being edited started
     * sending or was deleted meanwhile (Q2), the composer lets go of it ([detach]) and saves the edits as a
     * new draft, so they are not lost. A queued post is not saved without a target: the screen shows why.
     */
    suspend fun keepDraft(): DraftSave {
        if (editingQueued) {
            // Q2, R11: editing a queued post re-runs the limit checks; it stays in the queue only as it can be
            // sent. A running import is not a limit: the files still copying stay unsaved.
            val blocked = blockersNow(importing = false).second
            if (blocked.isNotEmpty()) return DraftSave(draftId ?: 0L, keptAsNew = false, blocked = blocked)
        }
        var id = 0L
        if (saveOrDetach { id = writeDraft() }) return DraftSave(id, keptAsNew = false)
        return DraftSave(writeDraft(), keptAsNew = true)
    }

    private suspend fun writeDraft(): Long {
        val input = draftInput()
        val snapshot = content()
        val id = container.posts.saveDraft(input)
        draftId = id
        container.posts.get(id)?.let { attachments = withSavedIds(attachments, it.attachments) }
        saved = snapshot
        return id
    }

    /**
     * Lets go of a post that can no longer be changed: its first delivery started or it was deleted while
     * the composer had it open (Q2). The text and options stay as a new, unsaved post. The attachments saved
     * with the old post stay with it (they are being sent, or were deleted with it); those added since stay
     * here. An import still running belongs to this composer and lands here too.
     */
    private fun detach() {
        draftId = null
        editingQueued = false
        attachments = attachments.filter { it.id < 0 }
        saved = null
    }

    /**
     * Drops the changes since the last save: a saved draft comes back as saved, a new one starts empty. The
     * files of attachments added since the last save are deleted (R9); the saved ones stay with the draft.
     */
    suspend fun discardChanges() {
        deleteUnsavedFiles()
        val id = draftId
        if (id != null && container.posts.get(id) != null) {
            load(id)
        } else {
            startNew()
        }
    }

    /** Clear in the ⋯ menu: a new empty draft; files added since the last save are deleted, a saved draft stays. */
    suspend fun clearDraft() {
        deleteUnsavedFiles()
        startNew()
    }

    /** Starts a new empty draft with the defaults; a draft saved before stays in Drafts. */
    suspend fun startNew() {
        newDraft(accounts.filterNotNull().first(), settings.filterNotNull().first())
        saved = content()
    }

    /** The screen told the user about [missingFiles]. */
    fun missingFilesShown() {
        missingFiles = 0
    }

    override fun onCleared() {
        container.filesInUse.release(this)
    }

    private suspend fun deleteUnsavedFiles() {
        val paths = attachments.filter { it.id < 0 }.map { it.path }
        if (paths.isNotEmpty()) withContext(Dispatchers.IO) { container.attachmentFiles.delete(paths) }
    }

    // Attachments (R9, R10, R11)

    /** The piece of the text the cursor is in, where new attachments go and whose attachments the strip shows (T14). */
    fun currentPiece(): Int {
        val source = text.text.toString()
        return pieceAt(source, text.selection.min).coerceAtMost(pieceCount(source) - 1)
    }

    /** The attachments of [piece], in order. */
    fun attachmentsOf(piece: Int): List<Attachment> {
        val pieces = pieceCount(text.text.toString())
        return attachments.filter { effectivePiece(it, pieces) == piece }
    }

    /**
     * Copies [uris] into app storage at once (R9) and adds them to the piece the cursor is in (T14). Returns
     * the number of files that could not be added.
     */
    suspend fun addAttachments(uris: List<Uri>): Int {
        if (uris.isEmpty()) return 0
        val piece = currentPiece()
        val generation = draftGeneration
        importing++
        try {
            val result = container.attachmentImporter.import(uris, piece)
            if (generation != draftGeneration) {
                // The draft the files were picked for was sent, cleared or discarded meanwhile.
                val paths = result.attachments.map { it.path }
                if (paths.isNotEmpty()) withContext(Dispatchers.IO) { container.attachmentFiles.delete(paths) }
                return result.failures.size
            }
            attachments = attachments + result.attachments.map { it.copy(id = nextNewId--) }
            return result.failures.size
        } finally {
            importing--
        }
    }

    /**
     * [addAttachments] on the model's own scope, so a rotation of the screen does not cancel the copy;
     * [onDone] gets the number of files that could not be added.
     */
    fun attach(uris: List<Uri>, onDone: (Int) -> Unit) {
        if (uris.isEmpty()) return
        viewModelScope.launch { onDone(addAttachments(uris)) }
    }

    /**
     * Removes the attachment [id]. The file of one added since the last save is deleted now; a saved one keeps
     * its file until the draft is saved without it, so discarding the change brings it back.
     */
    suspend fun removeAttachment(id: Long) {
        val removed = attachments.firstOrNull { it.id == id } ?: return
        attachments = attachments - removed
        if (removed.id < 0) withContext(Dispatchers.IO) { container.attachmentFiles.delete(listOf(removed.path)) }
    }

    fun setAlt(id: Long, alt: String) {
        attachments = attachments.map { if (it.id == id) it.copy(alt = alt) else it }
    }

    /** The Mastodon focus point, x and y from -1 to 1 with y up (networks.md N6). */
    fun setFocus(id: Long, x: Float, y: Float) {
        attachments = attachments.map { if (it.id == id) it.copy(focusX = x.coerceIn(-1f, 1f), focusY = y.coerceIn(-1f, 1f)) else it }
    }

    /**
     * Chooses [fix] for the conflicts of [kind] in [network] (A10). Removing the account takes every selected
     * account of the network out of the targets; any other fix is kept with the post.
     */
    fun chooseFix(network: Network, kind: ConflictKind, fix: FixKind) {
        if (fix == FixKind.REMOVE_ACCOUNT) {
            val ids = accounts.value.orEmpty().filter { it.network == network }.map { it.id }.toSet()
            targets = targets.filter { it !in ids }
            return
        }
        chosenFixes = chosenFixes + (network to (chosenFixes[network].orEmpty() + (kind to fix)))
    }

    /** Forgets the fix chosen for [kind] in [network]; the default of the settings, if any, applies again. */
    fun clearFix(network: Network, kind: ConflictKind) {
        val left = chosenFixes[network].orEmpty() - kind
        chosenFixes = if (left.isEmpty()) chosenFixes - network else chosenFixes + (network to left)
    }

    // Editing

    fun showSpoiler(shown: Boolean) {
        spoilerShown = shown
        if (!shown) spoiler.clearText()
    }

    fun chooseLanguage(code: String) {
        language = code
    }

    fun toggleTarget(id: Long) {
        // R40: an edit goes only to the accounts that hold the post.
        if (editing != null && id !in holders) return
        targets = if (id in targets) targets - id else targets + id
    }

    /** The options [account] gets for this post: its own entry, or its defaults (R17). */
    fun optionsFor(account: Account): AccountPostOptions {
        val options = accountOptions[account.id] ?: AccountPostOptions.from(account.defaults)
        // N28: a published post keeps the visibility it went out with, whatever the defaults say now.
        val published = editing?.items?.firstOrNull { it.accountId == account.id }?.mastodon?.visibility
        return if (published != null) options.copy(visibility = published) else options
    }

    fun setOptions(accountId: Long, options: AccountPostOptions) {
        accountOptions = accountOptions + (accountId to options)
    }

    fun changePoll(value: PollDraft?) {
        poll = value
    }

    /** A new poll with two empty options and the shortest offered duration the servers allow. */
    fun addPoll() {
        val limits = pollLimits()
        val duration = POLL_DURATIONS.firstOrNull { it in limits.minSeconds..limits.maxSeconds && it >= DEFAULT_POLL_SECONDS }
            ?: limits.minSeconds
        poll = PollDraft(options = listOf("", ""), expiresInSeconds = duration)
    }

    /** Replaces the text with [result] and its selection, as one edit. */
    fun apply(result: EditResult) {
        text.edit {
            replace(0, length, result.text)
            selection = TextRange(result.selectionStart, result.selectionEnd)
        }
    }

    fun selection(): TextRange = text.selection

    // Sending (R21, R27)

    /** The outcome of a send: blocked with the reasons, queued for this many accounts, or ignored as a repeat. */
    sealed interface SendResult {
        data class Blocked(val blockers: List<Blocker>) : SendResult
        data class Queued(val accounts: Int, val schedule: Schedule) : SendResult

        /** A queued post was edited and saved; it keeps its time (R25, Q2). */
        data object Saved : SendResult

        /** Another send of this composer was still running; nothing was done. */
        data object AlreadySending : SendResult

        /**
         * The post started sending or was deleted while it was edited, so it was not changed (Q2). The
         * composer let go of it: the edits stay on screen as a new, unsaved post.
         */
        data object NoLongerEditable : SendResult
    }

    /**
     * Sends the draft at [schedule], Send now when null: counts the text again for the selected accounts, and
     * when nothing blocks, saves the post as queued with its targets and options in one transaction (R27),
     * then wakes the sender and starts a new empty draft. A send that starts while another runs does nothing,
     * so a double tap cannot queue the same post twice.
     */
    suspend fun send(schedule: Schedule?): SendResult {
        if (sending) return SendResult.AlreadySending
        sending = true
        try {
            val (input, blocked) = blockersNow(importing = importing > 0)
            if (blocked.isNotEmpty()) return SendResult.Blocked(blocked)
            if (editUnavailable) return SendResult.NoLongerEditable
            if (editing != null && !editingQueued) {
                // R41: the edit is saved locally first and goes through the queue with retries.
                if (!saveOrDetach { container.posts.saveAndQueue(draftInput(), Schedule.Now(Instant.now())) }) return SendResult.NoLongerEditable
                container.publishTrigger.publishNow()
                startNew()
                return SendResult.Saved
            }
            if (editingQueued && schedule == null) {
                // Q2: the limits were checked again above; saving keeps the post's time and queue place.
                if (!saveOrDetach { container.posts.saveDraft(draftInput()) }) return SendResult.NoLongerEditable
                startNew()
                return SendResult.Saved
            }
            val time = schedule ?: Schedule.Now(Instant.now())
            if (!saveOrDetach { container.posts.saveAndQueue(draftInput(), time) }) return SendResult.NoLongerEditable
            container.publishTrigger.publishNow()
            startNew()
            return SendResult.Queued(input.targets.size, time)
        } finally {
            sending = false
        }
    }

    /** Counts the text again for the selected accounts and returns what blocks sending it now. */
    private suspend fun blockersNow(importing: Boolean): Pair<RenderInput, List<Blocker>> {
        val input = renderInput().copy(targets = liveTargets())
        val list = accounts.filterNotNull().first()
        val settings = settings.filterNotNull().first()
        val fresh = render(input, list, settings)
        renders = input to fresh
        val media = mediaOf(input, list, settings)
        return input to blockers(input.targets, input.text, fresh, poll, pollLimits(), attachments, media, settings.requireAltText, importing)
    }

    /** Runs [save]; false, with the composer let go of the post ([detach]), when the post can no longer be changed. */
    private suspend fun saveOrDetach(save: suspend () -> Unit): Boolean {
        try {
            save()
            return true
        } catch (_: PostNotEditable) {
            detach()
        } catch (_: PostNotFound) {
            detach()
        }
        return false
    }

    companion object {
        const val RENDER_DEBOUNCE_MS = 150L

        /** The key of the [ComposerSnapshot] in the saved state. */
        const val KEY_SNAPSHOT = "composer.snapshot"

        /**
         * [current] with the database ids of [savedBack], matched by file path, which is unique per copy. Only the
         * ids change: an attachment added, removed or edited while the save ran stays as the composer has it.
         */
        fun withSavedIds(current: List<Attachment>, savedBack: List<Attachment>): List<Attachment> {
            val ids = savedBack.associate { it.path to it.id }
            return current.map { attachment ->
                if (attachment.id >= 0) attachment else ids[attachment.path]?.let { attachment.copy(id = it) } ?: attachment
            }
        }
        private const val DEFAULT_POLL_SECONDS = 86_400L
    }
}
