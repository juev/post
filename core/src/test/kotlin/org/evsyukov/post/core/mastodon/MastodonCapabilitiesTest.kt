package org.evsyukov.post.core.mastodon

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.evsyukov.post.core.mastodon.MastodonCapabilities.Companion.TEXT_HTML
import org.evsyukov.post.core.mastodon.MastodonCapabilities.Companion.TEXT_MARKDOWN
import org.evsyukov.post.core.mastodon.MastodonCapabilities.Companion.TEXT_PLAIN
import org.junit.Test

/**
 * Limits (networks.md N1, N2, limits table) and post formats (formatting.md F1, F8–F10) of the
 * server families of networks.md N6a. Each fixture under `resources/mastodon/` names its source
 * in its `_source` field; the sources are repeated at each test.
 */
class MastodonCapabilitiesTest {
    private val json = MastodonClient.DefaultJson

    private fun text(name: String): String =
        requireNotNull(javaClass.getResource("/mastodon/$name")) { "fixture $name" }.readText()

    private fun v2(name: String): InstanceV2 = json.decodeFromString(text(name))
    private fun v1(name: String): InstanceV1 = json.decodeFromString(text(name))
    private fun nodeInfo(name: String): NodeInfo = json.decodeFromString(text(name))

    /** The vanilla column of the networks.md limits table. */
    private fun assertVanillaLimits(c: MastodonCapabilities) {
        assertThat(c.maxCharacters).isEqualTo(500)
        assertThat(c.maxMediaAttachments).isEqualTo(4)
        assertThat(c.charactersReservedPerUrl).isEqualTo(23)
        assertThat(c.imageSizeLimit).isEqualTo(16L * 1024 * 1024)
        assertThat(c.imageMatrixLimit).isEqualTo(7680L * 4320)
        assertThat(c.videoSizeLimit).isEqualTo(99L * 1024 * 1024)
        assertThat(c.videoMatrixLimit).isEqualTo(3840L * 2160)
        assertThat(c.videoFrameRateLimit).isEqualTo(120)
        assertThat(c.descriptionLimit).isEqualTo(10_000)
        assertThat(c.pollMaxOptions).isEqualTo(4)
        assertThat(c.pollMaxCharactersPerOption).isEqualTo(50)
        assertThat(c.pollMinExpirationSeconds).isEqualTo(300)
        assertThat(c.pollMaxExpirationSeconds).isEqualTo(2_629_746)
        assertThat(c.supportedMediaMimeTypes).containsAtLeast("image/jpeg", "image/png", "image/gif", "image/webp", "video/mp4", "video/webm", "video/quicktime", "video/ogg", "audio/mpeg")
    }

    // Source: docs.joinmastodon.org entities/Instance example; mastodon/mastodon main serializer.
    @Test
    fun vanillaMastodonReadsLimitsFromInstanceV2AndAcceptsPlainTextOnly() {
        val c = MastodonCapabilities.from(v2("mastodon-4.5-instance-v2.json"), null, nodeInfo("mastodon-nodeinfo-2.0.json"))

        assertVanillaLimits(c)
        assertThat(c.supportedMediaMimeTypes).hasSize(25)
        assertThat(c.postFormats).containsExactly(TEXT_PLAIN)
        assertThat(c.postFormatsDeclared).isFalse()
        assertThat(c.preferredFormat()).isEqualTo(TEXT_PLAIN)
        assertThat(c.statusContentType()).isNull()
        assertThat(c.mastodonApiVersion).isEqualTo(7)
        assertThat(c.supportsQuotePolicy).isTrue()
        assertThat(c.softwareName).isEqualTo("mastodon")
        assertThat(c.version).isEqualTo("4.5.0")
    }

