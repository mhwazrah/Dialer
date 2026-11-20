package com.goodwy.dialer.databases

import android.content.Context
import android.media.RingtoneManager
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.goodwy.commons.extensions.getDefaultAlarmSound
import com.goodwy.dialer.extensions.config
import com.goodwy.dialer.helpers.Converters
import com.goodwy.dialer.interfaces.TimerDao
import com.goodwy.dialer.models.Timer
import com.goodwy.dialer.models.TimerState

@Database(entities = [Timer::class], version = 2)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun TimerDao(): TimerDao

    companion object {
        @Volatile
        private var db: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return db ?: synchronized(AppDatabase::class) {
                db ?: buildDatabase(context.applicationContext).also { db = it }
            }
        }

        private fun buildDatabase(context: Context) = Room.databaseBuilder(context, AppDatabase::class.java, "app.db")
            .fallbackToDestructiveMigration()
            .addMigrations(MIGRATION_1_2)
            .addCallback(object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    insertDefaultTimer(db, context)
                }
            })
            .build()

        // Runs inside the database creation: plain SQL on the given connection, never the DAO or another thread.
        private fun insertDefaultTimer(db: SupportSQLiteDatabase, context: Context) {
            db.execSQL(
                "INSERT INTO timers (seconds, state, vibrate, soundUri, soundTitle, title, label, description, createdAt, channelId, oneShot) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(
                    600,
                    Converters().timerStateToJson(TimerState.Idle),
                    if (context.config.callVibration) 1 else 0,
                    context.getDefaultAlarmSound(RingtoneManager.TYPE_ALARM).uri,
                    "",
                    "Timer",
                    "",
                    "",
                    System.currentTimeMillis(),
                    "right_dialer_timer_channel",
                    0,
                )
            )
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `timers` ADD COLUMN `oneShot` INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
