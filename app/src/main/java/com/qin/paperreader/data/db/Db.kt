package com.qin.paperreader.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val fileName: String,
    val charset: String,
    val sizeBytes: Long,
    val charCount: Int,
    val chapterCount: Int,
    val addedAt: Long,
    val lastReadAt: Long,
    val lastOffset: Int,
    /** 自定义封面文件名（存放在应用私有 covers/ 目录），null = 用生成的默认封面。 */
    val coverPath: String? = null,
)

@Entity(tableName = "bookmarks", indices = [androidx.room.Index("bookId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val offset: Int,
    val preview: String,
    val note: String,
    val createdAt: Long,
)

@Dao
interface BookDao {
    @Query("SELECT * FROM books")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books")
    suspend fun getAll(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity)

    @Query("UPDATE books SET lastOffset = :offset, lastReadAt = :at WHERE id = :id")
    suspend fun updateProgress(id: String, offset: Int, at: Long)

    @Query("UPDATE books SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("UPDATE books SET charset = :charset, charCount = :charCount, chapterCount = :chapterCount WHERE id = :id")
    suspend fun updateEncoding(id: String, charset: String, charCount: Int, chapterCount: Int)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY offset ASC")
    fun observeForBook(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks")
    suspend fun getAll(): List<BookmarkEntity>

    @Insert
    suspend fun add(b: BookmarkEntity): Long

    @Update
    suspend fun update(b: BookmarkEntity)

    @Delete
    suspend fun delete(b: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)
}

@Database(entities = [BookEntity::class, BookmarkEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        /** v1 → v2：书架新增自定义封面列（保留用户数据，不破坏迁移）。 */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN coverPath TEXT")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "paper_reader.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
    }
}