    @Test
    fun quotePolicyNeedsApiVersionSeven() {
        val v6 = v2("mastodon-4.5-instance-v2.json").copy(apiVersions = ApiVersions(mastodon = 6))
        assertThat(MastodonCapabilities.from(v6, null, null).supportsQuotePolicy).isFalse()
        assertThat(MastodonCapabilities.from(v6, null, null).mastodonApiVersion).isEqualTo(6)
        val none = v2("mastodon-4.5-instance-v2.json").copy(apiVersions = null)
        assertThat(MastodonCapabilities.from(none, null, null).supportsQuotePolicy).isFalse()
        assertThat(MastodonCapabilities.from(none, null, null).mastodonApiVersion).isNull()
    }

    // Source: glitch-soc/mastodon 29468cc4, rest/instance_serializer.rb and html_aware_formatter.rb.
    @Test
    fun glitchSocListsMarkdownInInstanceV2AndGetsMarkdown() {
        val c = MastodonCapabilities.from(v2("glitch-soc-instance-v2.json"), null, null)

        assertThat(c.postFormats).containsExactly(TEXT_PLAIN, TEXT_MARKDOWN, TEXT_HTML).inOrder()
        assertThat(c.postFormatsDeclared).isTrue()
        assertThat(c.preferredFormat()).isEqualTo(TEXT_MARKDOWN)
        assertThat(c.statusContentType()).isEqualTo(TEXT_MARKDOWN)
        assertThat(c.maxCharacters).isEqualTo(5000)
        assertThat(c.mastodonApiVersion).isEqualTo(12)
        assertThat(c.supportsQuotePolicy).isTrue()
        assertThat(c.version).isEqualTo("5.0.0-alpha.3+glitch")
        // The media list is not mistaken for the post formats.
        assertThat(c.supportedMediaMimeTypes).doesNotContain(TEXT_MARKDOWN)
    }

    // Source: GoToSocial 257aad74, InstanceToAPIV2Instance.
    @Test
    fun goToSocialListsPlainAndMarkdownAndReportsItsOwnLimits() {
        val c = MastodonCapabilities.from(v2("gotosocial-instance-v2.json"), null, nodeInfo("gotosocial-nodeinfo-2.0.json"))

        assertThat(c.postFormats).containsExactly(TEXT_PLAIN, TEXT_MARKDOWN).inOrder()
        assertThat(c.preferredFormat()).isEqualTo(TEXT_MARKDOWN)
        assertThat(c.statusContentType()).isEqualTo(TEXT_MARKDOWN)
        assertThat(c.maxCharacters).isEqualTo(5000)
        assertThat(c.maxMediaAttachments).isEqualTo(6)
        assertThat(c.charactersReservedPerUrl).isEqualTo(25)
        assertThat(c.imageMatrixLimit).isEqualTo(Int.MAX_VALUE.toLong())
        assertThat(c.videoFrameRateLimit).isEqualTo(Int.MAX_VALUE)
        assertThat(c.descriptionLimit).isEqualTo(1500)
        assertThat(c.pollMaxOptions).isEqualTo(6)
        assertThat(c.supportedMediaMimeTypes).containsExactly("image/jpeg", "image/gif", "image/png", "image/webp", "video/mp4")
        assertThat(c.mastodonApiVersion).isNull()
        assertThat(c.supportsQuotePolicy).isFalse()
        assertThat(c.softwareName).isEqualTo("gotosocial")
    }

