package com.wolf.localmanager

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        ContextCompat.startForegroundService(this, Intent(this, BotService::class.java))
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = Color(0xFFCDBCFF), onPrimary = Color(0xFF381E72),
                        background = Color(0xFF141218), surface = Color(0xFF141218),
                        onBackground = Color(0xFFE6E0E9), onSurface = Color(0xFFE6E0E9)
                    )
                ) { App() }
            }
        }
    }
}

sealed class Screen {
    object Home : Screen()
    data class Sched(val id: String) : Screen()
    data class Logs(val id: String) : Screen()
    data class Cred(val id: String) : Screen()
}

class Wizard(
    val id: String, val name: String, val files: List<String>,
    val entry: String, val cred: String, val credUri: String, val isNew: Boolean
)

@Composable
fun App() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
    Surface(Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
        when (val s = screen) {
            is Screen.Home -> HomeScreen(
                onSched = { screen = Screen.Sched(it) },
                onLogs = { screen = Screen.Logs(it) },
                onCred = { screen = Screen.Cred(it) }
            )
            is Screen.Sched -> ScheduleScreen(s.id) { screen = Screen.Home }
            is Screen.Logs -> LogsScreen(s.id)
            is Screen.Cred -> CredScreen(s.id) { screen = Screen.Home }
        }
    }
}

// ------------------------------------------------------------------ import
fun importZip(ctx: Context, uri: Uri): Wizard? {
    val id = UUID.randomUUID().toString().take(8)
    return try {
        var name = "bot"
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) name = c.getString(i).removeSuffix(".zip")
            }
        }
        val tmp = File(ctx.filesDir, "bots/${id}_x")
        ctx.contentResolver.openInputStream(uri)!!.use { ZipUtil.unzip(it, tmp) }
        File(tmp, "__MACOSX").deleteRecursively()
        val kids = tmp.listFiles().orEmpty()
        val finalDir = BotStore.dir(ctx, id)
        val root = if (kids.size == 1 && kids[0].isDirectory && kids[0].name != "node_modules") kids[0] else tmp
        root.renameTo(finalDir)
        if (tmp.exists()) tmp.deleteRecursively()
        val files = ZipUtil.listFiles(finalDir)
        val entry = ZipUtil.guessEntry(finalDir, files)
        val last = CredSync.last(ctx)
        if (last != null) Wizard(id, name, files, entry, last.second, last.first, true)
        else Wizard(id, name, files, entry, ZipUtil.guessCred(files, entry), "", true)
    } catch (e: Exception) {
        toast(ctx, "فشل فك الملف: ${e.message}")
        File(ctx.filesDir, "bots/${id}_x").deleteRecursively()
        null
    }
}

