package ir.elviran.sam.data.dynamic

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DynamicDao {
    // ---- Modules ----
    @Query("SELECT * FROM dyn_modules ORDER BY position ASC, name ASC")
    fun modules(): Flow<List<DynamicModuleEntity>>

    @Query("SELECT * FROM dyn_modules WHERE parentId IS :parent ORDER BY position ASC, name ASC")
    fun modulesOfParent(parent: Long?): Flow<List<DynamicModuleEntity>>

    @Insert suspend fun addModule(item: DynamicModuleEntity): Long
    @Update suspend fun updateModule(item: DynamicModuleEntity)
    @Delete suspend fun deleteModule(item: DynamicModuleEntity)

    // ---- Fields ----
    @Query("SELECT * FROM dyn_fields WHERE moduleId = :moduleId ORDER BY position ASC, id ASC")
    fun fieldsOf(moduleId: Long): Flow<List<DynamicFieldEntity>>

    @Query("SELECT * FROM dyn_fields WHERE moduleId = :moduleId ORDER BY position ASC, id ASC")
    suspend fun fieldsOfNow(moduleId: Long): List<DynamicFieldEntity>

    @Insert suspend fun addField(item: DynamicFieldEntity): Long
    @Update suspend fun updateField(item: DynamicFieldEntity)
    @Delete suspend fun deleteField(item: DynamicFieldEntity)

    @Transaction
    suspend fun reorderFields(ordered: List<DynamicFieldEntity>) {
        ordered.forEachIndexed { index, f -> updateField(f.copy(position = index)) }
    }

    // ---- Records ----
    @Query("SELECT * FROM dyn_records WHERE moduleId = :moduleId ORDER BY updatedAt DESC")
    fun recordsOf(moduleId: Long): Flow<List<DynamicRecordEntity>>

    @Query("SELECT * FROM dyn_records WHERE moduleId = :moduleId ORDER BY updatedAt DESC")
    suspend fun recordsOfNow(moduleId: Long): List<DynamicRecordEntity>

    @Insert suspend fun addRecord(item: DynamicRecordEntity): Long
    @Update suspend fun updateRecord(item: DynamicRecordEntity)
    @Delete suspend fun deleteRecord(item: DynamicRecordEntity)

    @Query("SELECT COUNT(*) FROM dyn_records WHERE moduleId = :moduleId")
    suspend fun recordCount(moduleId: Long): Int

    // ---- Backup support ----
    @Query("SELECT * FROM dyn_modules") suspend fun allModules(): List<DynamicModuleEntity>
    @Query("SELECT * FROM dyn_fields") suspend fun allFields(): List<DynamicFieldEntity>
    @Query("SELECT * FROM dyn_records") suspend fun allRecords(): List<DynamicRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putModules(items: List<DynamicModuleEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putFields(items: List<DynamicFieldEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRecords(items: List<DynamicRecordEntity>)

    @Query("DELETE FROM dyn_modules") suspend fun clearModules()
    @Query("DELETE FROM dyn_fields") suspend fun clearFields()
    @Query("DELETE FROM dyn_records") suspend fun clearRecords()
}
