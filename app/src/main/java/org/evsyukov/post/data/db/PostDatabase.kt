package org.evsyukov.post.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.serialization.json.Json

/**
 * The local database of Post. Every post is written here before any network request (R27).
 *
 * The schema is exported to `app/schemas` and checked by tests. A later version ships a migration;
 * there is no destructive fallback, because dropping the tables would lose queued posts and tokens.
 */
@Database(
    entities = [
        AccountEntity::class,
        MastodonAppEntity::class,
        PostEntity::class,
        AttachmentEntity::class,
        VariantEntity::class,
        DeliveryEntity::class,
        HistoryEntity::class,
        ScheduleSlotEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class PostDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun mastodonAppDao(): MastodonAppDao
    abstract fun postDao(): PostDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun deliveryDao(): DeliveryDao
    abstract fun historyDao(): HistoryDao
    abstract fun scheduleDao(): ScheduleDao

    companion object {
        const val FILE_NAME = "post.db"

        fun open(context: Context): PostDatabase =
            Room.databaseBuilder(context, PostDatabase::class.java, FILE_NAME).build()
    }
}

/**
 * The JSON of the JSON columns. Unknown keys are skipped, so a field dropped in a later version does not
 * make old rows unreadable; a new field gets its default when an old row lacks it.
 */
internal val DbJson = Json {
    ignoreUnknownKeys = true
}
