package ir.elviran.sam.feature.factory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.elviran.sam.core.*
import ir.elviran.sam.data.*
import java.time.LocalDate

private val Ink = Color(0xFF101820)
private val Panel = Color(0xFF1B2932)
private val Teal = Color(0xFF55D6C2)
private val Muted = Color(0xFF9AAEB7)
private val Warn = Color(0xFFE2B341)
private val Bad = Color(0xFFE2685B)

private fun d(s: String): Double? = JalaliDate.toEnglishDigits(s).trim().toDoubleOrNull()
private fun quantity(v: Double): String = JalaliDate.toPersianDigits("%,.1f".format(v)) + " \u06a9\u06cc\u0644\u0648\u06af\u0631\u0645"
private fun pct(v: Double): String = JalaliDate.toPersianDigits("%.1f".format(v)) + "\u066a"

@Composable
fun FactoryToolsScreen(dao: SamDao, lines: List<ProductionLineEntity>, orders: List<ProductionOrderEntity>, navigate: (String) -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val titles = listOf("\u06a9\u0646\u062a\u0631\u0644 \u062a\u0648\u0644\u06cc\u062f", "\u0648\u0636\u0639\u06cc\u062a \u062a\u0648\u0644\u06cc\u062f (OEE)", "\u0642\u0648\u0627\u0639\u062f \u062a\u0627\u0641\u06cc \u0648 \u06a9\u0627\u0648\u0646\u062f\u06cc\u0634")
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navigate("\u0628\u06cc\u0634\u062a\u0631") }) { Icon(Icons.Default.ArrowForward, null, tint = Teal) }
            Text("\u0627\u0628\u0632\u0627\u0631 \u06a9\u0627\u0631\u062e\u0627\u0646\u0647", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        ScrollableTabRow(selectedTabIndex = tab, containerColor = Ink, edgePadding = 0.dp) {
            titles.forEachIndexed { idx, t -> Tab(selected = tab == idx, onClick = { tab = idx }, text = { Text(t) }) }
        }
        Spacer(Modifier.height(10.dp))
        when (tab) {
            0 -> SchedulerTab(lines, orders)
            1 -> OeeTab()
            else -> SpecialFactoryRulesTab()
        }
    }
}

@Composable private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable private fun Field(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
}

// ---------- Scheduler ----------
@Composable private fun SchedulerTab(lines: List<ProductionLineEntity>, orders: List<ProductionOrderEntity>) {
    val result = remember(lines, orders) {
        val schedLines = lines.map { SchedLine(it.id, it.name, it.capacityPerDay) }
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        val schedOrders = orders.map {
            val remaining = (it.plannedQuantity - it.actualQuantity).coerceAtLeast(0.0)
            val due = it.dueAt?.let { d -> ((d - now) / day).toInt().coerceAtLeast(0) }
            SchedOrder(it.id, it.lineId, it.product, remaining, due)
        }.filter { it.quantity > 0.0 }
        ProductionScheduler.schedule(schedLines, schedOrders)
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("\u0632\u0645\u0627\u0646\u200c\u0628\u0646\u062f\u06cc \u062e\u0648\u062f\u06a9\u0627\u0631 \u0628\u0631 \u067e\u0627\u06cc\u0647 \u0638\u0631\u0641\u06cc\u062a \u0631\u0648\u0632\u0627\u0646\u0647 \u062e\u0637 \u0648 \u0645\u0627\u0646\u062f\u0647 \u0633\u0641\u0627\u0631\u0634.", color = Muted, fontSize = 12.sp)
        }
        if (result.scheduled.isEmpty() && result.unassigned.isEmpty()) {
            item { Text("\u0633\u0641\u0627\u0631\u0634 \u0628\u0627\u0632\u06cc \u0628\u0631\u0627\u06cc \u0632\u0645\u0627\u0646\u200c\u0628\u0646\u062f\u06cc \u0648\u062c\u0648\u062f \u0646\u062f\u0627\u0631\u062f.", color = Muted) }
        }
        items(result.scheduled) { s ->
            SectionCard {
                Text(s.order.product, fontWeight = FontWeight.SemiBold)
                val startTxt = JalaliDate.toPersianDigits(s.startDay.toString())
                val finTxt = JalaliDate.toPersianDigits(s.finishDay.toString())
                Text("\u0634\u0631\u0648\u0639: \u0631\u0648\u0632 $startTxt \u2014 \u067e\u0627\u06cc\u0627\u0646: \u0631\u0648\u0632 $finTxt", color = Muted, fontSize = 13.sp)
                if (s.onTime) Text("\u062f\u0631 \u0645\u0647\u0644\u062a \u2714", color = Teal, fontSize = 13.sp)
                else Text("\u062a\u0623\u062e\u06cc\u0631 " + JalaliDate.toPersianDigits(s.daysLate.toString()) + " \u0631\u0648\u0632", color = Bad, fontSize = 13.sp)
            }
        }
        items(result.unassigned) { o ->
            SectionCard {
                Text(o.product, fontWeight = FontWeight.SemiBold)
                Text("\u0628\u062f\u0648\u0646 \u062e\u0637 \u0645\u0639\u062a\u0628\u0631 \u06cc\u0627 \u0638\u0631\u0641\u06cc\u062a \u0635\u0641\u0631 \u2014 \u0632\u0645\u0627\u0646\u200c\u0628\u0646\u062f\u06cc \u0646\u0634\u062f.", color = Warn, fontSize = 13.sp)
            }
        }
    }
}

