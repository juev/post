package org.evsyukov.post.ui.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.evsyukov.post.R
import org.evsyukov.post.core.markdown.HighlightSpan
import org.evsyukov.post.core.markdown.highlight
import kotlin.math.roundToInt

/** The colors of the Markdown highlighting (formatting.md F4), taken from the theme. */
data class HighlightColors(
    val syntax: Color,
    val link: Color,
    val quote: Color,
    val marker: Color,
    val codeBackground: Color,
)

/**
 * The style of each kind of highlight span. Markup characters are dimmed but stay visible and editable
 * (F4); the manual break character is transparent, since the editor draws a rule over its line (T13).
 */
fun HighlightSpan.Kind.style(colors: HighlightColors): SpanStyle = when (this) {
    HighlightSpan.Kind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
    HighlightSpan.Kind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
    HighlightSpan.Kind.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    HighlightSpan.Kind.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBackground)
    HighlightSpan.Kind.CODE_BLOCK -> SpanStyle(fontFamily = FontFamily.Monospace)
    HighlightSpan.Kind.QUOTE -> SpanStyle(color = colors.quote, fontStyle = FontStyle.Italic)
    HighlightSpan.Kind.LIST_MARKER -> SpanStyle(color = colors.marker, fontWeight = FontWeight.Bold)
    HighlightSpan.Kind.LINK_TEXT -> SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)
    HighlightSpan.Kind.LINK_URL -> SpanStyle(color = colors.link.copy(alpha = 0.7f))
    HighlightSpan.Kind.SYNTAX -> SpanStyle(color = colors.syntax)
    HighlightSpan.Kind.BREAK_MARKER -> SpanStyle(color = Color.Transparent)
}

/** Markup characters and the break marker go last, so their color wins over the color of what holds them. */
private val ORDER = listOf(
    HighlightSpan.Kind.QUOTE, HighlightSpan.Kind.CODE_BLOCK, HighlightSpan.Kind.CODE, HighlightSpan.Kind.BOLD,
    HighlightSpan.Kind.ITALIC, HighlightSpan.Kind.STRIKE, HighlightSpan.Kind.LINK_TEXT, HighlightSpan.Kind.LINK_URL,
    HighlightSpan.Kind.LIST_MARKER, HighlightSpan.Kind.SYNTAX, HighlightSpan.Kind.BREAK_MARKER,
)

/** The highlight spans of [text] in the order their styles are applied. */
fun orderedHighlight(text: String): List<HighlightSpan> = highlight(text).sortedBy { ORDER.indexOf(it.kind) }

/**
 * Styles the text as it is shown, never as it is stored: the draft keeps the Markdown as typed (F4).
 * The spans of the last text are kept, so drawing the same text again does not parse it again.
 */
class MarkdownHighlight(private val colors: HighlightColors) : OutputTransformation {
    private var lastText: String? = null
    private var lastSpans: List<HighlightSpan> = emptyList()

    override fun TextFieldBuffer.transformOutput() {
        val text = asCharSequence().toString()
        if (text != lastText) {
            lastSpans = orderedHighlight(text)
            lastText = text
        }
        for (span in lastSpans) {
            if (span.end <= length) addStyle(span.kind.style(colors), span.start, span.end)
        }
    }

    override fun equals(other: Any?): Boolean = other is MarkdownHighlight && other.colors == colors

    override fun hashCode(): Int = colors.hashCode()
}

/** The ranges `[start, end)` of the manual break lines of [text] (T13), as the highlighting finds them. */
fun breakLines(text: String): List<TextRange> =
    highlight(text).filter { it.kind == HighlightSpan.Kind.BREAK_MARKER }.map { TextRange(it.start, it.end) }

/**
 * The draft editor: one field for the whole text with live Markdown highlighting (F4). A manual break line
 * is drawn as a thin rule across the field with a remove button at its right edge (T13).
 */
@Composable
fun MarkdownEditor(
    state: TextFieldState,
    onRemoveBreak: (TextRange) -> Unit,
    focusRequester: FocusRequester = remember { FocusRequester() },
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = HighlightColors(
        syntax = scheme.onSurface.copy(alpha = 0.45f),
        link = scheme.primary,
        quote = scheme.onSurfaceVariant,
        marker = scheme.primary,
        codeBackground = scheme.surfaceVariant,
    )
    val transformation = remember(colors) { MarkdownHighlight(colors) }
    var layout by remember { mutableStateOf<(() -> TextLayoutResult?)?>(null) }
    val text = state.text.toString()
    val breaks = remember(text) { breakLines(text) }
    val ruleColor = scheme.outline
    val density = LocalDensity.current
    val buttonSize = 40.dp
    Box(modifier) {
        BasicTextField(
            state = state,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            outputTransformation = transformation,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            onTextLayout = { result -> layout = result },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .testTag(ComposerTags.EDITOR)
                .drawBehind {
                    val result = layout?.invoke() ?: return@drawBehind
                    val reserve = with(density) { buttonSize.toPx() }
                    for (range in breaks) {
                        if (range.start > result.layoutInput.text.length) continue
                        val line = result.getLineForOffset(range.start)
                        val y = (result.getLineTop(line) + result.getLineBottom(line)) / 2f
                        drawLine(ruleColor, Offset(0f, y), Offset(size.width - reserve, y), strokeWidth = 1.dp.toPx())
                    }
                },
            decorator = { inner ->
                Box {
                    if (state.text.isEmpty()) {
                        Text(
                            stringResource(R.string.composer_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    inner()
                }
            },
        )
        breaks.forEachIndexed { index, range ->
            IconButton(
                onClick = { onRemoveBreak(range) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // Placed from the text layout of the same frame, so the button follows its line.
                    .offset {
                        val result = layout?.invoke()
                        val y = if (result != null && range.start <= result.layoutInput.text.length) {
                            val line = result.getLineForOffset(range.start)
                            ((result.getLineTop(line) + result.getLineBottom(line)) / 2f - buttonSize.toPx() / 2f).roundToInt()
                        } else {
                            0
                        }
                        IntOffset(0, y)
                    }
                    .size(buttonSize)
                    .padding(4.dp)
                    .testTag(ComposerTags.breakRemove(index)),
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.composer_remove_break))
            }
        }
    }
}