    // Source: Pleroma cf6b7096, instance_view.ex show.json and show2.json, nodeinfo.ex, config.exs.
    @Test
    fun pleromaTakesPostFormatsFromInstanceV1AndLegacyLimits() {
        val c = MastodonCapabilities.from(
            v2("pleroma-instance-v2.json"),
            v1("pleroma-instance-v1.json"),
            nodeInfo("pleroma-nodeinfo-2.1.json"),
        )

        assertThat(c.postFormats).containsExactly(TEXT_PLAIN, TEXT_HTML, TEXT_MARKDOWN, "text/bbcode", "text/x.misskeymarkdown").inOrder()
        assertThat(c.postFormatsDeclared).isTrue()
        assertThat(c.preferredFormat()).isEqualTo(TEXT_MARKDOWN)
        assertThat(c.maxCharacters).isEqualTo(5000)
        assertThat(c.maxMediaAttachments).isEqualTo(1000)
        // Reported as 0 by Pleroma's v2 view; the value is kept as the server says it (see the KDoc).
        assertThat(c.charactersReservedPerUrl).isEqualTo(0)
        // The application/octet-stream placeholder is not the only accepted type (see below).
        assertThat(c.supportedMediaMimeTypes).isEqualTo(MastodonCapabilities.Defaults.MEDIA_MIME_TYPES)
        assertThat(c.imageSizeLimit).isEqualTo(16_000_000)
        assertThat(c.videoSizeLimit).isEqualTo(16_000_000)
        // Not in either configuration block: the top-level description_limit of v1.
        assertThat(c.descriptionLimit).isEqualTo(5000)
        assertThat(c.pollMaxOptions).isEqualTo(20)
        assertThat(c.pollMaxCharactersPerOption).isEqualTo(200)
        assertThat(c.pollMinExpirationSeconds).isEqualTo(0)
        assertThat(c.pollMaxExpirationSeconds).isEqualTo(31_536_000)
        assertThat(c.softwareName).isEqualTo("pleroma")
        assertThat(c.version).isEqualTo("2.9.1")
    }

    // Pleroma cf6b7096 instance_view.ex configuration/0 lists only "application/octet-stream" in
    // v1 and v2, while Pleroma.Upload accepts any image, audio and video type (config.exs
    // allowed_mime_types ["image", "audio", "video"]). Taken as is, every JPEG, MP4 and MP3 would
    // look unsupported and be converted or dropped (media.md A5, A7, "Type not supported").
    @Test
    fun pleromaOctetStreamPlaceholderGivesTheVanillaMediaTypes() {
        val fromV2 = MastodonCapabilities.from(v2("pleroma-instance-v2.json"), null, null)
        val fromV1 = MastodonCapabilities.from(null, v1("pleroma-instance-v1.json"), null)

        for (c in listOf(fromV2, fromV1)) {
            assertThat(c.supportedMediaMimeTypes).isEqualTo(MastodonCapabilities.Defaults.MEDIA_MIME_TYPES)
            assertThat(c.supportedMediaMimeTypes).containsAtLeast("image/jpeg", "image/png", "video/mp4", "audio/mpeg")
            assertThat(c.supportedMediaMimeTypes).doesNotContain("application/octet-stream")
        }

        // Next to real types the placeholder is dropped and the real types stay.
        val mixed = InstanceV2(
            configuration = InstanceConfiguration(
                mediaAttachments = MediaConfiguration(supportedMimeTypes = listOf("image/jpeg", "Application/Octet-Stream", "video/mp4")),
            ),
        )
        assertThat(MastodonCapabilities.from(mixed, null, null).supportedMediaMimeTypes).containsExactly("image/jpeg", "video/mp4").inOrder()

        // A placeholder in v2 does not hide the real list of the v1 configuration.
        val v1WithTypes = InstanceV1(
            configuration = InstanceConfiguration(mediaAttachments = MediaConfiguration(supportedMimeTypes = listOf("image/png"))),
        )
        assertThat(MastodonCapabilities.from(v2("pleroma-instance-v2.json"), v1WithTypes, null).supportedMediaMimeTypes)
            .containsExactly("image/png")
    }

