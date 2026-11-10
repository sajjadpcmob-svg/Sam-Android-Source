package ir.elviran.sam.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ir.elviran.sam.data.dynamic.DynamicDao
import ir.elviran.sam.data.dynamic.DynamicModuleEntity
import ir.elviran.sam.data.dynamic.DynamicFieldEntity
import ir.elviran.sam.data.dynamic.DynamicRecordEntity
import kotlinx.coroutines.flow.Flow

data class SamSnapshot(
    val notes: List<NoteEntity> = emptyList(), val tasks: List<TaskEntity> = emptyList(),
    val money: List<MoneyEntryEntity> = emptyList(), val factories: List<FactoryEntity> = emptyList(),
    val lines: List<ProductionLineEntity> = emptyList(), val machines: List<MachineEntity> = emptyList(),
    val orders: List<ProductionOrderEntity> = emptyList(), val memories: List<MemoryEntity> = emptyList(),
    val documents: List<DocumentEntity> = emptyList(), val audit: List<AuditEntity> = emptyList(),
    val chatMessages: List<ChatMessageEntity> = emptyList()
)

@Dao interface SamDao {
    @Query("SELECT * FROM chat_messages ORDER BY createdAt ASC, id ASC") fun chatMessages(): Flow<List<ChatMessageEntity>>
    @Query("SELECT * FROM chat_messages ORDER BY createdAt ASC, id ASC") suspend fun allChatMessages(): List<ChatMessageEntity>
    @Insert suspend fun addChatMessage(item: ChatMessageEntity): Long
    @Query("DELETE FROM chat_messages") suspend fun clearChatMessages()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putChatMessages(items: List<ChatMessageEntity>)

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC") fun notes(): Flow<List<NoteEntity>>
    @Insert suspend fun addNote(item: NoteEntity): Long
    @Update suspend fun updateNote(item: NoteEntity)
    @Delete suspend fun deleteNote(item: NoteEntity)

    @Query("SELECT * FROM tasks ORDER BY done ASC, dueAt ASC") fun tasks(): Flow<List<TaskEntity>>
    @Insert suspend fun addTask(item: TaskEntity): Long
    @Update suspend fun updateTask(item: TaskEntity)
    @Delete suspend fun deleteTask(item: TaskEntity)

    @Query("SELECT * FROM money_entries ORDER BY occurredAt DESC") fun moneyEntries(): Flow<List<MoneyEntryEntity>>
    @Insert suspend fun addMoneyEntry(item: MoneyEntryEntity): Long
    @Update suspend fun updateMoneyEntry(item: MoneyEntryEntity)
    @Delete suspend fun deleteMoneyEntry(item: MoneyEntryEntity)

    @Query("SELECT * FROM factories ORDER BY name") fun factories(): Flow<List<FactoryEntity>>
    @Insert suspend fun addFactory(item: FactoryEntity): Long
    @Update suspend fun updateFactory(item: FactoryEntity)
    @Delete suspend fun deleteFactory(item: FactoryEntity)

    @Query("SELECT * FROM production_lines ORDER BY name") fun lines(): Flow<List<ProductionLineEntity>>
    @Insert suspend fun addLine(item: ProductionLineEntity): Long
    @Update suspend fun updateLine(item: ProductionLineEntity)
    @Delete suspend fun deleteLine(item: ProductionLineEntity)

    @Query("SELECT * FROM machines ORDER BY name") fun machines(): Flow<List<MachineEntity>>
    @Insert suspend fun addMachine(item: MachineEntity): Long
    @Update suspend fun updateMachine(item: MachineEntity)
    @Delete suspend fun deleteMachine(item: MachineEntity)

    @Query("SELECT * FROM production_orders ORDER BY dueAt") fun orders(): Flow<List<ProductionOrderEntity>>
    @Insert suspend fun addOrder(item: ProductionOrderEntity): Long
    @Update suspend fun updateOrder(item: ProductionOrderEntity)
    @Delete suspend fun deleteOrder(item: ProductionOrderEntity)

    @Query("SELECT * FROM memories ORDER BY importance DESC, createdAt DESC") fun memories(): Flow<List<MemoryEntity>>
    @Insert suspend fun addMemory(item: MemoryEntity): Long
    @Update suspend fun updateMemory(item: MemoryEntity)
    @Delete suspend fun deleteMemory(item: MemoryEntity)
    @Query("SELECT * FROM memories WHERE content LIKE '%' || :term || '%' OR category LIKE '%' || :term || '%' ORDER BY importance DESC") suspend fun searchMemory(term: String): List<MemoryEntity>

    @Query("SELECT * FROM documents ORDER BY addedAt DESC") fun documents(): Flow<List<DocumentEntity>>
    @Insert suspend fun addDocument(item: DocumentEntity): Long
    @Delete suspend fun deleteDocument(item: DocumentEntity)

