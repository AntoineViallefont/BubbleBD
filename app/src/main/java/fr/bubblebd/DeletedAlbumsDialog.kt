package fr.bubblebd

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Composable fun DeletedAlbumsDialog(vm:LibraryViewModel,close:()->Unit) {
    var selected by remember {mutableStateOf(emptySet<String>())}
    LaunchedEffect(Unit) {vm.refreshDeletedNames()}
    val albums=vm.deletedBooks
    val previewSlots=remember {Semaphore(1)}
    LaunchedEffect(albums) {selected=selected.intersect(albums.map {it.id}.toSet())}
    Dialog(onDismissRequest=close) {
        Surface(shape=MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(ui("Albums supprimés"),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    IconButton(onClick=close) {Icon(Icons.Outlined.Close,ui("Fermer les albums supprimés"))}
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    Text(ui("${selected.size} sélectionné${if(selected.size>1)"s" else ""}"),style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={vm.restoreDeleted(albums.map {it.id}.toSet())},enabled=albums.isNotEmpty() && !vm.busy) {Text(ui("Tout réimporter"))}
                }
                if(albums.isEmpty()) Text(ui("Aucun album supprimé"),Modifier.padding(vertical=16.dp))
                LazyColumn(Modifier.weight(1f,fill=false),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(albums,key={it.id}) {book ->
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(checked=book.id in selected,onCheckedChange={selected=if(it)selected+book.id else selected-book.id},enabled=!vm.busy,modifier=Modifier.size(44.dp).semantics {contentDescription=ui("Sélectionner ${book.displayTitle}")})
                            DeletedPagePreview(vm.repo,book,previewSlots)
                            Text(book.displayTitle,Modifier.weight(1f).padding(horizontal=8.dp),maxLines=3,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)
                            TextButton(onClick={vm.restoreDeleted(setOf(book.id))},enabled=!vm.busy) {Text(ui("Réimport"))}
                        }
                    }
                }
                if(vm.busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(ui(vm.task),style=MaterialTheme.typography.bodySmall)}
                Button(onClick={vm.restoreDeleted(selected)},enabled=selected.isNotEmpty() && !vm.busy,modifier=Modifier.fillMaxWidth().padding(top=8.dp)) {Text(ui("Réimporter la sélection"))}
            }
        }
    }
}

@Composable private fun DeletedPagePreview(repo:Repository,book:Book,slots:Semaphore) {
    var bitmap by remember(book.uri,book.filename) {mutableStateOf<Bitmap?>(null)}
    var unavailable by remember(book.uri,book.filename) {mutableStateOf(false)}
    LaunchedEffect(book.uri,book.filename) {
        try {bitmap=slots.withPermit {repo.deletedPreview(book)}}
        catch(e:CancellationException) {throw e}
        catch(_:Exception) {unavailable=true}
    }
    DisposableEffect(bitmap) {val held=bitmap;onDispose {held?.recycle()}}
    Box(Modifier.size(width=48.dp,height=68.dp),contentAlignment=Alignment.Center) {
        val image=bitmap
        if(image!=null) {
            Image(image.asImageBitmap(),ui("Première page de ${book.displayTitle}"),Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
        } else if(unavailable) {
            Icon(Icons.Outlined.ImageNotSupported,ui("Première page inaccessible"))
        } else {
            CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
        }
    }
}
