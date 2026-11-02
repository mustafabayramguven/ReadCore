package com.readcore.app

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ReadCoreTheme { ReadCoreApp() } }
    }
}

@Composable
fun ReadCoreTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val base = if (Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(context) else darkColorScheme(primary = Color(0xFFBAC6FF))
    MaterialTheme(colorScheme = base.copy(background = Color.Black, surface = Color.Black, surfaceContainer = Color(0xFF111111)), content = content)
}

@Composable
fun ReadCoreApp(model: ReadModel = viewModel()) {
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(model.message) { model.message?.let { snack.showSnackbar(it); model.message = null } }
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (route != "reader") NavigationBar {
                listOf(Triple("library", "Library", Icons.Default.Book), Triple("stats", "Activity", Icons.Default.Timeline), Triple("settings", "Settings", Icons.Default.Settings)).forEach { (destination, label, icon) ->
                    NavigationBarItem(selected = route == destination, onClick = { nav.navigate(destination) { popUpTo("library") { saveState = true }; launchSingleTop = true; restoreState = true } }, icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
        }
    ) { padding ->
        NavHost(navController = nav, startDestination = "library", modifier = Modifier.padding(padding)) {
            composable("library") { LibraryScreen(model) { book -> model.open(book) { nav.navigate("reader") } } }
            composable("stats") { StatsScreen(model) }
            composable("settings") { SettingsScreen(model) }
            composable("reader") { ReaderScreen(model) { nav.popBackStack() } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(model: ReadModel, open: (LibraryBook) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::importBook) }
    Scaffold(topBar = { TopAppBar(title = { Text("ReadCore") }, actions = {
        IconButton(onClick = model::refresh) { Icon(Icons.Default.Refresh, "Refresh server library") }
        IconButton(onClick = { picker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Add, "Import book") }
    }) }) { padding ->
        if (model.loading) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else if (model.library.isEmpty()) Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Import a book or connect your server in Settings, then refresh.", style = MaterialTheme.typography.bodyLarge)
        } else LazyVerticalGrid(columns = GridCells.Adaptive(140.dp), contentPadding = PaddingValues(16.dp), modifier = Modifier.padding(padding), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(model.library, key = { it.id }) { book ->
                var cover by remember(book.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
                LaunchedEffect(book.id, book.remote) { cover = model.loadCover(book) }
                Column(Modifier.clickable { open(book) }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(.7f).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.BottomStart) {
                        if (cover != null) Image(cover!!.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Column(Modifier.padding(16.dp)) {
                            Text(book.format.uppercase(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.height(8.dp))
                            Text(book.title, maxLines = 5, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                    if (book.author.isNotBlank()) Text(book.author, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(model: ReadModel) {
    val days by model.days.collectAsState(initial = emptyList())
    val books by model.bookTotals.collectAsState(initial = emptyList())
    val today = LocalDate.now()
    val activity = days.associateBy { it.day }
    var streak = 0
    var date = if ((activity[today.toString()]?.seconds ?: 0) > 0) today else today.minusDays(1)
    while ((activity[date.toString()]?.seconds ?: 0) > 0) { streak++; date = date.minusDays(1) }
    Scaffold(topBar = { TopAppBar(title = { Text("Your activity") }) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("$streak day streak", style = MaterialTheme.typography.headlineMedium)
            Text("${days.sumOf { it.seconds } / 60} minutes read · ${days.sumOf { it.pages }} pages or chapters · ${days.sumOf { it.words }} words", style = MaterialTheme.typography.bodyLarge)
            Text("Last 13 weeks", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (week in 0 until 13) Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (weekday in 0 until 7) {
                        val day = today.minusDays((90 - week * 7 - weekday).toLong())
                        val seconds = activity[day.toString()]?.seconds ?: 0
                        val color = when { seconds >= 3600 -> MaterialTheme.colorScheme.primary; seconds >= 1200 -> MaterialTheme.colorScheme.primary.copy(alpha = .7f); seconds > 0 -> MaterialTheme.colorScheme.primary.copy(alpha = .4f); else -> MaterialTheme.colorScheme.surfaceContainerHighest }
                        Box(Modifier.size(16.dp).background(color, RoundedCornerShape(3.dp)))
                    }
                }
            }
            Text("Time by book", style = MaterialTheme.typography.titleMedium)
            books.sortedByDescending { it.seconds }.forEach { total ->
                val title = model.library.find { it.id == total.bookId }?.title ?: "Imported book"
                Text("$title · ${total.seconds / 60} min")
            }
            Text("Text counts are estimates when advancing a chapter. A PDF or comic page is counted when you move forward.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(model: ReadModel) {
    var url by remember { mutableStateOf(model.url) }
    var token by remember { mutableStateOf(model.token) }
    var background by remember { mutableStateOf(model.background) }
    var foreground by remember { mutableStateOf(model.textColor) }
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::importFont) }
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Server", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(url, { url = it }, label = { Text("HTTPS server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text("Server token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { model.saveServer(url, token); model.refresh() }) { Text("Save and connect") }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Reader", style = MaterialTheme.typography.titleLarge)
            Text("Font size: ${model.fontSize} sp")
            Slider(model.fontSize.toFloat(), { model.typography(size = it.toInt()) }, valueRange = 12f..36f)
            Text("Line height: ${model.lineHeight}%")
            Slider(model.lineHeight.toFloat(), { model.typography(line = it.toInt()) }, valueRange = 110f..220f)
            Text("Paragraph spacing: ${model.paragraph} dp")
            Slider(model.paragraph.toFloat(), { model.typography(margin = it.toInt()) }, valueRange = 0f..40f)
            OutlinedTextField(background, { background = it }, label = { Text("Background (#RRGGBB)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(foreground, { foreground = it }, label = { Text("Text (#RRGGBB)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                if (background.matches(Regex("#[0-9a-fA-F]{6}")) && foreground.matches(Regex("#[0-9a-fA-F]{6}"))) model.typography(bg = background, fg = foreground)
                else model.message = "Enter colors as #RRGGBB"
            }) { Text("Apply colors") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { fontPicker.launch(arrayOf("*/*")) }) { Text("Import TTF/OTF") }
                TextButton(onClick = model::resetFont) { Text("Default font") }
            }
            Text(if (model.fontPath.isEmpty()) "Default font" else "Custom font selected", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(model: ReadModel, back: () -> Unit) {
    val book = model.current ?: return
    val content = model.opened ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var started by remember { mutableLongStateOf(if (resumed) SystemClock.elapsedRealtime() else 0L) }
    fun flushTime() {
        if (started != 0L) {
            val now = SystemClock.elapsedRealtime(); val seconds = (now - started) / 1000
            if (seconds > 0) { model.record(book.id, seconds); started += seconds * 1000 }
        }
    }
    DisposableEffect(lifecycle, book.id) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { resumed = true; started = SystemClock.elapsedRealtime() }
                Lifecycle.Event.ON_PAUSE -> { flushTime(); resumed = false; started = 0L }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { flushTime(); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(book.id, resumed) {
        if (resumed) while (true) { delay(30_000); flushTime(); if (book.remote) model.syncProgress() }
    }
    val chapter = model.chapter.coerceIn(0, content.count - 1)
    val bg = try { Color(android.graphics.Color.parseColor(model.background)) } catch (_: Exception) { Color.Black }
    val fg = try { Color(android.graphics.Color.parseColor(model.textColor)) } catch (_: Exception) { Color.White }
    val family = remember(model.fontPath) {
        if (model.fontPath.isEmpty()) FontFamily.Default else try { FontFamily(android.graphics.Typeface.createFromFile(File(model.fontPath))) } catch (_: Exception) { FontFamily.Default }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }, bottomBar = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { model.changeChapter(chapter - 1) }, enabled = chapter > 0) { Text("Previous") }
            Text("${chapter + 1} / ${content.count}")
            TextButton(onClick = { model.changeChapter(chapter + 1) }, enabled = chapter < content.count - 1) { Text("Next") }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(bg)) {
            if (content is OpenBook.Text) {
                val paragraphs = remember(content, chapter) { content.chapters[chapter].split(Regex("\\n{2,}")).filter { it.isNotBlank() } }
                key(chapter) {
                    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(model.paragraph.dp), modifier = Modifier.fillMaxSize()) {
                        items(paragraphs) { paragraph -> Text(paragraph.trim(), color = fg, fontFamily = family, fontSize = model.fontSize.sp, lineHeight = (model.fontSize * model.lineHeight / 100f).sp) }
                    }
                }
            } else {
                var bitmap by remember(content, chapter) { mutableStateOf<android.graphics.Bitmap?>(null) }
                var error by remember(content, chapter) { mutableStateOf<String?>(null) }
                LaunchedEffect(content, chapter) { try { bitmap = withContext(Dispatchers.IO) { ReaderCore.page(content, chapter) } } catch (e: Exception) { error = e.message ?: "Cannot render page" } }
                when {
                    error != null -> Text(error!!, color = fg, modifier = Modifier.align(Alignment.Center))
                    bitmap != null -> Image(bitmap!!.asImageBitmap(), contentDescription = "Page ${chapter + 1}", modifier = Modifier.fillMaxSize().align(Alignment.Center))
                    else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
        }
    }
}
