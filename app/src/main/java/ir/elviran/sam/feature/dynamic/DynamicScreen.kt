package ir.elviran.sam.feature.dynamic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.elviran.sam.core.DynamicValidation
import ir.elviran.sam.core.FormulaEngine
import ir.elviran.sam.core.JalaliDate
import ir.elviran.sam.data.dynamic.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val Ink = Color(0xFF101820)
private val Panel = Color(0xFF1B2932)
private val Teal = Color(0xFF55D6C2)
private val Muted = Color(0xFF9AAEB7)

// ---------- value helpers ----------
fun recordValues(json: String): MutableMap<Long, String> {
    val out = linkedMapOf<Long, String>()
    runCatching {
        val o = JSONObject(json)
        val keys = o.keys()
        while (keys.hasNext()) { val k = keys.next(); out[k.toLongOrNull() ?: return@runCatching] = o.optString(k) }
    }
    return out
}

fun valuesToJson(values: Map<Long, String>): String {
    val o = JSONObject()
    for ((k, v) in values) o.put(k.toString(), v)
    return o.toString()
}

fun optionsToJson(options: List<String>): String {
    val a = JSONArray(); options.forEach { a.put(it) }; return a.toString()
}

/** Top-level Dynamic Data Engine screen. Self-contained navigation via local state. */
@Composable
fun DynamicScreen(dyn: DynamicDao) {
    var openModule by remember { mutableStateOf<DynamicModuleEntity?>(null) }
    if (openModule == null) {
        ModuleListScreen(dyn) { openModule = it }
    } else {
        ModuleDetailScreen(dyn, openModule!!) { openModule = null }
    }
}

@Composable
private fun ModuleListScreen(dyn: DynamicDao, onOpen: (DynamicModuleEntity) -> Unit) {
    val scope = rememberCoroutineScope()
    val modules by dyn.modules().collectAsState(initial = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var editModule by remember { mutableStateOf<DynamicModuleEntity?>(null) }
    var deleteModule by remember { mutableStateOf<DynamicModuleEntity?>(null) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("داده‌های پویا", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("ماژول و فرم اختصاصی خودت را بساز", color = Muted, fontSize = 12.sp)
            }
            FilledTonalButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("ماژول جدید") }
        }
        if (modules.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                Text("هنوز ماژولی نساخته‌ای. با دکمه «ماژول جدید» شروع کن؛ مثلاً «توقفات»، «کنترل کیفیت» یا «مصرف مواد».", Modifier.padding(16.dp), color = Muted)
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(modules) { m ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(m) }, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, fontWeight = FontWeight.SemiBold, color = Color.White)
                            if (m.description.isNotBlank()) Text(m.description, color = Muted, fontSize = 12.sp)
                        }
                        IconButton(onClick = { editModule = m }) { Icon(Icons.Default.Edit, null, tint = Teal) }
                        IconButton(onClick = { deleteModule = m }) { Icon(Icons.Default.Delete, null, tint = Muted) }
                        Icon(Icons.Default.ChevronLeft, null, tint = Teal)
                    }
                }
            }
        }
    }

    if (showAdd || editModule != null) {
        val target = editModule
        ModuleEditDialog(initial = target, onDismiss = { showAdd = false; editModule = null }) { name, desc ->
            scope.launch {
                if (target == null) dyn.addModule(DynamicModuleEntity(name = name, description = desc, position = modules.size))
                else dyn.updateModule(target.copy(name = name, description = desc))
            }
            showAdd = false; editModule = null
        }
    }
    deleteModule?.let { m ->
        AlertDialog(
            onDismissRequest = { deleteModule = null },
            title = { Text("حذف ماژول؟") },
            text = { Text("ماژول «${m.name}» همراه تمام فیلدها و رکوردهای آن حذف می‌شود. این عمل بازگشت‌پذیر نیست.") },
            confirmButton = { TextButton(onClick = { scope.launch { dyn.deleteModule(m) }; deleteModule = null }) { Text("حذف") } },
            dismissButton = { TextButton(onClick = { deleteModule = null }) { Text("انصراف") } }
        )
    }
}