// ------------------------------------------------------------------ home
@Composable
fun HomeScreen(onSched: (String) -> Unit, onLogs: (String) -> Unit, onCred: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pm = remember { ctx.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var bots by remember { mutableStateOf(BotStore.all(ctx)) }
    var status by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var wizard by remember { mutableStateOf<Wizard?>(null) }
    var deleting by remember { mutableStateOf<Bot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var batteryOk by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }

    LaunchedEffect(Unit) {
        while (true) {
            bots = BotStore.all(ctx)
            status = withContext(Dispatchers.IO) { NodeClient.statusSafe() }
            batteryOk = pm.isIgnoringBatteryOptimizations(ctx.packageName)
            delay(2000)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val w = withContext(Dispatchers.IO) { importZip(ctx, uri) }
                busy = false
                if (w != null) wizard = w
            }
        }
    }

    fun act(block: () -> String) {
        scope.launch(Dispatchers.IO) {
            val r = try { block() } catch (e: Exception) { "المحرك غير جاهز بعد: ${e.message}" }
            if (r != "ok") toast(ctx, r)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Text("🐺 WOLF Local", fontSize = 28.sp, modifier = Modifier.padding(vertical = 8.dp)) }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!batteryOk) item {
                Button(onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                    )
                }, modifier = Modifier.fillMaxWidth()) { Text("🔋 استثنِ التطبيق من توفير البطارية (مهم)") }
            }
            if (bots.isEmpty()) item {
                Text("لا توجد بوتات. اضغط ＋ لرفع ملف zip.", color = Color.Gray, modifier = Modifier.padding(24.dp))
            }
            items(bots, key = { it.id }) { b ->
                BotCard(
                    b, status[b.id],
                    onStart = { act { startBot(ctx, b) } },
                    onStop = { act { NodeClient.stop(b.id) } },
                    onAuto = { BotStore.update(ctx, b.id) { it.auto = !it.auto }; bots = BotStore.all(ctx) },
                    onSched = { onSched(b.id) },
                    onDelete = { deleting = b },
                    onLogs = { onLogs(b.id) },
                    onCred = {
                        if (b.credUri.isNotEmpty())
                            toast(ctx, "ملف الاتصال يؤخذ من الجوال: ${CredSync.displayName(ctx, Uri.parse(b.credUri))} — لتغييره افتح ⚙️ إعدادات")
                        else onCred(b.id)
                    },
                    onSettings = {
                        wizard = Wizard(b.id, b.name, ZipUtil.listFiles(BotStore.dir(ctx, b.id)), b.entry, b.cred, b.credUri, false)
                    }
                )
            }
        }

        FloatingActionButton(
            onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
            modifier = Modifier.align(Alignment.BottomStart).padding(20.dp),
            containerColor = Color(0xFF4A9EFF), contentColor = Color.White
        ) { Text("＋", fontSize = 28.sp) }
    }

    wizard?.let { w ->
        WizardDialog(
            w,
            onCancel = {
                wizard = null
                if (w.isNew) scope.launch(Dispatchers.IO) { BotStore.dir(ctx, w.id).deleteRecursively() }
            },
            onOk = { name, entry, target, cu ->
                wizard = null
                scope.launch(Dispatchers.IO) {
                    if (cu.isEmpty() && target.isNotEmpty()) CredUtil.normalizeFile(BotStore.dir(ctx, w.id), target)
                    if (w.isNew) BotStore.add(ctx, Bot(w.id, name, entry, target, cu, false, BooleanArray(24) { true }))
                    else BotStore.update(ctx, w.id) { it.name = name; it.entry = entry; it.cred = target; it.credUri = cu }
                    if (cu.isNotEmpty()) {
                        CredSync.remember(ctx, cu, target)
                        BotStore.all(ctx).find { it.id == w.id }?.let { CredSync.sync(ctx, it) }?.let { toast(ctx, it) }
                    }
                    bots = BotStore.all(ctx)
                }
            }
        )
    }

    deleting?.let { b ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("حذف ${b.name}؟") },
            text = { Text("سيتم إيقاف البوت وحذف ملفاته من الجوال.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch(Dispatchers.IO) {
                        try { NodeClient.stop(b.id) } catch (e: Exception) { }
                        BotStore.dir(ctx, b.id).deleteRecursively()
                        BotStore.remove(ctx, b.id)
                        bots = BotStore.all(ctx)
                    }
                }) { Text("حذف") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("إلغاء") } }
        )
    }
}

