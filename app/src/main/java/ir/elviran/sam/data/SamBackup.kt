package ir.elviran.sam.data

import android.content.Context
import android.net.Uri
import ir.elviran.sam.data.dynamic.DynamicDao
import ir.elviran.sam.data.dynamic.DynamicFieldEntity
import ir.elviran.sam.data.dynamic.DynamicModuleEntity
import ir.elviran.sam.data.dynamic.DynamicRecordEntity
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.first
import androidx.room.withTransaction

/** Versioned, validated JSON backup. Restore is one Room transaction; malformed files never clear data. */
object SamBackup {
    private const val FORMAT = "SAM_BACKUP_V3"
    // NOTE: V3 is retained for backward compatibility. Legacy HR fields in older backups are ignored.
    private const val MAX_BYTES = 25 * 1024 * 1024

    suspend fun export(context: Context, uri: Uri, dao: SamDao, dyn: DynamicDao) {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("createdAt", System.currentTimeMillis())
            .put("notes", JSONArray(dao.notes().firstList().map { JSONObject().put("id",it.id).put("title",it.title).put("body",it.body).put("createdAt",it.createdAt).put("updatedAt",it.updatedAt) }))
            .put("tasks", JSONArray(dao.tasks().firstList().map { JSONObject().put("id",it.id).put("title",it.title).put("details",it.details).put("dueAt",it.dueAt ?: JSONObject.NULL).put("done",it.done).put("createdAt",it.createdAt) }))
            .put("money", JSONArray(dao.moneyEntries().firstList().map { JSONObject().put("id",it.id).put("title",it.title).put("amount",it.amount).put("kind",it.kind).put("category",it.category).put("occurredAt",it.occurredAt).put("note",it.note) }))
            .put("factories", JSONArray(dao.factories().firstList().map { JSONObject().put("id",it.id).put("name",it.name).put("location",it.location).put("description",it.description) }))
            .put("lines", JSONArray(dao.lines().firstList().map { JSONObject().put("id",it.id).put("factoryId",it.factoryId).put("name",it.name).put("capacityPerDay",it.capacityPerDay).put("unit",it.unit).put("active",it.active) }))
            .put("machines", JSONArray(dao.machines().firstList().map { JSONObject().put("id",it.id).put("lineId",it.lineId).put("name",it.name).put("code",it.code).put("status",it.status).put("capacity",it.capacity) }))
            .put("orders", JSONArray(dao.orders().firstList().map { JSONObject().put("id",it.id).put("lineId",it.lineId).put("product",it.product).put("plannedQuantity",it.plannedQuantity).put("actualQuantity",it.actualQuantity).put("startAt",it.startAt ?: JSONObject.NULL).put("dueAt",it.dueAt ?: JSONObject.NULL).put("status",it.status) }))
            .put("memories", JSONArray(dao.memories().firstList().map { JSONObject().put("id",it.id).put("content",it.content).put("category",it.category).put("importance",it.importance).put("createdAt",it.createdAt).put("source",it.source) }))
            .put("documents", JSONArray(dao.documents().firstList().map { JSONObject().put("id",it.id).put("name",it.name).put("uri",it.uri).put("mimeType",it.mimeType).put("addedAt",it.addedAt).put("tags",it.tags) }))
            .put("audit", JSONArray(dao.audit().firstList().map { JSONObject().put("id",it.id).put("action",it.action).put("target",it.target).put("detail",it.detail).put("createdAt",it.createdAt).put("requiresApproval",it.requiresApproval) }))
            .put("chatMessages", JSONArray(dao.allChatMessages().map { JSONObject().put("id",it.id).put("role",it.role).put("content",it.content).put("createdAt",it.createdAt) }))
            .put("dynModules", JSONArray(dyn.allModules().map { JSONObject().put("id",it.id).put("name",it.name).put("icon",it.icon).put("parentId",it.parentId ?: JSONObject.NULL).put("position",it.position).put("active",it.active).put("description",it.description).put("createdAt",it.createdAt) }))
            .put("dynFields", JSONArray(dyn.allFields().map { JSONObject().put("id",it.id).put("moduleId",it.moduleId).put("name",it.name).put("typeCode",it.typeCode).put("required",it.required).put("position",it.position).put("active",it.active).put("optionsJson",it.optionsJson).put("minValue",it.minValue ?: JSONObject.NULL).put("maxValue",it.maxValue ?: JSONObject.NULL).put("defaultValue",it.defaultValue).put("computeFormula",it.computeFormula).put("relatedModuleId",it.relatedModuleId ?: JSONObject.NULL).put("helpText",it.helpText) }))
            .put("dynRecords", JSONArray(dyn.allRecords().map { JSONObject().put("id",it.id).put("moduleId",it.moduleId).put("valuesJson",it.valuesJson).put("createdAt",it.createdAt).put("updatedAt",it.updatedAt) }))
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(root.toString(2)) }
            ?: error("امکان نوشتن فایل پشتیبان وجود ندارد")
    }

    suspend fun restore(context: Context, uri: Uri, database: SamDatabase) {
        val dao = database.dao()
        val dyn = database.dynamicDao()
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0
            while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= MAX_BYTES) { "حجم فایل بیش از حد مجاز است" }; out.write(buffer, 0, n) }
            out.toByteArray()
        } ?: error("فایل انتخاب‌شده قابل خواندن نیست")
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.optString("format") == FORMAT) { "نسخه یا قالب فایل پشتیبان معتبر نیست" }
        val requiredArrays = listOf("notes", "tasks", "money", "factories", "lines", "machines", "orders", "memories", "documents", "audit")
        require(requiredArrays.all { root.opt(it) is JSONArray }) { "فایل پشتیبان ناقص است؛ هیچ داده‌ای تغییر نکرد" }
        fun arr(key: String) = root.getJSONArray(key).also { require(it.length() <= 100_000) { "تعداد رکوردهای $key بیش از حد مجاز است" } }
        fun JSONArray.objects(block: (JSONObject) -> Unit) { for (i in 0 until length()) block(getJSONObject(i)) }
        val notes = mutableListOf<NoteEntity>(); arr("notes").objects { notes += NoteEntity(it.getLong("id"),it.getString("title"),it.optString("body"),it.optLong("createdAt"),it.optLong("updatedAt")) }
        val tasks = mutableListOf<TaskEntity>(); arr("tasks").objects { tasks += TaskEntity(it.getLong("id"),it.getString("title"),it.optString("details"),if(it.isNull("dueAt")) null else it.optLong("dueAt"),it.optBoolean("done"),it.optLong("createdAt")) }
        val money = mutableListOf<MoneyEntryEntity>(); arr("money").objects { money += MoneyEntryEntity(it.getLong("id"),it.getString("title"),it.getLong("amount"),it.getString("kind"),it.optString("category"),it.optLong("occurredAt"),it.optString("note")) }
        val factories = mutableListOf<FactoryEntity>(); arr("factories").objects { factories += FactoryEntity(it.getLong("id"),it.getString("name"),it.optString("location"),it.optString("description")) }
        val lines = mutableListOf<ProductionLineEntity>(); arr("lines").objects { lines += ProductionLineEntity(it.getLong("id"),it.getLong("factoryId"),it.getString("name"),it.optDouble("capacityPerDay"),it.optString("unit","kg"),it.optBoolean("active",true)) }
        val machines = mutableListOf<MachineEntity>(); arr("machines").objects { machines += MachineEntity(it.getLong("id"),it.getLong("lineId"),it.getString("name"),it.optString("code"),it.optString("status","فعال"),it.optDouble("capacity")) }
        val orders = mutableListOf<ProductionOrderEntity>(); arr("orders").objects { orders += ProductionOrderEntity(it.getLong("id"),it.getLong("lineId"),it.getString("product"),it.optDouble("plannedQuantity"),it.optDouble("actualQuantity"),if(it.isNull("startAt")) null else it.optLong("startAt"),if(it.isNull("dueAt")) null else it.optLong("dueAt"),it.optString("status","برنامه‌ریزی‌شده")) }
        val memories = mutableListOf<MemoryEntity>(); arr("memories").objects { memories += MemoryEntity(it.getLong("id"),it.getString("content"),it.optString("category"),it.optInt("importance",3),it.optLong("createdAt"),it.optString("source","کاربر")) }
        val documents = mutableListOf<DocumentEntity>(); arr("documents").objects { documents += DocumentEntity(it.getLong("id"),it.getString("name"),it.getString("uri"),it.optString("mimeType"),it.optLong("addedAt"),it.optString("tags")) }
        val audit = mutableListOf<AuditEntity>(); arr("audit").objects { audit += AuditEntity(it.getLong("id"),it.getString("action"),it.optString("target"),it.optString("detail"),it.optLong("createdAt"),it.optBoolean("requiresApproval")) }
        val chatMessages = mutableListOf<ChatMessageEntity>()
        (root.opt("chatMessages") as? JSONArray)?.objects { chatMessages += ChatMessageEntity(it.getLong("id"),it.getString("role"),it.getString("content"),it.optLong("createdAt")) }
        require(chatMessages.all { it.role in setOf("user", "assistant", "system") && it.content.isNotBlank() }) { "تاریخچه گفتگو در فایل پشتیبان معتبر نیست" }
        // Validate foreign-key-like relationships before touching the database.
        val factoryIds = factories.map { it.id }.toSet(); val lineIds = lines.map { it.id }.toSet()
        require(lines.all { it.factoryId in factoryIds } && machines.all { it.lineId in lineIds } && orders.all { it.lineId in lineIds }) { "ارتباط کارخانه، خط یا دستگاه در فایل پشتیبان ناسازگار است" }
        require(notes.all { it.title.isNotBlank() } && tasks.all { it.title.isNotBlank() } && factories.all { it.name.isNotBlank() } && lines.all { it.name.isNotBlank() && it.capacityPerDay.isFinite() && it.capacityPerDay >= 0.0 } && machines.all { it.name.isNotBlank() && it.capacity.isFinite() && it.capacity >= 0.0 } && orders.all { it.product.isNotBlank() && it.plannedQuantity.isFinite() && it.actualQuantity.isFinite() && it.plannedQuantity >= 0.0 && it.actualQuantity >= 0.0 }) { "مقادیر یا ظرفیت‌ها در فایل پشتیبان معتبر نیستند" }
        // ---- Dynamic Data Engine (optional block; absent in older files) ----
        val dynModules = mutableListOf<DynamicModuleEntity>()
        val dynFields = mutableListOf<DynamicFieldEntity>()
        val dynRecords = mutableListOf<DynamicRecordEntity>()
        (root.opt("dynModules") as? JSONArray)?.objects { dynModules += DynamicModuleEntity(it.getLong("id"),it.getString("name"),it.optString("icon","نادیده"),if(it.isNull("parentId")) null else it.optLong("parentId"),it.optInt("position"),it.optBoolean("active",true),it.optString("description"),it.optLong("createdAt")) }
        (root.opt("dynFields") as? JSONArray)?.objects { dynFields += DynamicFieldEntity(it.getLong("id"),it.getLong("moduleId"),it.getString("name"),it.optString("typeCode","text"),it.optBoolean("required"),it.optInt("position"),it.optBoolean("active",true),it.optString("optionsJson","[]"),if(it.isNull("minValue")) null else it.optDouble("minValue"),if(it.isNull("maxValue")) null else it.optDouble("maxValue"),it.optString("defaultValue"),it.optString("computeFormula"),if(it.isNull("relatedModuleId")) null else it.optLong("relatedModuleId"),it.optString("helpText")) }
        (root.opt("dynRecords") as? JSONArray)?.objects { dynRecords += DynamicRecordEntity(it.getLong("id"),it.getLong("moduleId"),it.optString("valuesJson","{}"),it.optLong("createdAt"),it.optLong("updatedAt")) }
        val moduleIds = dynModules.map { it.id }.toSet()
        require(dynModules.all { it.name.isNotBlank() && (it.parentId == null || it.parentId in moduleIds) } &&
            dynFields.all { field -> field.name.isNotBlank() && field.moduleId in moduleIds &&
                (field.relatedModuleId == null || field.relatedModuleId in moduleIds) &&
                (field.minValue == null || field.minValue.isFinite()) && (field.maxValue == null || field.maxValue.isFinite()) &&
                (field.minValue == null || field.maxValue == null || field.minValue <= field.maxValue) &&
                runCatching { JSONArray(field.optionsJson) }.isSuccess } &&
            dynRecords.all { record -> record.moduleId in moduleIds && runCatching { JSONObject(record.valuesJson) }.isSuccess }) {
            "ساختار داده‌های پویا در فایل پشتیبان ناسازگار است"
        }
        // Legacy HR blocks, if present in an older V3 backup, are intentionally ignored.
        // All table families are replaced under ONE database transaction. If any insert or
        // constraint fails, Room rolls back every table and the previous user data survives.
        database.withTransaction {
            dao.replaceAll(SamSnapshot(notes,tasks,money,factories,lines,machines,orders,memories,documents,audit,chatMessages))
            // Respect foreign-key order while replacing dynamic structures.
            dyn.clearRecords(); dyn.clearFields(); dyn.clearModules()
            dyn.putModules(dynModules); dyn.putFields(dynFields); dyn.putRecords(dynRecords)
        }
    }
}

// Collect only the latest Flow emission; used for a consistent export snapshot.
private suspend fun <T> kotlinx.coroutines.flow.Flow<List<T>>.firstList(): List<T> = first()
