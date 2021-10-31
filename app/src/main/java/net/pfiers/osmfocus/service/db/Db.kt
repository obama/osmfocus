package net.pfiers.osmfocus.service.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    version = 3,
    entities = [UserBaseMap::class, KeyMeta::class, TagMeta::class],
    autoMigrations = [
        AutoMigration(from = 2, to = 3)
    ],
    exportSchema = true
)
abstract class Db : RoomDatabase() {
    abstract fun baseMapDefinitionDao(): BaseMapDefinitionDao
    abstract fun wikiPageDao(): TagMetaDao

    companion object {
        @Volatile
        private var INSTANCE: Db? = null
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `key_meta` (`key` TEXT NOT NULL, `highestValueFraction` REAL NOT NULL, `wikiPageLanguagesJson` TEXT NOT NULL, PRIMARY KEY(`key`))"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tag_meta` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, `wikiPageLanguagesJson` TEXT NOT NULL, PRIMARY KEY(`key`, `value`), FOREIGN KEY(`key`) REFERENCES `key_meta`(`key`) ON UPDATE NO ACTION ON DELETE RESTRICT )"
                )
            }
        }

        fun getDatabase(context: Context): Db {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    Db::class.java,
                    "osmfocus"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
