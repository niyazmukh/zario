package com.niyaz.zario.data.local


import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration // Import Migration
import androidx.sqlite.db.SupportSQLiteDatabase // Import SupportSQLiteDatabase




/**
 * The Room database for the application.
 * Contains the usage_stats table.
 */
@Database(
    entities = [UsageStatEntity::class], // List all entities (tables)
    version = 2,                          // <<< INCREMENT VERSION to 2
    exportSchema = true                  // Set true to export schema for version control/migration testing
)
abstract class AppDatabase : RoomDatabase() {


    // Abstract function to get the DAO for the UsageStatEntity table
    abstract fun usageStatDao(): UsageStatDao


    // Companion object to provide a singleton instance of the database
    companion object {
        // Volatile ensures visibility of writes across threads
        @Volatile
        private var INSTANCE: AppDatabase? = null


        fun getDatabase(context: Context): AppDatabase {
            // Return existing instance if available
            // Otherwise, create a new database instance synchronized to avoid race conditions
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext, // Use application context
                    AppDatabase::class.java,
                    "zario_app_database" // Name of the database file
                )
                    // *** ADD MIGRATION from version 1 to 2 ***
                    .addMigrations(MIGRATION_1_2)
                    // to prevent data loss or app crashes.
                    .build()
                INSTANCE = instance
                // Return the newly created instance
                instance
            }
        }


        // --- Migration from version 1 to 2 (Table Recreation Strategy) ---
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.d("AppDatabaseMigration", "Running Migration 1 -> 2 (Recreate Table)")


                // 1. Create the new table with the target schema (V2)
                //    Ensure column names and types match UsageStatEntity exactly.
                //    Define constraints (PRIMARY KEY, NOT NULL) as expected by Room.
                db.execSQL("""
                   CREATE TABLE usage_stats_new (
                       id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                       userId TEXT NOT NULL,
                       packageName TEXT NOT NULL,
                       durationMs INTEGER NOT NULL,
                       intervalStartTimestamp INTEGER NOT NULL,
                       intervalEndTimestamp INTEGER NOT NULL,
                       dayTimestamp INTEGER NOT NULL,
                       isSynced INTEGER NOT NULL DEFAULT 0
                   )
               """.trimIndent())
                Log.d("AppDatabaseMigration", "Created usage_stats_new table")


                // 2. Copy data from the old table (usage_stats) to the new table (usage_stats_new)
                //    Provide a sensible default for the new 'userId' column for any existing V1 rows.
                //    Since data should be cleared, this might not copy anything, but it's robust.
                //    Use a placeholder like 'unknown_migrated' or an empty string.
                //    Set isSynced to 0 for all migrated rows.
                db.execSQL("""
                   INSERT INTO usage_stats_new (id, userId, packageName, durationMs, intervalStartTimestamp, intervalEndTimestamp, dayTimestamp, isSynced)
                   SELECT id, '', packageName, durationMs, intervalStartTimestamp, intervalEndTimestamp, dayTimestamp, 0
                   FROM usage_stats
               """.trimIndent())
                // Note: Using '' for userId default during copy. App logic should handle this.
                Log.d("AppDatabaseMigration", "Copied data from usage_stats to usage_stats_new")


                // 3. Drop the old table (V1)
                db.execSQL("DROP TABLE usage_stats")
                Log.d("AppDatabaseMigration", "Dropped old usage_stats table")


                // 4. Rename the new table to the original name
                db.execSQL("ALTER TABLE usage_stats_new RENAME TO usage_stats")
                Log.d("AppDatabaseMigration", "Renamed usage_stats_new to usage_stats")


                // 5. Re-create indices needed for V2 on the final table
                //    Index on userId is needed.
                db.execSQL("CREATE INDEX IF NOT EXISTS index_usage_stats_userId ON usage_stats(userId)")
                //    The old index on (dayTimestamp, packageName) is NOT recreated as it's not in the V2 entity definition.
                Log.d("AppDatabaseMigration", "Recreated indices on new usage_stats table")


                Log.d("AppDatabaseMigration", "Migration 1 -> 2 (Recreate Table) Finished")
            }
        }
        // --- End Migration 1-2 ---
    }
}

