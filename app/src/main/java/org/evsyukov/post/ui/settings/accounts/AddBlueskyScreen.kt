package org.evsyukov.post.ui.settings.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import java.util.UUID
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.evsyukov.post.R
import org.evsyukov.post.accounts.BlueskySignIn
import org.evsyukov.post.accounts.SignInException
import org.evsyukov.post.accounts.SignInProblem
import org.evsyukov.post.core.bluesky.IdentityResolver
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.common.BackTopBar

object AddBlueskyTags {
    const val SCREEN = "add-bluesky"
    const val HANDLE = "add-bluesky-handle"
    const val PASSWORD = "add-bluesky-password"
    const val PASSWORD_WARNING = "add-bluesky-password-warning"
    const val TOGGLE_PASSWORD = "add-bluesky-toggle-password"
    const val CODE = "add-bluesky-code"
    const val SIGN_IN = "add-bluesky-sign-in"
    const val LINK = "add-bluesky-link"
    const val ERROR = "add-bluesky-error"
}

/** Where a Bluesky sign-in stands on its screen. */
sealed interface BlueskyFormState {
    data object Idle : BlueskyFormState

    data object Working : BlueskyFormState

    /** The PDS asked for the code from the e-mail (AuthFactorTokenRequired). */
    data object NeedsCode : BlueskyFormState

    data class Failed(val problem: SignInProblem, val codeAsked: Boolean) : BlueskyFormState
}

/** The page where Bluesky users create app passwords. */
const val APP_PASSWORDS_URL = "https://bsky.app/settings/app-passwords"

private val APP_PASSWORD = Regex("^[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{4}$")

/** Whether [text] has the shape Bluesky gives app passwords, `xxxx-xxxx-xxxx-xxxx`. */
fun looksLikeAppPassword(text: String): Boolean = APP_PASSWORD.matches(text.trim())

/** Whether [text] is a handle (with or without `@`) or a DID. */
fun isHandleOrDid(text: String): Boolean {
    val normalized = IdentityResolver.normalizeHandle(text)
    return IdentityResolver.isValidHandle(normalized) || (normalized.startsWith("did:") && IdentityResolver.isValidDid(normalized))
}

/**
 * Add Bluesky account (screens.md): the handle and an app password (networks.md N13–N15). With
 * [accountId] it signs that account in again; the same DID keeps its row.
 */
@Composable
fun AddBlueskyScreen(accountId: Long?, initialHandle: String, onBack: () -> Unit, onDone: () -> Unit) {
    val container = LocalAppContainer.current
    val activity = LocalActivity.current
    var handle by rememberSaveable { mutableStateOf(initialHandle) }
    var code by rememberSaveable { mutableStateOf("") }
    // One form per opening of the screen; a rotation finds the same one again.
    val visit = rememberSaveable { UUID.randomUUID().toString() }
    val form: BlueskyFormModel = viewModel(key = "add-bluesky-$visit") { BlueskyFormModel() }
    DisposableEffect(form) {
        // Leaving the screen, not rotating it, forgets the password.
        onDispose { if (activity?.isChangingConfigurations != true) form.forget() }
    }
    LaunchedEffect(form.done) {
        if (form.done) {
            form.done = false
            onDone()
        }
    }
    AddBlueskyContent(
        handle = handle,
        onHandleChange = { handle = it },
        password = form.password,
        onPasswordChange = { form.password = it },
        code = code,
        onCodeChange = { code = it },
        state = form.state,
        reSignIn = accountId != null,
        onSignIn = { form.signIn(container.blueskySignIn, handle, code) },
        onBack = onBack,
    )
}

/**
 * The part of the Bluesky form a rotation must not lose: the password, which stays in memory and never reaches
 * the saved-state bundle, and the sign-in in progress, which a rotation does not cancel.
 */
class BlueskyFormModel : ViewModel() {
    var password by mutableStateOf("")
    var state by mutableStateOf<BlueskyFormState>(BlueskyFormState.Idle)
        private set

    /** The sign-in succeeded; the screen closes and sets this back. */
    var done by mutableStateOf(false)

    private var job: Job? = null

