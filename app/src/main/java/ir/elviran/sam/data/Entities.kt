package ir.elviran.sam.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notes") data class NoteEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val title: String, val body: String, val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = System.currentTimeMillis())
@Entity(tableName = "tasks") data class TaskEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val title: String, val details: String = "", val dueAt: Long? = null, val done: Boolean = false, val createdAt: Long = System.currentTimeMillis())
@Entity(tableName = "money_entries") data class MoneyEntryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val title: String, val amount: Long, val kind: String, val category: String, val occurredAt: Long = System.currentTimeMillis(), val note: String = "")
@Entity(tableName = "factories") data class FactoryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val location: String = "", val description: String = "")
@Entity(tableName = "production_lines") data class ProductionLineEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val factoryId: Long, val name: String, val capacityPerDay: Double = 0.0, val unit: String = "kg", val active: Boolean = true)
@Entity(tableName = "machines") data class MachineEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val lineId: Long, val name: String, val code: String = "", val status: String = "فعال", val capacity: Double = 0.0)
@Entity(tableName = "production_orders") data class ProductionOrderEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val lineId: Long, val product: String, val plannedQuantity: Double, val actualQuantity: Double = 0.0, val startAt: Long? = null, val dueAt: Long? = null, val status: String = "برنامه‌ریزی‌شده")
@Entity(tableName = "memories") data class MemoryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val content: String, val category: String, val importance: Int = 3, val createdAt: Long = System.currentTimeMillis(), val source: String = "کاربر")
@Entity(tableName = "documents") data class DocumentEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val uri: String, val mimeType: String = "application/octet-stream", val addedAt: Long = System.currentTimeMillis(), val tags: String = "")
@Entity(tableName = "audit_log") data class AuditEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val action: String, val target: String, val detail: String, val createdAt: Long = System.currentTimeMillis(), val requiresApproval: Boolean = false)

@Entity(tableName = "chat_messages") data class ChatMessageEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val role: String, val content: String, val createdAt: Long = System.currentTimeMillis())
