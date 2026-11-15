package com.logscope.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF0A1020)
private val Panel = Color(0xFF141E32)
private val Panel2 = Color(0xFF1D2A43)
private val Cyan = Color(0xFF50DED4)
private val Muted = Color(0xFF93A4BE)
private val Red = Color(0xFFFF687D)
private val Amber = Color(0xFFFFC66D)
private val Green = Color(0xFF68D9A0)
private val Blue = Color(0xFF80AFFF)

class MainActivity : ComponentActivity() {
    private val requestCode = 7102
    private val shizukuRunning = mutableStateOf(false)
    private val shizukuGranted = mutableStateOf(false)
    private val connectionText = mutableStateOf("Shizuku is not connected")
    private val isCapturing = mutableStateOf(false)
    private val binderState = mutableStateOf<ILogScopeService?>(null)
    private val db by lazy { LogDatabase.get(this) }
    private var pollingJob: Job? = null
    private var bound = false
    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(this, LogReaderUserService::class.java))
            .daemon(false).tag("LogScopeAI.LogReader").version(2)
    }
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val api = ILogScopeService.Stub.asInterface(service)
            binderState.value = api
            try {
                api.startLogcat()
                isCapturing.value = true
                connectionText.value = "Live log stream active · Shizuku shell identity"
                startPolling(api)
            } catch (e: Exception) {
                connectionText.value = "Could not start logcat: ${e.message}"
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            stopPolling(); binderState.value = null; isCapturing.value = false
            connectionText.value = "Shizuku service disconnected"
        }
    }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == requestCode) {
            shizukuGranted.value = result == PackageManager.PERMISSION_GRANTED
            connectionText.value = if (shizukuGranted.value) "Shizuku permission granted" else "Shizuku permission denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        refreshShizuku()
        setContent {
            val logs by db.logs().observeRecent().collectAsState(initial = emptyList())
            val prefs = getSharedPreferences("logscope", Context.MODE_PRIVATE)
            var dark by remember { mutableStateOf(prefs.getBoolean("dark", true)) }
            LogScopeTheme(dark) {
                LogScopeApp(
                    logs = logs,
                    connected = shizukuRunning.value && shizukuGranted.value,
                    capturing = isCapturing.value,
                    status = connectionText.value,
                    dark = dark,
                    onDarkChange = { dark = it; prefs.edit().putBoolean("dark", it).apply() },
                    onConnect = { connectShizuku() },
                    onRefresh = { refreshShizuku() },
                    onStart = { startCapture() },
                    onStop = { stopCapture() },
                    onClear = { lifecycleScope.launch(Dispatchers.IO) { db.logs().clear() } },
                    onExport = { uri, format -> exportLogs(uri, format) },
                    onImport = { uri -> importLogs(uri) },
                    onAi = { endpoint, model, key, callback -> runAi(endpoint, model, key, logs, callback) }
                )
            }
        }
    }

    private fun refreshShizuku() {
        shizukuRunning.value = try { Shizuku.pingBinder() } catch (_: Exception) { false }
        shizukuGranted.value = shizukuRunning.value && try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Exception) { false }
        connectionText.value = when {
            !shizukuRunning.value -> "Shizuku service is not running. Start it, then refresh."
            shizukuGranted.value -> "Shizuku is ready. You can start a live capture."
            else -> "Shizuku is running; LogScope permission is required."
        }
    }

    private fun connectShizuku() {
        refreshShizuku()
        if (!shizukuRunning.value) return
        if (shizukuGranted.value) { connectionText.value = "Permission already granted"; return }
        if (Shizuku.isPreV11()) { connectionText.value = "Please update Shizuku to v11 or newer"; return }
        Shizuku.requestPermission(requestCode)
    }

    private fun startCapture() {
        refreshShizuku()
        if (!shizukuRunning.value) { connectionText.value = "Start Shizuku first"; return }
        if (!shizukuGranted.value) { connectShizuku(); return }
        try {
            Shizuku.bindUserService(args, serviceConnection)
            bound = true
            connectionText.value = "Connecting to the privileged log reader…"
        } catch (e: Exception) { connectionText.value = "Shizuku connection failed: ${e.message}" }
    }

    private fun startPolling(api: ILogScopeService) {
        stopPolling()
        pollingJob = lifecycleScope.launch {
            while (true) {
                val rows = try { withContext(Dispatchers.IO) { api.readAvailable(250).toList() } } catch (e: Exception) {
                    connectionText.value = "Log stream interrupted: ${e.message}"; emptyList()
                }
                if (rows.isNotEmpty()) {
                    val parsed = rows.mapNotNull(LogParser::parse)
                    if (parsed.isNotEmpty()) withContext(Dispatchers.IO) { db.logs().insertAll(parsed); db.logs().trim() }
                }
                delay(700)
            }
        }
    }

    private fun stopPolling() { pollingJob?.cancel(); pollingJob = null }
    private fun stopCapture() {
        stopPolling()
        try { binderState.value?.stopLogcat() } catch (_: Exception) { }
        if (bound) try { Shizuku.unbindUserService(args, serviceConnection, true) } catch (_: Exception) { }
        bound = false; binderState.value = null; isCapturing.value = false
        connectionText.value = "Capture stopped. Stored entries remain on this device."
    }

    private fun importLogs(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    val parsed = ArrayList<LogEntity>()
                    var count = 0
                    while (count < 100000) {
                        val line = reader.readLine() ?: break
                        LogParser.parse(line)?.let { parsed.add(it) }
                        count++
                    }
                    if (parsed.isNotEmpty()) { db.logs().insertAll(parsed); db.logs().trim() }
                }
            }.onFailure { connectionText.value = "Import failed: ${it.message}" }
        }
    }

    private fun exportLogs(uri: Uri, format: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val rows = db.logs().getRecent().asReversed()
            val text = when (format) {
                "json" -> rows.joinToString(prefix = "[\n", postfix = "\n]\n", separator = ",\n") { e ->
                    "  {\"timestamp\":${e.timestamp},\"level\":\"${jsonEscape(e.level)}\",\"pid\":\"${jsonEscape(e.pid)}\",\"tid\":\"${jsonEscape(e.tid)}\",\"tag\":\"${jsonEscape(e.tag)}\",\"category\":\"${jsonEscape(e.category)}\",\"message\":\"${jsonEscape(e.message)}\"}"
                }
                "csv" -> "timestamp,level,pid,tid,tag,category,message\n" + rows.joinToString("\n") { e -> listOf(e.timestamp.toString(),e.level,e.pid,e.tid,e.tag,e.category,e.message).joinToString(",") { csvEscape(it) } }
                else -> rows.joinToString("\n") { e -> "${Date(e.timestamp)} ${e.level}/${e.tag} (${e.pid}:${e.tid}) [${e.category}] ${e.message}" }
            }
            runCatching { contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) } }
        }
    }
    private fun jsonEscape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    private fun csvEscape(s: String) = "\"${s.replace("\"", "\"\"").replace("\n", " ")}\""

    private fun runAi(endpoint: String, model: String, key: String, logs: List<LogEntity>, callback: (String) -> Unit) {
        lifecycleScope.launch {
            val local = LocalAnalyzer.analyze(logs)
            val localText = local.joinToString("\n\n") { "${it.severity}: ${it.title}\n${it.explanation}\nNext step: ${it.action}" }
            if (key.isBlank()) { callback("LOCAL ANALYSIS\n\n$localText\n\nCloud AI is not configured. Add your own HTTPS-compatible API endpoint and key in Settings to enable optional remote analysis."); return@launch }
            callback("AI analysis is running…")
            val raw = logs.take(180).joinToString("\n") { it.raw }
            val result = withContext(Dispatchers.IO) { runCatching { AiClient.analyze(endpoint, model, key, raw) } }
            callback(result.getOrElse { "Cloud analysis failed: ${it.message}\n\nLocal findings:\n$localText" })
        }
    }

    override fun onDestroy() {
        stopCapture()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }
}

