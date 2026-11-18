package org.evsyukov.post.ui.composer

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownEditorTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theEditorRequestsSentenceCapitalizationAndAllowsAutocorrect() {
        val info = EditorInfo()
        compose.setContent {
            InterceptPlatformTextInput(interceptor = { request, _ ->
                request.createInputConnection(info).closeConnection()
                awaitCancellation()
            }) {
                MarkdownEditor(state = rememberTextFieldState(), onRemoveBreak = {})
            }
        }

        compose.onNodeWithTag(ComposerTags.EDITOR).performClick()

        compose.runOnIdle {
            assertWithMessage("autocorrect")
                .that(info.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT != 0).isTrue()
            assertWithMessage("sentence capitalization")
                .that(info.inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0).isTrue()
        }
    }
}
