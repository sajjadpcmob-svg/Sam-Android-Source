package ir.elviran.sam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.room.Room
import ir.elviran.sam.ai.AiRouter
import ir.elviran.sam.ai.ChatTurn
import ir.elviran.sam.core.Intent as SamIntent
import ir.elviran.sam.core.JalaliDate
import ir.elviran.sam.core.OfflineBrain
import ir.elviran.sam.data.*
import ir.elviran.sam.data.dynamic.DynamicDao
import ir.elviran.sam.feature.dynamic.DynamicScreen
import ir.elviran.sam.feature.factory.FactoryToolsScreen
import ir.elviran.sam.voice.VoiceController
import ir.elviran.sam.voice.VoiceAssistantScreen
import ir.elviran.sam.voice.VoiceForegroundService
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.text.NumberFormat
import java.util.Locale
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

private val Ink = Color(0xFF101820); private val Panel = Color(0xFF1B2932); private val Teal = Color(0xFF55D6C2); private val Muted = Color(0xFF9AAEB7)
private fun parseFaDouble(value:String):Double? = JalaliDate.toEnglishDigits(value).replace("٬","").replace(",","").replace("٫",".").trim().toDoubleOrNull()
private fun parseFaLong(value:String):Long? = JalaliDate.toEnglishDigits(value).replace("٬","").replace(",","").trim().toLongOrNull()
private fun parseFaInt(value:String):Int? = JalaliDate.toEnglishDigits(value).trim().toIntOrNull()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); window.statusBarColor = android.graphics.Color.rgb(16,24,32); window.navigationBarColor = android.graphics.Color.rgb(16,24,32); setContent { SamApp() } }
}