    @Insert suspend fun log(item: AuditEntity)
    @Query("SELECT * FROM audit_log ORDER BY createdAt DESC LIMIT 300") fun audit(): Flow<List<AuditEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putNotes(items: List<NoteEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTasks(items: List<TaskEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMoney(items: List<MoneyEntryEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putFactories(items: List<FactoryEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putLines(items: List<ProductionLineEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMachines(items: List<MachineEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putOrders(items: List<ProductionOrderEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMemories(items: List<MemoryEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDocuments(items: List<DocumentEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAudit(items: List<AuditEntity>)

    @Query("DELETE FROM notes") suspend fun clearNotes()
    @Query("DELETE FROM tasks") suspend fun clearTasks()
    @Query("DELETE FROM money_entries") suspend fun clearMoney()
    @Query("DELETE FROM factories") suspend fun clearFactories()
    @Query("DELETE FROM production_lines") suspend fun clearLines()
    @Query("DELETE FROM machines") suspend fun clearMachines()
    @Query("DELETE FROM production_orders") suspend fun clearOrders()
    @Query("DELETE FROM memories") suspend fun clearMemories()
    @Query("DELETE FROM documents") suspend fun clearDocuments()
    @Query("DELETE FROM audit_log") suspend fun clearAudit()

    @Transaction
    suspend fun replaceAll(snapshot: SamSnapshot) {
        clearOrders(); clearMachines(); clearLines(); clearFactories()
        clearNotes(); clearTasks(); clearMoney(); clearMemories(); clearDocuments(); clearAudit(); clearChatMessages()
        putFactories(snapshot.factories); putLines(snapshot.lines); putMachines(snapshot.machines); putOrders(snapshot.orders)
        putNotes(snapshot.notes); putTasks(snapshot.tasks); putMoney(snapshot.money); putMemories(snapshot.memories)
        putDocuments(snapshot.documents); putAudit(snapshot.audit); putChatMessages(snapshot.chatMessages)
    }
}

@Database(
    entities = [
        NoteEntity::class, TaskEntity::class, MoneyEntryEntity::class, FactoryEntity::class,
        ProductionLineEntity::class, MachineEntity::class, ProductionOrderEntity::class,
        MemoryEntity::class, DocumentEntity::class, AuditEntity::class, ChatMessageEntity::class,
        DynamicModuleEntity::class, DynamicFieldEntity::class, DynamicRecordEntity::class,
    ],
    version = 5,
    exportSchema = true
)
abstract class SamDatabase : RoomDatabase() {
    abstract fun dao(): SamDao
    abstract fun dynamicDao(): DynamicDao
    companion object {
        /**
         * v1 -> v2: add the Dynamic Data Engine tables. Existing user data is preserved;
         * only new tables and indices are created. Safe and versioned (no destructive fallback).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dyn_modules` (" +
                        "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "`name` TEXT NOT NULL, `icon` TEXT NOT NULL, `parentId` INTEGER, " +
                        "`position` INTEGER NOT NULL, `active` INTEGER NOT NULL, " +
                        "`description` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dyn_modules_parentId` ON `dyn_modules` (`parentId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dyn_fields` (" +
                        "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "`moduleId` INTEGER NOT NULL, `name` TEXT NOT NULL, `typeCode` TEXT NOT NULL, " +
                        "`required` INTEGER NOT NULL, `position` INTEGER NOT NULL, `active` INTEGER NOT NULL, " +
                        "`optionsJson` TEXT NOT NULL, `minValue` REAL, `maxValue` REAL, " +
                        "`defaultValue` TEXT NOT NULL, `computeFormula` TEXT NOT NULL, " +
                        "`relatedModuleId` INTEGER, `helpText` TEXT NOT NULL, " +
                        "FOREIGN KEY(`moduleId`) REFERENCES `dyn_modules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dyn_fields_moduleId` ON `dyn_fields` (`moduleId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dyn_records` (" +
                        "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "`moduleId` INTEGER NOT NULL, `valuesJson` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`moduleId`) REFERENCES `dyn_modules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dyn_records_moduleId` ON `dyn_records` (`moduleId`)")
            }
        }

        /**
         * v2 -> v3: add the HR tables (employees + salary scales). Additive and non-destructive.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `employees` (" +
                        "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "`fullName` TEXT NOT NULL, `nationalId` TEXT NOT NULL, `role` TEXT NOT NULL, " +
                        "`hall` TEXT NOT NULL, `hireDateJalali` TEXT NOT NULL, `hasSeniority` INTEGER NOT NULL, " +
                        "`children` INTEGER NOT NULL, `active` INTEGER NOT NULL, `phone` TEXT NOT NULL, `note` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `salary_scales` (" +
                        "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "`title` TEXT NOT NULL, `yearJalali` INTEGER NOT NULL, `dailyBaseWage` REAL NOT NULL, " +
                        "`senioritySumPerDay` REAL NOT NULL, `housingAllowance` REAL NOT NULL, " +
                        "`foodAllowance` REAL NOT NULL, `childAllowancePerChild` REAL NOT NULL, " +
                        "`maxChildrenForAllowance` INTEGER NOT NULL)"
                )
            }
        }

        /** v3 -> v4: persist conversation history without changing existing records. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `chat_messages` (`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
            }
        }

        /** v4 -> v5: SAM is a single-user personal/factory-production assistant. Remove legacy HR tables. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `employees`")
                db.execSQL("DROP TABLE IF EXISTS `salary_scales`")
            }
        }

        @Volatile private var INSTANCE: SamDatabase? = null
        fun get(context: android.content.Context): SamDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, SamDatabase::class.java, "sam_local.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build().also { INSTANCE = it }
        }
    }
}