// ---------- Taffy + Kavandish business rules ----------
@Composable private fun SpecialFactoryRulesTab() {
    val today = remember { LocalDate.now() }
    val jalaliToday = remember(today) { JalaliDate.fromGregorian(today.year, today.monthValue, today.dayOfMonth) }
    var startJalali by remember { mutableStateOf("${jalaliToday.year}/${jalaliToday.month.toString().padStart(2,'0')}/${jalaliToday.day.toString().padStart(2,'0')}") }
    var bitter by remember { mutableStateOf("0") }
    var fruit by remember { mutableStateOf("0") }
    var ratio by remember { mutableStateOf("70") }
    val plan = remember(startJalali) {
        runCatching {
            val parts = JalaliDate.toEnglishDigits(startJalali).split("/", "-").map { it.toInt() }
            require(parts.size == 3)
            val g = JalaliDate.toGregorian(JalaliDate(parts[0], parts[1], parts[2]))
            TaffyCapacityRules.plan(LocalDate.of(g.first, g.second, g.third), 14)
        }.getOrNull()
    }
    val packaging = remember(bitter, fruit, ratio) {
        runCatching {
            val r = (JalaliDate.toEnglishDigits(ratio).toDoubleOrNull() ?: error("نسبت نامعتبر")) / 100.0
            val b = JalaliDate.toEnglishDigits(bitter).toDoubleOrNull() ?: error("مقدار تلخ نامعتبر")
            val f = JalaliDate.toEnglishDigits(fruit).toDoubleOrNull() ?: error("مقدار میوه نامعتبر")
            KavandishPackagingRules.plan(mapOf("تلخ" to b, "میوه" to f), r)
        }.getOrNull()
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionCard {
            Text("ظرفیت خط تافی", fontWeight = FontWeight.Bold, color = Teal)
            Text("جمعه تعطیل • شنبه شروع ۰۷:۳۰ • یکشنبه تا پنجشنبه شروع ۰۷:۰۰", color = Muted, fontSize = 12.sp)
            Field(startJalali, "تاریخ شروع (شمسی: ۱۴۰۵/۰۸/۱۸)") { startJalali = it }
            if (plan == null) Text("تاریخ شمسی معتبر وارد کن.", color = Bad) else {
                plan.forEach { day ->
                    val j = JalaliDate.fromGregorian(day.date.year, day.date.monthValue, day.date.dayOfMonth)
                    val dateText = JalaliDate.toPersianDigits("${j.year}/${j.month.toString().padStart(2,'0')}/${j.day.toString().padStart(2,'0')}")
                    Text(if (!day.isProductionDay) "$dateText • جمعه، تعطیل" else "$dateText • شروع ${day.startTime} • ظرفیت ${day.totalBatches} بچ (${day.netBatches} خالص + ${day.lossBatches} افت)", color = if(day.isProductionDay) Color.White else Muted, fontSize = 12.sp)
                    if (day.cleaningWindow != null) Text("نظافت: ${day.cleaningWindow}", color = Warn, fontSize = 11.sp)
                }
            }
            Text("وزن بچ محصولات پرشده: ۱۵۶٫۵ کیلوگرم • ظرفیت خالص روز ۷۲ بچ = ۱۱٬۲۶۸ کیلوگرم؛ پنجشنبه ۵۲ بچ = ۸٬۱۳۸ کیلوگرم.", color = Muted, fontSize = 11.sp)
        } }
        item { SectionCard {
            Text("تقسیم بسته‌بندی کاوندیش کلاسیک", fontWeight = FontWeight.Bold, color = Teal)
            Text("طعم تلخ و میوه جداگانه محاسبه می‌شوند؛ بسته‌بندی دو سر و بالشتی موازی برنامه‌ریزی می‌شوند.", color = Muted, fontSize = 12.sp)
            Field(bitter, "مقدار طعم تلخ (کیلوگرم)") { bitter = it }
            Field(fruit, "مقدار طعم میوه (کیلوگرم)") { fruit = it }
            Field(ratio, "درصد بسته‌بندی دو سر (پیش‌فرض ۷۰)") { ratio = it }
            if (packaging == null) Text("مقادیر معتبر و نسبت بین صفر تا ۱۰۰ وارد کن.", color = Bad) else packaging.forEach { p ->
                Text("طعم ${p.flavor}: کل ${quantity(p.totalKg)}", fontWeight = FontWeight.SemiBold)
                Text("دو سر: ${quantity(p.twoEndKg)} • بالشتی: ${quantity(p.cushionKg)}", color = Muted, fontSize = 12.sp)
            }
        } }
    }
}