@Composable private fun LogScopeTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) darkColorScheme(primary=Cyan, background=Bg, surface=Panel, onBackground=Color(0xFFEAF0FA), onSurface=Color(0xFFEAF0FA))
    else lightColorScheme(primary=Color(0xFF087F83), background=Color(0xFFF3F6FA), surface=Color.White, onBackground=Color(0xFF152033), onSurface=Color(0xFF152033))
    MaterialTheme(colorScheme=scheme, content=content)
}

private enum class Page(val label: String) { HOME("Home"), LOGS("Logs"), CRASHES("Crashes"), AI("AI Lab"), SETTINGS("Settings") }

@Composable private fun LogScopeApp(
    logs: List<LogEntity>, connected: Boolean, capturing: Boolean, status: String, dark: Boolean,
    onDarkChange: (Boolean)->Unit, onConnect: ()->Unit, onRefresh: ()->Unit, onStart: ()->Unit, onStop: ()->Unit,
    onClear: ()->Unit, onExport: (Uri,String)->Unit, onImport: (Uri)->Unit, onAi: (String,String,String,(String)->Unit)->Unit
) {
    var page by remember { mutableStateOf(Page.HOME) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("ALL") }
    var category by remember { mutableStateOf("ALL") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("logscope", Context.MODE_PRIVATE) }
    var endpoint by remember { mutableStateOf(prefs.getString("endpoint", "https://api.openai.com/v1") ?: "https://api.openai.com/v1") }
    var model by remember { mutableStateOf(prefs.getString("model", "gpt-4o-mini") ?: "gpt-4o-mini") }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var aiResult by remember { mutableStateOf("Run an analysis to inspect the latest captured events.") }
    var busy by remember { mutableStateOf(false) }
    val txtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let { uri -> onExport(uri,"txt") } }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let { uri -> onExport(uri,"csv") } }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let { uri -> onExport(uri,"json") } }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(onImport) }
    val errors = logs.count { it.level == "ERROR" }
    val warnings = logs.count { it.level == "WARN" }
    val visible = logs.filter { e ->
        (filter == "ALL" || e.level == filter) && (category == "ALL" || e.category == category) &&
        (query.isBlank() || "${e.tag} ${e.message} ${e.category} ${e.pid}".contains(query,true))
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor=MaterialTheme.colorScheme.surface) {
                val icons = listOf(Icons.Default.Dashboard,Icons.Default.Terminal,Icons.Default.BugReport,Icons.Default.AutoAwesome,Icons.Default.Settings)
                Page.values().forEachIndexed { i,p ->
                    NavigationBarItem(selected=page==p,onClick={page=p},icon={Icon(icons[i],p.label)},label={Text(p.label,fontSize=10.sp)},colors=NavigationBarItemDefaults.colors(selectedIconColor=Cyan,selectedTextColor=Cyan,indicatorColor=Panel2))
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Header(connected,capturing)
            when(page) {
                Page.HOME -> HomePage(logs.size,errors,warnings,logs.take(5),status,capturing,onConnect,onRefresh,onStart,onStop,{page=Page.LOGS})
                Page.LOGS -> LogsPage(visible,query,{query=it},filter,{filter=it},category,{category=it},onClear,{txtLauncher.launch("logscope-session.txt")},{csvLauncher.launch("logscope-session.csv")},{jsonLauncher.launch("logscope-session.json")},{importLauncher.launch(arrayOf("text/*","application/octet-stream"))})
                Page.CRASHES -> CrashPage(logs.filter { it.category=="CRASH" || it.category=="ANR" || (it.level=="ERROR" && (it.message.contains("exception",true) || it.message.contains("fatal",true))) })
                Page.AI -> AiPage(aiResult,busy,apiKey.isNotBlank(),{ busy=true; onAi(endpoint,model,apiKey) { aiResult=it; busy=false } })
                Page.SETTINGS -> SettingsPage(status,dark,onDarkChange,endpoint,{endpoint=it},model,{model=it},apiKey,{apiKey=it}, { prefs.edit().putString("endpoint",endpoint).putString("model",model).putString("api_key",apiKey).apply() },onRefresh)
            }
        }
    }
}

