package com.niyaz.zario.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The Room database for the application.
 * Contains the usage_stats table.
 */
@Database(
    entities = [UsageStatEntity::class], // List all entities (tables)
    version = 1,                          // Increment version on schema changes
    exportSchema = false                  // Set true to export schema for version control/migration testing
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
                    // Add migration strategies here if needed when increasing version
                    // .addMigrations(...)
                    // Consider fallbackToDestructiveMigration() only during development
                    .fallbackToDestructiveMigration() // DELETES old data on version mismatch! Use with caution.
                    .build()
                INSTANCE = instance
                // Return the newly created instance
                instance
            }
        }
    }
}