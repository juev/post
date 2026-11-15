package org.evsyukov.post.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.evsyukov.post.core.markdown.BlueskyLinkMode
import org.evsyukov.post.core.media.ConflictKind
import org.evsyukov.post.core.media.FixKind
import org.evsyukov.post.core.schedule.LateThreshold
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant

/** What Send in the Share sheet does by default (product.md R18). */
enum class ShareAction { COMPOSER, SEND_NOW, SEND_LATER }

/** The default fix for more files than a network allows (media.md A12). */
enum class TooManyFilesDefault { ASK, MOVE_TO_REPLY }

/** The default fix for a type a network does not take (media.md A12). */
enum class UnsupportedTypeDefault { ASK, SEND_WITHOUT_FILE }

/** How long the Sent tab keeps a record (R31); [OFF] keeps none. */
enum class HistoryRetention(val days: Long?) {
    OFF(null),
    DAYS_7(7),
    DAYS_30(30),
    DAYS_90(90),
    ;

    /** When a record sent at [sentAt] expires, or null when no record is kept. */
    fun expiresAt(sentAt: Instant): Instant? = days?.let { sentAt.plus(Duration.ofDays(it)) }
}

/**
 * The setting "video transcoding quality" (screens.md, Sending), for the converter of step 7. Both keep
 * the output inside the network's size limit (media.md A7); they differ in how much of it they use.
 *
 * - [STANDARD]: the targets of `VideoTargets` as they are: the bitrate is capped at 8 Mbit/s for 1080p
 *   and 5 Mbit/s for 720p, so a short clip stays small and uploads fast.
 * - [HIGH]: no cap below the size budget: the bitrate fills the budget of the limit, but never exceeds
 *   the source's own bitrate, so the output keeps more detail and is larger.
 */
enum class VideoQuality { STANDARD, HIGH }

/**
 * The settings of screens.md (Defaults, Sending) and a few values the app keeps for itself. The
 * defaults are those of the spec for a new install.
 *
 * @property defaultTargets accounts preselected in a new draft and used by Send now from Share (R16).
 * @property lastLimitsRefresh when the account limits were last read from the servers (N1).
 */
data class Settings(
    val defaultTargets: List<Long> = emptyList(),
    val shareAction: ShareAction = ShareAction.COMPOSER,
    val requireAltText: Boolean = false,
    val defaultFixTooManyFiles: TooManyFilesDefault = TooManyFilesDefault.ASK,
    val defaultFixUnsupportedType: UnsupportedTypeDefault = UnsupportedTypeDefault.ASK,
    val blueskyLinkMode: BlueskyLinkMode = BlueskyLinkMode.FACET_ON_TEXT,
    val successNotifications: Boolean = false,
    val historyRetention: HistoryRetention = HistoryRetention.DAYS_30,
    val lateThreshold: LateThreshold = LateThreshold.DEFAULT,
    val numberThreadParts: Boolean = false,
    val videoQuality: VideoQuality = VideoQuality.STANDARD,
    val keepImageMetadata: Boolean = false,
    val notificationPermissionAsked: Boolean = false,
    val lastLimitsRefresh: Instant? = null,
) {
    /** The default fixes as the media planner takes them (A12); "ask" chooses nothing. */
    fun defaultFixes(): Map<ConflictKind, FixKind> = buildMap {
        if (defaultFixTooManyFiles == TooManyFilesDefault.MOVE_TO_REPLY) put(ConflictKind.TOO_MANY_FILES, FixKind.MOVE_TO_REPLY)
        if (defaultFixUnsupportedType == UnsupportedTypeDefault.SEND_WITHOUT_FILE) {
            put(ConflictKind.UNSUPPORTED_TYPE, FixKind.SEND_WITHOUT_FILE)
        }
    }
}

/**
 * The settings in DataStore Preferences. A damaged file is replaced by an empty one, so the app starts
 * with the defaults instead of failing. One file must be opened by one instance per process.
 */