@Composable
private fun ModuleEditDialog(initial: DynamicModuleEntity?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var desc by remember { mutableStateOf(initial?.description ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "ماژول جدید" else "ویرایش ماژول") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("نام ماژول") }, singleLine = true)
                OutlinedTextField(desc, { desc = it }, label = { Text("توضیح (اختیاری)") })
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim(), desc.trim()) }) { Text("ذخیره") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}

@Composable
private fun ModuleDetailScreen(dyn: DynamicDao, module: DynamicModuleEntity, onBack: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val fields by dyn.fieldsOf(module.id).collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowForward, null, tint = Teal) }
            Text(module.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        TabRow(selectedTabIndex = tab, containerColor = Ink, contentColor = Teal) {
            Tab(tab == 0, { tab = 0 }, text = { Text("رکوردها") })
            Tab(tab == 1, { tab = 1 }, text = { Text("طراحی فیلدها") })
        }
        if (tab == 0) RecordsTab(dyn, module, fields) else FieldsTab(dyn, module, fields)
    }
}

@Composable
private fun FieldsTab(dyn: DynamicDao, module: DynamicModuleEntity, fields: List<DynamicFieldEntity>) {
    val scope = rememberCoroutineScope()
    var editField by remember { mutableStateOf<DynamicFieldEntity?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var deleteField by remember { mutableStateOf<DynamicFieldEntity?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("فیلد جدید") }
        if (fields.isEmpty()) Text("برای این ماژول هنوز فیلدی تعریف نشده.", color = Muted, fontSize = 12.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(fields) { index, f ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name + if (f.required) " *" else "", fontWeight = FontWeight.SemiBold, color = if (f.active) Color.White else Muted)
                            Text(f.type.label + (if (!f.active) " • غیرفعال" else ""), color = Muted, fontSize = 11.sp)
                        }
                        IconButton(enabled = index > 0, onClick = { scope.launch { dyn.reorderFields(fields.toMutableList().also { it.add(index - 1, it.removeAt(index)) }) } }) { Icon(Icons.Default.KeyboardArrowUp, null, tint = if (index > 0) Teal else Muted) }
                        IconButton(enabled = index < fields.lastIndex, onClick = { scope.launch { dyn.reorderFields(fields.toMutableList().also { it.add(index + 1, it.removeAt(index)) }) } }) { Icon(Icons.Default.KeyboardArrowDown, null, tint = if (index < fields.lastIndex) Teal else Muted) }
                        IconButton(onClick = { editField = f }) { Icon(Icons.Default.Edit, null, tint = Teal) }
                        IconButton(onClick = { deleteField = f }) { Icon(Icons.Default.Delete, null, tint = Muted) }
                    }
                }
            }
        }
    }
    if (showAdd || editField != null) {
        FieldEditDialog(initial = editField, onDismiss = { showAdd = false; editField = null }) { built ->
            scope.launch {
                if (editField == null) dyn.addField(built.copy(moduleId = module.id, position = fields.size))
                else dyn.updateField(editField!!.copy(name = built.name, typeCode = built.typeCode, required = built.required, active = built.active, optionsJson = built.optionsJson, minValue = built.minValue, maxValue = built.maxValue, defaultValue = built.defaultValue, computeFormula = built.computeFormula, helpText = built.helpText))
            }
            showAdd = false; editField = null
        }
    }
    deleteField?.let { f ->
        AlertDialog(
            onDismissRequest = { deleteField = null },
            title = { Text("حذف فیلد؟") },
            text = { Text("فیلد «${f.name}» حذف شود؟ مقادیر ذخیره‌شده‌ی این فیلد در رکوردها دیگر نمایش داده نمی‌شود.") },
            confirmButton = { TextButton(onClick = { scope.launch { dyn.deleteField(f) }; deleteField = null }) { Text("حذف") } },
            dismissButton = { TextButton(onClick = { deleteField = null }) { Text("انصراف") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldEditDialog(initial: DynamicFieldEntity?, onDismiss: () -> Unit, onSave: (DynamicFieldEntity) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: FieldType.TEXT) }
    var required by remember { mutableStateOf(initial?.required ?: false) }
    var active by remember { mutableStateOf(initial?.active ?: true) }
    var optionsText by remember { mutableStateOf(DynamicValidation.parseOptions(initial?.optionsJson ?: "[]").joinToString("\u060c")) }
    var minText by remember { mutableStateOf(initial?.minValue?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() } ?: "") }
    var maxText by remember { mutableStateOf(initial?.maxValue?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() } ?: "") }
    var formula by remember { mutableStateOf(initial?.computeFormula ?: "") }
    var help by remember { mutableStateOf(initial?.helpText ?: "") }
    var typeMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "فیلد جدید" else "ویرایش فیلد") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("نام فیلد") }, singleLine = true)
                ExposedDropdownMenuBox(expanded = typeMenu, onExpandedChange = { typeMenu = it }) {
                    OutlinedTextField(
                        value = type.label, onValueChange = {}, readOnly = true,
                        label = { Text("نوع فیلد") },
                        trailingIcon = { Icon(Icons.Default.Tune, null) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        FieldType.entries.forEach { ft ->
                            DropdownMenuItem(text = { Text(ft.label) }, onClick = { type = ft; typeMenu = false })
                        }
                    }
                }
                if (type == FieldType.SELECT || type == FieldType.MULTISELECT)
                    OutlinedTextField(optionsText, { optionsText = it }, label = { Text("گزینه‌ها (با «،» جدا کن)") })
                if (type == FieldType.INTEGER || type == FieldType.DECIMAL) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(minText, { minText = it }, label = { Text("حداقل") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(maxText, { maxText = it }, label = { Text("حداکثر") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }
                if (type == FieldType.COMPUTED)
                    OutlinedTextField(formula, { formula = it }, label = { Text("فرمول مثل {قیمت}*{تعداد}") })
                OutlinedTextField(help, { help = it }, label = { Text("راهنما (اختیاری)") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(required, { required = it }); Text("اجباری"); Spacer(Modifier.width(16.dp)); Checkbox(active, { active = it }); Text("فعال") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    val opts = optionsText.split('\u060c', ',').map { it.trim() }.filter { it.isNotEmpty() }
                    onSave(
                        DynamicFieldEntity(
                            id = initial?.id ?: 0, moduleId = initial?.moduleId ?: 0, name = name.trim(),
                            typeCode = type.code, required = required, active = active,
                            optionsJson = optionsToJson(opts),
                            minValue = JalaliDate.toEnglishDigits(minText).trim().toDoubleOrNull(),
                            maxValue = JalaliDate.toEnglishDigits(maxText).trim().toDoubleOrNull(),
                            computeFormula = formula.trim(), helpText = help.trim()
                        )
                    )
                }
            }) { Text("ذخیره") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}

/** Build a name->rawValue map used for computed fields and relation display. */
private fun namedValues(fields: List<DynamicFieldEntity>, values: Map<Long, String>): Map<String, String> =
    fields.associate { it.name to (values[it.id] ?: it.defaultValue) }

/** Short one-line summary of a record for the list. */
private fun recordSummary(fields: List<DynamicFieldEntity>, values: Map<Long, String>): String {
    val shown = fields.filter { it.active }.take(3).mapNotNull { f ->
        val v = when (f.type) {
            FieldType.COMPUTED -> FormulaEngine.evaluate(f.computeFormula, namedValues(fields, values))?.let {
                if (it == it.toLong().toDouble()) it.toLong().toString() else "%.2f".format(it)
            } ?: "—"
            else -> values[f.id].orEmpty()
        }
        if (v.isBlank()) null else "${f.name}: ${JalaliDate.toPersianDigits(v)}"
    }
    return shown.joinToString("  •  ").ifBlank { "(بدون مقدار)" }
}

@Composable
private fun RecordsTab(dyn: DynamicDao, module: DynamicModuleEntity, fields: List<DynamicFieldEntity>) {
    val scope = rememberCoroutineScope()
    val records by dyn.recordsOf(module.id).collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var showForm by remember { mutableStateOf(false) }
    var editRecord by remember { mutableStateOf<DynamicRecordEntity?>(null) }
    var deleteRecord by remember { mutableStateOf<DynamicRecordEntity?>(null) }

    val filtered = remember(records, query, fields) {
        val q = JalaliDate.toEnglishDigits(query).trim()
        if (q.isBlank()) records else records.filter { rec ->
            val vals = recordValues(rec.valuesJson)
            recordSummary(fields, vals).contains(query.trim()) ||
                vals.values.any { JalaliDate.toEnglishDigits(it).contains(q, ignoreCase = true) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("جست‌وجو در رکوردها") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(JalaliDate.toPersianDigits("${filtered.size} رکورد"), color = Muted, fontSize = 12.sp)
            FilledTonalButton(onClick = { if (fields.any { it.active }) showForm = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("رکورد جدید") }
        }
        if (fields.none { it.active }) Text("ابتدا از تب «طراحی فیلدها» حداقل یک فیلد بساز.", color = Muted, fontSize = 12.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(filtered) { rec ->
                val vals = recordValues(rec.valuesJson)
                Card(Modifier.fillMaxWidth().clickable { editRecord = rec }, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(recordSummary(fields, vals), Modifier.weight(1f), color = Color.White, fontSize = 13.sp)
                        IconButton(onClick = { editRecord = rec }) { Icon(Icons.Default.Edit, null, tint = Teal) }
                        IconButton(onClick = { deleteRecord = rec }) { Icon(Icons.Default.Delete, null, tint = Muted) }
                    }
                }
            }
        }
    }
    if (showForm || editRecord != null) {
        RecordFormDialog(dyn, fields, editRecord, onDismiss = { showForm = false; editRecord = null }) { json ->
            scope.launch {
                val now = System.currentTimeMillis()
                if (editRecord == null) dyn.addRecord(DynamicRecordEntity(moduleId = module.id, valuesJson = json, createdAt = now, updatedAt = now))
                else dyn.updateRecord(editRecord!!.copy(valuesJson = json, updatedAt = now))
            }
            showForm = false; editRecord = null
        }
    }
    deleteRecord?.let { rec ->
        AlertDialog(
            onDismissRequest = { deleteRecord = null },
            title = { Text("حذف رکورد؟") },
            text = { Text("این رکورد حذف شود؟ این عمل بازگشت‌پذیر نیست.") },
            confirmButton = { TextButton(onClick = { scope.launch { dyn.deleteRecord(rec) }; deleteRecord = null }) { Text("حذف") } },
            dismissButton = { TextButton(onClick = { deleteRecord = null }) { Text("انصراف") } }
        )
    }
}
@Composable
private fun RecordFormDialog(
    dyn: DynamicDao,
    fields: List<DynamicFieldEntity>,
    initial: DynamicRecordEntity?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val values = remember(initial?.id) { mutableStateMapOf<Long, String>() }
    var errors by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    LaunchedEffect(initial?.id, fields) {
        values.clear()
        val existing = initial?.let { recordValues(it.valuesJson) } ?: emptyMap()
        fields.forEach { f -> values[f.id] = existing[f.id] ?: f.defaultValue }
    }
    val activeFields = fields.filter { it.active }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "ثبت رکورد" else "ویرایش رکورد") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                activeFields.forEach { f -> FieldInput(dyn, f, fields, values, errors[f.id]) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val errs = DynamicValidation.validate(activeFields, values.toMap())
                errors = errs
                if (errs.isEmpty()) {
                    val toStore = activeFields.filter { it.type != FieldType.COMPUTED }
                        .associate { it.id to (values[it.id].orEmpty()) }
                    onSave(valuesToJson(toStore))
                }
            }) { Text("ذخیره") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldInput(
    dyn: DynamicDao,
    field: DynamicFieldEntity,
    allFields: List<DynamicFieldEntity>,
    values: MutableMap<Long, String>,
    error: String?
) {
    val current = values[field.id] ?: ""
    val label = field.name + if (field.required) " *" else ""
    when (field.type) {
        FieldType.BOOL -> Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(current == "true", { values[field.id] = if (it) "true" else "false" }); Text(label, color = Color.White)
        }
        FieldType.LONGTEXT -> OutlinedTextField(current, { values[field.id] = it }, Modifier.fillMaxWidth(), label = { Text(label) }, minLines = 2, isError = error != null, supportingText = { error?.let { Text(it) } })
        FieldType.INTEGER -> OutlinedTextField(current, { values[field.id] = it }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, isError = error != null, supportingText = { (error ?: field.helpText.ifBlank { null })?.let { Text(it) } })
        FieldType.DECIMAL -> OutlinedTextField(current, { values[field.id] = it }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, isError = error != null, supportingText = { (error ?: field.helpText.ifBlank { null })?.let { Text(it) } })
        FieldType.JDATE -> OutlinedTextField(current, { values[field.id] = it }, Modifier.fillMaxWidth(), label = { Text("$label (۱۴۰۴/۱۲/۳۰)") }, singleLine = true, isError = error != null, supportingText = { error?.let { Text(it) } })
        FieldType.TIME -> OutlinedTextField(current, { values[field.id] = it }, Modifier.fillMaxWidth(), label = { Text("$label (۰۷:۳۰)") }, singleLine = true, isError = error != null, supportingText = { error?.let { Text(it) } })
        FieldType.FILE -> OutlinedTextField(current, { values[field.id] = it }, Modifier.fillMaxWidth(), label = { Text("$label (نشانی فایل/تصویر)") }, singleLine = true, supportingText = { error?.let { Text(it) } })
        FieldType.COMPUTED -> {
            val result = FormulaEngine.evaluate(field.computeFormula, namedValues(allFields, values))
            val show = result?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else "%.2f".format(it) } ?: "—"
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(field.name, color = Muted); Text(JalaliDate.toPersianDigits(show), color = Teal, fontWeight = FontWeight.Bold)
                }
            }
        }
        FieldType.SELECT -> {
            var open by remember { mutableStateOf(false) }
            val opts = DynamicValidation.parseOptions(field.optionsJson)
            ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
                OutlinedTextField(current, {}, readOnly = true, label = { Text(label) }, trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) }, isError = error != null, supportingText = { error?.let { Text(it) } }, modifier = Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    opts.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { values[field.id] = o; open = false }) }
                }
            }
        }
        FieldType.MULTISELECT -> {
            val opts = DynamicValidation.parseOptions(field.optionsJson)
            val chosen = current.split('\u060c', ',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
            Column { Text(label, color = Color.White, fontSize = 13.sp)
                opts.forEach { o ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(o in chosen, { on -> if (on) chosen.add(o) else chosen.remove(o); values[field.id] = chosen.joinToString("،") }); Text(o, color = Muted)
                    }
                }
                error?.let { Text(it, color = Color(0xFFE57373), fontSize = 11.sp) }
            }
        }
        FieldType.RELATION -> {
            var open by remember { mutableStateOf(false) }
            var options by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
            LaunchedEffect(field.relatedModuleId) {
                val mid = field.relatedModuleId
                if (mid != null) {
                    val relFields = dyn.fieldsOfNow(mid)
                    options = dyn.recordsOfNow(mid).map { it.id to recordSummary(relFields, recordValues(it.valuesJson)) }
                }
            }
            val selectedLabel = options.firstOrNull { it.first.toString() == current }?.second ?: current
            ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
                OutlinedTextField(selectedLabel, {}, readOnly = true, label = { Text("$label (وابسته)") }, trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) }, isError = error != null, supportingText = { error?.let { Text(it) } }, modifier = Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    if (options.isEmpty()) DropdownMenuItem(text = { Text("رکوردی برای اتصال نیست") }, onClick = { open = false })
                    options.forEach { (id, lbl) -> DropdownMenuItem(text = { Text(lbl) }, onClick = { values[field.id] = id.toString(); open = false }) }
                }
            }
        }
        else -> OutlinedTextField(current, { values[field.id] = it }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, isError = error != null, supportingText = { (error ?: field.helpText.ifBlank { null })?.let { Text(it) } })
    }
}