    fun signIn(signIn: BlueskySignIn, handle: String, code: String) {
        val codeAsked = state == BlueskyFormState.NeedsCode || (state as? BlueskyFormState.Failed)?.codeAsked == true
        val password = password
        state = BlueskyFormState.Working
        job = viewModelScope.launch {
            state = try {
                when (signIn.signIn(handle, password, code.takeIf { codeAsked })) {
                    is BlueskySignIn.Result.Done -> {
                        this@BlueskyFormModel.password = ""
                        done = true
                        BlueskyFormState.Idle
                    }
                    BlueskySignIn.Result.NeedsCode -> BlueskyFormState.NeedsCode
                }
            } catch (e: SignInException) {
                BlueskyFormState.Failed(e.problem, codeAsked)
            }
        }
    }

    /** The user left the screen: the password goes from memory and nothing reopens the screen later. */
    fun forget() {
        job?.cancel()
        password = ""
        state = BlueskyFormState.Idle
        done = false
    }
}

/** The form without the sign-in machinery. */
@Composable
fun AddBlueskyContent(
    handle: String,
    onHandleChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    code: String,
    onCodeChange: (String) -> Unit,
    state: BlueskyFormState,
    reSignIn: Boolean,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val busy = state == BlueskyFormState.Working
    val codeAsked = state == BlueskyFormState.NeedsCode || (state as? BlueskyFormState.Failed)?.codeAsked == true
    val handleValid = isHandleOrDid(handle)
    val showInvalidHandle = handle.isNotBlank() && !handleValid
    val canSignIn = !busy && handleValid && password.isNotBlank() && (!codeAsked || code.isNotBlank())
    Scaffold(
        modifier = Modifier.testTag(AddBlueskyTags.SCREEN),
        topBar = {
            BackTopBar(title = stringResource(if (reSignIn) R.string.sign_in_again_title else R.string.add_bluesky_title), onBack = onBack)
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = handle,
                onValueChange = onHandleChange,
                label = { Text(stringResource(R.string.add_bluesky_handle)) },
                placeholder = { Text(stringResource(R.string.add_bluesky_handle_hint)) },
                singleLine = true,
                enabled = !busy,
                isError = showInvalidHandle,
                supportingText = if (showInvalidHandle) {
                    { Text(stringResource(R.string.problem_invalid_handle)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag(AddBlueskyTags.HANDLE),
            )
            val warn = password.isNotEmpty() && !looksLikeAppPassword(password)
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.add_bluesky_password)) },
                singleLine = true,
                enabled = !busy,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }, modifier = Modifier.testTag(AddBlueskyTags.TOGGLE_PASSWORD)) {
                        Icon(
                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = stringResource(if (showPassword) R.string.hide_password else R.string.show_password),
                        )
                    }
                },
                // A warning only: the sign-in still goes ahead with what the user typed.
                supportingText = if (warn) {
                    { Text(stringResource(R.string.add_bluesky_password_warning), modifier = Modifier.testTag(AddBlueskyTags.PASSWORD_WARNING)) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth().testTag(AddBlueskyTags.PASSWORD),
            )
            Text(stringResource(R.string.add_bluesky_password_hint), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { uriHandler.openUri(APP_PASSWORDS_URL) }, modifier = Modifier.testTag(AddBlueskyTags.LINK)) {
                Text(stringResource(R.string.add_bluesky_password_link))
            }
            if (codeAsked) {
                Text(stringResource(R.string.add_bluesky_code_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = code,
                    onValueChange = onCodeChange,
                    label = { Text(stringResource(R.string.add_bluesky_code)) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().testTag(AddBlueskyTags.CODE),
                )
            }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.signing_in), style = MaterialTheme.typography.bodyMedium)
            }
            if (state is BlueskyFormState.Failed) {
                Text(stringResource(state.problem.message()), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(AddBlueskyTags.ERROR))
            }
            Button(onClick = onSignIn, enabled = canSignIn, modifier = Modifier.testTag(AddBlueskyTags.SIGN_IN)) {
                Text(stringResource(if (state is BlueskyFormState.Failed) R.string.try_again else R.string.add_bluesky_sign_in))
            }
        }
    }
}