@Composable private fun SamApp() {
    val context = LocalContext.current
    val db = remember { SamDatabase.get(context) }; val dao = remember { db.dao() }; val dyn = remember { db.dynamicDao() }; val ai = remember { AiRouter(context) }
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf("خانه") }; var voiceState by remember { mutableStateOf("آماده") }; var voicePartial by remember { mutableStateOf("") }; var reply by remember { mutableStateOf("سلام! من سام هستم. چه کاری برات انجام بدم؟") }
    var prompt by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    val voice = remember { VoiceController(context, { prompt = it }, { voiceState = it }, { voicePartial = it }) }
    DisposableEffect(Unit) { onDispose { voice.close() } }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) voice.startListening() else voiceState = "مجوز میکروفن داده نشد" }
    val voiceServiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) VoiceForegroundService.start(context)
        else voiceState = "مجوز میکروفن برای دستیار صوتی لازم است"
    }
    val chatMessages by dao.chatMessages().collectAsState(initial = emptyList()); val notes by dao.notes().collectAsState(initial = emptyList()); val tasks by dao.tasks().collectAsState(initial = emptyList()); val money by dao.moneyEntries().collectAsState(initial = emptyList()); val factories by dao.factories().collectAsState(initial = emptyList()); val lines by dao.lines().collectAsState(initial = emptyList()); val machines by dao.machines().collectAsState(initial = emptyList()); val orders by dao.orders().collectAsState(initial = emptyList()); val memories by dao.memories().collectAsState(initial = emptyList()); val documents by dao.documents().collectAsState(initial = emptyList())
    MaterialTheme(colorScheme = darkColorScheme(primary = Teal, background = Ink, surface = Panel, onBackground = Color.White, onSurface = Color.White)) {
        CompositionLocalProvider(LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
            Scaffold(containerColor = Ink, bottomBar = { NavigationBar(containerColor = Panel) { listOf("خانه" to Icons.Default.Home, "سام" to Icons.Default.GraphicEq, "گفتگو" to Icons.Default.Chat, "کارخانه" to Icons.Default.Factory, "شخصی" to Icons.Default.Person, "بیشتر" to Icons.Default.Apps).forEach { (label, icon) -> NavigationBarItem(selected = page == label, onClick = { page = label }, icon = { Icon(icon, null) }, label = { Text(label, fontSize = 11.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Teal, indicatorColor = Teal.copy(alpha=.14f), selectedTextColor = Teal, unselectedTextColor = Muted)) } } }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top=16.dp,bottom=18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column { Text("SAM", color = Teal, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp); Text("دستیار هوشمند شما", color = Muted, fontSize = 12.sp) }
                        Box(Modifier.size(44.dp).background(Panel, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.AutoAwesome, null, tint = Teal) }
                    }
                    AnimatedContent(page, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "page") { selected ->
                        when(selected) {
                            "خانه" -> HomeScreen(tasks.size, notes.size, factories.size, lines.size, money.sumOf { if(it.kind=="درآمد") it.amount else -it.amount }, reply) { page = it }
                            "سام" -> VoiceAssistantScreen(onActivate = {
                                val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                                if (android.os.Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) VoiceForegroundService.start(context)
                                else voiceServiceLauncher.launch(perms.toTypedArray())
                            })
                            "گفتگو" -> ChatScreen(prompt, {prompt=it}, reply, chatMessages, busy, voiceState, voicePartial, { voice.speak(reply) }, { if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) voice.startListening() else micPermission.launch(Manifest.permission.RECORD_AUDIO) }, { voice.stop() }, { scope.launch { busy=true; val userMessage=prompt.trim(); prompt=""; try {
                                val priorHistory=dao.allChatMessages().takeLast(20).map { ChatTurn(it.role,it.content) }
                                dao.addChatMessage(ChatMessageEntity(role="user",content=userMessage))
                                reply = when (val intent = OfflineBrain.interpret(userMessage)) {
                                    is SamIntent.AddTask -> { dao.addTask(TaskEntity(title=intent.title)); "کار «${intent.title}» بدون اینترنت ثبت شد." }
                                    is SamIntent.AddNote -> { dao.addNote(NoteEntity(title=intent.text.take(48).ifBlank { "یادداشت صوتی" }, body=intent.text)); "یادداشتت ذخیره شد: ${intent.text}" }
                                    is SamIntent.AddMoney -> { dao.addMoneyEntry(MoneyEntryEntity(title=intent.title,amount=intent.amount,kind=if(intent.isIncome) "درآمد" else "هزینه",category="ثبت صوتی")); "${if(intent.isIncome) "درآمد" else "هزینه"} ${intent.amount} ثبت شد." }
                                    is SamIntent.Calculate -> "نتیجه محاسبه: ${intent.result}"
                                    is SamIntent.Query -> when(intent.topic) {
                                        "کارها" -> { val open=dao.tasks().first().filter { !it.done }; if(open.isEmpty()) "هیچ کار بازی ثبت نشده است." else "${open.size} کار باز داری: "+open.take(5).joinToString("، ") { it.title } }
                                        "مالی" -> { val entries=dao.moneyEntries().first(); val balance=entries.sumOf { if(it.kind=="درآمد") it.amount else -it.amount }; "مانده ثبت‌شده: ${NumberFormat.getNumberInstance(Locale("fa","IR")).format(balance)} تومان، بر اساس ${entries.size} تراکنش." }
                                        else -> { val os=dao.orders().first(); "${os.size} سفارش تولید ثبت شده است؛ مجموع مقدار برنامه‌ریزی‌شده ${os.sumOf { it.plannedQuantity }} واحد است. این عدد کل سفارش‌های ثبت‌شده است، نه الزاماً تولید امروز." }
                                    }
                                    SamIntent.Unknown -> ai.respond(userMessage,priorHistory)
                                }
                                dao.addChatMessage(ChatMessageEntity(role="assistant",content=reply))
                                voice.speak(reply)
                            } catch (e: Exception) { reply="خطا در پردازش درخواست: ${e.message ?: "نامشخص"}"; runCatching { dao.addChatMessage(ChatMessageEntity(role="assistant",content=reply)) } } finally { busy=false } } })
                            "کارخانه" -> FactoryScreen(dao, factories, lines, machines, orders)
                            "داده‌ها" -> DynamicScreen(dyn)
                            "ابزار کارخانه" -> FactoryToolsScreen(dao, lines, orders) { page = it }
                            "شخصی" -> PersonalScreen(dao, notes, tasks, money, memories)
                            "ماشین‌حساب" -> CalculatorScreen { page = "بیشتر" }
                            "تنظیم هوش مصنوعی" -> AiSettingsScreen(ai) { page = "بیشتر" }
                            else -> MoreScreen(db, dao, dyn, documents, memories, money, notes, tasks, ai, { page=it }, { page="گفتگو" })
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun HomeScreen(taskCount:Int,noteCount:Int,factoryCount:Int,lineCount:Int,balance:Long,reply:String,onNavigate:(String)->Unit) {
    LazyColumn(verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { Text("خوش برگشتی", color=Color.White,fontSize=25.sp,fontWeight=FontWeight.Bold); Text("نمای کلی امروز",color=Muted) }
        item { Card(colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(24.dp)) { Column(Modifier.fillMaxWidth().padding(20.dp)) { Text("سام آماده است",color=Teal,fontWeight=FontWeight.SemiBold); Spacer(Modifier.height(8.dp)); Text(reply,color=Color.White); Spacer(Modifier.height(12.dp)); Button(onClick={onNavigate("گفتگو")},colors=ButtonDefaults.buttonColors(containerColor=Teal,contentColor=Ink)) { Icon(Icons.Default.Mic,null); Spacer(Modifier.width(8.dp)); Text("شروع گفتگو با سام") } } } }
        item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { MetricCard("کارهای باز",taskCount.toString(),Icons.Default.CheckCircle,Modifier.weight(1f)); MetricCard("یادداشت‌ها",noteCount.toString(),Icons.Default.StickyNote2,Modifier.weight(1f)) } }
        item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { MetricCard("کارخانه‌ها",factoryCount.toString(),Icons.Default.Factory,Modifier.weight(1f)); MetricCard("خطوط تولید",lineCount.toString(),Icons.Default.Settings,Modifier.weight(1f)) } }
        item { Card(colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(20.dp)) { Column(Modifier.fillMaxWidth().padding(18.dp)) { Text("مانده خالص ثبت‌شده",color=Muted); Text(NumberFormat.getNumberInstance(Locale("fa","IR")).format(balance)+" تومان",fontSize=24.sp,fontWeight=FontWeight.Bold,color=Teal); Text("بر اساس تراکنش‌های واردشده",color=Muted,fontSize=11.sp) } } }
        item { Text("دسترسی سریع",color=Color.White,fontWeight=FontWeight.Bold,fontSize=18.sp) }
        item { listOf("گفتگو","کارخانه","شخصی","بیشتر").forEach { label -> Card(Modifier.fillMaxWidth().padding(bottom=6.dp).clickable{onNavigate(label)},colors=CardDefaults.cardColors(containerColor=Panel)) { Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) { Text(label,Modifier.weight(1f)); Icon(Icons.Default.ChevronLeft,null,tint=Teal) } } } }
    }
}
@Composable private fun MetricCard(label:String,value:String,icon:androidx.compose.ui.graphics.vector.ImageVector,modifier:Modifier=Modifier){ Card(modifier,colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(20.dp)){Column(Modifier.padding(16.dp)){Icon(icon,null,tint=Teal);Spacer(Modifier.height(10.dp));Text(value,fontSize=24.sp,fontWeight=FontWeight.Bold);Text(label,color=Muted,fontSize=12.sp)}} }

@Composable private fun ChatScreen(prompt:String,onPrompt:(String)->Unit,reply:String,messages:List<ChatMessageEntity>,busy:Boolean,state:String,partial:String,onSpeak:()->Unit,onMic:()->Unit,onStop:()->Unit,onSend:()->Unit){
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("گفتگوی هوشمند",fontSize=22.sp,fontWeight=FontWeight.Bold); Text(state,color=Teal,fontSize=12.sp); if(partial.isNotBlank()) Text("در حال تشخیص: $partial",color=Muted,fontSize=13.sp)
        Card(Modifier.weight(1f).fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(22.dp)){LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){if(messages.isEmpty()){item{Text("برای شروع، پیام بنویس یا دکمه میکروفن را بزن.",color=Muted)}} else items(messages,key={it.id}){m->Column(horizontalAlignment=if(m.role=="user")Alignment.End else Alignment.Start,modifier=Modifier.fillMaxWidth()){Text(if(m.role=="user")"شما" else "سام",color=Teal,fontWeight=FontWeight.Bold);Text(m.content,color=Color.White)}}}}
        OutlinedTextField(prompt,onPrompt,Modifier.fillMaxWidth(),placeholder={Text("پیامت را بنویس…")},shape=RoundedCornerShape(18.dp),maxLines=4)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){ FilledTonalButton(onClick=onMic,modifier=Modifier.weight(1f)){Icon(Icons.Default.Mic,null);Text("صحبت")}; FilledTonalButton(onClick=onSpeak,enabled=reply.isNotBlank()){Icon(Icons.Default.VolumeUp,null)}; FilledTonalButton(onClick=onStop){Icon(Icons.Default.Stop,null)}; Button(onClick=onSend,enabled=!busy && prompt.isNotBlank(),modifier=Modifier.weight(1f)){if(busy)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp) else Icon(Icons.Default.Send,null);Text("ارسال")} }
        Text("ورودی گفتار فارسی به سرویس تشخیص گفتار نصب‌شده روی گوشی وابسته است. برای صدای فارسی، موتور TTS و دادهٔ فارسی باید در تنظیمات سامسونگ نصب باشند.",color=Muted,fontSize=11.sp)
    }
}

