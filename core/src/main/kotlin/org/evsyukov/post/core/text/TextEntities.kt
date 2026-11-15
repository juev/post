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
     * (`extract_url_without_protocol: false` in `StatusLengthValidator`). The rules follow
     * `Uts58::Extractor#extract_urls_with_indices` of the gem `uts58` 0.2.6 (`lib/uts58/extractor.rb`),
     * which `app/lib/extractor.rb` of Mastodon `main` uses at commit
     * `296ca2874f3b7c02e79d2041b54f352a392f17ba`:
     *
     * - The scheme must not follow a letter, decimal digit, mark, `-`, `.`, `/` or `@`, except a run of
     *   CJK, Thai, Lao, Khmer or Myanmar letters that itself starts after such a boundary
     *   (`テストhttps://example.com`).
     * - The host is two to five labels of letters, numbers, marks and hyphens, separated by `.` or `。`,
     *   under 254 chars, with no label that starts or ends with a hyphen. It ends at the first character
     *   that cannot be in it, so `«https://example.com»` and `https://example.com,` hold the host alone.
     * - The last label must look like a top-level domain: two or more letters, or `xn--` and a punycode
     *   label, and not `invalid`. The server checks it against the Public Suffix List, which this does
     *   not reproduce.
     * - A port of 1–65535 may follow; a port outside it means no URL at all.
     * - The path segments, the query and the fragment end by UTS #58 Link_Term ([LinkTerm]): at
     *   whitespace and other Hard characters; before Soft punctuation (`.` `,` `;` `:` `!` `?` `'` `"`
     *   `«` `»` `“` `”` `。` `，` and others) that no more of the link follows; at a closing bracket that
     *   has no opening one in the same segment, so `(https://example.com/a)` and
     *   `https://en.wikipedia.org/wiki/Foo_(bar)` both come out right.
     *
     * One difference from the gem, by the rule of this app that trailing punctuation is not part of a
     * link: Soft characters at the very end are dropped, so an empty query `?` and the `.` of `a.)` are
     * not part of the URL.
     */
    fun urls(text: String): List<TextEntity> = urls(text, Int.MAX_VALUE)

    /**
     * [urls], where a URL longer than [maxCodePoints] code points is skipped as Mastodon's
     * `Extractor::UTS58.max_length` skips it; a URL that starts inside a skipped one is still found.
     */
    internal fun urls(text: String, maxCodePoints: Int): List<TextEntity> {
        val result = ArrayList<TextEntity>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != 'h' && c != 'H') {
                i++
                continue
            }
            val end = urlEndAt(text, i)
            if (end > i && text.codePointCount(i, end) <= maxCodePoints) {
                result += TextEntity(i, end, TextEntity.Kind.URL, text.substring(i, end))
                i = end
            } else {
                i++
            }
        }
        return result
    }

    /** The end of the URL of [urls] that starts at [start], or -1 when none starts there. */
    internal fun urlEndAt(text: String, start: Int): Int {
        val schemeLength = schemeLengthAt(text, start)
        if (schemeLength == 0 || !mayPrecedeUrl(text, start)) return -1
        val hostStart = start + schemeLength
        val hostEnd = hostEnd(text, hostStart)
        if (hostEnd < 0 || !isValidHost(text, hostStart, hostEnd)) return -1
        var i = hostEnd
        // "example.com." keeps its dot only when a path, query or fragment follows.
        if (i + 1 < text.length && text[i] == '.' && text[i + 1].let { it == '/' || it == '?' || it == '#' }) i++
        if (i + 1 < text.length && text[i] == ':' && text[i + 1] in '0'..'9') {
            var portEnd = i + 1
            while (portEnd < text.length && text[portEnd] in '0'..'9') portEnd++
            val digits = text.substring(i + 1, portEnd).trimStart('0')
            if (digits.isEmpty() || digits.length > 5 || digits.toInt() > 65535) return -1
            i = portEnd
        }
        while (i < text.length && text[i] == '/') i = componentEnd(text, i, Component.PATH)
        if (i < text.length && text[i] == '?') i = componentEnd(text, i, Component.QUERY)
        if (i < text.length && text[i] == '#') i = componentEnd(text, i, Component.FRAGMENT)
        while (i > hostEnd && LinkTerm.of(text.codePointBefore(i)) == LinkTerm.Kind.SOFT) {
            i -= Character.charCount(text.codePointBefore(i))
        }
        return i
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
            mastodonMention(text, matcher)?.let { result += it }
        }
        return result
    }

    /** The end of the mention of [mastodonMentions] that starts at [start], or -1. */
    internal fun mastodonMentionEndAt(text: String, start: Int): Int {
        if (start >= text.length || text[start] != '@') return -1
        val matcher = MASTODON_MENTION.matcher(text)
        matcher.region(start, text.length).useTransparentBounds(true).useAnchoringBounds(false)
        if (!matcher.lookingAt()) return -1
        return mastodonMention(text, matcher)?.end ?: -1
    }

    private fun mastodonMention(text: String, matcher: java.util.regex.Matcher): TextEntity? {
        val endMention = END_MENTION_MATCH.matcher(text)
        endMention.region(matcher.end(), text.length)
        if (endMention.lookingAt()) return null
        val screenName = matcher.group(1)
        val domain = screenName.substringAfter('@', "")
        if (domain.length > MAX_DOMAIN_LENGTH) return null
        return TextEntity(matcher.start(), matcher.end(), TextEntity.Kind.MENTION, screenName)
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
            val end = blueskyMentionEndAt(text, i)
            if (end > i) {
                result += TextEntity(i, end, TextEntity.Kind.MENTION, text.substring(i + 1, end))
                next = end
            }
            i = text.indexOf('@', next)
        }
        return result
    }

    /** The end of the mention of [blueskyMentions] that starts at [start], or -1. */
    internal fun blueskyMentionEndAt(text: String, start: Int): Int {
        if (start >= text.length || text[start] != '@' || !mayPrecedeMention(text, start)) return -1
        var end = start + 1
        while (end < text.length && isHandleChar(text[end])) end++
        val scanned = end
        while (end > start + 1 && (text[end - 1] == '.' || text[end - 1] == '-')) end--
        val followedByAt = end == scanned && end < text.length && (text[end] == '@' || text[end] == '＠')
        return if (!followedByAt && isValidHandle(text.substring(start + 1, end))) end else -1
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
            val end = hashtagEndAt(text, i)
            if (end > i) {
                result += TextEntity(i, end, TextEntity.Kind.HASHTAG, text.substring(i + 1, end))
                i = end
            } else {
                i++
            }
        }
        return result
    }

    /** The end of the hashtag of [hashtags] that starts at [start], or -1. */
    internal fun hashtagEndAt(text: String, start: Int): Int {
        if (start >= text.length) return -1
        val c = text[start]
        if ((c != '#' && c != '＃') || followsWordChar(text, start)) return -1
        var end = start + 1
        var hasLetter = false
        while (end < text.length) {
            val cp = text.codePointAt(end)
            if (!isHashtagCodePoint(cp)) break
            if (Character.isLetter(cp) || isMark(cp)) hasLetter = true
            end += Character.charCount(cp)
        }
        return if (hasLetter && !text.startsWith("://", end)) end else -1
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

    /**
     * Whether a scheme at [start] may begin a URL. The gem starts a candidate where no letter, digit, mark,
     * `-`, `.`, `/` or `@` precedes (`(?<![-\p{Alnum}\p{M}.\/@])`), then lets a run of CJK-script letters
     * stand between that point and the scheme.
     */
    private fun mayPrecedeUrl(text: String, start: Int): Boolean {
        var t = start
        while (true) {
            // A candidate starts only at an alphanumeric character; the scheme's "h" is one.
            val candidate = t == start || isAlnum(text.codePointAt(t))
            if (candidate && (t == 0 || isUrlBoundary(text.codePointBefore(t)))) return true
            if (t == 0) return false
            val cp = text.codePointBefore(t)
            if (!isSchemePrefixChar(cp)) return false
            t -= Character.charCount(cp)
        }
    }

    private fun isUrlBoundary(cp: Int): Boolean =
        !isAlnum(cp) && !isMark(cp) && cp != '-'.code && cp != '.'.code && cp != '/'.code && cp != '@'.code

    /** Ruby's `\p{Alnum}`: alphabetic or a decimal digit. */
    private fun isAlnum(cp: Int): Boolean = Character.isAlphabetic(cp) || Character.isDigit(cp)

    private fun isSchemePrefixChar(cp: Int): Boolean =
        Character.UnicodeScript.of(cp) in SCRIPTS_BEFORE_SCHEME &&
            (Character.isLetter(cp) || isMark(cp) || isNumber(cp))

    private fun isNumber(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
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

    /**
     * The end of the host that starts at [from], or -1: the gem's
     * `([-\p{L}\p{N}\p{M}ßς۽۾་〇]+[.。]){1,4}[-\p{L}\p{N}\p{M}]+(?![-\p{L}\p{N}\p{M}])`, that is up to four
     * labels each followed by a dot, then a last label of the stricter class; with backtracking, the most
     * dots that still leave a last label.
     */
    private fun hostEnd(text: String, from: Int): Int {
        val afterDots = IntArray(4)
        var dots = 0
        var i = from
        while (dots < 4) {
            val labelEnd = scanHostChars(text, i, extended = true)
            if (labelEnd == i || labelEnd >= text.length || (text[labelEnd] != '.' && text[labelEnd] != '。')) break
            i = labelEnd + 1
            afterDots[dots++] = i
        }
        for (k in dots downTo 1) {
            val end = scanHostChars(text, afterDots[k - 1], extended = false)
            if (end > afterDots[k - 1]) return end
        }
        return -1
    }

    private fun scanHostChars(text: String, from: Int, extended: Boolean): Int {
        var i = from
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val hostChar = cp == '-'.code || Character.isLetter(cp) || isNumber(cp) || isMark(cp) ||
                (extended && (cp == 0x06FD || cp == 0x06FE || cp == 0x0F0B))
            if (!hostChar) break
            i += Character.charCount(cp)
        }
        return i
    }

    /** The gem's checks of a host: under 254 code points, no label starting or ending with `-`, a known kind of TLD. */
    private fun isValidHost(text: String, from: Int, to: Int): Boolean {
        if (text.codePointCount(from, to) >= 254) return false
        val labels = text.substring(from, to).replace('。', '.').split('.')
        if (labels.any { it.startsWith('-') || it.endsWith('-') }) return false
        val tld = labels.last()
        if (tld.equals("invalid", ignoreCase = true)) return false
        if (tld.startsWith("xn--", ignoreCase = true)) {
            return tld.length > 4 && tld.substring(4).all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
        }
        return tld.codePointCount(0, tld.length) >= 2 &&
            tld.codePoints().allMatch { Character.isLetter(it) || isMark(it) }
    }

    /** A part of a URL after the host, as `Uts58::Extractor#skip_component` reads it. */
    private enum class Component(val closers: String, val separators: String) {
        /** A path segment ends at the next `/`, `?` or `#`. */
        PATH("/?#", ""),

        /** The query ends at `#`; `=` and `&` start a new part, where brackets pair anew. */
        QUERY("#", "=&"),

        /** The fragment; after a `:~:` text directive, `,`, `=` and `&` start new parts. */
        FRAGMENT("", ""),
    }

    /**
     * The end of the component whose lead-in (`/`, `?` or `#`) is at [start]: the next closer of the
     * component, a Hard character, a run of Soft characters followed by a Hard one or the end of the text,
     * or a closing bracket whose opening bracket is not open in this part.
     */
    private fun componentEnd(text: String, start: Int, component: Component): Int {
        var openers = IntArray(8)
        var depth = 0
        var separators = component.separators
        // Soft characters before this index are known to be followed by a non-Hard one; it keeps runs linear.
        var softRunEnd = -1
        var i = start + 1
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (cp < 0x80 && component.closers.indexOf(cp.toChar()) >= 0) return i
            if (component == Component.FRAGMENT && text.startsWith(":~:", i)) {
                depth = 0
                separators = ",=&"
                i += 3
                continue
            }
            if (cp < 0x80 && separators.indexOf(cp.toChar()) >= 0) {
                depth = 0
                i++
                continue
            }
            when (LinkTerm.of(cp)) {
                LinkTerm.Kind.INCLUDE -> Unit
                LinkTerm.Kind.HARD -> return i
                LinkTerm.Kind.SOFT -> if (i >= softRunEnd) {
                    // A Soft character stays when the run it starts is followed by a non-Hard character.
                    var j = i
                    while (j < text.length && LinkTerm.of(text.codePointAt(j)) == LinkTerm.Kind.SOFT) {
                        j += Character.charCount(text.codePointAt(j))
                    }
                    if (j >= text.length || LinkTerm.of(text.codePointAt(j)) == LinkTerm.Kind.HARD) return i
                    softRunEnd = j
                }
                LinkTerm.Kind.OPEN -> {
                    if (depth == openers.size) openers = openers.copyOf(depth * 2)
                    openers[depth++] = cp
                }
                LinkTerm.Kind.CLOSE -> {
                    if (depth > 0 && openers[depth - 1] == LinkTerm.openerOf(cp)) depth-- else return i
                }
            }
            i += Character.charCount(cp)
        }
        return text.length
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
