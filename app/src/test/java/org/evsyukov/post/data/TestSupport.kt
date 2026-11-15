package org.evsyukov.post.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.evsyukov.post.data.db.PostDatabase
import org.evsyukov.post.data.posts.AttachmentFiles
import org.evsyukov.post.data.secrets.TokenCipher
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Collections

/**
 * A stand-in for the Keystore cipher on the JVM: a marker, then the bytes reversed and XOR-ed, so no
 * byte of the input survives in place. Input without the marker does not decrypt, as damaged data
 * does not with AES-GCM.
 */
class FakeCipher : TokenCipher {
    override fun encrypt(plain: ByteArray): ByteArray = MARKER + plain.reversedArray().map { (it.toInt() xor 0x5A).toByte() }

    override fun decrypt(sealed: ByteArray): ByteArray {
        if (sealed.size < MARKER.size || !sealed.copyOfRange(0, MARKER.size).contentEquals(MARKER)) {
            throw javax.crypto.AEADBadTagException("tag mismatch")
        }
        return sealed.copyOfRange(MARKER.size, sealed.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray().reversedArray()
    }

    private companion object {
        val MARKER = byteArrayOf(0x7F, 0x13, 0x37)
    }
}

/** A [FakeCipher] that remembers the thread of every call. */
class ThreadRecordingCipher : TokenCipher {
    private val inner = FakeCipher()
    val threads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())

    override fun encrypt(plain: ByteArray): ByteArray {
        threads += Thread.currentThread()
        return inner.encrypt(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        threads += Thread.currentThread()
        return inner.decrypt(sealed)
    }
}

/** Remembers which files the repository asked to delete. */
class RecordingFiles : AttachmentFiles {
    val deleted = mutableListOf<String>()

    override fun delete(paths: Collection<String>) {
        deleted += paths
    }
}

/** A clock the test moves by hand. */
class MutableClock(var now: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
    override fun instant(): Instant = now

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}

fun inMemoryDatabase(): PostDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PostDatabase::class.java)
        .allowMainThreadQueries()
        .build()

/** The rows of a raw query, each as a list of its column values. */
fun PostDatabase.rows(sql: String): List<List<Any?>> = openHelper.writableDatabase.query(sql).use { cursor ->
    buildList {
        while (cursor.moveToNext()) {
            add(
                (0 until cursor.columnCount).map { column ->
                    when (cursor.getType(column)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> null
                        android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                        android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column)
                        else -> cursor.getString(column)
                    }
                },
            )
        }
    }
}

/** Whether [needle] occurs anywhere in this array. */
fun ByteArray.containsBytes(needle: ByteArray): Boolean =
    (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
