package com.logscope.ai

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "log_entries", indices = [Index("timestamp"), Index("level"), Index("tag")])
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val level: String,
    val pid: String,
    val tid: String,
    val tag: String,
    val message: String,
    val raw: String,
    val category: String
)

@Dao interface LogDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(entries: List<LogEntity>)
    @Query("SELECT * FROM log_entries ORDER BY timestamp DESC LIMIT 3000") fun observeRecent(): Flow<List<LogEntity>>
    @Query("SELECT * FROM log_entries ORDER BY timestamp DESC LIMIT 3000") suspend fun getRecent(): List<LogEntity>
    @Query("SELECT COUNT(*) FROM log_entries") fun count(): Flow<Int>
    @Query("DELETE FROM log_entries WHERE id NOT IN (SELECT id FROM log_entries ORDER BY timestamp DESC LIMIT 10000)") suspend fun trim()
    @Query("DELETE FROM log_entries") suspend fun clear()
}

@Database(entities = [LogEntity::class], version = 1, exportSchema = false)
abstract class LogDatabase : RoomDatabase() {
    abstract fun logs(): LogDao
    companion object {
        @Volatile private var INSTANCE: LogDatabase? = null
        fun get(context: Context): LogDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, LogDatabase::class.java, "logscope.db")
                .fallbackToDestructiveMigration().build().also { INSTANCE = it }
        }
    }
}
