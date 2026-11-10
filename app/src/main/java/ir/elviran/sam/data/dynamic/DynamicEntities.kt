package ir.elviran.sam.data.dynamic

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Dynamic Data Engine schema. The user defines modules, fields and records from inside the app;
 * no code change or ad-hoc table creation is needed. Stored in Room and included in backup.
 */

enum class FieldType(val code: String, val label: String) {
    TEXT("text", "متن"),
    INTEGER("int", "عدد صحیح"),
    DECIMAL("dec", "عدد اعشاری"),
    JDATE("jdate", "تاریخ شمسی"),
    TIME("time", "ساعت"),
    SELECT("select", "انتخابی"),
    MULTISELECT("multi", "چندانتخابی"),
    BOOL("bool", "بله/خیر"),
    LONGTEXT("longtext", "توضیحات بلند"),
    FILE("file", "فایل و تصویر"),
    COMPUTED("computed", "محاسباتی"),
    RELATION("relation", "وابسته به اطلاعات دیگر");

    companion object {
        fun fromCode(code: String): FieldType = entries.firstOrNull { it.code == code } ?: TEXT
    }
}

@Entity(
    tableName = "dyn_modules",
    indices = [Index("parentId")]
)
data class DynamicModuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String = "نادیده",
    val parentId: Long? = null,            // category / sub-category tree
    val position: Int = 0,
    val active: Boolean = true,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "dyn_fields",
    foreignKeys = [ForeignKey(
        entity = DynamicModuleEntity::class,
        parentColumns = ["id"],
        childColumns = ["moduleId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("moduleId")]
)
data class DynamicFieldEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val moduleId: Long,
    val name: String,
    @ColumnInfo(name = "typeCode") val typeCode: String = FieldType.TEXT.code,
    val required: Boolean = false,
    val position: Int = 0,
    val active: Boolean = true,
    val optionsJson: String = "[]",       // for SELECT / MULTISELECT: JSON array of strings
    val minValue: Double? = null,         // numeric range
    val maxValue: Double? = null,
    val defaultValue: String = "",
    val computeFormula: String = "",      // for COMPUTED: e.g. {price}*{qty}
    val relatedModuleId: Long? = null,    // for RELATION: target module id
    val helpText: String = ""
) {
    val type: FieldType get() = FieldType.fromCode(typeCode)
}

@Entity(
    tableName = "dyn_records",
    foreignKeys = [ForeignKey(
        entity = DynamicModuleEntity::class,
        parentColumns = ["id"],
        childColumns = ["moduleId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("moduleId")]
)
data class DynamicRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val moduleId: Long,
    val valuesJson: String = "{}",        // JSON object: fieldId(String) -> value
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
