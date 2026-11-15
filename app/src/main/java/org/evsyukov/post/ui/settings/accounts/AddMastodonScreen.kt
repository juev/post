package org.evsyukov.post.ui.settings.accounts

import androidx.core.net.toUri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.evsyukov.post.R
import org.evsyukov.post.accounts.InstanceAddress
import org.evsyukov.post.accounts.SignInProblem
import org.evsyukov.post.oauth.MastodonFlowState
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.LocalBrowserLauncher
import org.evsyukov.post.ui.common.BackTopBar

object AddMastodonTags {
    const val SCREEN = "add-mastodon"
    const val ADDRESS = "add-mastodon-address"
    const val CONTINUE = "add-mastodon-continue"
    const val CANCEL = "add-mastodon-cancel"
    const val ERROR = "add-mastodon-error"
    const val PROGRESS = "add-mastodon-progress"
}

/** The sentence shown for [this] problem. */
@StringRes
fun SignInProblem.message(): Int = when (this) {
    SignInProblem.INVALID_ADDRESS -> R.string.problem_invalid_address
    SignInProblem.NOT_MASTODON -> R.string.problem_not_mastodon
    SignInProblem.NETWORK -> R.string.problem_network
    SignInProblem.SERVER -> R.string.problem_server
    SignInProblem.UNKNOWN_STATE -> R.string.problem_unknown_state
    SignInProblem.DENIED -> R.string.problem_denied
    SignInProblem.CLIENT_REJECTED -> R.string.problem_client_rejected
    SignInProblem.NO_BROWSER -> R.string.problem_no_browser
    SignInProblem.INVALID_HANDLE -> R.string.problem_invalid_handle
    SignInProblem.HANDLE_NOT_FOUND -> R.string.problem_handle_not_found
    SignInProblem.HANDLE_MISMATCH -> R.string.problem_handle_mismatch
    SignInProblem.NO_PDS -> R.string.problem_no_pds
    SignInProblem.WRONG_PASSWORD -> R.string.problem_wrong_password
    SignInProblem.WRONG_CODE -> R.string.problem_wrong_code
    SignInProblem.ACCOUNT_INACTIVE -> R.string.problem_account_inactive
    SignInProblem.ACCOUNT_TAKEN_DOWN -> R.string.problem_account_taken_down
    SignInProblem.RATE_LIMITED -> R.string.problem_rate_limited
}

/**
 * Add Mastodon account (screens.md): the instance, then sign-in in the browser (networks.md N4–N5).
 * With [accountId] it signs that account in again.
 */
@Composable
fun AddMastodonScreen(accountId: Long?, initialAddress: String, onBack: () -> Unit, onDone: () -> Unit) {
    val container = LocalAppContainer.current
    val flow = container.mastodonSignInFlow
    val launcher = LocalBrowserLauncher.current
    val state by flow.state.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf(initialAddress) }
    // A new visit starts clean: the outcome of an earlier attempt is not shown again.
    rememberSaveable {
        flow.reset()
        true
    }
    LaunchedEffect(state) {
        when (val current = state) {
            is MastodonFlowState.OpenBrowser ->
                if (launcher.open(current.url.toString().toUri())) flow.browserOpened() else flow.browserMissing()
            is MastodonFlowState.Done -> {
                flow.reset()
                onDone()
            }
            else -> Unit
        }
    }
    AddMastodonContent(
        address = address,
        onAddressChange = { address = it },
        addressValid = InstanceAddress.parse(address, container.serverPolicy) != null,
        state = state,
        reSignIn = accountId != null,
        onContinue = { flow.start(address, accountId) },
        onCancel = { flow.cancel() },
        onBack = {
            flow.cancel()
            onBack()
        },
    )
}

/** The form without the sign-in machinery. */
@Composable
fun AddMastodonContent(
    address: String,
    onAddressChange: (String) -> Unit,
    addressValid: Boolean,
    state: MastodonFlowState,
    reSignIn: Boolean,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    val busy = state is MastodonFlowState.Working || state is MastodonFlowState.OpenBrowser || state is MastodonFlowState.InBrowser
    val showInvalid = address.isNotBlank() && !addressValid
    Scaffold(
        modifier = Modifier.testTag(AddMastodonTags.SCREEN),
        topBar = {
            BackTopBar(title = stringResource(if (reSignIn) R.string.sign_in_again_title else R.string.add_mastodon_title), onBack = onBack)
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.add_mastodon_explain), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = address,
                onValueChange = onAddressChange,
                label = { Text(stringResource(R.string.add_mastodon_instance)) },
                placeholder = { Text(stringResource(R.string.add_mastodon_instance_hint)) },
                singleLine = true,
                enabled = !busy,
                isError = showInvalid,
                supportingText = if (showInvalid) {
                    { Text(stringResource(R.string.problem_invalid_address)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag(AddMastodonTags.ADDRESS),
            )
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag(AddMastodonTags.PROGRESS))
                Text(
                    stringResource(if (state is MastodonFlowState.InBrowser) R.string.add_mastodon_in_browser else R.string.signing_in),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state is MastodonFlowState.Failed) {
                Text(
                    stringResource(state.problem.message()),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(AddMastodonTags.ERROR),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.testTag(AddMastodonTags.CANCEL)) {
                        Text(stringResource(R.string.cancel))
                    }
                } else {
                    Button(onClick = onContinue, enabled = addressValid, modifier = Modifier.testTag(AddMastodonTags.CONTINUE)) {
                        Text(stringResource(if (state is MastodonFlowState.Failed) R.string.try_again else R.string.add_mastodon_continue))
                    }
                }
            }
        }
    }
}