class AppSettings(scope: CoroutineScope, produceFile: () -> File) {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = produceFile,
    )

    /**
     * The settings as stored; the first value comes once the file is read. When the file cannot be read
     * at all, the flow gives the defaults and ends, as for a damaged file.
     */
    val settings: Flow<Settings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }

    /** The stored settings now; background work reads this rather than a default value. */
    suspend fun current(): Settings = settings.first()

    suspend fun setDefaultTargets(accountIds: List<Long>) = edit { it[DEFAULT_TARGETS] = encodeIds(accountIds) }
    suspend fun setShareAction(value: ShareAction) = edit { it[SHARE_ACTION] = value.name }
    suspend fun setRequireAltText(value: Boolean) = edit { it[REQUIRE_ALT_TEXT] = value }
    suspend fun setDefaultFixTooManyFiles(value: TooManyFilesDefault) = edit { it[FIX_TOO_MANY_FILES] = value.name }
    suspend fun setDefaultFixUnsupportedType(value: UnsupportedTypeDefault) = edit { it[FIX_UNSUPPORTED_TYPE] = value.name }
    suspend fun setBlueskyLinkMode(value: BlueskyLinkMode) = edit { it[BLUESKY_LINK_MODE] = value.name }
    suspend fun setSuccessNotifications(value: Boolean) = edit { it[SUCCESS_NOTIFICATIONS] = value }
    suspend fun setHistoryRetention(value: HistoryRetention) = edit { it[HISTORY_RETENTION] = value.name }
    suspend fun setLateThreshold(value: LateThreshold) = edit { it[LATE_THRESHOLD] = value.name }
    suspend fun setNumberThreadParts(value: Boolean) = edit { it[NUMBER_THREAD_PARTS] = value }
    suspend fun setVideoQuality(value: VideoQuality) = edit { it[VIDEO_QUALITY] = value.name }
    suspend fun setKeepImageMetadata(value: Boolean) = edit { it[KEEP_IMAGE_METADATA] = value }
    suspend fun setNotificationPermissionAsked(value: Boolean) = edit { it[NOTIFICATION_PERMISSION_ASKED] = value }
    suspend fun setLastLimitsRefresh(value: Instant) = edit { it[LAST_LIMITS_REFRESH] = value.toEpochMilli() }

    private suspend fun edit(change: (MutablePreferences) -> Unit) {
        store.edit { change(it) }
    }

    companion object {
        const val FILE_NAME = "settings"

        /** The settings file of the app, in its files directory. */
        fun create(context: Context, scope: CoroutineScope): AppSettings =
            AppSettings(scope) { context.preferencesDataStoreFile(FILE_NAME) }

        private val DEFAULT_TARGETS = stringPreferencesKey("default_targets")
        private val SHARE_ACTION = stringPreferencesKey("share_action")
        private val REQUIRE_ALT_TEXT = booleanPreferencesKey("require_alt_text")
        private val FIX_TOO_MANY_FILES = stringPreferencesKey("default_fix_too_many_files")
        private val FIX_UNSUPPORTED_TYPE = stringPreferencesKey("default_fix_unsupported_type")
        private val BLUESKY_LINK_MODE = stringPreferencesKey("bluesky_link_mode")
        private val SUCCESS_NOTIFICATIONS = booleanPreferencesKey("success_notifications")
        private val HISTORY_RETENTION = stringPreferencesKey("history_retention")
        private val LATE_THRESHOLD = stringPreferencesKey("late_threshold")
        private val NUMBER_THREAD_PARTS = booleanPreferencesKey("number_thread_parts")
        private val VIDEO_QUALITY = stringPreferencesKey("video_quality")
        private val KEEP_IMAGE_METADATA = booleanPreferencesKey("keep_image_metadata")
        private val NOTIFICATION_PERMISSION_ASKED = booleanPreferencesKey("notification_permission_asked")
        private val LAST_LIMITS_REFRESH = longPreferencesKey("last_limits_refresh")

        private val idsSerializer = ListSerializer(Long.serializer())

        private fun encodeIds(ids: List<Long>): String = Json.encodeToString(idsSerializer, ids)

        private fun decodeIds(json: String?): List<Long>? =
            json?.let { runCatching { Json.decodeFromString(idsSerializer, it) }.getOrNull() }

        /** A stored name that no longer names a value (or none) gives the default. */
        private inline fun <reified E : Enum<E>> Preferences.enumOr(key: Preferences.Key<String>, default: E): E {
            val name = this[key] ?: return default
            return enumValues<E>().firstOrNull { it.name == name } ?: default
        }

        private fun Preferences.toSettings(): Settings {
            val defaults = Settings()
            return Settings(
                defaultTargets = decodeIds(this[DEFAULT_TARGETS]) ?: defaults.defaultTargets,
                shareAction = enumOr(SHARE_ACTION, defaults.shareAction),
                requireAltText = this[REQUIRE_ALT_TEXT] ?: defaults.requireAltText,
                defaultFixTooManyFiles = enumOr(FIX_TOO_MANY_FILES, defaults.defaultFixTooManyFiles),
                defaultFixUnsupportedType = enumOr(FIX_UNSUPPORTED_TYPE, defaults.defaultFixUnsupportedType),
                blueskyLinkMode = enumOr(BLUESKY_LINK_MODE, defaults.blueskyLinkMode),
                successNotifications = this[SUCCESS_NOTIFICATIONS] ?: defaults.successNotifications,
                historyRetention = enumOr(HISTORY_RETENTION, defaults.historyRetention),
                lateThreshold = enumOr(LATE_THRESHOLD, defaults.lateThreshold),
                numberThreadParts = this[NUMBER_THREAD_PARTS] ?: defaults.numberThreadParts,
                videoQuality = enumOr(VIDEO_QUALITY, defaults.videoQuality),
                keepImageMetadata = this[KEEP_IMAGE_METADATA] ?: defaults.keepImageMetadata,
                notificationPermissionAsked = this[NOTIFICATION_PERMISSION_ASKED] ?: defaults.notificationPermissionAsked,
                lastLimitsRefresh = this[LAST_LIMITS_REFRESH]?.let(Instant::ofEpochMilli),
            )
        }
    }
}