@Composable private fun FactoryScreen(dao:SamDao,factories:List<FactoryEntity>,lines:List<ProductionLineEntity>,machines:List<MachineEntity>,orders:List<ProductionOrderEntity>){
    var tab by remember{mutableStateOf("کارخانه")}
    var name by remember{mutableStateOf("")}
    var capacity by remember{mutableStateOf("")}
    var planned by remember{mutableStateOf("")}
    var actual by remember{mutableStateOf("")}
    var dueJalali by remember{mutableStateOf("")}
    var code by remember{mutableStateOf("")}
    var machineStatus by remember{mutableStateOf("فعال")}
    var orderStatus by remember{mutableStateOf("برنامه‌ریزی‌شده")}
    var selectedFactory by remember{mutableLongStateOf(0L)}
    var selectedLine by remember{mutableLongStateOf(0L)}
    var editingId by remember{mutableLongStateOf(0L)}
    var status by remember{mutableStateOf("")}
    val scope= rememberCoroutineScope()
    fun reset(){editingId=0;name="";capacity="";planned="";actual="";dueJalali="";code="";machineStatus="فعال";orderStatus="برنامه‌ریزی‌شده"}
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("مدیریت کارخانه",fontSize=22.sp,fontWeight=FontWeight.Bold)
        ScrollableTabRow(selectedTabIndex=listOf("کارخانه","خط تولید","ماشین","سفارش").indexOf(tab),containerColor=Ink,contentColor=Teal){listOf("کارخانه","خط تولید","ماشین","سفارش").forEach{Tab(tab==it,{tab=it;reset()},text={Text(it)})}}
        OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text(when(tab){"کارخانه"->"نام کارخانه";"خط تولید"->"نام خط تولید";"ماشین"->"نام ماشین";else->"نام محصول / سفارش"})},singleLine=true)
        if(tab!="کارخانه") Text("کارخانه انتخاب‌شده: ${factories.firstOrNull{it.id==selectedFactory}?.name ?: "انتخاب نشده"}",color=Muted,fontSize=12.sp)
        if(tab=="ماشین"||tab=="سفارش") Text("خط انتخاب‌شده: ${lines.firstOrNull{it.id==selectedLine}?.name ?: "انتخاب نشده"}",color=Muted,fontSize=12.sp)
        if(tab=="خط تولید"||tab=="ماشین") OutlinedTextField(capacity,{capacity=it},Modifier.fillMaxWidth(),label={Text(if(tab=="خط تولید")"ظرفیت روزانه خط (کیلوگرم)" else "ظرفیت ماشین")},singleLine=true)
        if(tab=="ماشین") {
            OutlinedTextField(code,{code=it},Modifier.fillMaxWidth(),label={Text("کد ماشین")},singleLine=true)
            TextButton(onClick={machineStatus=when(machineStatus){"فعال"->"متوقف";"متوقف"->"تعمیرات";"تعمیرات"->"آماده‌به‌کار";else->"فعال"}}) { Text("وضعیت ماشین: $machineStatus") }
        }
        if(tab=="سفارش") {
            OutlinedTextField(planned,{planned=it},Modifier.fillMaxWidth(),label={Text("مقدار برنامه‌ریزی‌شده")},singleLine=true)
            OutlinedTextField(actual,{actual=it},Modifier.fillMaxWidth(),label={Text("مقدار تولید واقعی")},singleLine=true)
            OutlinedTextField(dueJalali,{dueJalali=it},Modifier.fillMaxWidth(),label={Text("موعد تحویل شمسی (اختیاری: ۱۴۰۵/۰۹/۱۵)")},singleLine=true)
            TextButton(onClick={orderStatus=when(orderStatus){"برنامه‌ریزی‌شده"->"در حال تولید";"در حال تولید"->"متوقف";"متوقف"->"تکمیل‌شده";else->"برنامه‌ریزی‌شده"}}) { Text("وضعیت سفارش: $orderStatus") }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Button(onClick={scope.launch{
                val n=name.trim()
                if(n.isBlank()){status="نام را وارد کن.";return@launch}
                runCatching {
                    when(tab){
                        "کارخانه" -> { val old=factories.firstOrNull{it.id==editingId}; if(old==null) dao.addFactory(FactoryEntity(name=n)) else dao.updateFactory(old.copy(name=n)) }
                        "خط تولید" -> { val cap=parseFaDouble(capacity) ?: error("ظرفیت معتبر وارد کن"); require(cap>=0); if(selectedFactory<=0) error("ابتدا کارخانه را انتخاب کن"); val old=lines.firstOrNull{it.id==editingId}; if(old==null) dao.addLine(ProductionLineEntity(factoryId=selectedFactory,name=n,capacityPerDay=cap)) else dao.updateLine(old.copy(factoryId=selectedFactory,name=n,capacityPerDay=cap)) }
                        "ماشین" -> { val cap=parseFaDouble(capacity) ?: 0.0; require(cap>=0); if(selectedLine<=0) error("ابتدا خط را انتخاب کن"); val old=machines.firstOrNull{it.id==editingId}; if(old==null) dao.addMachine(MachineEntity(lineId=selectedLine,name=n,code=code,capacity=cap,status=machineStatus)) else dao.updateMachine(old.copy(lineId=selectedLine,name=n,code=code,capacity=cap,status=machineStatus)) }
                        else -> { val p=parseFaDouble(planned) ?: error("مقدار برنامه‌ریزی‌شده معتبر نیست"); val a=parseFaDouble(actual) ?: 0.0; require(p>=0 && a>=0); if(selectedLine<=0) error("ابتدا خط را انتخاب کن"); val due=if(dueJalali.isBlank()) null else { val parts=JalaliDate.toEnglishDigits(dueJalali).split("/","-").map{it.toInt()}; require(parts.size==3); val g=JalaliDate.toGregorian(JalaliDate(parts[0],parts[1],parts[2])); LocalDate.of(g.first,g.second,g.third).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }; val old=orders.firstOrNull{it.id==editingId}; if(old==null) dao.addOrder(ProductionOrderEntity(lineId=selectedLine,product=n,plannedQuantity=p,actualQuantity=a,dueAt=due,status=orderStatus)) else dao.updateOrder(old.copy(lineId=selectedLine,product=n,plannedQuantity=p,actualQuantity=a,dueAt=due,status=orderStatus)) }
                    }
                }.onSuccess{status=if(editingId==0L)"اطلاعات ثبت شد." else "ویرایش ذخیره شد.";reset()}.onFailure{status="عملیات انجام نشد: ${it.message}"}
            }}){Text(if(editingId==0L)"افزودن" else "ذخیره ویرایش")}
            if(editingId!=0L) OutlinedButton(onClick={reset()}){Text("لغو")}
        }
        Text(status,color=Muted,fontSize=12.sp)
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){
            when(tab){
                "کارخانه" -> items(factories,key={it.id}){item->FactoryManageRow(item.name,"شناسه ${item.id}",item.id==selectedFactory,{selectedFactory=item.id;editingId=item.id;name=item.name},{scope.launch{if(lines.any{it.factoryId==item.id})status="ابتدا خطوط وابسته را منتقل یا حذف کن." else runCatching{dao.deleteFactory(item)}.onSuccess{status="کارخانه حذف شد."}}})}
                "خط تولید" -> items(lines,key={it.id}){item->FactoryManageRow(item.name,"کارخانه ${item.factoryId} • ظرفیت ${item.capacityPerDay} ${item.unit}",item.id==selectedLine,{selectedLine=item.id;editingId=item.id;selectedFactory=item.factoryId;name=item.name;capacity=item.capacityPerDay.toString()},{scope.launch{if(machines.any{it.lineId==item.id}||orders.any{it.lineId==item.id})status="ابتدا ماشین‌ها و سفارش‌های این خط را منتقل یا حذف کن." else runCatching{dao.deleteLine(item)}.onSuccess{status="خط حذف شد."}}})}
                "ماشین" -> items(machines,key={it.id}){item->FactoryManageRow(item.name,"خط ${item.lineId} • ${item.status} • ${item.code}",false,{selectedLine=item.lineId;editingId=item.id;name=item.name;capacity=item.capacity.toString();code=item.code;machineStatus=item.status},{scope.launch{runCatching{dao.deleteMachine(item)}.onSuccess{status="ماشین حذف شد."}}})}
                else -> items(orders,key={it.id}){item->FactoryManageRow(item.product,"${item.status} • برنامه ${item.plannedQuantity} • واقعی ${item.actualQuantity}",false,{selectedLine=item.lineId;editingId=item.id;name=item.product;planned=item.plannedQuantity.toString();actual=item.actualQuantity.toString();orderStatus=item.status;dueJalali=item.dueAt?.let{val dt=Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate();val j=JalaliDate.fromGregorian(dt.year,dt.monthValue,dt.dayOfMonth);"${j.year}/${j.month.toString().padStart(2,'0')}/${j.day.toString().padStart(2,'0')}"}.orEmpty()},{scope.launch{runCatching{dao.deleteOrder(item)}.onSuccess{status="سفارش حذف شد."}}})}
            }
        }
    }
}