@Composable private fun Header(connected:Boolean,capturing:Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Panel2),contentAlignment=Alignment.Center) { Icon(Icons.Default.MonitorHeart,null,tint=Cyan,modifier=Modifier.size(24.dp)) }
        Spacer(Modifier.width(11.dp)); Column(Modifier.weight(1f)) {
            Text("LogScope AI",fontSize=20.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onBackground)
            Text("ANDROID DIAGNOSTICS STUDIO",fontSize=9.sp,letterSpacing=1.5.sp,color=Muted)
        }
        Row(Modifier.clip(CircleShape).background(Panel).padding(horizontal=10.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(if(capturing) Green else if(connected) Cyan else Amber,CircleShape)); Spacer(Modifier.width(6.dp))
            Text(if(capturing) "LIVE" else if(connected) "READY" else "OFFLINE",fontSize=9.sp,fontWeight=FontWeight.Bold,color=if(capturing) Green else if(connected) Cyan else Amber)
        }
    }
}

@Composable private fun HomePage(total:Int,errors:Int,warnings:Int,recent:List<LogEntity>,status:String,capturing:Boolean,onConnect:()->Unit,onRefresh:()->Unit,onStart:()->Unit,onStop:()->Unit,onView:()->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(13.dp)) {
        item { Column { Text("DEVICE OVERVIEW",fontSize=11.sp,letterSpacing=1.5.sp,color=Cyan,fontWeight=FontWeight.Bold); Text("Your diagnostic command center",fontSize=23.sp,fontWeight=FontWeight.Bold); Text("Live device events, patterns and app health.",fontSize=12.sp,color=Muted) } }
        item { Row(horizontalArrangement=Arrangement.spacedBy(9.dp)) { Metric("LOGS",total.toString(),Cyan,Modifier.weight(1f)); Metric("ERRORS",errors.toString(),Red,Modifier.weight(1f)); Metric("WARNINGS",warnings.toString(),Amber,Modifier.weight(1f)) } }
        item {
            Card(colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(11.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Default.Hub,null,tint=Cyan); Spacer(Modifier.width(8.dp)); Text("Shizuku bridge",fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f)); Text(if(capturing) "CAPTURING" else "ADB ACCESS",fontSize=9.sp,color=if(capturing) Green else Amber,fontWeight=FontWeight.Bold) }
                    Text(status,fontSize=12.sp,color=Muted)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Button(onClick=if(capturing) onStop else onStart,modifier=Modifier.weight(1f),colors=ButtonDefaults.buttonColors(containerColor=if(capturing) Red else Cyan,contentColor=Bg)) { Icon(if(capturing) Icons.Default.Stop else Icons.Default.PlayArrow,null); Spacer(Modifier.width(5.dp)); Text(if(capturing) "Stop capture" else "Start live capture",maxLines=1,fontSize=13.sp) }
                        OutlinedButton(onClick=onConnect) { Text("Permission") }
                        IconButton(onClick=onRefresh) { Icon(Icons.Default.Refresh,null) }
                    }
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={ runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/RikkaApps/Shizuku/releases/latest")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },modifier=Modifier.weight(1f)) { Text("Get Shizuku",maxLines=1,fontSize=12.sp) }
                        OutlinedButton(onClick={ runCatching { ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { ctx.startActivity(it) } } },modifier=Modifier.weight(1f)) { Text("Open Shizuku",maxLines=1,fontSize=12.sp) }
                    }
                    Text("Requires Shizuku to be started and LogScope AI permission approved. Collection runs only while this app is active.",fontSize=10.sp,color=Muted)
                }
            }
        }
        item { Row(verticalAlignment=Alignment.CenterVertically) { Text("Recent events",fontWeight=FontWeight.Bold,fontSize=17.sp,modifier=Modifier.weight(1f)); TextButton(onClick=onView){Text("Explore all",color=Cyan)} } }
        if(recent.isEmpty()) item { EmptyState("No captured logs yet","Start a live capture to collect device log events. Sample data is not mixed into real results.",Icons.Default.Timeline) }
        items(recent,key={it.id}) { LogRow(it) }
    }
}

