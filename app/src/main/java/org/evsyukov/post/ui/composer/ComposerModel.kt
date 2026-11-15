package org.evsyukov.post.ui.composer

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
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
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.posts.AccountPostOptions
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.PollDraft
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
)

/**
 * The state of the composer: the draft being edited, its targets and options, and the counters of the
 * selected accounts (screens.md, Composer). It outlives configuration changes; the text fields are
 * [TextFieldState]s the editor writes into directly.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ComposerModel(private val container: AppContainer, initialDraftId: Long?) : ViewModel() {
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

    /** The post being edited, or null for a new one that was never saved. */
    var draftId by mutableStateOf(initialDraftId)
        private set

    /** False until the draft or the defaults of a new one are in place. */
    var loaded by mutableStateOf(false)
        private set

    /** The counters of the current text, or null before the first render. */
    var renders by mutableStateOf<Pair<RenderInput, List<ProfileRender>>?>(null)
        private set

    private var saved: ComposerContent? = null

    val accounts: StateFlow<List<Account>?> = container.accounts.observe().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val settings: StateFlow<Settings?> = container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Posts waiting in the queue, for the badge on Posts. */
    val queueCount: StateFlow<Int> = container.posts.observeQueue().map { it.size }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    init {
        viewModelScope.launch { load(initialDraftId) }
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
    )

    private suspend fun render(input: RenderInput, accounts: List<Account>, settings: Settings): List<ProfileRender> =
        withContext(Dispatchers.Default) { renderTargets(input, accounts, settings, container.textSegmenter) }

    /** The selected accounts in the order of the targets. */
    fun selectedAccounts(): List<Account> {
        val byId = accounts.value.orEmpty().associateBy { it.id }
        return targets.mapNotNull { byId[it] }
    }

    fun pollLimits(): PollLimits = PollLimits.of(selectedAccounts())

    /** What keeps Send disabled for the counters shown now. */
    fun currentBlockers(): List<Blocker> =
        blockers(liveTargets(), text.text.toString(), renders?.second.orEmpty(), poll, pollLimits())

    /** The targets that still exist: an account removed while selected is dropped. */
    private fun liveTargets(): List<Long> {
        val existing = accounts.value?.map { it.id }?.toSet() ?: return targets
        return targets.filter { it in existing }
    }

    // Loading and drafts (R15, R16, R17)

    private suspend fun load(id: Long?) {
        val accounts = accounts.filterNotNull().first()
        val settings = settings.filterNotNull().first()
        val post = id?.let { container.posts.get(it) }
        if (post == null) {
            newDraft(accounts, settings)
        } else {
            draftId = post.id
            text.setTextAndPlaceCursorAtEnd(post.text)
            spoiler.setTextAndPlaceCursorAtEnd(post.options.spoilerText)
            spoilerShown = post.options.spoilerText.isNotEmpty()
            language = post.options.language ?: defaultLanguage(accounts.filter { it.id in post.targets })
            targets = post.targets
            accountOptions = post.options.accounts
            poll = post.options.poll
        }
        saved = content()
        loaded = true
    }

    /** An empty draft with the default targets (R16) and the defaults of the first of them (R17). */
    private fun newDraft(accounts: List<Account>, settings: Settings) {
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
    )

    /**
     * Whether leaving would lose something (R15): the content differs from the last save, and there is
     * something worth keeping. Choosing other targets for an empty new draft is not worth a question.
     */
    fun hasUnsavedChanges(): Boolean {
        if (!loaded) return false
        val now = content()
        if (now == saved) return false
        return draftId != null || now.text.isNotBlank() || !now.spoilerText.isNullOrBlank() || now.poll != null
    }

    private fun options() = PostOptions(
        language = language,
        spoilerText = if (spoilerShown) spoiler.text.toString() else "",
        accounts = accountOptions.filterKeys { it in targets },
        poll = poll,
    )

    private fun draftInput() = DraftInput(id = draftId, text = text.text.toString(), options = options(), targets = liveTargets())

    /** Saves the draft and remembers it as saved; the composer keeps editing it. */
    suspend fun saveDraft(): Long {
        val content = content()
        val id = container.posts.saveDraft(draftInput())
        draftId = id
        saved = content
        return id
    }

    /** Drops the changes since the last save: a saved draft comes back as saved, a new one starts empty. */
    suspend fun discardChanges() {
        val id = draftId
        if (id != null && container.posts.get(id) != null) {
            load(id)
        } else {
            startNew()
        }
    }

    /** Starts a new empty draft with the defaults; a draft saved before stays in Drafts. */
    suspend fun startNew() {
        newDraft(accounts.filterNotNull().first(), settings.filterNotNull().first())
        saved = content()
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
        targets = if (id in targets) targets - id else targets + id
    }

    /** The options [account] gets for this post: its own entry, or its defaults (R17). */
    fun optionsFor(account: Account): AccountPostOptions = accountOptions[account.id] ?: AccountPostOptions.from(account.defaults)

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

    /** The outcome of a send: blocked with the reasons, or queued for this many accounts. */
    sealed interface SendResult {
        data class Blocked(val blockers: List<Blocker>) : SendResult
        data class Queued(val accounts: Int, val schedule: Schedule) : SendResult
    }

    /**
     * Sends the draft at [schedule], Send now when null: counts the text again for the selected accounts, and
     * when nothing blocks, saves the post as queued with its targets and options in one transaction (R27),
     * then wakes the sender and starts a new empty draft.
     */
    suspend fun send(schedule: Schedule?): SendResult {
        val input = renderInput().copy(targets = liveTargets())
        val list = accounts.filterNotNull().first()
        val settings = settings.filterNotNull().first()
        val fresh = render(input, list, settings)
        renders = input to fresh
        val blocked = blockers(input.targets, input.text, fresh, poll, pollLimits())
        if (blocked.isNotEmpty()) return SendResult.Blocked(blocked)
        val time = schedule ?: Schedule.Now(Instant.now())
        container.posts.saveAndQueue(draftInput(), time)
        container.publishTrigger.publishNow()
        startNew()
        return SendResult.Queued(input.targets.size, time)
    }

    companion object {
        const val RENDER_DEBOUNCE_MS = 150L
        private const val DEFAULT_POLL_SECONDS = 86_400L
    }
}
