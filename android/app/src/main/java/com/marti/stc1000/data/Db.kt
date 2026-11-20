package com.marti.stc1000.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface SampleDao {
    /** REPLACE = ante el mismo ts gana la muestra nueva (deduplicación). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(samples: List<Sample>)

    @Query("SELECT * FROM samples WHERE ts >= :from ORDER BY ts ASC")
    fun observeSince(from: Long): Flow<List<Sample>>

    @Query("SELECT * FROM samples ORDER BY ts DESC LIMIT 1")
    fun observeLast(): Flow<Sample?>

    @Query("SELECT * FROM samples ORDER BY ts DESC LIMIT 1")
    suspend fun last(): Sample?

    @Query("DELETE FROM samples WHERE ts < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Database(entities = [Sample::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun samples(): SampleDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "stc1000.db")
                .build()
                .also { instance = it }
        }
    }
}