@Composable private fun Metric(label:String,value:String,tint:Color,modifier:Modifier) {
    Card(modifier,colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(16.dp)) { Column(Modifier.padding(13.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) { Box(Modifier.size(7.dp).background(tint,CircleShape)); Text(value,fontSize=23.sp,fontWeight=FontWeight.Bold); Text(label,fontSize=9.sp,color=Muted,letterSpacing=1.sp) } }
}

@Composable private fun LogsPage(entries:List<LogEntity>,query:String,onQuery:(String)->Unit,filter:String,onFilter:(String)->Unit,category:String,onCategory:(String)->Unit,onClear:()->Unit,onTxt:()->Unit,onCsv:()->Unit,onJson:()->Unit,onImport:()->Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
        Text("Log Explorer",fontSize=24.sp,fontWeight=FontWeight.Bold); Text("Search, filter and export captured events",fontSize=12.sp,color=Muted)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(query,onQuery,Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("Search tag, message, PID…")},leadingIcon={Icon(Icons.Default.Search,null)},shape=RoundedCornerShape(14.dp))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)) { listOf("ALL","ERROR","WARN","INFO","DEBUG").forEach { f -> FilterChip(selected=filter==f,onClick={onFilter(f)},label={Text(f,fontSize=9.sp)}) } }
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)) { listOf("ALL","CRASH","ANR","NETWORK","POWER","PERMISSION","MEMORY").forEach { f -> FilterChip(selected=category==f,onClick={onCategory(f)},label={Text(f,fontSize=8.sp)}) } }
        Row(verticalAlignment=Alignment.CenterVertically) { Text("${entries.size} entries",fontSize=11.sp,color=Muted,maxLines=1,modifier=Modifier.weight(1f)); Box { var expanded by remember { mutableStateOf(false) }; TextButton(onClick={expanded=true}) { Icon(Icons.Default.FileDownload,null); Text("Export") }; DropdownMenu(expanded,{expanded=false}) { DropdownMenuItem(text={Text("TXT")},onClick=onTxt.thenClose { expanded=false }); DropdownMenuItem(text={Text("CSV")},onClick=onCsv.thenClose { expanded=false }); DropdownMenuItem(text={Text("JSON")},onClick=onJson.thenClose { expanded=false }) } }; TextButton(onClick=onImport) { Icon(Icons.Default.FileOpen,null); Text("Import") }; TextButton(onClick=onClear) { Text("Clear",color=Red) } }
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=16.dp)) { if(entries.isEmpty()) item { EmptyState("Nothing matches","Try another filter or start a capture.",Icons.Default.SearchOff) }; items(entries,key={it.id}) { LogRow(it) } }
    }
}

