package fr.bubblebd

import java.text.DateFormat
import java.util.Date

/** A completed lookup and a network attempt are different events. */
data class AlbumResearch(val state:State=State.PENDING,val completedAt:Long?=null,val attemptedAt:Long?=null) {
    enum class State { PENDING, SEARCHING, RETRY, QUOTA, FOUND, NO_MATCH, LEGACY }
    val label:String get()=when(state) {
        State.PENDING -> "Recherche en attente"
        State.SEARCHING -> "Recherche en cours…"
        State.RETRY -> "Recherche interrompue · nouvel essai prévu"
        State.QUOTA -> "Quota gratuit atteint · recherche différée"
        State.FOUND -> "Recherche effectuée · informations trouvées"
        State.NO_MATCH -> "Recherche effectuée · aucun résultat fiable"
        State.LEGACY -> "Recherche déjà effectuée"
    }
    val date:String get()=completedAt?.let {"Dernière recherche : "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT,java.util.Locale.FRANCE).format(Date(it))}.orEmpty()
}