// ---------- OEE ----------
@Composable private fun OeeTab() {
    var planned by remember { mutableStateOf("") }
    var downtime by remember { mutableStateOf("") }
    var rate by remember { mutableStateOf("") }
    var total by remember { mutableStateOf("") }
    var good by remember { mutableStateOf("") }
    var res by remember { mutableStateOf<OeeResult?>(null) }
    var err by remember { mutableStateOf("") }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionCard {
            Field(planned, "\u0632\u0645\u0627\u0646 \u0628\u0631\u0646\u0627\u0645\u0647\u200c\u0631\u06cc\u0632\u06cc\u200c\u0634\u062f\u0647 (\u062f\u0642\u06cc\u0642\u0647)") { planned = it }
            Field(downtime, "\u062a\u0648\u0642\u0641 \u063a\u06cc\u0631\u0628\u0631\u0646\u0627\u0645\u0647\u200c\u0627\u06cc (\u062f\u0642\u06cc\u0642\u0647)") { downtime = it }
            Field(rate, "\u0646\u0631\u062e \u0627\u06cc\u062f\u0647\u200c\u0622\u0644 (\u062a\u0639\u062f\u0627\u062f \u062f\u0631 \u062f\u0642\u06cc\u0642\u0647)") { rate = it }
            Field(total, "\u062a\u0639\u062f\u0627\u062f \u06a9\u0644 \u062a\u0648\u0644\u06cc\u062f") { total = it }
            Field(good, "\u062a\u0639\u062f\u0627\u062f \u0633\u0627\u0644\u0645") { good = it }
            Button(onClick = {
                err = ""
                val inp = runCatching { OeeInput(d(planned) ?: 0.0, d(downtime) ?: 0.0, d(rate) ?: 0.0, d(total) ?: 0.0, d(good) ?: 0.0) }
                res = inp.mapCatching { OeeEngine.compute(it) }.getOrElse { err = it.message ?: "\u062e\u0637\u0627 \u062f\u0631 \u0645\u062d\u0627\u0633\u0628\u0647"; null }
            }, Modifier.fillMaxWidth()) { Text("\u0645\u062d\u0627\u0633\u0628\u0647 OEE") }
            if (err.isNotBlank()) Text(err, color = Bad, fontSize = 13.sp)
        } }
        res?.let { r -> item { SectionCard {
            Text("OEE: " + pct(r.oeePct) + "  (" + OeeEngine.grade(r.oee) + ")", fontWeight = FontWeight.Bold, color = Teal, fontSize = 18.sp)
            Text("\u062f\u0633\u062a\u0631\u0633\u200c\u067e\u0630\u06cc\u0631\u06cc: " + pct(r.availabilityPct), color = Muted)
            Text("\u0639\u0645\u0644\u06a9\u0631\u062f: " + pct(r.performancePct), color = Muted)
            Text("\u06a9\u06cc\u0641\u06cc\u062a: " + pct(r.qualityPct), color = Muted)
        } } }
    }
}