private fun (() -> Unit).thenClose(close:()->Unit):()->Unit = { this(); close() }

@Composable private fun LogRow(e:LogEntity) {
    val tint=when(e.level){"ERROR"->Red;"WARN"->Amber;"INFO"->Cyan;"DEBUG"->Blue;else->Muted}
    var expanded by remember { mutableStateOf(false) }
    Card(colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth().clickable { expanded=!expanded }) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) { Text(e.tag,modifier=Modifier.weight(1f),fontWeight=FontWeight.SemiBold,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis); Text(e.level,color=tint,fontWeight=FontWeight.Bold,fontSize=9.sp); Spacer(Modifier.width(7.dp)); Text(SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(e.timestamp)),fontSize=9.sp,color=Muted) }
            Text(e.message,fontSize=11.sp,color=Muted,maxLines=if(expanded) 12 else 2,overflow=TextOverflow.Ellipsis)
            Row(verticalAlignment=Alignment.CenterVertically) { Text(e.category,fontSize=8.sp,color=Cyan); Spacer(Modifier.width(8.dp)); Text("PID ${e.pid}",fontSize=8.sp,color=Muted); if(expanded) Text(" · TID ${e.tid}",fontSize=8.sp,color=Muted) }
            if(expanded) SelectionContainer { Text(e.raw,fontSize=10.sp,color=MaterialTheme.colorScheme.onSurface) }
        }
    }
}

@Composable private fun CrashPage(crashes:List<LogEntity>) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item { Text("Crash & ANR Center",fontSize=23.sp,fontWeight=FontWeight.Bold); Text("Detected crash signatures in captured logs",fontSize=12.sp,color=Muted) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(9.dp)) { Metric("CRASHES",crashes.count{it.category=="CRASH"}.toString(),Red,Modifier.weight(1f)); Metric("ANRs",crashes.count{it.category=="ANR"}.toString(),Amber,Modifier.weight(1f)) } }
        if(crashes.isEmpty()) item { EmptyState("No crash signatures found","This means no matching signature was found in the currently stored capture. It does not prove that every app is healthy.",Icons.Default.Verified) }
        items(crashes,key={it.id}) { LogRow(it) }
    }
}

