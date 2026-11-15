package org.evsyukov.post.core.text

/**
 * A URL, mention or hashtag found in plain text. [start] and [end] are UTF-16 char offsets, [end]
 * exclusive, so `text.substring(start, end)` is the entity as typed.
 *
 * [value] is what the entity refers to:
 * - [Kind.URL]: the URL as typed;
 * - [Kind.MENTION]: the account without the `@` sign, such as `alice`, `alice@example.com` or
 *   `alice.bsky.social`;
 * - [Kind.HASHTAG]: the tag without the `#` sign.
 */
data class TextEntity(val start: Int, val end: Int, val kind: Kind, val value: String) {
    enum class Kind { URL, MENTION, HASHTAG }
}

/**
 * Finds links, mentions and hashtags in plain text: the atoms that thread splitting must keep whole
 * (threads.md, T5), that the Mastodon counter rewrites (networks.md, N3) and that become Bluesky facets
 * (networks.md, N17).
 *
 * Every function returns its entities sorted by [TextEntity.start], without overlaps.
 */
object TextEntities {

    /**
     * URLs that start with `http://` or `https://` (any letter case), as Mastodon counts them
     * (`extract_url_without_protocol: false` in `StatusLengthValidator`).
     *
     * - The URL must not start right after a letter, digit, mark, `_`, `-`, `.`, `/` or `@`, except a
     *   CJK, Thai, Lao, Khmer or Myanmar letter, which UTS #58 lets precede a scheme.
     * - The host is a dot-separated name of letters, digits, marks and hyphens, with at least one dot and
     *   no label that is empty or starts or ends with a hyphen. A port of 1–65535 may follow.
     * - The URL ends at whitespace, a control character, `<` or `>`.
     * - Trailing `.` `,` `;` `:` `!` `?` `'` `"` are not part of it. A trailing `)` is not part of it
     *   unless the URL has more `(` than `)` before it; the same holds for `]` and `}`.
     *
     * These rules approximate the UTS #58 extractor that Mastodon `main` uses (gem `uts58` 0.2.6) and
     * the twitter-text rules of Mastodon 4.x; the server stays the final judge of a link.
     */
    fun urls(text: String): List<TextEntity> {
        val result = ArrayList<TextEntity>()
        var i = 0
        while (i < text.length) {
            val schemeLength = schemeLengthAt(text, i)
            if (schemeLength == 0 || !mayPrecedeUrl(text, i)) {
                i++
                continue
            }
            val end = urlEnd(text, i, i + schemeLength)
            if (end < 0) {
                i += schemeLength
                continue
            }
            result += TextEntity(i, end, TextEntity.Kind.URL, text.substring(i, end))
            i = end
        }
        return result
    }

    /**
     * Mastodon mentions `@user` and `@user@domain`, by the regular expression of Mastodon `main`
     * (`Account::MENTION_RE` and `Extractor.extract_mentions_or_lists_with_indices`, commit
     * `296ca2874f3b7c02e79d2041b54f352a392f17ba`):
     * - `@` must not follow a word character, `=` or `/`;
     * - the user name is ASCII letters, digits and `_`, with single runs of `.` or `-` inside;
     * - the domain, when present, is word characters with runs of `.` or `-` between them, at most
     *   253 chars;
     * - a candidate followed by `@`, `＠`, a Latin accented letter or `://` is not a mention
     *   (`@noël@example.com` is not `@no`).
     */
    fun mastodonMentions(text: String): List<TextEntity> {
        if (text.indexOf('@') < 0) return emptyList()
        val result = ArrayList<TextEntity>()
        val matcher = MASTODON_MENTION.matcher(text)
        while (matcher.find()) {
            val after = text.substring(matcher.end())
            if (END_MENTION_MATCH.matcher(after).lookingAt()) continue
            val screenName = matcher.group(1)
            val domain = screenName.substringAfter('@', "")
            if (domain.length > MAX_DOMAIN_LENGTH) continue
            result += TextEntity(matcher.start(), matcher.end(), TextEntity.Kind.MENTION, screenName)
        }
        return result
    }