@Composable private fun FactoryManageRow(title:String,subtitle:String,selected:Boolean,onEdit:()->Unit,onDelete:()->Unit){
    Card(colors=CardDefaults.cardColors(containerColor=if(selected)Teal.copy(alpha=.14f) else Panel),shape=RoundedCornerShape(14.dp)){
        Column(Modifier.fillMaxWidth().padding(12.dp)){
            Text(title,fontWeight=FontWeight.SemiBold);Text(subtitle,color=Muted,fontSize=11.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                TextButton(onClick=onEdit){Text("انتخاب / ویرایش")}
                TextButton(onClick=onDelete){Text("حذف",color=MaterialTheme.colorScheme.error)}
            }
        }
    }
}

@Composable private fun PersonalScreen(dao:SamDao,notes:List<NoteEntity>,tasks:List<TaskEntity>,money:List<MoneyEntryEntity>,memories:List<MemoryEntity>){
    var tab by remember{mutableStateOf("یادداشت")}
    var title by remember{mutableStateOf("")}; var body by remember{mutableStateOf("")}; var amount by remember{mutableStateOf("")}
    var editingId by remember{mutableLongStateOf(0L)}; var status by remember{mutableStateOf("")}; var moneyKind by remember{mutableStateOf("هزینه")}
    val scope= rememberCoroutineScope()
    fun reset(){editingId=0;title="";body="";amount="";moneyKind="هزینه"}
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("مدیریت شخصی",fontSize=22.sp,fontWeight=FontWeight.Bold)
        ScrollableTabRow(selectedTabIndex=listOf("یادداشت","وظیفه","مالی","حافظه").indexOf(tab),containerColor=Ink,contentColor=Teal){listOf("یادداشت","وظیفه","مالی","حافظه").forEach{Tab(tab==it,{tab=it;reset()},text={Text(it)})}}
        OutlinedTextField(title,{title=it},Modifier.fillMaxWidth(),label={Text(when(tab){"یادداشت"->"عنوان";"وظیفه"->"عنوان کار";"مالی"->"شرح تراکنش";else->"مطلبی برای حافظه سام"})},singleLine=true)
        if(tab=="یادداشت"||tab=="حافظه") OutlinedTextField(body,{body=it},Modifier.fillMaxWidth(),label={Text("توضیحات")},minLines=2)
        if(tab=="مالی") { OutlinedTextField(amount,{amount=it.filter{c->c.isDigit()}},Modifier.fillMaxWidth(),label={Text("مبلغ به تومان")},singleLine=true); TextButton(onClick={moneyKind=if(moneyKind=="هزینه")"درآمد" else "هزینه"}){Text("نوع تراکنش: $moneyKind (برای تغییر لمس کن)")} }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Button(onClick={scope.launch{runCatching{
                when(tab){
                    "یادداشت" -> { val old=notes.firstOrNull{it.id==editingId}; val item=old?.copy(title=title,body=body,updatedAt=System.currentTimeMillis()) ?: NoteEntity(title=title,body=body); if(editingId==0L)dao.addNote(item) else dao.updateNote(item) }
                    "وظیفه" -> { val old=tasks.firstOrNull{it.id==editingId}; val item=old?.copy(title=title) ?: TaskEntity(title=title); if(editingId==0L)dao.addTask(item) else dao.updateTask(item) }
                    "مالی" -> { val value=parseFaLong(amount)?:error("مبلغ معتبر وارد کن"); val old=money.firstOrNull{it.id==editingId}; val item=old?.copy(title=title,amount=value) ?: MoneyEntryEntity(title=title,amount=value,kind=moneyKind,category="عمومی"); if(editingId==0L)dao.addMoneyEntry(item) else dao.updateMoneyEntry(item) }
                    else -> { val old=memories.firstOrNull{it.id==editingId}; val item=old?.copy(content=(title+" "+body).trim()) ?: MemoryEntity(content=(title+" "+body).trim(),category="شخصی"); if(editingId==0L)dao.addMemory(item) else dao.updateMemory(item) }
                }
            }.onSuccess{status="اطلاعات ذخیره شد.";reset()}.onFailure{status="خطا: ${it.message}"}}},enabled=title.isNotBlank()){Text(if(editingId==0L)"ثبت" else "ذخیره ویرایش")}
            if(editingId!=0L) OutlinedButton(onClick={reset()}){Text("لغو")}
        }
        Text(status,color=Muted,fontSize=12.sp)
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){
            when(tab){
                "یادداشت" -> items(notes,key={it.id}){e->PersonalManageRow(e.title,e.body,{editingId=e.id;title=e.title;body=e.body},{scope.launch{runCatching{dao.deleteNote(e)}.onSuccess{status="یادداشت حذف شد."}}})}
                "وظیفه" -> items(tasks,key={it.id}){e->Card(colors=CardDefaults.cardColors(containerColor=Panel)){Column(Modifier.fillMaxWidth().padding(10.dp)){Row(verticalAlignment=Alignment.CenterVertically){Checkbox(e.done,{scope.launch{dao.updateTask(e.copy(done=it))}});Text(e.title,Modifier.weight(1f))};Row{TextButton(onClick={editingId=e.id;title=e.title}){Text("ویرایش")};TextButton(onClick={scope.launch{runCatching{dao.deleteTask(e)}.onSuccess{status="کار حذف شد."}}}){Text("حذف",color=MaterialTheme.colorScheme.error)}}}}}
                "مالی" -> items(money,key={it.id}){e->PersonalManageRow(e.title,NumberFormat.getNumberInstance(Locale("fa","IR")).format(e.amount)+" تومان • ${e.kind}",{editingId=e.id;title=e.title;amount=e.amount.toString();moneyKind=e.kind},{scope.launch{runCatching{dao.deleteMoneyEntry(e)}.onSuccess{status="تراکنش حذف شد."}}})}
                else -> items(memories,key={it.id}){e->PersonalManageRow(e.content,e.category,{editingId=e.id;title=e.content;body=""},{scope.launch{runCatching{dao.deleteMemory(e)}.onSuccess{status="مورد حافظه حذف شد."}}})}
            }
        }
    }
}

