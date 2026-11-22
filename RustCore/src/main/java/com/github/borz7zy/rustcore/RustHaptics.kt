/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.rustcore

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.ByteBuffer

/** All database operations and PCM processing must run on a worker thread. */
class RustHaptics(path: File) {
    private val database = HapticsSqlite(path)

    fun version(): Int = nativeVersion()
    fun register(key: String, revision: String, uri: String, pinned: Boolean): Int =
        nativeDatabase(database, 0, key, revision, uri, if (pinned) 1 else 0)[0].toInt()
    fun claim(key: String, revision: String, token: String, attempt: Int): Boolean =
        nativeDatabase(database, 1, key, revision, token, attempt)[0] == "1"
    fun state(key: String, revision: String, token: String, state: Int) {
        nativeDatabase(database, 2, key, revision, token, state)
    }
    fun status(key: String, revision: String): Int =
        nativeDatabase(database, 3, key, revision, "", 0)[0].toInt()
    fun stats(): LongArray = nativeDatabase(database, 4, "", "", "", 0).map(String::toLong).toLongArray()
    fun clean(megabytes: Int, all: Boolean) {
        nativeDatabase(database, 5, "", "", "", if (all) -1 else megabytes)
    }
    fun sources(): Array<String> = nativeDatabase(database, 6, "", "", "", 0)
    fun cancel(key: String, revision: String) {
        nativeDatabase(database, 8, key, revision, "", 0)
    }
    fun flushUsage() {
        nativeDatabase(database, 10, "", "", "", 0)
    }
    fun recover() {
        nativeDatabase(database, 11, "", "", "", 0)
    }
    fun invalidate() {
        nativeDatabase(database, 9, "", "", "", 0)
    }
    fun unpin(key: String) {
        nativeDatabase(database, 7, key, "", "", 0)
    }
    fun lookup(key: String, revision: String, profile: Int): ByteArray? =
        nativeLookup(database, key, revision, profile)
    fun begin(sampleRate: Int, channels: Int, encoding: Int): Long = nativeBegin(sampleRate, channels, encoding)
    fun feed(handle: Long, buffer: ByteBuffer, offset: Int, size: Int) = nativeFeed(handle, buffer, offset, size)
    fun save(handle: Long, key: String, revision: String, token: String, megabytes: Int) =
        nativeSave(database, handle, key, revision, token, megabytes)
    fun release(handle: Long) = nativeRelease(handle)

    companion object {
        init { System.loadLibrary("rustcore") }

        fun plan(
            frames: ByteArray, startMs: Long, lengthMs: Long, speed: Float,
            intensity: Int, basic: Boolean,
        ): IntArray = nativePlan(frames, startMs, lengthMs, speed, intensity, if (basic) 1 else 0)

        fun notificationPlan(frames: ByteArray, offsetMs: Int, intensity: Int, basic: Boolean): IntArray =
            nativePlan(frames, -offsetMs.toLong(), 6_000, 1f, intensity, if (basic) 2 else 3)

        fun trim() = nativeTrim()

        @JvmStatic private external fun nativeVersion(): Int
        @JvmStatic private external fun nativeDatabase(
            database: HapticsSqlite, operation: Int, key: String, revision: String, token: String, value: Int,
        ): Array<String>
        @JvmStatic private external fun nativeLookup(
            database: HapticsSqlite, key: String, revision: String, profile: Int,
        ): ByteArray?
        @JvmStatic private external fun nativeBegin(sampleRate: Int, channels: Int, encoding: Int): Long
        @JvmStatic private external fun nativeFeed(handle: Long, data: ByteBuffer, offset: Int, size: Int): Boolean
        @JvmStatic private external fun nativeSave(
            database: HapticsSqlite, handle: Long, key: String, revision: String, token: String, limit: Int,
        ): Boolean
        @JvmStatic private external fun nativeRelease(handle: Long)
        @JvmStatic private external fun nativePlan(
            frames: ByteArray, startMs: Long, lengthMs: Long, speed: Float, intensity: Int, mode: Int,
        ): IntArray
        @JvmStatic private external fun nativeTrim()
    }
}

/** Public framework SQLite bindings. Rust owns the SQL, schema and transaction scope. */
@android.annotation.SuppressLint("UnusedPrivateMember")
private class HapticsSqlite(private val path: File) {
    private var connection: SQLiteDatabase? = null
    private val database: SQLiteDatabase get() = connection ?: open().also { connection = it }

    private fun open(): SQLiteDatabase {
        path.parentFile?.mkdirs()
        // A corruption error stays visible and can be recovered from settings.
        return SQLiteDatabase.openDatabase(
            path.path, null,
            SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
            DatabaseErrorHandler { },
        ).apply { setForeignKeyConstraintsEnabled(true) }
    }

    @Suppress("unused") // JNI
    fun recover() {
        connection?.close()
        connection = null
        check(!path.exists() || SQLiteDatabase.deleteDatabase(path)) { "Could not reset haptics database" }
    }

    @Suppress("unused") // JNI
    fun execute(sql: String, args: Array<String>, blob: ByteArray?) {
        database.compileStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.bindString(index + 1, value) }
            blob?.let { statement.bindBlob(args.size + 1, it) }
            statement.execute()
        }
    }

    @Suppress("unused") // JNI
    fun query(sql: String, args: Array<String>): Array<String> =
        database.rawQuery(sql, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    for (column in 0 until cursor.columnCount) add(cursor.getString(column).orEmpty())
                }
            }.toTypedArray()
        }

    @Suppress("unused") // JNI
    fun blob(sql: String, args: Array<String>): ByteArray? =
        database.rawQuery(sql, args).use { if (it.moveToFirst()) it.getBlob(0) else null }

    @Suppress("unused") // JNI
    fun begin() = database.beginTransactionNonExclusive()

    @Suppress("unused") // JNI
    fun commit() = database.setTransactionSuccessful()

    @Suppress("unused") // JNI
    fun end() = database.endTransaction()
}