@Composable
fun BotCard(
    b: Bot, st: JSONObject?,
    onStart: () -> Unit, onStop: () -> Unit, onAuto: () -> Unit, onSched: () -> Unit, onDelete: () -> Unit,
    onLogs: () -> Unit, onCred: () -> Unit, onSettings: () -> Unit
) {
    val running = st?.optBoolean("running", false) == true
    val last = st?.optLong("last", 0L) ?: 0L
    val fmt = remember { SimpleDateFormat("HH:mm MM/dd", Locale.US) }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF414141)),
        shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(b.name, fontSize = 24.sp)
            Text(b.entry.ifEmpty { "— لم يُحدد ملف التشغيل —" }, fontSize = 13.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
            Text(if (running) "🟢 يعمل الآن" else "⚪ متوقف", fontSize = 17.sp)
            Text(
                if (b.auto) "🔓 التشغيل التلقائي مفعّل" else "🔒 معطّل — لن يعمل تلقائيًا",
                fontSize = 15.sp, color = Color.LightGray
            )
            if (last > 0) Text("🕐 آخر نشاط: ${fmt.format(Date(last))}", fontSize = 14.sp, color = Color.Gray)
            Text("📅 ${b.hours.count { it }}/24 ساعة مفعلة", fontSize = 15.sp, color = Color(0xFF4A9EFF))
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Act("▶️", Modifier.weight(1f), onStart)
                Act("⏹", Modifier.weight(1f), onStop)
                Act(if (b.auto) "🔓" else "🔒", Modifier.weight(1f), onAuto)
                Act("📅", Modifier.weight(1f), onSched)
                Act("🗑", Modifier.weight(1f), onDelete)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onLogs, modifier = Modifier.weight(1f)) { Text("📜 السجل") }
                TextButton(onClick = onCred, modifier = Modifier.weight(1f)) { Text("🔑 الاتصال") }
                TextButton(onClick = onSettings, modifier = Modifier.weight(1f)) { Text("⚙️ إعدادات") }
            }
        }
    }
}

@Composable
fun Act(label: String, modifier: Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.height(52.dp), contentPadding = PaddingValues(0.dp)) {
        Text(label, fontSize = 20.sp)
    }
}

@Composable
fun Picker(label: String, value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, fontSize = 12.sp, color = Color.Gray)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(value.ifEmpty { "— بدون —" }, maxLines = 1)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach {
                    DropdownMenuItem(text = { Text(it.ifEmpty { "— بدون —" }) }, onClick = { onPick(it); open = false })
                }
            }
        }
    }
}

@Composable
fun WizardDialog(w: Wizard, onCancel: () -> Unit, onOk: (String, String, String, String) -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(w.name) }
    var entry by remember { mutableStateOf(w.entry) }
    var target by remember { mutableStateOf(w.cred) }
    var credUri by remember { mutableStateOf(w.credUri) }
    val credLabel = remember(credUri) {
        if (credUri.isEmpty()) "" else CredSync.displayName(ctx, Uri.parse(credUri)).ifEmpty { "ملف محدد" }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
            credUri = uri.toString()
            val n = CredSync.displayName(ctx, uri)
            if (target.isEmpty() && n.isNotEmpty()) target = n
        }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (w.isNew) "بوت جديد" else "إعدادات البوت") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("الاسم") }, singleLine = true)
                Picker("ملف تشغيل البوت", entry, w.files.filter { ZipUtil.isCode(it) }) { entry = it }
                Column {
                    Text("ملف بيانات الاتصال (من الجوال، مشترك بين البوتات)", fontSize = 12.sp, color = Color.Gray)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                            Text(if (credUri.isEmpty()) "📂 اختر من التنزيلات" else "📄 $credLabel", maxLines = 1)
                        }
                        if (credUri.isNotEmpty()) TextButton(onClick = { credUri = "" }) { Text("✖") }
                    }
                }
                OutlinedTextField(
                    value = target, onValueChange = { target = it }, singleLine = true,
                    label = { Text("اسم الملف الذي يقرأه البوت (مثل x1.js)") }
                )
                if (w.files.isEmpty()) Text("لم أجد ملفات js داخل الـ zip!", color = Color(0xFFFF8A80))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onOk(name.trim().ifEmpty { w.name }, entry, target.trim(), credUri) },
                enabled = entry.isNotEmpty() && (credUri.isEmpty() || target.isNotBlank())
            ) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("إلغاء") } }
    )
}