@Composable private fun PersonalManageRow(title:String,subtitle:String,onEdit:()->Unit,onDelete:()->Unit){
    Card(colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(14.dp)){
        Column(Modifier.fillMaxWidth().padding(12.dp)){
            Text(title,fontWeight=FontWeight.SemiBold);if(subtitle.isNotBlank())Text(subtitle,color=Muted,fontSize=12.sp)
            Row { TextButton(onClick=onEdit){Text("ویرایش")};TextButton(onClick=onDelete){Text("حذف",color=MaterialTheme.colorScheme.error)} }
        }
    }
}

@Composable private fun MoreScreen(db:SamDatabase,dao:SamDao,dyn:DynamicDao,documents:List<DocumentEntity>,memories:List<MemoryEntity>,money:List<MoneyEntryEntity>,notes:List<NoteEntity>,tasks:List<TaskEntity>,ai:AiRouter,navigate:(String)->Unit,chat:()->Unit){
    val context= LocalContext.current
    val scope= rememberCoroutineScope()
    var status by remember{mutableStateOf("پشتیبان شامل اطلاعات شخصی، تاریخچه گفتگو و داده‌های تولیدی است؛ فایل‌های اصلی اسناد داخل JSON کپی نمی‌شوند.")}
    var showRestoreConfirm by remember{mutableStateOf(false)}
    var pendingRestore by remember{mutableStateOf<android.net.Uri?>(null)}
    val picker= rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null) scope.launch {
            runCatching { context.contentResolver.takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            runCatching { dao.addDocument(DocumentEntity(name=uri.lastPathSegment?:"سند",uri=uri.toString(),mimeType=context.contentResolver.getType(uri)?:"application/octet-stream")) }
                .onSuccess { status="سند به فهرست اضافه شد." }.onFailure { status="ثبت سند ناموفق بود: ${it.message}" }
        }
    }
    val backupPicker=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        if(uri!=null) scope.launch { runCatching { SamBackup.export(context,uri,dao,dyn) }.onSuccess { status="پشتیبان کامل SAM ذخیره شد." }.onFailure { status="خطا در پشتیبان‌گیری: ${it.message}" } }
    }
    val restorePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){pendingRestore=uri;showRestoreConfirm=true}}
    if(showRestoreConfirm) AlertDialog(
        onDismissRequest={showRestoreConfirm=false;pendingRestore=null},
        title={Text("بازیابی اطلاعات؟")},
        text={Text("این کار اطلاعات فعلی برنامه را با محتوای فایل پشتیبان جایگزین می‌کند. قبل از ادامه، یک نسخه پشتیبان از وضعیت فعلی بگیر.")},
        confirmButton={TextButton(onClick={val uri=pendingRestore;showRestoreConfirm=false;pendingRestore=null;if(uri!=null)scope.launch{runCatching{SamBackup.restore(context,uri,db)}.onSuccess{status="بازیابی کامل شد."}.onFailure{status="بازیابی انجام نشد؛ داده‌های فعلی دست‌نخورده ماندند. ${it.message}"}}}){Text("بازیابی کن")}},
        dismissButton={TextButton(onClick={showRestoreConfirm=false;pendingRestore=null}){Text("انصراف")}}
    )
    LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Text("ابزارها و تنظیمات",fontSize=22.sp,fontWeight=FontWeight.Bold)}
        item{ToolCard("ماشین‌حساب", "محاسبه سریع با ارقام فارسی و انگلیسی"){navigate("ماشین‌حساب")}}
        item{ToolCard("داده‌ساز پویا", "ساخت ماژول و فیلد دلخواه بدون تغییر کد"){navigate("داده‌ها")}}
        item{ToolCard("ابزار کارخانه", "کنترل تولید و وضعیت تولید (OEE)"){navigate("ابزار کارخانه")}}
        item{ToolCard("مدیریت اسناد", "انتخاب و ثبت سند از حافظه دستگاه"){picker.launch(arrayOf("*/*"))}}
        item{Text(status,color=Muted,fontSize=12.sp)}
        items(documents,key={it.id}){doc->PersonalManageRow(doc.name,doc.mimeType,{status="برای افزودن فایل جایگزین، از انتخاب سند استفاده کن."},{scope.launch{runCatching{dao.deleteDocument(doc)}.onSuccess{status="سند از فهرست حذف شد."}}})}
        item{ToolCard("حافظه بلندمدت", "${memories.size} مورد ذخیره‌شده"){chat()}}
        item{ToolCard("پشتیبان‌گیری کامل", "خروجی نسخه‌دار از اطلاعات شخصی و کارخانه"){backupPicker.launch("SAM-backup.json")}}
        item{ToolCard("بازیابی پشتیبان", "اعتبارسنجی فایل و جایگزینی تراکنشی اطلاعات"){restorePicker.launch(arrayOf("application/json","text/*"))}}
        item{ToolCard("تنظیم هوش مصنوعی", "اتصال اختیاری به سرویس سازگار یا مدل محلی"){navigate("تنظیم هوش مصنوعی")}}
        item{ToolCard("اتصال Google / Microsoft", "اتصال ابری پس از تنظیم OAuth معتبر"){status="همگام‌سازی ابری هنوز به شناسه OAuth و پیاده‌سازی اتصال نیاز دارد؛ در این نسخه ادعای اتصال فعال نمی‌شود."}}
        item{ToolCard("امنیت و گزارش عملیات", "تأیید عملیات حساس و ثبت رخدادها"){status="عملیات حساس در هسته اجرایی نیازمند تأیید صریح هستند."}}
    }
}
@Composable private fun ToolCard(title:String,description:String,onClick:()->Unit){Card(Modifier.fillMaxWidth().clickable{onClick()},colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(18.dp)){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,fontWeight=FontWeight.SemiBold);Text(description,color=Muted,fontSize=12.sp)};Icon(Icons.Default.ChevronLeft,null,tint=Teal)}}}
@Composable private fun DataRow(title:String,subtitle:String,onClick:(()->Unit)?=null,selected:Boolean=false){Card(Modifier.fillMaxWidth().then(if(onClick!=null)Modifier.clickable{onClick()}else Modifier),colors=CardDefaults.cardColors(containerColor=if(selected)Teal.copy(alpha=.14f)else Panel),shape=RoundedCornerShape(14.dp)){Column(Modifier.padding(14.dp)){Text(title,fontWeight=FontWeight.SemiBold);if(subtitle.isNotBlank())Text(subtitle,color=Muted,fontSize=12.sp)}}}