    // Source: Akkoma 3e9ad30e, instance_view.ex show.json (no configuration) and nodeinfo.ex.
    @Test
    fun akkomaWithoutConfigurationUsesItsTopLevelV1Limits() {
        val c = MastodonCapabilities.from(null, v1("akkoma-instance-v1.json"), null)

        assertThat(c.maxCharacters).isEqualTo(5000)
        assertThat(c.descriptionLimit).isEqualTo(5000)
        assertThat(c.imageSizeLimit).isEqualTo(16_000_000)
        assertThat(c.videoSizeLimit).isEqualTo(16_000_000)
        assertThat(c.pollMaxOptions).isEqualTo(20)
        assertThat(c.pollMaxCharactersPerOption).isEqualTo(200)
        assertThat(c.pollMinExpirationSeconds).isEqualTo(0)
        assertThat(c.pollMaxExpirationSeconds).isEqualTo(31_536_000)
        assertThat(c.supportedMediaMimeTypes).isEqualTo(MastodonCapabilities.Defaults.MEDIA_MIME_TYPES)
        // Not reported anywhere: vanilla defaults.
        assertThat(c.maxMediaAttachments).isEqualTo(4)
        assertThat(c.charactersReservedPerUrl).isEqualTo(23)
        assertThat(c.videoMatrixLimit).isEqualTo(3840L * 2160)
        assertThat(c.postFormats).contains(TEXT_MARKDOWN)
        assertThat(c.version).isEqualTo("2.7.2 (compatible; Akkoma 3.15.2)")
    }

    // F1 step 3. Source: Akkoma 3e9ad30e nodeinfo.ex (metadata.postFormats).
    @Test
    fun postFormatsFallBackToNodeInfoWhenTheInstanceDoesNotListThem() {
        val akkomaV1WithoutFormats = v1("akkoma-instance-v1.json").copy(pleroma = null)
        val c = MastodonCapabilities.from(null, akkomaV1WithoutFormats, nodeInfo("akkoma-nodeinfo-2.1.json"))

        assertThat(c.postFormats).containsExactly(TEXT_PLAIN, TEXT_MARKDOWN).inOrder()
        assertThat(c.postFormatsDeclared).isTrue()
        assertThat(c.preferredFormat()).isEqualTo(TEXT_MARKDOWN)
        assertThat(c.softwareName).isEqualTo("akkoma")
        assertThat(c.version).isEqualTo("3.15.2")
    }

    @Test
    fun postFormatsFollowTheOrderOfF1() {
        val glitch = v2("glitch-soc-instance-v2.json")
        val pleromaV1 = v1("pleroma-instance-v1.json")
        val akkomaNodeInfo = nodeInfo("akkoma-nodeinfo-2.1.json")

        // 1. configuration.statuses.supported_mime_types wins over both other sources.
        assertThat(MastodonCapabilities.from(glitch, pleromaV1, akkomaNodeInfo).postFormats)
            .containsExactly(TEXT_PLAIN, TEXT_MARKDOWN, TEXT_HTML).inOrder()
        // 2. pleroma.metadata.post_formats wins over nodeinfo.
        assertThat(MastodonCapabilities.from(v2("mastodon-4.5-instance-v2.json"), pleromaV1, akkomaNodeInfo).postFormats)
            .containsExactly(TEXT_PLAIN, TEXT_HTML, TEXT_MARKDOWN, "text/bbcode", "text/x.misskeymarkdown").inOrder()
        // 3. nodeinfo when neither instance document lists formats.
        assertThat(MastodonCapabilities.from(v2("mastodon-4.5-instance-v2.json"), null, akkomaNodeInfo).postFormats)
            .containsExactly(TEXT_PLAIN, TEXT_MARKDOWN).inOrder()
        // An empty list counts as not listed.
        val emptyList = glitch.copy(
            configuration = glitch.configuration!!.copy(statuses = glitch.configuration.statuses!!.copy(supportedMimeTypes = emptyList())),
        )
        assertThat(MastodonCapabilities.from(emptyList, null, akkomaNodeInfo).postFormats).containsExactly(TEXT_PLAIN, TEXT_MARKDOWN).inOrder()
    }

