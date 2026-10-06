package fr.bubblebd

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AboutScreen(back:()->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=back) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Retour aux réglages")}
                Heading("À propos")
            }
            Text("BubbleBD ${BuildConfig.VERSION_NAME}",color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { AboutText("BubbleBD est gratuite et permet de lire vos BD sur téléphone, sans compte à créer. Elle ouvre les PDF, CBZ / ZIP et CBR / RAR ; certains formats d’image dépendent du téléphone.") }
        item { AboutSection("Lire à votre rythme") {
            AboutText("Touchez deux fois une case pour l’agrandir ou revenir à la page entière. Pincez pour zoomer ou voir les cases voisines, puis déplacez l’image. Le pincement s’arrête au cadrage de la case : relâchez pour reprendre le défilement, ou pincez à nouveau pour continuer à zoomer. Un toucher affiche les commandes. Le sens de lecture se change dans l’en-tête et l’assombrissement dans Réglages.")
            AboutText("Les cases sont calculées sur votre téléphone, avec préparation de la page suivante. La détection peut se tromper : les cases reconnues restent utilisables. Sans case reconnue, la page entière reste affichée et le mode automatique reprend sur les pages suivantes. Une bulle ambiguë reste visible dans les cases concernées ; deux cases consécutives d’une même ligne peuvent être affichées ensemble.")
        } }
        item { AboutSection("Vos albums") {
            AboutText("Ajoutez un dossier depuis Android, y compris Google Drive si disponible, ou connectez votre OneDrive personnel. Les sous-dossiers sont aussi parcourus. La bibliothèque regroupe les séries et reprend votre progression. « Télécharger » conserve une copie pour lire sans connexion. Vider le cache garde ces copies.")
            AboutText("« Supprimer » retire l’album de BubbleBD et ses copies de lecture, sans toucher à l’original. Ses infos, sa progression et sa première page restent enregistrées, même si l’original devient inaccessible. Dans « Albums supprimés », vous pouvez réimporter un album, une sélection ou tous les albums ; la réimportation nécessite l’accès au fichier.")
        } }
        item { AboutSection("Les informations des BD") {
            AboutText("La recherche se lance dans Réglages ou à l’import, pour les nouveaux albums. Elle privilégie les ouvertures récentes, puis les nouveaux imports. Le bouton manuel permet aussi de réexaminer les fiches complètes. Ouvrir l’application ne relance pas la recherche. Après un échec, vous pouvez la relancer depuis Réglages.")
            AboutText("Les renseignements déjà connus servent à identifier l’album. La base commune est consultée avant Mistral ; des catalogues et sites publics complètent la recherche. Les résultats de Mistral sont partagés pour éviter de refaire le travail. Les quotas gratuits peuvent retarder la recherche. Les informations incertaines restent vides ; une autre édition du même album peut servir de référence. Une fiche identifiée peut corriger les champs déjà renseignés, y compris le titre. Les champs absents de la source restent conservés. Le résumé se lit en entier en défilant.")
        } }
        item { AboutSection("Données et crédits") {
            AboutText("Votre bibliothèque et votre progression restent sur ce téléphone. La recherche en ligne transmet les renseignements utiles à l’identification ; en dernier recours, du texte et une miniature de couverture peuvent être analysés. Aucun album complet n’est envoyé. La miniature n’est pas conservée dans la base commune.")
            AboutText("Logiciel libre sous licence GNU AGPL v3 ou ultérieure. Les BD et leurs images restent la propriété de leurs ayants droit. Modèle de détection : Leandro Narosky, basé sur Ultralytics YOLO26n / Manga109-s. LiteRT, Tesseract4Android et tessdata_fast : Apache-2.0 ; libarchive : BSD. BnF : Licence Ouverte ; Wikipédia : CC BY-SA, avec attribution des extraits.")
            AboutLink("Code source, licences et crédits","https://bubblebd-source-macavi.web.app")
        } }
    }
}

@Composable
private fun AboutSection(title:String,content:@Composable ColumnScope.()->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
        content()
    }
}

@Composable
private fun AboutText(value:String) {Text(value,modifier=Modifier.fillMaxWidth(),textAlign=androidx.compose.ui.text.style.TextAlign.Justify,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}

@Composable
private fun AboutLink(label:String,url:String) {
    val context=LocalContext.current
    TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))},contentPadding=PaddingValues(horizontal=0.dp,vertical=4.dp)) {
        Text(label,Modifier.weight(1f));Spacer(Modifier.width(8.dp));Icon(Icons.Outlined.OpenInNew,null,Modifier.size(18.dp))
    }
}
