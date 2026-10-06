package fr.bubblebd

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

@Composable fun EditBookDialog(book:Book,close:()->Unit,search:((Book)->Unit)?=null,save:(Book)->Unit) {
    var title by rememberSaveable(book.id) {mutableStateOf(book.title)}
    var series by rememberSaveable(book.id) {mutableStateOf(book.series)}
    var number by rememberSaveable(book.id) {mutableStateOf(book.number)}
    var date by rememberSaveable(book.id) {mutableStateOf(book.date)}
    var genre by rememberSaveable(book.id) {mutableStateOf(book.genre)}
    var artist by rememberSaveable(book.id) {mutableStateOf(book.artist)}
    var writer by rememberSaveable(book.id) {mutableStateOf(book.writer)}
    var publisher by rememberSaveable(book.id) {mutableStateOf(book.publisher)}
    var isbn by rememberSaveable(book.id) {mutableStateOf(book.isbn)}
    var synopsis by rememberSaveable(book.id) {mutableStateOf(book.synopsis)}
    var locked by rememberSaveable(book.id) {mutableStateOf(book.metadataLocked)}
    fun edited()=book.copy(title=title.trim(),series=series.trim(),number=number.trim(),date=date.trim(),genre=genre.trim(),artist=artist.trim(),writer=writer.trim(),publisher=publisher.trim(),isbn=isbn.trim(),synopsis=synopsis.trim(),metadataEdited=true,metadataLocked=locked,seriesFromFile=if(series.trim()!=book.series)false else book.seriesFromFile)
    AlertDialog(onDismissRequest=close,title={Text(ui("Modifier la fiche"))},text={
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock,null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ui("Figer les informations"))
                    Text(ui("Exclure cette fiche des recherches."),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked=locked,onCheckedChange={locked=it},modifier=Modifier.semantics {contentDescription=ui("Figer les informations")})
            }
            OutlinedButton(onClick={search?.invoke(edited())},enabled=!locked && !book.demo && title.isNotBlank() && search!=null,modifier=Modifier.fillMaxWidth().padding(vertical=8.dp)) {
                Icon(Icons.Outlined.Search,null);Spacer(Modifier.width(8.dp));Text(ui("Rechercher pour ce titre"))
            }
            EditBookField(ui("Titre"),title,{title=it})
            EditBookField(ui("Série"),series,{series=it})
            EditBookField(ui("N°"),number,{number=it})
            EditBookField(ui("Parution"),date,{date=it})
            EditBookField(ui("Genre"),genre,{genre=it})
            EditBookField(ui("Dessinateur"),artist,{artist=it})
            EditBookField(ui("Scénariste"),writer,{writer=it})
            EditBookField(ui("Éditeur"),publisher,{publisher=it})
            EditBookField("ISBN",isbn,{isbn=it})
            EditBookField(ui("Résumé"),synopsis,{synopsis=it},false)
        }
    },confirmButton={TextButton(enabled=title.isNotBlank(),onClick={
        save(edited())
    }) {Text(ui("Enregistrer"))}},dismissButton={TextButton(onClick=close) {Text(ui("Annuler"))}})
}

@Composable private fun EditBookField(label:String,value:String,change:(String)->Unit,singleLine:Boolean=true) {
    OutlinedTextField(value,change,Modifier.fillMaxWidth(),label={Text(label)},singleLine=singleLine,minLines=if(singleLine)1 else 3)
}