    /**
     * Bluesky mentions `@handle`, where the handle is a domain name such as `alice.bsky.social`
     * (atproto handle syntax): two or more labels of ASCII letters, digits and hyphens, each 1–63 chars,
     * not starting or ending with a hyphen, the last one starting with a letter, 253 chars at most.
     * Trailing dots and hyphens are not part of the handle, as in social-app, whose detector stops at a
     * word boundary. `@` must not follow a word character, `=`, `/` or `@`, and a handle followed by `@`
     * is a Mastodon address, not a Bluesky mention.
     *
     * Whether the handle exists is decided later, when it is resolved to a DID (N17).
     */
    fun blueskyMentions(text: String): List<TextEntity> {
        val result = ArrayList<TextEntity>()
        var i = text.indexOf('@')
        while (i >= 0) {
            var next = i + 1
            if (mayPrecedeMention(text, i)) {
                var end = i + 1
                while (end < text.length && isHandleChar(text[end])) end++
                val scanned = end
                while (end > i + 1 && (text[end - 1] == '.' || text[end - 1] == '-')) end--
                val handle = text.substring(i + 1, end)
                val followedByAt = end == scanned && end < text.length && (text[end] == '@' || text[end] == '＠')
                if (!followedByAt && isValidHandle(handle)) {
                    result += TextEntity(i, end, TextEntity.Kind.MENTION, handle)
                    next = end
                }
            }
            i = text.indexOf('@', next)
        }
        return result
    }

