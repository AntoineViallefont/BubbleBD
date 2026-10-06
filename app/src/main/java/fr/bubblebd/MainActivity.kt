package fr.bubblebd

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription

import kotlin.math.roundToInt

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(android.os.Build.VERSION.SDK_INT>=31) {
            splashScreen.setOnExitAnimationListener {screen ->screen.remove()}
        }
        enableEdgeToEdge()
        setContent { val vm:LibraryViewModel=viewModel(); BubbleTheme(vm.prefs.theme) { BubbleApp(vm) } }
    }
}
val Blue=Color(0xFF479DEC)
val Red=Color(0xFFEF535D)
@Composable fun BubbleTheme(theme:String, content:@Composable ()->Unit) {
    val dark=theme=="dark" || theme=="system" && isSystemInDarkTheme()
    val activity=LocalActivity.current!!
    SideEffect {WindowCompat.getInsetsController(activity.window,activity.window.decorView).apply {isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark}}
    val colors=if(dark) darkColorScheme(primary=Blue,onPrimary=Color(0xFF09263D),background=Color(0xFF151A21),onBackground=Color(0xFFF3F5F8),surface=Color(0xFF151A21),surfaceVariant=Color(0xFF252E3B),onSurface=Color(0xFFF3F5F8),onSurfaceVariant=Color(0xFFB4BECF),secondary=Blue,primaryContainer=Color(0xFF163954),onPrimaryContainer=Color(0xFFD4E9FF),secondaryContainer=Color(0xFF163954),onSecondaryContainer=Color(0xFFD4E9FF),surfaceContainerHigh=Color(0xFF222B36),surfaceContainerHighest=Color(0xFF2D3947),surfaceContainerLow=Color(0xFF1C232C))
    else lightColorScheme(primary=Color(0xFF1368B5),onPrimary=Color.White,background=Color(0xFFFAFAF8),onBackground=Color(0xFF15263C),surface=Color(0xFFFAFAF8),surfaceVariant=Color(0xFFE7EDF4),onSurface=Color(0xFF15263C),onSurfaceVariant=Color(0xFF526176),secondary=Color(0xFF1368B5),primaryContainer=Color(0xFFD9EAFB),onPrimaryContainer=Color(0xFF15436E),secondaryContainer=Color(0xFFD9EAFB),onSecondaryContainer=Color(0xFF15436E),surfaceContainerHigh=Color(0xFFEEF3F7),surfaceContainerHighest=Color(0xFFE1E8EF),surfaceContainerLow=Color(0xFFF5F7FA))
    MaterialTheme(colorScheme=colors, typography=Typography(bodyLarge=androidx.compose.ui.text.TextStyle(fontSize=16.sp),bodyMedium=androidx.compose.ui.text.TextStyle(fontSize=14.sp),titleLarge=androidx.compose.ui.text.TextStyle(fontSize=23.sp,fontWeight=FontWeight.Bold)),content=content)
}
@Composable fun BubbleApp(vm:LibraryViewModel,metadataStatus:MetadataSearchStatus=vm.metadataSearchStatus) {

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Book?>(null) }
    var selectedTargets by remember {mutableStateOf<List<Book>>(emptyList())}
    fun selectBook(book:Book,targets:List<Book> = listOf(book)) {selectedTargets=targets;selected=book}
    var resume by remember { mutableStateOf<Book?>(null) }
    var reading by rememberSaveable { mutableStateOf<String?>(null) }
    var startPage by rememberSaveable { mutableIntStateOf(0) }
    var cloudBrowser by remember {mutableStateOf(false)}
    val ctx=LocalContext.current
    val snackbar=remember { SnackbarHostState() }
    val folderPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if(uri!=null) runCatching { ctx.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); vm.source(uri) }.onFailure { vm.message="Impossible d’obtenir l’accès à ce dossier : ${it.message}" }
    }

    val cloudLogin=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {result ->
        result.data?.let {vm.finishOneDrive(it) {cloudBrowser=true}}
    }
    val connectCloud:()->Unit={
        if(vm.oneDriveConnected) cloudBrowser=true
        else runCatching {cloudLogin.launch(vm.oneDrive.loginIntent())}.onFailure {vm.message=it.message}
    }
    val open:(Book)->Unit={ b -> vm.prepare(b) {ready ->
        if(ready.issue.isNotEmpty()) selectBook(ready) else if(ready.started) resume=ready else {startPage=0;reading=ready.id}
    } }

    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message=null } }
    val readingBook=vm.books.firstOrNull { it.id==reading }
    if(readingBook!=null) {
        ReaderScreen(readingBook,startPage,vm.repo,vm.prefs,vm::progress) { reading=null }
        return
    }
    BackHandler(enabled=tab!=0 && selected==null && resume==null) {tab=0}
    Scaffold(
        topBar={ Column(Modifier.statusBarsPadding()) {
            BoxWithConstraints(Modifier.fillMaxWidth().height(58.dp).padding(horizontal=16.dp),contentAlignment=Alignment.Center) {
                Image(painterResource(R.drawable.bubble_logo),"Icône BubbleBD",Modifier.align(Alignment.CenterStart).size(48.dp).clip(RoundedCornerShape(9.dp)),contentScale=ContentScale.Crop)
                Text(buildAnnotatedString { withStyle(SpanStyle(color=Blue)) { append("Bubble") }; withStyle(SpanStyle(color=Red)) { append("BD") } },modifier=Modifier.widthIn(max=(maxWidth-192.dp).coerceAtLeast(64.dp)),fontSize=if(maxWidth>=340.dp)26.sp else if(maxWidth>=300.dp)22.sp else 17.sp,fontWeight=FontWeight.Bold,maxLines=1)
                val dark=MaterialTheme.colorScheme.background.luminance()<.5f
                Row(Modifier.align(Alignment.CenterEnd),verticalAlignment=Alignment.CenterVertically) {
                    IconToggleButton(checked=vm.prefs.rtl,onCheckedChange={vm.preferences(vm.prefs.copy(rtl=it))},modifier=Modifier.semantics {stateDescription=if(vm.prefs.rtl)"Droite vers gauche" else "Gauche vers droite"},colors=IconButtonDefaults.iconToggleButtonColors(contentColor=MaterialTheme.colorScheme.primary,checkedContainerColor=FinishedColor().copy(alpha=.14f),checkedContentColor=FinishedColor())) {
                        Icon(Icons.Outlined.SwapHoriz,if(vm.prefs.rtl)"Revenir à la lecture de gauche à droite" else "Activer la lecture manga de droite à gauche")
                    }
                    IconButton(onClick={vm.preferences(vm.prefs.copy(theme=if(dark)"light" else "dark"))}) {
                        Icon(if(dark)Icons.Outlined.LightMode else Icons.Outlined.DarkMode,if(dark)"Passer au thème jour" else "Passer au thème nuit")
                    }
                }

            }
            if(vm.busy || vm.coversLoading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(if(vm.busy)vm.task else vm.coversTask,Modifier.padding(horizontal=20.dp,vertical=6.dp),fontSize=12.sp) }
        } },
        bottomBar={ Surface(tonalElevation=0.dp) { Column {
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.35f))
            Row(Modifier.fillMaxWidth().navigationBarsPadding().height(60.dp)) {
                listOf(Triple("Accueil",Icons.Outlined.Home,0),Triple("Bibliothèque",Icons.Outlined.LibraryBooks,1),Triple("Réglages",Icons.Outlined.Settings,2)).forEach { (label,icon,index) ->
                    val color=if(tab==index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(Modifier.weight(1f).fillMaxHeight().clickable { tab=index }.padding(top=7.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(3.dp)) { if(index==1) Icon(painterResource(R.drawable.ic_books),label,Modifier.size(23.dp),tint=color) else Icon(icon,label,Modifier.size(23.dp),tint=color); Text(label,fontSize=12.sp,color=color,fontWeight=if(tab==index) FontWeight.SemiBold else FontWeight.Normal) }
                }
            }
        } } },snackbarHost={SnackbarHost(snackbar)}
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when(tab) {
                0 -> HomeScreen(vm.books,vm.prefs,vm::preferences,open,{selectBook(it)},{tab=1},{tab=2},{selectBook(it)})
                1 -> LibraryScreen(vm.books,vm.prefs,vm::preferences,search,{search=it},open,{selectBook(it)},{tab=2},{targets -> targets.firstOrNull()?.let {selectBook(it,targets)}},{selectBook(it)})
                2 -> SettingsScreen(vm,{folderPicker.launch(null)},connectCloud,metadataStatus)
            }
        }
    }
    if(cloudBrowser) OneDriveFolderDialog(vm.oneDrive,{cloudBrowser=false}) {vm.cloudSource(it);cloudBrowser=false}
    resume?.let { b -> AlertDialog(onDismissRequest={resume=null},title={Text("Reprendre la lecture ?")},text={Text("${b.displayTitle}\nPage ${b.page+1} sur ${b.pages}")},confirmButton={TextButton(onClick={startPage=b.page; reading=b.id; resume=null}) {Text("Reprendre")}},dismissButton={TextButton(onClick={startPage=0; reading=b.id; resume=null}) {Text("Première page")}}) }
    selected?.let { b ->
        val targets=selectedTargets.takeIf {it.any {target->target.id==b.id}} ?: listOf(b)
        BookSheet(vm.books.firstOrNull { it.id==b.id } ?: b,{selected=null},vm::offline,{vm.removeAlbum(it);selected=null},
            markRead={vm.markReading(targets.map {it.id}.toSet(),true);selected=null},seriesCount=targets.size,save=vm::saveMetadata,search=vm::searchMetadataFor,research=vm.albumResearch(vm.books.firstOrNull {it.id==b.id} ?: b))
    }
}
@Composable fun MetadataSearchIndicator(status:MetadataSearchStatus) {
    if(!status.visible)return
    val label="Recherche des infos"
    Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=4.dp)
        .semantics(mergeDescendants=true) {contentDescription=listOf(label,status.counter,status.title).filter {it.isNotBlank()}.joinToString(" · ")},
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=1.5.dp)
        Text(label,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
        if(status.counter.isNotBlank())Text(status.counter,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurface,maxLines=1)
        if(status.title.isNotBlank())Text(status.title,Modifier.weight(1f),fontSize=12.sp,
            color=MaterialTheme.colorScheme.onSurface,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}
@Composable fun FinishedColor()=if(MaterialTheme.colorScheme.background.luminance()<.5f)Red else Color(0xFFB3261E)
@Composable fun StatusColor(b:Book)=when {b.progress==100->FinishedColor();b.started->MaterialTheme.colorScheme.primary;else->MaterialTheme.colorScheme.onSurfaceVariant}
@Composable fun NumberStepper(label:String,value:Int,minimum:Int,maximum:Int,step:Int,onChange:(Int)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f),fontSize=14.sp)
        IconButton(onClick={onChange((value-step).coerceAtLeast(minimum))},enabled=value>minimum) {Icon(Icons.Outlined.Remove,"Diminuer $label")}
        Text(value.toString(),fontWeight=FontWeight.SemiBold)
        IconButton(onClick={onChange((value+step).coerceAtMost(maximum))},enabled=value<maximum) {Icon(Icons.Outlined.Add,"Augmenter $label")}
    }
}
@Composable fun Heading(text:String) { Text(text,fontSize=27.sp,fontWeight=FontWeight.Bold,lineHeight=33.sp) }
@Composable fun Cover(b:Book,modifier:Modifier=Modifier) {
    Box(modifier.clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center) {
        if(b.cover.isNotBlank()) AsyncImage(File(b.cover),"Couverture de ${b.displayTitle}",Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        else Icon(Icons.Outlined.MenuBook,null,Modifier.size(32.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(if(b.local)Icons.Outlined.CheckCircle else Icons.Outlined.Cloud,if(b.local)"Disponible en local" else "Album cloud",
            Modifier.align(Alignment.BottomEnd).padding(4.dp).background(MaterialTheme.colorScheme.surface.copy(alpha=.95f),RoundedCornerShape(20.dp)).padding(3.dp).size(17.dp),
            tint=if(b.local)Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary)
        if(b.issue.isNotEmpty()) Icon(Icons.Outlined.ErrorOutline,"Album à vérifier",Modifier.align(Alignment.TopEnd).padding(5.dp),tint=MaterialTheme.colorScheme.error)
    }
}
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun HomeScreen(books:List<Book>,prefs:Preferences,change:(Preferences)->Unit,open:(Book)->Unit,details:(Book)->Unit,library:()->Unit,settings:()->Unit,longActions:(Book)->Unit=details) {
    val resumeWidth=(LocalConfiguration.current.screenWidthDp*.42f).coerceIn(128f,180f).dp
    val current=books.filter { it.started && it.progress<100 }.maxByOrNull { it.lastRead }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=8.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        if(books.isEmpty()) {
            item { Heading("Vos BD, simplement.") }
            item { Text("Ajoutez vos dossiers pour retrouver vos albums et reprendre votre lecture en un geste.",color=MaterialTheme.colorScheme.onSurfaceVariant,lineHeight=24.sp) }
            item { Button(onClick=settings) { Icon(Icons.Outlined.CreateNewFolder,null); Spacer(Modifier.width(8.dp)); Text("Ajouter mes BD") } }
        } else {
            if(current!=null) {
                item { Heading("À reprendre") }
                item { Row(Modifier.fillMaxWidth().combinedClickable(onClick={open(current)},onLongClick={longActions(current)}),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
                    Cover(current,Modifier.width(resumeWidth).height(resumeWidth*1.5f))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(current.displayTitle,fontSize=18.sp,fontWeight=FontWeight.Bold,lineHeight=26.sp)
                        if(current.volume.isNotBlank()) Text(current.volume,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(2.dp))
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text("Page ${current.page+1} sur ${current.pages}",Modifier.weight(1f),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(current.status,fontSize=12.sp,color=StatusColor(current))
                        }
                        LinearProgressIndicator(progress={current.progress/100f},modifier=Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)),trackColor=MaterialTheme.colorScheme.surfaceVariant,gapSize=0.dp,drawStopIndicator={})
                    }
                } }
            }
            val recent=books.sortedByDescending { it.added }.filter { it.id!=current?.id }.take(9)
            item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {Text("Derniers ajouts",fontSize=22.sp,fontWeight=FontWeight.Bold);Text("${recent.size} album${if(recent.size>1)"s" else ""}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                ViewModeButtons(prefs.homeGrid) {change(prefs.copy(homeGrid=it))}
            } }
            if(prefs.homeGrid) items(recent.chunked(3)) { row -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                row.forEach { b -> BookTile(b,Modifier.weight(1f),open,details,longActions) }
                repeat(3-row.size) { Spacer(Modifier.weight(1f)) }
            } } else items(recent,key={it.id}) {b -> CompactBookRow(b,open,details,longActions)}
            item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) { TextButton(onClick=library) { Text("Voir tous les albums"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Outlined.ArrowForward,null,Modifier.size(19.dp)) } } }
        }
    }
}
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun BookTile(b:Book,modifier:Modifier=Modifier,open:(Book)->Unit,details:(Book)->Unit,longActions:(Book)->Unit=details) {
    val landscape=LocalConfiguration.current.screenWidthDp>LocalConfiguration.current.screenHeightDp
    if(landscape) Row(modifier.combinedClickable(onClick={open(b)},onLongClick={longActions(b)}),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
        Cover(b,Modifier.width(76.dp).height(114.dp))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(b.displayTitle,fontSize=14.sp,fontWeight=FontWeight.SemiBold,lineHeight=18.sp,maxLines=3,overflow=TextOverflow.Ellipsis)
            if(b.volume.isNotBlank())Text(b.volume,fontSize=12.sp)
            Text(b.status,fontSize=12.sp,color=StatusColor(b))
        }
    } else Column(modifier.combinedClickable(onClick={open(b)},onLongClick={longActions(b)}),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Cover(b,Modifier.fillMaxWidth().aspectRatio(2f/3))
        Text(b.displayTitle,fontSize=13.sp,fontWeight=FontWeight.SemiBold,lineHeight=17.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
        Text(b.status,fontSize=12.sp,color=StatusColor(b))
    }
}
@Composable fun ViewModeButtons(grid:Boolean,onChange:(Boolean)->Unit) {
    Row {
        IconButton(onClick={onChange(true)},modifier=Modifier.size(40.dp)) {Icon(Icons.Filled.GridView,"Affichage couvertures",tint=if(grid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}
        IconButton(onClick={onChange(false)},modifier=Modifier.size(40.dp)) {Icon(Icons.Outlined.Menu,"Affichage liste",tint=if(!grid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun CompactBookRow(b:Book,open:(Book)->Unit,details:(Book)->Unit,longActions:(Book)->Unit=details) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick={open(b)},onLongClick={longActions(b)}).padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Cover(b,Modifier.width(46.dp).height(69.dp))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {Text(b.displayTitle,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis);Text(listOf(b.volume,b.status).filter {it.isNotBlank()}.joinToString(" · "),fontSize=12.sp,color=StatusColor(b))}
        IconButton(onClick={details(b)}) {Icon(Icons.Outlined.MoreVert,"Fiche de ${b.displayTitle}")}
    }
}
@Composable fun ChoiceMenu(label:String,value:String,choices:List<String>,modifier:Modifier=Modifier,onChange:(String)->Unit) {
    var expanded by remember {mutableStateOf(false)}
    val color=when(value) {"En cours"->MaterialTheme.colorScheme.primary;"Terminés"->FinishedColor();else->MaterialTheme.colorScheme.onSurface}
    Box(modifier) {
        OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()) {
            Text(value,Modifier.weight(1f),maxLines=1,color=color)
            Icon(Icons.Outlined.ExpandMore,label,Modifier.size(20.dp))
        }
        DropdownMenu(expanded,onDismissRequest={expanded=false}) {
            choices.forEach {choice ->DropdownMenuItem(text={Text(choice)},onClick={onChange(choice);expanded=false})}
        }
    }
}
@Composable fun LibraryScreen(books:List<Book>,prefs:Preferences,change:(Preferences)->Unit,query:String,onQuery:(String)->Unit,open:(Book)->Unit,details:(Book)->Unit,settings:()->Unit,seriesActions:(List<Book>)->Unit,longActions:(Book)->Unit=details) {
    var filter by rememberSaveable { mutableStateOf("Tous") }; var options by remember { mutableStateOf(false) }
    var location by rememberSaveable {mutableStateOf("Partout")}
    var expanded by rememberSaveable {mutableStateOf(arrayListOf<String>())}
    val result=remember(books,prefs,query,filter,location) { BookRules.sort(books.filter { b ->
        (query.isBlank() || BookRules.normalized(listOf(b.title,b.series,b.number,b.genre,b.artist,b.writer,b.publisher).joinToString(" ")).contains(BookRules.normalized(query))) &&
        (when(filter) { "En cours"->b.started && b.progress<100; "Non lus"->!b.started; "Terminés"->b.progress==100; else->true }) &&
        (when(location) {"Local"->b.local;"Cloud"->!b.local;else->true})
    },prefs.sort,prefs.descending) }
    val landscape=LocalConfiguration.current.screenWidthDp>LocalConfiguration.current.screenHeightDp
    val columns=if(!prefs.grid)1 else if(landscape)(LocalConfiguration.current.screenWidthDp/280).coerceIn(2,4) else prefs.columns
    LazyVerticalGrid(GridCells.Fixed(columns),modifier=Modifier.fillMaxSize().padding(horizontal=16.dp),contentPadding=PaddingValues(bottom=24.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
      item(span={GridItemSpan(maxLineSpan)}) {Column {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(query,onQuery,Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("Titre, série, auteur…")},shape=RoundedCornerShape(12.dp))
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("${result.size} albums\n${result.count {it.local}} en local · ${result.count {!it.local}} dans le cloud",Modifier.weight(1f),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            ViewModeButtons(prefs.grid) {change(prefs.copy(grid=it))}
            IconButton(onClick={options=true}) {Icon(Icons.Outlined.Tune,"Trier et régler la taille")}
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ChoiceMenu("État de lecture",filter,listOf("Tous","En cours","Non lus","Terminés"),Modifier.weight(1f)) {filter=it}
            ChoiceMenu("Emplacement",location,listOf("Partout","Local","Cloud"),Modifier.weight(1f)) {location=it}
        }
      }}
        if(result.isEmpty()) item(span={GridItemSpan(maxLineSpan)}) {
            Column {
                Spacer(Modifier.height(32.dp))
                Text(if(books.isEmpty()) "Votre bibliothèque est vide." else "Aucun album ne correspond à cette recherche.")
                if(books.isEmpty())TextButton(onClick=settings) {Text("Choisir mes dossiers")}
            }
        } else {
            BookRules.seriesGroups(result).forEach {group ->
                val isOpen=group.key in expanded
                if(group.grouped) item(key=group.key,span={GridItemSpan(if(prefs.grid && !isOpen) 1 else maxLineSpan)}) {
                    val toggle={expanded=ArrayList(if(isOpen)expanded-group.key else expanded+group.key)}
                    val action=if(isOpen)"Replier ${group.title}" else "Déployer ${group.title}"
                    if(prefs.grid && !isOpen && !landscape) Column(Modifier.combinedClickable(onClick=toggle,onLongClick={seriesActions(books.filter {it.series.isNotBlank() && BookRules.normalized(it.series)==BookRules.normalized(group.title)})}),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Cover(group.books.first(),Modifier.fillMaxWidth().aspectRatio(2f/3))
                        Text(group.title,fontSize=13.sp,fontWeight=FontWeight.SemiBold,lineHeight=17.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("Série · ${group.books.size} tomes",Modifier.weight(1f),fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
                            Icon(Icons.Outlined.ChevronRight,action,Modifier.size(18.dp))
                        }
                    } else Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.45f)).combinedClickable(onClick=toggle,onLongClick={seriesActions(books.filter {it.series.isNotBlank() && BookRules.normalized(it.series)==BookRules.normalized(group.title)})}).padding(10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Cover(group.books.first(),Modifier.width(46.dp).height(69.dp))
                        Column(Modifier.weight(1f)) {
                            Text(group.title,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                            Text("Série · ${group.books.size} tomes",fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
                        }
                        Icon(if(isOpen)Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,action,Modifier.size(24.dp))
                    }
                }
                if(!group.grouped || group.key in expanded) items(group.books,key={it.id}) {book ->
                    if(prefs.grid)BookTile(book,open=open,details=details,longActions=longActions) else CompactBookRow(book,open,details,longActions)
                }
            }
        }

    }
    if(options) AlertDialog(onDismissRequest={options=false},title={Text("Affichage et tri")},text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState())) {
        NumberStepper("Couvertures par ligne",prefs.columns,2,5,1) {change(prefs.copy(columns=it))}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {FilterChip(!prefs.descending,{change(prefs.copy(descending=false))},label={Text("Croissant")});FilterChip(prefs.descending,{change(prefs.copy(descending=true))},label={Text("Décroissant")})}
        if(prefs.sort=="Dernière ouverture")Text(if(prefs.descending)"Les plus anciennes lectures en premier" else "Le dernier album ouvert en premier",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        listOf("Dernière ouverture","Titre","Série","N°","Parution","Genre","Dessinateur","Scénariste","Éditeur","Ajout","Progression").forEach { s -> Row(Modifier.fillMaxWidth().clickable {change(prefs.copy(sort=s,descending=s in listOf("Ajout","Progression")))},verticalAlignment=Alignment.CenterVertically) {RadioButton(prefs.sort==s,{change(prefs.copy(sort=s,descending=s in listOf("Ajout","Progression")))}); Text(s)} }
    }},confirmButton={TextButton(onClick={options=false}) {Text("Terminé")}})
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SettingsScreen(vm:LibraryViewModel,pickFolder:()->Unit,connectCloud:()->Unit,metadataStatus:MetadataSearchStatus=vm.metadataSearchStatus) {
    var about by rememberSaveable {mutableStateOf(false)}
    if(about) {
        BackHandler {about=false}
        AboutScreen {about=false}
        return
    }
    var deleted by remember {mutableStateOf(false)}
    var delete by remember { mutableStateOf<SourceFolder?>(null) }; var cache by remember {mutableLongStateOf(vm.repo.cacheSize())}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        item {Heading("Réglages")}
        item {
            Text("Informations des BD",fontWeight=FontWeight.Bold,fontSize=19.sp)
            OutlinedButton(onClick=vm::refreshMetadata,enabled=!vm.busy && metadataStatus.phase==MetadataSearchStatus.Phase.IDLE && vm.books.any {!it.demo}) {Text("Relancer la recherche des infos")}
            MetadataSearchIndicator(metadataStatus)
        }
        item {Text("Mes dossiers",fontWeight=FontWeight.Bold,fontSize=19.sp) }
        if(vm.folders.isEmpty()) item {Text("Aucun dossier ajouté",fontSize=13.sp)}
        items(vm.folders,key={it.uri}) { source ->
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {Icon(Icons.Outlined.FolderOpen,null,Modifier.padding(end=12.dp)); Column(Modifier.weight(1f)) {Text(source.name,fontWeight=FontWeight.SemiBold); Text("${source.count} album(s)",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant); Text(if(source.uri.startsWith("onedrive:"))"OneDrive" else "Dossier Android",fontSize=12.sp,color=MaterialTheme.colorScheme.primary); if(source.scanned>0) Text("Scanné le ${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(source.scanned))}",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant); if(source.error.isNotEmpty()) Text(source.error,fontSize=12.sp,color=MaterialTheme.colorScheme.error) }; IconButton(onClick={delete=source},enabled=!vm.busy) {Icon(Icons.Outlined.Close,"Retirer ce dossier du scan",tint=MaterialTheme.colorScheme.error)} }
        }
        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {OutlinedButton(onClick=pickFolder,enabled=!vm.busy) {Text("Ajouter un dossier")}; TextButton(onClick=vm::scan,enabled=!vm.busy && vm.folders.isNotEmpty()) {Text("Scanner")}} }
        item {
            OneDriveSettingsRow(vm.oneDriveConnected,vm.busy,connectCloud,vm::disconnectOneDrive)
            if(vm.oneDriveConnected)vm.folders.filter {it.uri.startsWith("onedrive:")}.forEach {source ->
                Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {Icon(Icons.Outlined.FolderOpen,null,Modifier.padding(end=12.dp));Text(source.name,fontWeight=FontWeight.SemiBold)}
            }
        }
        item {OutlinedButton(onClick={deleted=true},enabled=!vm.busy) {Icon(Icons.Outlined.RestoreFromTrash,null);Spacer(Modifier.width(8.dp));Text("Albums supprimés (${vm.deletedBooks.size})")}}
        item {HorizontalDivider(); Spacer(Modifier.height(4.dp)); Text("Stockage local",fontSize=19.sp,fontWeight=FontWeight.Bold); Text("Cache temporaire : $cache Mo",Modifier.padding(top=4.dp)); NumberStepper("Limite en Mo",vm.prefs.cacheMb,128,2048,128) {vm.preferences(vm.prefs.copy(cacheMb=it))}; Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {TextButton(onClick={vm.repo.clearCache(); cache=vm.repo.cacheSize(); vm.message="Cache vidé. Les copies conservées hors ligne sont intactes."},enabled=!vm.busy) {Text("Vider le cache",color=MaterialTheme.colorScheme.error)} } }
        item {HorizontalDivider(); Spacer(Modifier.height(4.dp)); Text("Lecture guidée",fontSize=19.sp,fontWeight=FontWeight.Bold) }
        item {
            var dim by remember(vm.prefs.outsideDim) {mutableFloatStateOf(vm.prefs.outsideDim.toFloat())}
            Text("Assombrissement hors case : ${dim.roundToInt()} %",fontWeight=FontWeight.SemiBold)
            Slider(value=dim,thumb={},onValueChange={dim=it},onValueChangeFinished={vm.preferences(vm.prefs.copy(outsideDim=dim.roundToInt()))},valueRange=0f..100f,modifier=Modifier.semantics {contentDescription="Assombrissement hors case"})
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text("0 %",fontSize=12.sp);Text("100 %",fontSize=12.sp)}
        }
        item {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center) {OutlinedButton(onClick={about=true}) {
                Icon(Icons.Outlined.Info,null);Spacer(Modifier.width(8.dp));Text("À propos")
            }}
        }
    }
    delete?.let {s -> AlertDialog(onDismissRequest={delete=null},title={Text("Retirer ce dossier ?")},text={Text("Il ne sera plus scanné. Les fichiers, les albums déjà indexés et leur progression sont conservés.")},confirmButton={TextButton(onClick={vm.removeSource(s);delete=null}) {Text("Retirer",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={delete=null}) {Text("Annuler")}}) }
    if(deleted) DeletedAlbumsDialog(vm) {deleted=false}
}
@Composable fun OneDriveSettingsRow(connected:Boolean,busy:Boolean,connect:()->Unit,disconnect:()->Unit) {
    var confirming by remember {mutableStateOf(false)}
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        OutlinedButton(onClick=connect,enabled=!busy,modifier=Modifier.weight(1f,fill=false)) {Icon(Icons.Outlined.Cloud,null);Spacer(Modifier.width(8.dp));Text(if(connected)"Choisir un dossier OneDrive" else "Connecter OneDrive")}
        if(connected) IconButton(onClick={confirming=true},enabled=!busy) {Icon(Icons.Outlined.Close,"Déconnecter OneDrive",tint=MaterialTheme.colorScheme.error)}
    }
    if(confirming) AlertDialog(onDismissRequest={confirming=false},title={Text("Déconnecter OneDrive ?")},text={Text("Les dossiers OneDrive ne seront plus accessibles. Les copies hors ligne restent disponibles.")},confirmButton={TextButton(onClick={disconnect();confirming=false}) {Text("Déconnecter",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={confirming=false}) {Text("Annuler")}})
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun BookSheet(b:Book,close:()->Unit,offline:(Book)->Unit,remove:(Book)->Unit,markRead:()->Unit,seriesCount:Int=1,research:AlbumResearch=AlbumResearch(),save:(Book)->Unit={},search:((Book)->Unit)?=null) {
    var editing by remember(b.id) {mutableStateOf(false)}
    var deleting by remember {mutableStateOf(false)}
    val ctx=LocalContext.current
    ModalBottomSheet(onDismissRequest=close) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(if(seriesCount>1)b.series.ifBlank {b.title} else b.displayTitle,fontWeight=FontWeight.Bold,fontSize=20.sp)
            if(seriesCount>1)Text("$seriesCount albums · marquage de la série entière",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(b.demo)Text("Album fictif de démonstration · pages répétées",fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
            if(b.issue.isNotBlank())Text(b.issue,color=MaterialTheme.colorScheme.error)
            listOf("Série" to b.series,"N°" to b.number,"Parution" to PublicationDate.display(b.date),"Genre" to b.genre,"Dessinateur" to b.artist,"Scénariste" to b.writer,"Éditeur" to b.publisher)
                .filter {it.second.isNotBlank()}.forEach {(key,value)->Row {Text("$key : ",fontWeight=FontWeight.SemiBold);Text(value,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
            Text("${b.pages} pages · ${b.status}",color=StatusColor(b))
            if(b.synopsis.isNotBlank())Text(b.synopsis,Modifier.fillMaxWidth(),fontSize=13.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Justify)
            if(b.ratingSource.isNotBlank() && (b.rating!=null || b.reviewCount!=null)) {
                val score=if(b.rating!=null && b.ratingScale!=null)String.format(java.util.Locale.FRANCE,"%.2f / %.0f",b.rating,b.ratingScale) else "Note non disponible"
                Text("${BibliographicSources.sourceLabel(b.ratingSource)} · $score${b.reviewCount?.let {" · $it avis"}.orEmpty()}",Modifier.clickable {ctx.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(b.ratingSource)))},fontSize=13.sp,color=MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.CenterHorizontally),verticalAlignment=Alignment.CenterVertically) {
                OutlinedButton(onClick=markRead,modifier=Modifier.weight(1f),colors=ButtonDefaults.outlinedButtonColors(contentColor=FinishedColor())) {Text("Marquer Lu")}
                OutlinedButton(onClick={deleting=true},modifier=Modifier.weight(1f),border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.error)) {Icon(Icons.Outlined.DeleteOutline,null,tint=MaterialTheme.colorScheme.error);Spacer(Modifier.width(6.dp));Text("Supprimer",color=MaterialTheme.colorScheme.error)}
            }
            Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                OutlinedButton(onClick={editing=true},modifier=Modifier.weight(1f)) {Text("Modifier la fiche")}
                if(!b.demo) {
                val offlineColor=if(b.pinned) {if(MaterialTheme.colorScheme.background.luminance()<.5f)Color(0xFF81C784) else Color(0xFF256B2B)} else MaterialTheme.colorScheme.primary
                OutlinedButton(onClick={offline(b)},modifier=Modifier.weight(1f),colors=ButtonDefaults.outlinedButtonColors(contentColor=offlineColor),border=androidx.compose.foundation.BorderStroke(1.dp,if(b.pinned)offlineColor else MaterialTheme.colorScheme.outline)) {Icon(if(b.pinned) Icons.Outlined.OfflinePin else Icons.Outlined.Download,null);Spacer(Modifier.width(8.dp));Text(if(b.pinned)"Libérer la copie hors ligne" else "Télécharger")}
                } else Spacer(Modifier.weight(1f))
            }
        }
    }

    if(editing) EditBookDialog(b,{editing=false},search=search?.let {action -> {updated:Book ->action(updated);editing=false}}) {updated -> save(updated);editing=false}
    if(deleting) AlertDialog(onDismissRequest={deleting=false},title={Text("Supprimer cet album ?")},text={Text("Il sera retiré de BubbleBD avec ses copies de lecture. Ses infos, sa progression et sa première page restent enregistrées. Le fichier original est conservé. Les prochains scans ne le réimporteront pas automatiquement.")},confirmButton={TextButton(onClick={deleting=false;remove(b)}) {Text("Supprimer")}},dismissButton={TextButton(onClick={deleting=false}) {Text("Annuler")}})

}
