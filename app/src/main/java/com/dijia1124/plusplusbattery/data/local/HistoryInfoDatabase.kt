package com.dijia1124.plusplusbattery.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dijia1124.plusplusbattery.data.model.ChargeEvent
import com.dijia1124.plusplusbattery.data.model.HistoryInfo

@Database(
    entities = [HistoryInfo::class, ChargeEvent::class],
    version = 3,
    exportSchema = false
)
abstract class HistoryInfoDatabase : RoomDatabase() {
    abstract fun historyInfoDao(): HistoryInfoDao
    abstract fun chargeEventDao(): ChargeEventDao

    companion object {
        @Volatile
        private var INSTANCE: HistoryInfoDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `charge_events` (
                        `uid` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `type` TEXT NOT NULL,
                        `startTime` INTEGER NOT NULL,
                        `endTime` INTEGER,
                        `durationMs` INTEGER,
                        `startLevel` INTEGER NOT NULL,
                        `endLevel` INTEGER,
                        `deltaLevel` INTEGER,
                        `mah` REAL,
                        `mahSource` TEXT,
                        `screenOnMs` INTEGER NOT NULL,
                        `screenOffMs` INTEGER NOT NULL,
                        `deepSleepMs` INTEGER NOT NULL,
                        `wearCycles` REAL,
                        `isComplete` INTEGER NOT NULL,
                        `startCounterUah` INTEGER,
                        `endCounterUah` INTEGER,
                        `integratedUah` INTEGER,
                        `designCapacityMah` INTEGER,
                        `lastSampleElapsedMs` INTEGER,
                        `lastSampleUptimeMs` INTEGER
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `charge_events` ADD COLUMN `lastSampleWallMs` INTEGER")
            }
        }

        fun getDatabase(context: Context): HistoryInfoDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    HistoryInfoDatabase::class.java,
                    "history_info_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration(false)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