@Composable private fun AiPage(result:String,busy:Boolean,cloudConfigured:Boolean,onAnalyze:()->Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("AI Diagnostics Lab",fontSize=24.sp,fontWeight=FontWeight.Bold); Text("Explain patterns and plan safe next checks",fontSize=12.sp,color=Muted)
        Card(colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Default.AutoAwesome,null,tint=Cyan); Spacer(Modifier.width(8.dp)); Text("Analysis engine",fontWeight=FontWeight.Bold); Spacer(Modifier.weight(1f)); Text(if(cloudConfigured) "CLOUD READY" else "LOCAL MODE",fontSize=9.sp,color=if(cloudConfigured) Green else Cyan) }
                Text("Local pattern analysis is available without an API key. Optional cloud analysis sends a redacted excerpt only after you configure a provider in Settings.",fontSize=11.sp,color=Muted)
                Button(onClick=onAnalyze,enabled=!busy,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Cyan,contentColor=Bg)) { if(busy) CircularProgressIndicator(Modifier.size(17.dp),strokeWidth=2.dp) else Icon(Icons.Default.Bolt,null); Spacer(Modifier.width(7.dp)); Text(if(busy) "Analyzing…" else "Analyze captured logs") }
            }
        }
        Card(Modifier.fillMaxSize(),colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(18.dp)) { Column(Modifier.padding(14.dp)) { Text("Analysis report",fontWeight=FontWeight.Bold,color=Cyan); Spacer(Modifier.height(9.dp)); LazyColumn { item { SelectionContainer { Text(result,fontSize=12.sp,lineHeight=19.sp,color=MaterialTheme.colorScheme.onSurface) } } } } }
    }
}

@Composable private fun SettingsPage(status:String,dark:Boolean,onDark:(Boolean)->Unit,endpoint:String,onEndpoint:(String)->Unit,model:String,onModel:(String)->Unit,key:String,onKey:(String)->Unit,onSave:()->Unit,onRefresh:()->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text("Settings",fontSize=24.sp,fontWeight=FontWeight.Bold); Text("Connection, privacy and appearance",fontSize=12.sp,color=Muted) }
        item { Card(colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(18.dp)) { Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) { Text("Appearance",fontWeight=FontWeight.Bold); Row(verticalAlignment=Alignment.CenterVertically) { Text("Dark theme",modifier=Modifier.weight(1f)); Switch(dark,onDark) } } } }
        item { Card(colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(18.dp)) { Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) { Text("Shizuku connection",fontWeight=FontWeight.Bold); Text(status,fontSize=11.sp,color=Muted); OutlinedButton(onClick=onRefresh) { Icon(Icons.Default.Refresh,null); Spacer(Modifier.width(5.dp)); Text("Refresh status") } } } }
        item { Card(colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(18.dp)) { Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) { Text("Optional AI provider",fontWeight=FontWeight.Bold); Text("Use an OpenAI-compatible HTTPS endpoint. Leave the key empty for offline local analysis. Your key stays in app-private preferences.",fontSize=10.sp,color=Muted); OutlinedTextField(endpoint,onEndpoint,Modifier.fillMaxWidth(),singleLine=true,label={Text("HTTPS API base URL")}); OutlinedTextField(model,onModel,Modifier.fillMaxWidth(),singleLine=true,label={Text("Model name")}); OutlinedTextField(key,onKey,Modifier.fillMaxWidth(),singleLine=true,label={Text("API key")}); Button(onClick=onSave,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Cyan,contentColor=Bg)) { Text("Save AI settings") } } } }
        item { Card(colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(18.dp)) { Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) { Text("Privacy & data",fontWeight=FontWeight.Bold); Text("Logs are stored locally in the app database. Nothing is uploaded unless you explicitly configure cloud AI and run an analysis. Before sending, common email, phone, bearer-token and key patterns are redacted. Review sensitive log content before enabling cloud analysis.",fontSize=10.sp,color=Muted); Text("No root required · Shizuku/ADB permission required for broad logcat access",fontSize=10.sp,color=Cyan) } } }
        item { Text("LogScope AI · 0.2.0 · Minimum Android 8.0 · Designed for Android 14+",fontSize=10.sp,color=Muted) }
    }
}

@Composable private fun EmptyState(title:String,body:String,icon:androidx.compose.ui.graphics.vector.ImageVector) {
    Card(colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(18.dp)) { Column(Modifier.fillMaxWidth().padding(22.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(7.dp)) { Icon(icon,null,tint=Cyan,modifier=Modifier.size(30.dp)); Text(title,fontWeight=FontWeight.Bold); Text(body,fontSize=11.sp,color=Muted) } }
}
