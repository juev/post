package org.evsyukov.post.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import org.evsyukov.post.core.schedule.WeeklySchedule

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
    // Version 2 has the tables of version 1; its migration only writes the default schedule (#9).
    version = 2,
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

        fun open(context: Context): PostDatabase = builder(context, FILE_NAME).build()

        /** The builder of the database file [name], with its migrations and the default schedule of a new file. */
        fun builder(context: Context, name: String): RoomDatabase.Builder<PostDatabase> =
            Room.databaseBuilder(context, PostDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .addCallback(WriteDefaultScheduleOnCreate)

        /**
         * Version 1 to 2: the tables stay the same. An install of 0.1.0-rc.1 never had a default schedule, and
         * its schedule can only be empty if the user never saved one, so the default is written there.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) = db.writeDefaultScheduleIfEmpty()
        }

        private object WriteDefaultScheduleOnCreate : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) = db.writeDefaultScheduleIfEmpty()
        }
    }
}

/**
 * Writes [WeeklySchedule.DEFAULT] into an empty schedule (S1). It runs once per database file, when the file is
 * created or migrated to version 2; a schedule the user clears later stays empty.
 */
internal fun SupportSQLiteDatabase.writeDefaultScheduleIfEmpty() {
    val empty = query("SELECT COUNT(*) FROM schedule_slots").use { it.moveToFirst() && it.getLong(0) == 0L }
    if (!empty) return
    for ((day, times) in WeeklySchedule.DEFAULT.times) {
        for (time in times) {
            execSQL(
                "INSERT INTO schedule_slots (dayOfWeek, minuteOfDay) VALUES (?, ?)",
                arrayOf<Any>(day.value, time.hour * MINUTES_PER_HOUR + time.minute),
            )
        }
    }
}

private const val MINUTES_PER_HOUR = 60

/**
 * The JSON of the JSON columns. Unknown keys are skipped, so a field dropped in a later version does not
 * make old rows unreadable; a new field gets its default when an old row lacks it.
 */
internal val DbJson = Json {
    ignoreUnknownKeys = true
}
