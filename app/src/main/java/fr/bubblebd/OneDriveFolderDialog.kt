package fr.bubblebd

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

@Composable fun OneDriveFolderDialog(drive:OneDrive,close:()->Unit,choose:(OneDrive.Item)->Unit) {
    var path by remember {mutableStateOf<List<OneDrive.Item>>(emptyList())}
    var folders by remember {mutableStateOf<List<OneDrive.Item>>(emptyList())}
    var loading by remember {mutableStateOf(true)}
    var error by remember {mutableStateOf<String?>(null)}
    var revision by remember {mutableIntStateOf(0)}
    LaunchedEffect(path,revision) {
        loading=true;error=null
        try {if(path.isEmpty())path=listOf(drive.root()) else folders=drive.children(path.last().uri).filter {it.folder}}
        catch(e:Exception) {if(e is kotlinx.coroutines.CancellationException)throw e;error=e.message}
        finally {loading=false}
    }
    AlertDialog(onDismissRequest=close,title={Text("Dossier OneDrive")},text={Column(Modifier.fillMaxWidth().heightIn(min=200.dp,max=430.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {if(path.size>1) IconButton(onClick={path=path.dropLast(1)},enabled=!loading) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Dossier parent")};Text(path.joinToString(" / ") {it.name})}
        if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let {Text(it,color=MaterialTheme.colorScheme.error);TextButton(onClick={revision++}) {Text("Réessayer")}}
        if(!loading && error==null && folders.isEmpty()) Text("Aucun sous-dossier. Vous pouvez choisir ce dossier.")
        LazyColumn {items(folders,key={it.uri}) {folder -> Row(Modifier.fillMaxWidth().clickable(enabled=!loading) {path=path+folder}.padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {Icon(Icons.Outlined.FolderOpen,null);Spacer(Modifier.width(12.dp));Text(folder.name)}}}
    }},confirmButton={TextButton(onClick={path.lastOrNull()?.let(choose)},enabled=!loading && path.isNotEmpty() && error==null) {Text("Choisir ce dossier")}},dismissButton={TextButton(onClick=close) {Text("Annuler")}})
}
