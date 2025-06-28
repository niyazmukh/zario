package com.niyaz.zario.data.local


import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey


/**
 * Represents a single record of app usage duration within a specific interval.
 */
@Entity(tableName = "usage_stats") // Use the actual table name
data class UsageStatEntity(
   @PrimaryKey(autoGenerate = true)
   val id: Long = 0, // Keep primary key


   // Add userId to associate record with the logged-in user
   @ColumnInfo(index = true) // Index userId for faster queries in the worker
   val userId: String,


   val packageName: String, // Package name of the app used


   // Store duration in milliseconds for precision
   val durationMs: Long,


   // Store the start and end timestamps of the interval during which this duration occurred
   val intervalStartTimestamp: Long,
   val intervalEndTimestamp: Long,


   // Add a timestamp representing the start of the day (00:00:00) for easy daily aggregation
   val dayTimestamp: Long, // Keep dayTimestamp for potential daily queries


   // Add flag to track if this record has been synced to Firestore
   @ColumnInfo(defaultValue = "0") // Default to not synced (0 for false)
   val isSynced: Boolean = false
   // We could add a userId later if needed for multi-user support, but for now assume single user -> This comment is now outdated by the addition of userId
)