    @Test
    fun aServerThatListsOnlyPlainTextGetsPlainTextWithAnExplicitContentType() {
        // formatting.md verification scenario: a Pleroma server whose admin left only text/plain.
        val onlyPlain = v1("pleroma-instance-v1.json").copy(pleroma = PleromaInstance(PleromaMetadata(listOf(TEXT_PLAIN))))
        val c = MastodonCapabilities.from(null, onlyPlain, null)

        assertThat(c.postFormats).containsExactly(TEXT_PLAIN)
        assertThat(c.preferredFormat()).isEqualTo(TEXT_PLAIN)
        assertThat(c.statusContentType()).isEqualTo(TEXT_PLAIN)
    }

    @Test
    fun htmlIsPreferredOnlyWithoutMarkdown() {
        val htmlOnly = v1("pleroma-instance-v1.json").copy(pleroma = PleromaInstance(PleromaMetadata(listOf(TEXT_PLAIN, TEXT_HTML))))
        assertThat(MastodonCapabilities.from(null, htmlOnly, null).preferredFormat()).isEqualTo(TEXT_HTML)
        assertThat(MastodonCapabilities.from(null, htmlOnly, null).statusContentType()).isEqualTo(TEXT_HTML)
    }

    // Source: hometown-fork/hometown hometown-1.0.8-security, rest/instance_serializer.rb.
    @Test
    fun hometownOnMastodon3ReadsTheConfigurationOfInstanceV1() {
        val c = MastodonCapabilities.from(null, v1("hometown-1.0.8-instance-v1.json"), null)

        assertThat(c.maxCharacters).isEqualTo(1000)
        assertThat(c.videoFrameRateLimit).isEqualTo(60)
        assertThat(c.videoMatrixLimit).isEqualTo(2_304_000)
        assertThat(c.imageSizeLimit).isEqualTo(10_485_760)
        assertThat(c.imageMatrixLimit).isEqualTo(16_777_216)
        assertThat(c.videoSizeLimit).isEqualTo(41_943_040)
        assertThat(c.supportedMediaMimeTypes).containsExactly(
            "image/jpeg", "image/png", "image/gif", "video/webm", "video/mp4", "video/quicktime", "video/ogg", "audio/wave", "audio/ogg", "audio/mpeg",
        ).inOrder()
        // This configuration has no description_limit and no post formats.
        assertThat(c.descriptionLimit).isEqualTo(10_000)
        assertThat(c.postFormats).containsExactly(TEXT_PLAIN)
        assertThat(c.statusContentType()).isNull()
        assertThat(c.version).isEqualTo("3.4.6+hometown-1.0.8")
    }

    // Source: docs.joinmastodon.org entities/V1_Instance (configuration added in 3.4.2).
    @Test
    fun oldServerWithoutConfigurationGetsTheVanillaDefaults() {
        val c = MastodonCapabilities.from(null, v1("mastodon-3.3-instance-v1.json"), null)

        assertVanillaLimits(c)
        assertThat(c.supportedMediaMimeTypes).isEqualTo(MastodonCapabilities.Defaults.MEDIA_MIME_TYPES)
        assertThat(c.postFormats).containsExactly(TEXT_PLAIN)
        assertThat(c.mastodonApiVersion).isNull()
        assertThat(c.supportsQuotePolicy).isFalse()
        assertThat(c.version).isEqualTo("3.3.3")
    }

    @Test
    fun nothingAtAllGivesTheVanillaDefaults() {
        val c = MastodonCapabilities.from(null, null, null)
        assertVanillaLimits(c)
        assertThat(c.postFormats).containsExactly(TEXT_PLAIN)
        assertThat(c.softwareName).isNull()
        assertThat(c.version).isNull()
    }

