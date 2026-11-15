package org.evsyukov.post.oauth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import org.evsyukov.post.accounts.MastodonSignIn
import org.evsyukov.post.accounts.SignInException
import org.evsyukov.post.accounts.SignInProblem

/** Where a Mastodon sign-in started from the Add Mastodon account screen stands. */
sealed interface MastodonFlowState {
    data object Idle : MastodonFlowState

    /** Talking to the server. */
    data object Working : MastodonFlowState

    /** The screen opens [url] in the browser, then calls [MastodonSignInFlow.browserOpened]. */
    data class OpenBrowser(val url: HttpUrl) : MastodonFlowState

    /** The sign-in page is open; the redirect or the Auth Tab result comes next. */
    data object InBrowser : MastodonFlowState

    data class Done(val accountId: Long) : MastodonFlowState

    data class Failed(val problem: SignInProblem) : MastodonFlowState
}

/**
 * Runs [MastodonSignIn] in the application scope, so that the redirect is handled whichever activity
 * receives it: MainActivity through Auth Tab, or [OAuthRedirectActivity] after a Custom Tab.
 */
class MastodonSignInFlow(private val signIn: MastodonSignIn, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow<MastodonFlowState>(MastodonFlowState.Idle)
    val state: StateFlow<MastodonFlowState> = mutableState.asStateFlow()
    private var job: Job? = null

    /** Starts the sign-in at [address]; [accountId] signs that account in again. */
    fun start(address: String, accountId: Long? = null) = run {
        mutableState.value = MastodonFlowState.OpenBrowser(signIn.start(address, accountId))
    }

    /** The browser shows the sign-in page. */
    fun browserOpened() {
        if (mutableState.value is MastodonFlowState.OpenBrowser) mutableState.value = MastodonFlowState.InBrowser
    }

    /** No browser could open the page. */
    fun browserMissing() {
        scope.launch { signIn.cancel() }
        mutableState.value = MastodonFlowState.Failed(SignInProblem.NO_BROWSER)
    }

    /**
     * The browser came back to `org.evsyukov.post://oauth/mastodon`. A redirect with a foreign `state` is
     * rejected and leaves a sign-in in progress alone.
     */
    fun onRedirect(redirect: String): Job = run {
        when (val completion = signIn.complete(redirect)) {
            is MastodonSignIn.Completion.Done -> mutableState.value = MastodonFlowState.Done(completion.accountId)
            is MastodonSignIn.Completion.Restart -> mutableState.value = MastodonFlowState.OpenBrowser(completion.url)
        }
    }

    /** The user closed the sign-in page or the screen. */
    fun cancel() {
        job?.cancel()
        scope.launch { signIn.cancel() }
        mutableState.value = MastodonFlowState.Idle
    }

    /** Back to [MastodonFlowState.Idle] after the screen showed the outcome. */
    fun reset() {
        val current = mutableState.value
        if (current is MastodonFlowState.Done || current is MastodonFlowState.Failed) mutableState.value = MastodonFlowState.Idle
    }

    private fun run(block: suspend () -> Unit): Job {
        val previous = mutableState.value
        mutableState.value = MastodonFlowState.Working
        return scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SignInException) {
                // A stray redirect must not end a sign-in that is still waiting for the real one.
                mutableState.value = if (e.problem == SignInProblem.UNKNOWN_STATE && previous == MastodonFlowState.InBrowser) {
                    previous
                } else {
                    MastodonFlowState.Failed(e.problem)
                }
            } catch (e: Exception) {
                mutableState.value = MastodonFlowState.Failed(SignInProblem.SERVER)
            }
        }.also { job = it }
    }
}
