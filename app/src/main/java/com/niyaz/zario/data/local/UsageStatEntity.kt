package com.niyaz.zario.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

/**
 * Represents a record of app usage duration within a specific time interval.
 */
@Entity(
    tableName = "usage_stats",
    // Add indices for columns frequently used in queries (e.g., day and package name)
    indices = [Index(value = ["dayTimestamp", "packageName"])]
)
data class UsageStatEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0, // Auto-generated primary key

    val packageName: String, // Package name of the app used

    // Store duration in milliseconds for precision
    val durationMs: Long,

    // Store the start and end timestamps of the interval during which this duration occurred
    val intervalStartTimestamp: Long,
    val intervalEndTimestamp: Long,

    // Add a timestamp representing the start of the day (00:00:00) for easy daily aggregation
    val dayTimestamp: Long
    // We could add a userId later if needed for multi-user support, but for now assume single user
)