    @Test
    fun aPartialConfigurationFillsOnlyTheMissingFieldsWithDefaults() {
        val partial = InstanceV2(configuration = InstanceConfiguration(statuses = StatusesConfiguration(maxCharacters = 1234)))
        val c = MastodonCapabilities.from(partial, null, null)
        assertThat(c.maxCharacters).isEqualTo(1234)
        assertThat(c.maxMediaAttachments).isEqualTo(4)
        assertThat(c.charactersReservedPerUrl).isEqualTo(23)
        assertThat(c.descriptionLimit).isEqualTo(10_000)
    }

    // The order of from(): v2 configuration, v1 configuration, legacy top-level v1 fields, default.
    @Test
    fun theConfigurationOfInstanceV1WinsOverItsLegacyFields() {
        val v1 = InstanceV1(
            configuration = InstanceConfiguration(
                statuses = StatusesConfiguration(maxCharacters = 1000),
                polls = PollsConfiguration(minExpiration = 600),
            ),
            maxTootChars = 2000,
            pollLimits = PollLimits(minExpiration = 60),
        )
        val c = MastodonCapabilities.from(null, v1, null)
        assertThat(c.maxCharacters).isEqualTo(1000)
        assertThat(c.pollMinExpirationSeconds).isEqualTo(600)

        val v2 = InstanceV2(configuration = InstanceConfiguration(statuses = StatusesConfiguration(maxCharacters = 3000)))
        assertThat(MastodonCapabilities.from(v2, v1, null).maxCharacters).isEqualTo(3000)
    }

    @Test
    fun apiVersionFallsBackToInstanceV1() {
        val v1 = InstanceV1(apiVersions = ApiVersions(mastodon = 7))
        val c = MastodonCapabilities.from(null, v1, null)
        assertThat(c.mastodonApiVersion).isEqualTo(7)
        assertThat(c.supportsQuotePolicy).isTrue()

        val v2 = InstanceV2(apiVersions = ApiVersions(mastodon = 6))
        assertThat(MastodonCapabilities.from(v2, v1, null).mastodonApiVersion).isEqualTo(6)
    }

    @Test
    fun listedValuesAreTrimmedAndBlankEntriesDropped() {
        val padded = InstanceV2(
            configuration = InstanceConfiguration(
                statuses = StatusesConfiguration(supportedMimeTypes = listOf(" text/markdown ", "", "text/plain")),
                mediaAttachments = MediaConfiguration(supportedMimeTypes = listOf(" image/png", "  ")),
            ),
        )
        val c = MastodonCapabilities.from(padded, null, null)
        assertThat(c.postFormats).containsExactly(TEXT_MARKDOWN, TEXT_PLAIN).inOrder()
        assertThat(c.supportedMediaMimeTypes).containsExactly("image/png")

        // Only blank entries count as not listed: the next F1 source, then the defaults.
        val blank = InstanceV2(
            configuration = InstanceConfiguration(
                statuses = StatusesConfiguration(supportedMimeTypes = listOf("", " ")),
                mediaAttachments = MediaConfiguration(supportedMimeTypes = listOf(" ")),
            ),
        )
        val fallback = MastodonCapabilities.from(blank, null, nodeInfo("akkoma-nodeinfo-2.1.json"))
        assertThat(fallback.postFormats).containsExactly(TEXT_PLAIN, TEXT_MARKDOWN).inOrder()
        assertThat(fallback.supportedMediaMimeTypes).isEqualTo(MastodonCapabilities.Defaults.MEDIA_MIME_TYPES)
        assertThat(MastodonCapabilities.from(blank, null, null).postFormatsDeclared).isFalse()
    }

    @Test
    fun capabilitiesSurviveAJsonRoundTripForStorage() {
        val c = MastodonCapabilities.from(v2("pleroma-instance-v2.json"), v1("pleroma-instance-v1.json"), nodeInfo("pleroma-nodeinfo-2.1.json"))
        val stored = Json.encodeToString(MastodonCapabilities.serializer(), c)
        assertThat(Json.decodeFromString(MastodonCapabilities.serializer(), stored)).isEqualTo(c)
    }
}