@Composable private fun AiSettingsScreen(ai: AiRouter, onBack: () -> Unit) {
    var endpoint by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("کلید در Android Keystore رمز می‌شود و در کد منبع ذخیره نمی‌شود. استفاده از سرویس آنلاین اختیاری است.") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { runCatching { ai.readSettings() }.onSuccess { settings -> endpoint = settings.endpoint; model = settings.model } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowForward, null, tint = Teal) }; Text("تنظیم هوش مصنوعی", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("نشانی API سازگار با OpenAI") }, placeholder = { Text("https://…/v1/chat/completions") }, singleLine = true)
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("شناسه مدل") }, singleLine = true)
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("کلید API (اختیاری برای سرویس‌های محلی بدون کلید)") }, singleLine = true)
        Button(onClick = { scope.launch { runCatching { ai.configure(endpoint, key, model) }.onSuccess { status = "تنظیمات ذخیره شد. کلید با Android Keystore رمز شده است." }.onFailure { status = "ذخیره تنظیمات ناموفق بود: ${it.message}" } } }, enabled = endpoint.startsWith("https://") || endpoint.startsWith("http://10.0.2.2") || endpoint.startsWith("http://127.0.0.1") || endpoint.startsWith("http://localhost"), modifier = Modifier.fillMaxWidth()) { Text("ذخیره تنظیمات") }
        Text(status, color = Muted, fontSize = 12.sp)
        Text("برای حفظ کلید قبلی، فیلد کلید را خالی بگذار. ارتباط HTTP فقط برای localhost و آدرس شبیه‌ساز مجاز است.", color = Muted, fontSize = 11.sp)
        Text("نکته: واردکردن کلید به معنی رایگان‌بودن سرویس نیست. از endpoint/model رایگان یا سرور محلی خودت استفاده کن؛ هیچ خرید یا اشتراکی از طرف برنامه فعال نمی‌شود.", color = Muted, fontSize = 12.sp)
    }
}

