package org.evsyukov.post.ui.composer

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.R
import org.evsyukov.post.core.markdown.MANUAL_BREAK
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The formatting buttons (formatting.md F5) and manual breaks (threads.md T13) in the composer. */
@RunWith(AndroidJUnit4::class)
class ComposerEditingTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var h: ComposerHarness

    @Before
    fun setUp() {
        h = ComposerHarness(compose)
        h.show()
    }

    @After
    fun tearDown() = h.close()

    @Test
    fun eachWrappingButtonWrapsTheSelection() {
        val markers = listOf(ComposerTags.BOLD to "**", ComposerTags.ITALIC to "*", ComposerTags.STRIKE to "~~", ComposerTags.CODE to "`")
        h.type("one")
        for ((tag, marker) in markers) {
            val text = h.text()
            h.select(0, text.length)
            h.click(tag)
            assertThat(h.text()).isEqualTo(marker + text + marker)
        }
        assertThat(h.text()).isEqualTo("`~~***one***~~`")
    }

    @Test
    fun aWrappingButtonWithoutSelectionInsertsAPairWithTheCursorInside() {
        h.type("say ")
        h.click(ComposerTags.BOLD)
        assertThat(h.text()).isEqualTo("say ****")

        h.type("hi")

        assertThat(h.text()).isEqualTo("say **hi**")
    }

    @Test
    fun quoteAndListToggleThePrefixOfTheSelectedLines() {
        h.type("first\nsecond\nthird")
        h.select(2, 9) // from "first" into "second"

        h.click(ComposerTags.QUOTE)
        assertThat(h.text()).isEqualTo("> first\n> second\nthird")

        h.select(0, h.text().length)
        h.click(ComposerTags.QUOTE)
        assertThat(h.text()).isEqualTo("> first\n> second\n> third")

        h.click(ComposerTags.QUOTE)
        assertThat(h.text()).isEqualTo("first\nsecond\nthird")

        h.select(8, 8) // the cursor on "second"
        h.click(ComposerTags.LIST)
        assertThat(h.text()).isEqualTo("first\n- second\nthird")
        h.click(ComposerTags.LIST)
        assertThat(h.text()).isEqualTo("first\nsecond\nthird")
    }

    @Test
    fun theLinkButtonAsksForTheAddressAndWrapsTheSelection() {
        h.type("read this")
        h.select(5, 9)

        h.click(ComposerTags.LINK)
        h.node(ComposerTags.LINK_URL).performTextInput("https://example.com/a")
        h.click(ComposerTags.LINK_INSERT)

        assertThat(h.text()).isEqualTo("read [this](https://example.com/a)")
    }

    @Test
    fun theLinkDialogOffersAUrlFromTheClipboardAndPutsTheCursorInTheEmptyText() {
        val clipboard = h.context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("url", "https://clip.example/x"))
        h.type("see ")

        h.click(ComposerTags.LINK)
        h.click(ComposerTags.LINK_CLIPBOARD)
        h.click(ComposerTags.LINK_INSERT)
        assertThat(h.text()).isEqualTo("see [](https://clip.example/x)")

        h.type("it")
        assertThat(h.text()).isEqualTo("see [it](https://clip.example/x)")
    }

    @Test
    fun theLinkDialogOffersNothingWhenTheClipboardHoldsNoUrl() {
        val clipboard = h.context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("text", "just words"))

        h.click(ComposerTags.LINK)

        assertThat(compose.onAllNodes(androidx.compose.ui.test.hasTestTag(ComposerTags.LINK_CLIPBOARD)).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun breakHereInsertsTheMarkerOnItsOwnLineAndTheRemoveButtonTakesItOut() {
        h.type("first part")
        h.select(5, 5)

        h.menu(ComposerTags.MENU_BREAK)

        assertThat(h.text()).isEqualTo("first\n$MANUAL_BREAK\n part")
        h.node(ComposerTags.breakRemove(0)).assertIsDisplayed()
        compose.onNodeWithContentDescription(h.string(R.string.composer_remove_break)).assertIsDisplayed()

        h.click(ComposerTags.breakRemove(0))

        assertThat(h.text()).isEqualTo("first\n part")
        assertThat(compose.onAllNodes(androidx.compose.ui.test.hasTestTag(ComposerTags.breakRemove(0))).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun breakHereAtTheEndStartsANewLineForTheNextPiece() {
        h.type("first")
        h.menu(ComposerTags.MENU_BREAK)
        h.type("second")

        assertThat(h.text()).isEqualTo("first\n$MANUAL_BREAK\nsecond")
    }

    @Test
    fun theContentWarningButtonShowsAndHidesItsField() {
        h.click(ComposerTags.CW)
        h.node(ComposerTags.SPOILER).assertIsDisplayed()

        h.click(ComposerTags.CW)
        assertThat(compose.onAllNodes(androidx.compose.ui.test.hasTestTag(ComposerTags.SPOILER)).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun everyToolbarIconButtonHasAScreenReaderLabel() {
        val labels = listOf(
            R.string.composer_attach, R.string.composer_bold, R.string.composer_italic, R.string.composer_strikethrough,
            R.string.composer_code, R.string.composer_quote, R.string.composer_list, R.string.composer_link,
            R.string.content_warning, R.string.composer_more,
        )
        for (label in labels) compose.onNodeWithContentDescription(h.string(label)).assertExists()
    }
}