// ------------------------------------------------------------------ schedule
@Composable
fun ScheduleScreen(id: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val bot = remember { BotStore.all(ctx).find { it.id == id } }
    if (bot == null) { LaunchedEffect(Unit) { onDone() }; return }
    val hours = remember { mutableStateListOf<Boolean>().apply { addAll(bot.hours.toList()) } }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("📅 جدولة: ${bot.name}", fontSize = 22.sp)
        Text("✅ = يعمل في هذه الساعة", fontSize = 14.sp, color = Color.Gray)
        Text("⬛ = متوقف في هذه الساعة", fontSize = 14.sp, color = Color.Gray)
        Text("الجدولة تعمل فقط عندما يكون 🔓 التشغيل التلقائي مفعّلًا. التوقيت: توقيت الجوال.", fontSize = 12.sp, color = Color.Gray)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { for (i in 0 until 24) hours[i] = true }, modifier = Modifier.weight(1f)) { Text("تحديد الكل") }
            Button(onClick = { for (i in 0 until 24) hours[i] = false }, modifier = Modifier.weight(1f)) { Text("إلغاء الكل") }
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            LazyColumn(Modifier.weight(1f)) {
                items(12) { r ->
                    Row(Modifier.fillMaxWidth()) {
                        HourCell(r, hours)
                        HourCell(r + 12, hours)
                    }
                }
            }
        }
        Button(
            onClick = { BotStore.update(ctx, id) { it.hours = hours.toBooleanArray() }; onDone() },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text("💾 حفظ الجدولة", fontSize = 17.sp) }
    }
}

@Composable
fun RowScope.HourCell(h: Int, hours: MutableList<Boolean>) {
    val h12 = if (h % 12 == 0) 12 else h % 12
    val label = String.format(Locale.US, "%02d:00 %s", h12, if (h < 12) "AM" else "PM")
    Row(
        Modifier.weight(1f).clickable { hours[h] = !hours[h] }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = hours[h], onCheckedChange = { hours[h] = it })
        Text(label, fontSize = 17.sp)
    }
}

// ------------------------------------------------------------------ logs
@Composable
fun LogsScreen(id: String) {
    val ctx = LocalContext.current
    val name = remember { BotStore.all(ctx).find { it.id == id }?.name ?: "" }
    val lines = remember { mutableStateListOf<String>() }
    val state = rememberLazyListState()

    LaunchedEffect(id) {
        var since = 0
        while (true) {
            try {
                val (l, n) = withContext(Dispatchers.IO) { NodeClient.logs(id, since) }
                if (l.isNotEmpty()) {
                    lines.addAll(l)
                    while (lines.size > 500) lines.removeAt(0)
                }
                since = n
            } catch (e: Exception) { }
            delay(1500)
        }
    }
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.scrollToItem(lines.size - 1) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("📜 سجل: $name", fontSize = 20.sp, modifier = Modifier.padding(bottom = 8.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        }
    }
}

// ------------------------------------------------------------------ credentials editor
@Composable
fun CredScreen(id: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val bot = remember { BotStore.all(ctx).find { it.id == id } }
    if (bot == null || bot.cred.isEmpty()) {
        LaunchedEffect(Unit) { toast(ctx, "لم يتم تحديد ملف بيانات الاتصال — اختره من ⚙️ إعدادات"); onDone() }
        return
    }
    val dir = BotStore.dir(ctx, id)
    var text by remember { mutableStateOf(File(dir, bot.cred).takeIf { it.exists() }?.readText() ?: "") }

    Column(Modifier.fillMaxSize().padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("🔑 ${bot.cred}", fontSize = 20.sp)
        Text("عدّل التوكنات ثم احفظ، وأعد تشغيل البوت ليقرأها. إذا كان المحتوى JSON داخل ملف js يُحوَّل تلقائيًا.", fontSize = 12.sp, color = Color.Gray)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.White)
            )
        }
        Button(
            onClick = {
                File(dir, bot.cred).writeText(CredUtil.wrap(dir, bot.cred, text))
                toast(ctx, "تم الحفظ — أعد تشغيل البوت")
                onDone()
            },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text("💾 حفظ", fontSize = 17.sp) }
    }
}
