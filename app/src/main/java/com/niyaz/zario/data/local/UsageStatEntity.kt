package com.niyaz.zario.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index // Ensure Index is imported if using @Index separately
import androidx.room.PrimaryKey

/**
 * Represents a single record of application usage duration stored in the local Room database.
 * Each entity captures the time spent in a specific app during a particular interval,
 * associated with a specific user, and tracks its synchronization status with Firestore.
 *
 * @property id Auto-generated primary key for the database record.
 * @property userId The unique identifier (Firebase Auth UID) of the user this record belongs to. Indexed for efficient retrieval.
 * @property packageName The package name of the application that was used (e.g., "com.example.app").
 * @property durationMs The duration, in milliseconds, for which the app was used during the defined interval.
 * @property intervalStartTimestamp The start timestamp (epoch milliseconds) of the interval during which usage was tracked.
 * @property intervalEndTimestamp The end timestamp (epoch milliseconds) of the interval during which usage was tracked.
 * @property dayTimestamp The timestamp (epoch milliseconds) representing the start (00:00:00) of the day this usage occurred on. Used for daily aggregation.
 * @property isSynced Flag indicating whether this specific record has been successfully aggregated and synced to Firestore. `false` (0) means unsynced, `true` (1) means synced.
 */
@Entity(
    tableName = "usage_stats",
    // Add index explicitly within the @Entity annotation for clarity
    indices = [Index(value = ["userId"]),
    Index(value = ["userId", "isSynced"])
    ]
)
data class UsageStatEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Removed explicit @ColumnInfo(index = true) here as it's defined in @Entity.indices above
    val userId: String,

    val packageName: String,

    val durationMs: Long,

    val intervalStartTimestamp: Long,
    val intervalEndTimestamp: Long,

    // Consider adding an index if frequently querying by dayTimestamp AND userId
    // indices = [Index(value = ["userId"]), Index(value = ["dayTimestamp", "userId"])]
    val dayTimestamp: Long,

    // Default value handled by Room if column added via migration,
    // but good practice to set default in data class too.
    // Room uses INTEGER type for Boolean, 0 for false, 1 for true.
    @ColumnInfo(defaultValue = "0")
    val isSynced: Boolean = false
)