@Composable private fun CalculatorScreen(onBack:()->Unit){
    var expression by remember{mutableStateOf("")}; var result by remember{mutableStateOf("")}
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)){Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onBack){Icon(Icons.Default.ArrowForward,null,tint=Teal)};Text("ماشین‌حساب",fontSize=22.sp,fontWeight=FontWeight.Bold)};Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(20.dp)){Column(Modifier.fillMaxWidth().padding(20.dp),horizontalAlignment=Alignment.End){Text(expression.ifBlank{"۰"},fontSize=24.sp);Text(result,color=Teal,fontSize=30.sp,fontWeight=FontWeight.Bold)}}
        val keys=listOf(listOf("۷","۸","۹","÷"),listOf("۴","۵","۶","×"),listOf("۱","۲","۳","−"),listOf("۰",".","C","+"),listOf("⌫","=") )
        keys.forEach{row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){row.forEach{key->Button(onClick={when(key){"C"->{expression="";result=""};"⌫"->{expression=expression.dropLast(1)};"="->{result=runCatching{SafeMath.evaluate(expression)}.fold({NumberFormat.getNumberInstance(Locale("fa","IR")).format(it)}, {"عبارت نامعتبر"})};else->expression+=key}},modifier=Modifier.weight(1f).height(58.dp),colors=ButtonDefaults.buttonColors(containerColor=if(key=="=")Teal else Panel,contentColor=if(key=="=")Ink else Color.White),shape=RoundedCornerShape(14.dp)){Text(key,fontSize=21.sp)}}}}
    }
}

