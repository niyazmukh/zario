package com.niyaz.zario.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The main Room database definition for the Zario application.
 * Manages the persistence of local application data, currently including usage statistics.
 * Provides Data Access Objects (DAOs) for interacting with the database tables.
 * Includes migration strategies for handling schema updates across app versions.
 *
 * @see UsageStatEntity The entity representing usage statistics records.
 * @see UsageStatDao The Data Access Object for usage statistics.
 */
@Database(
    entities = [UsageStatEntity::class], // List all entities (tables)
    version = 3,                          // <<< INCREMENT VERSION to 3
    exportSchema = true                  // Keep schema export enabled
)
abstract class AppDatabase : RoomDatabase() {

    /** Abstract function to provide access to the UsageStatDao. */
    abstract fun usageStatDao(): UsageStatDao

    /**
     * Companion object providing a singleton instance of the [AppDatabase].
     * Includes database creation logic and migration definitions.
     */
    companion object {
        private const val DATABASE_NAME = "zario_app_database"
        private const val TAG = "AppDatabase"

        // Volatile annotation ensures that writes to this field are immediately visible to other threads.
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Gets the singleton instance of the [AppDatabase].
         * Creates the database if it doesn't exist yet, applying necessary migrations.
         *
         * @param context The application context.
         * @return The singleton [AppDatabase] instance.
         */
        fun getDatabase(context: Context): AppDatabase {
            // Return existing instance if available (double-checked locking pattern)
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        /** Builds the Room database instance with migrations. */
        private fun buildDatabase(context: Context): AppDatabase {
            Log.d(TAG, "Building database instance...")
            return Room.databaseBuilder(
                context.applicationContext, // Use application context to prevent leaks
                AppDatabase::class.java,
                DATABASE_NAME // Name of the database file
            )
                // Add all migrations needed to reach the current version
                .addMigrations(
                    MIGRATION_1_2, // Handles adding userId and isSynced columns
                    MIGRATION_2_3  // <<< ADDED: Handles adding the composite index
                )
                // Note: Removed fallbackToDestructiveMigration() to enforce proper migrations.
                // If a migration is missing for a version jump, the app will crash,
                // prompting the developer to add the required migration path.
                .build()
                .also { Log.d(TAG, "Database instance built successfully.") }
        }

        // --- Migration from version 1 to 2 (Table Recreation Strategy) ---
        // Handles adding userId (TEXT NOT NULL) and isSynced (INTEGER NOT NULL DEFAULT 0) columns.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i("AppDatabaseMigration", "Running Migration 1 -> 2 (Recreate Table)")
                // 1. Create the new table with V2 schema
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
                // 2. Copy data (if any existed, unlikely in V1)
                db.execSQL("""
                    INSERT INTO usage_stats_new (id, userId, packageName, durationMs, intervalStartTimestamp, intervalEndTimestamp, dayTimestamp, isSynced)
                    SELECT id, '', packageName, durationMs, intervalStartTimestamp, intervalEndTimestamp, dayTimestamp, 0
                    FROM usage_stats
                """.trimIndent()) // Use empty string as default for userId if needed
                // 3. Drop the old table
                db.execSQL("DROP TABLE usage_stats")
                // 4. Rename the new table
                db.execSQL("ALTER TABLE usage_stats_new RENAME TO usage_stats")
                // 5. Re-create indices needed for V2 (index on userId)
                // Note: V2 entity now defines this index via @Entity, so explicit creation here might be redundant
                // depending on Room's internal handling, but being explicit is safer during manual migration.
                db.execSQL("CREATE INDEX IF NOT EXISTS index_usage_stats_userId ON usage_stats(userId)")
                Log.i("AppDatabaseMigration", "Migration 1 -> 2 Finished")
            }
        }

        // --- Migration from version 2 to 3 (Adding Index) ---
        // Handles adding the composite index on (userId, isSynced) for sync worker efficiency.
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i("AppDatabaseMigration", "Running Migration 2 -> 3 (Add Index)")
                // SQL command to create the composite index.
                // "IF NOT EXISTS" provides safety against re-running the migration.
                db.execSQL("CREATE INDEX IF NOT EXISTS index_usage_stats_userId_isSynced ON usage_stats (userId, isSynced)")
                Log.i("AppDatabaseMigration", "Migration 2 -> 3 Finished (Index added)")
            }
        }
        // --- End Migration Definitions ---
    }
}