    /**
     * Hashtags `#tag` (also with the full-width `＃`). The tag is letters, marks, decimal digits and `_`,
     * and it must contain a letter or mark: `#2026` and `#_1` are not hashtags, as on Mastodon and
     * Bluesky. `#` must not follow a word character, so `C#` and `a#b` hold no hashtag.
     *
     * Bluesky's limit of 64 graphemes per tag (N17) is applied where facets are built.
     */
    fun hashtags(text: String): List<TextEntity> {
        val result = ArrayList<TextEntity>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if ((c == '#' || c == '＃') && !followsWordChar(text, i)) {
                var end = i + 1
                var hasLetter = false
                while (end < text.length) {
                    val cp = text.codePointAt(end)
                    if (!isHashtagCodePoint(cp)) break
                    if (Character.isLetter(cp) || isMark(cp)) hasLetter = true
                    end += Character.charCount(cp)
                }
                if (hasLetter && !text.startsWith("://", end)) {
                    result += TextEntity(i, end, TextEntity.Kind.HASHTAG, text.substring(i + 1, end))
                    i = end
                    continue
                }
            }
            i++
        }
        return result
    }

    /** The domain length limit of Mastodon's `Extractor::MAX_DOMAIN_LENGTH`. */
    private const val MAX_DOMAIN_LENGTH = 253

    // Account::MENTION_RE: USERNAME_RE = /[a-z0-9_]+([.-]+[a-z0-9_]+)*/i, HOST_LABEL_CHAR = [[:word:]ßς۽۾་〇].
    // Ruby's [[:word:]] is letters, marks, decimal digits and connector punctuation.
    private const val WORD = "\\p{L}\\p{M}\\p{Nd}\\p{Pc}"
    private const val HOST_LABEL_CHAR = "[${WORD}ßς۽۾་〇]"
    private val MASTODON_MENTION = Regex(
        "(?<![=/$WORD])@([a-z0-9_]+(?:[.-]+[a-z0-9_]+)*(?:@$HOST_LABEL_CHAR+(?:[.-]+$HOST_LABEL_CHAR+)*)?)",
        RegexOption.IGNORE_CASE,
    ).toPattern()

    // Extractor::END_MENTION_MATCH with the LATIN_ACCENTS ranges of twitter-text 3.1.0.
    private val END_MENTION_MATCH = Regex(
        "(?:[@＠]|[\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u00FF\\u0100-\\u024F\\u0253-\\u0254" +
            "\\u0256-\\u0257\\u0259\\u025B\\u0263\\u0268\\u026F\\u0272\\u0289\\u028B\\u02BB" +
            "\\u0300-\\u036F\\u1E00-\\u1EFF]+|://)",
    ).toPattern()

    private fun schemeLengthAt(text: String, i: Int): Int = when {
        text.regionMatches(i, "https://", 0, 8, ignoreCase = true) -> 8
        text.regionMatches(i, "http://", 0, 7, ignoreCase = true) -> 7
        else -> 0
    }

    private fun mayPrecedeUrl(text: String, i: Int): Boolean {
        if (i == 0) return true
        val cp = text.codePointBefore(i)
        if (cp == '_'.code || cp == '-'.code || cp == '.'.code || cp == '/'.code || cp == '@'.code) return false
        if (!Character.isLetterOrDigit(cp) && !isMark(cp)) return true
        return Character.isLetter(cp) && Character.UnicodeScript.of(cp) in SCRIPTS_BEFORE_SCHEME
    }

    private val SCRIPTS_BEFORE_SCHEME = setOf(
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.THAI,
        Character.UnicodeScript.LAO,
        Character.UnicodeScript.KHMER,
        Character.UnicodeScript.MYANMAR,
    )

    /** The end of the URL whose host starts at [hostStart], or -1 when the text there is not a URL. */
    private fun urlEnd(text: String, start: Int, hostStart: Int): Int {
        var end = hostStart
        while (end < text.length && !isUrlTerminator(text[end])) end++
        end = trimUrlEnd(text, hostStart, end)
        var hostEnd = hostStart
        while (hostEnd < end && text[hostEnd] != '/' && text[hostEnd] != '?' && text[hostEnd] != '#' &&
            text[hostEnd] != ':'
        ) {
            hostEnd++
        }
        if (!isValidHost(text, hostStart, hostEnd)) return -1
        if (hostEnd < end && text[hostEnd] == ':') {
            var portEnd = hostEnd + 1
            while (portEnd < end && text[portEnd] in '0'..'9') portEnd++
            val port = text.substring(hostEnd + 1, portEnd).toIntOrNull()
            if (port == null || port !in 1..65535 || portEnd - hostEnd > 6) return -1
            if (portEnd < end && text[portEnd] != '/' && text[portEnd] != '?' && text[portEnd] != '#') return -1
        }
        return if (end > start) end else -1
    }

    private fun isUrlTerminator(c: Char): Boolean =
        c.isWhitespace() || c == '<' || c == '>' || c.isISOControl()

    private fun trimUrlEnd(text: String, from: Int, to: Int): Int {
        var end = to
        while (end > from) {
            val last = text[end - 1]
            end = when {
                last in TRAILING_PUNCTUATION -> end - 1
                last == ')' && unbalanced(text, from, end, '(', ')') -> end - 1
                last == ']' && unbalanced(text, from, end, '[', ']') -> end - 1
                last == '}' && unbalanced(text, from, end, '{', '}') -> end - 1
                else -> return end
            }
        }
        return end
    }

    private const val TRAILING_PUNCTUATION = ".,;:!?'\""

    private fun unbalanced(text: String, from: Int, to: Int, open: Char, close: Char): Boolean {
        var opens = 0
        var closes = 0
        for (k in from until to) {
            when (text[k]) {
                open -> opens++
                close -> closes++
            }
        }
        return closes > opens
    }

    private fun isValidHost(text: String, from: Int, to: Int): Boolean {
        if (to <= from || to - from > 253) return false
        var labels = 0
        var labelStart = from
        var k = from
        while (k <= to) {
            if (k == to || text[k] == '.') {
                if (k == labelStart) return false
                if (text[labelStart] == '-' || text[k - 1] == '-') return false
                labels++
                labelStart = k + 1
                k++
                continue
            }
            val cp = text.codePointAt(k)
            if (!Character.isLetterOrDigit(cp) && !isMark(cp) && cp != '-'.code) return false
            k += Character.charCount(cp)
        }
        return labels >= 2
    }

    private fun mayPrecedeMention(text: String, i: Int): Boolean {
        if (i == 0) return true
        val c = text[i - 1]
        return c != '=' && c != '/' && c != '@' && c != '＠' && !followsWordChar(text, i)
    }

    private fun isHandleChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-'

    private fun isValidHandle(handle: String): Boolean {
        if (handle.isEmpty() || handle.length > 253) return false
        val labels = handle.split('.')
        if (labels.size < 2) return false
        if (labels.any { it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') }) return false
        return labels.last().first().isLetter()
    }

    private fun isHashtagCodePoint(cp: Int): Boolean =
        Character.isLetter(cp) || isMark(cp) || Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt() ||
            cp == '_'.code

    /** Whether the code point before [i] is a word character: a letter, mark, decimal digit or `_`. */
    private fun followsWordChar(text: String, i: Int): Boolean {
        if (i == 0) return false
        val cp = text.codePointBefore(i)
        return Character.isLetterOrDigit(cp) || isMark(cp) || cp == '_'.code
    }

    private fun isMark(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }
}
