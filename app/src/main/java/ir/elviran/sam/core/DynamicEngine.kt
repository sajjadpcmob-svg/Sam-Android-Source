package ir.elviran.sam.core

import ir.elviran.sam.SafeMath
import ir.elviran.sam.data.dynamic.DynamicFieldEntity
import ir.elviran.sam.data.dynamic.FieldType

/**
 * Validation + computed-field engine for the Dynamic Data Engine. Pure logic, fully unit-testable.
 * Inputs are raw string values keyed by field id (as produced by the form renderer).
 */
object DynamicValidation {

    /** Returns a map fieldId -> localized error message. Empty map means the record is valid. */
    fun validate(fields: List<DynamicFieldEntity>, values: Map<Long, String>): Map<Long, String> {
        val errors = LinkedHashMap<Long, String>()
        for (f in fields) {
            if (!f.active || f.type == FieldType.COMPUTED) continue
            val raw = values[f.id]?.trim().orEmpty()
            if (raw.isEmpty()) {
                if (f.required) errors[f.id] = "«${f.name}» اجباری است"
                continue
            }
            when (f.type) {
                FieldType.INTEGER -> {
                    val n = JalaliDate.toEnglishDigits(raw).toLongOrNull()
                    if (n == null) errors[f.id] = "«${f.name}» باید عدد صحیح باشد"
                    else rangeError(f, n.toDouble())?.let { errors[f.id] = it }
                }
                FieldType.DECIMAL -> {
                    val n = JalaliDate.toEnglishDigits(raw).toDoubleOrNull()
                    if (n == null || !n.isFinite()) errors[f.id] = "«${f.name}» باید عدد باشد"
                    else rangeError(f, n)?.let { errors[f.id] = it }
                }
                FieldType.JDATE -> {
                    if (parseJalali(raw) == null) errors[f.id] = "«${f.name}» تاریخ شمسی معتبر نیست (مثال ۱۴۰۴/۱۲/۳۰)"
                }
                FieldType.TIME -> {
                    if (!isValidTime(raw)) errors[f.id] = "«${f.name}» ساعت معتبر نیست (مثال ۰۷:۳۰)"
                }
                FieldType.SELECT -> {
                    val opts = parseOptions(f.optionsJson)
                    if (opts.isNotEmpty() && raw !in opts) errors[f.id] = "«${f.name}» خارج از گزینه‌های مجاز است"
                }
                FieldType.MULTISELECT -> {
                    val opts = parseOptions(f.optionsJson)
                    val chosen = raw.split('\u001f', ',').map { it.trim() }.filter { it.isNotEmpty() }
                    if (opts.isNotEmpty() && chosen.any { it !in opts }) errors[f.id] = "«${f.name}» شامل گزینه نامعتبر است"
                }
                else -> { /* TEXT, LONGTEXT, BOOL, FILE, RELATION: presence check already done */ }
            }
        }
        return errors
    }

    private fun rangeError(f: DynamicFieldEntity, v: Double): String? {
        f.minValue?.let { if (v < it) return "«${f.name}» نباید کمتر از ${trim(it)} باشد" }
        f.maxValue?.let { if (v > it) return "«${f.name}» نباید بیشتر از ${trim(it)} باشد" }
        return null
    }

    private fun trim(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    fun parseOptions(json: String): List<String> = runCatching {
        val a = org.json.JSONArray(json)
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    fun parseJalali(raw: String): JalaliDate? {
        val s = JalaliDate.toEnglishDigits(raw).trim().replace('-', '/').replace('.', '/')
        val parts = s.split('/')
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        return runCatching { JalaliDate(y, m, d) }.getOrNull()
    }

    fun isValidTime(raw: String): Boolean {
        val s = JalaliDate.toEnglishDigits(raw).trim()
        val parts = s.split(':')
        if (parts.size !in 2..3) return false
        val h = parts[0].toIntOrNull() ?: return false
        val mn = parts[1].toIntOrNull() ?: return false
        if (parts.size == 3 && parts[2].toIntOrNull() == null) return false
        return h in 0..23 && mn in 0..59
    }
}

/**
 * Evaluates a COMPUTED field formula such as "{price}*{qty}" by substituting referenced field
 * values (looked up by field NAME) and delegating arithmetic to the bounded SafeMath parser.
 */
object FormulaEngine {
    private val REF = Regex("\\{([^}]+)}")

    /** @param namedValues map of fieldName -> raw string value of sibling fields. */
    fun evaluate(formula: String, namedValues: Map<String, String>): Double? {
        if (formula.isBlank()) return null
        val substituted = REF.replace(formula) { m ->
            val name = m.groupValues[1].trim()
            val v = namedValues[name]?.let { JalaliDate.toEnglishDigits(it).trim() }
            val num = v?.toDoubleOrNull() ?: 0.0
            // wrap negatives to keep SafeMath grammar happy
            if (num < 0) "(0-${-num})" else num.toString()
        }
        return runCatching { SafeMath.evaluate(substituted) }.getOrNull()
    }

    /** Field names referenced by a formula (for dependency display / cycle avoidance by the UI). */
    fun referencedNames(formula: String): List<String> =
        REF.findAll(formula).map { it.groupValues[1].trim() }.toList()
}