object SafeMath {
    fun evaluate(raw: String): Double {
        val s = raw.replace("۰", "0").replace("۱", "1").replace("۲", "2").replace("۳", "3").replace("۴", "4").replace("۵", "5").replace("۶", "6").replace("۷", "7").replace("۸", "8").replace("۹", "9").replace("٫", ".").replace("÷", "/").replace("×", "*").replace("−", "-").replace(" ", "")
        require(s.isNotBlank() && s.length <= 120 && s.matches(Regex("[0-9.+*/()\\-]+")))
        var pos = 0
        lateinit var expr: () -> Double
        lateinit var term: () -> Double
        lateinit var factor: () -> Double
        expr = {
            var value = term()
            while (pos < s.length && (s[pos] == '+' || s[pos] == '-')) {
                val op = s[pos++]; val rhs = term(); value = if (op == '+') value + rhs else value - rhs
            }
            value
        }
        term = {
            var value = factor()
            while (pos < s.length && (s[pos] == '*' || s[pos] == '/')) {
                val op = s[pos++]; val rhs = factor(); require(op != '/' || rhs != 0.0); value = if (op == '*') value * rhs else value / rhs
            }
            value
        }
        factor = {
            if (pos < s.length && s[pos] == '-') { pos++; -factor() }
            else if (pos < s.length && s[pos] == '+') { pos++; factor() }
            else if (pos < s.length && s[pos] == '(') { pos++; val value = expr(); require(pos < s.length && s[pos++] == ')'); value }
            else {
                val begin = pos
                while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
                require(pos > begin)
                s.substring(begin, pos).toDouble()
            }
        }
        val value = expr()
        require(pos == s.length && value.isFinite())
        return value
    }
}
