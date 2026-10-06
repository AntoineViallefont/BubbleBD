package fr.bubblebd

import androidx.work.WorkInfo
import androidx.work.workDataOf

data class MetadataSearchStatus(val phase:Phase=Phase.IDLE,val title:String="",val position:Int=0,val total:Int=0,val bookId:String="") {
    enum class Phase { IDLE, SEARCHING, WAITING }
    val visible get()=phase==Phase.SEARCHING
    val counter:String get()=if(total>0 && position in 1..total)"BD $position/$total" else ""
    fun progress()=workDataOf("metadataPhase" to phase.name,"metadataTitle" to title.take(240),"metadataPosition" to position,"metadataTotal" to total,"metadataBookId" to bookId.take(512))

    companion object {
        /** A stopped/retried worker must never display its previous active album. */
        fun fromWork(infos:List<WorkInfo>):MetadataSearchStatus {
            val running=infos.firstOrNull {it.state==WorkInfo.State.RUNNING}
            if(running!=null) {
                val searching=running.progress.getString("metadataPhase")==Phase.SEARCHING.name
                return if(searching)MetadataSearchStatus(Phase.SEARCHING,running.progress.getString("metadataTitle").orEmpty(),running.progress.getInt("metadataPosition",0),running.progress.getInt("metadataTotal",0),running.progress.getString("metadataBookId").orEmpty())
                else MetadataSearchStatus(Phase.WAITING,position=running.progress.getInt("metadataPosition",0),total=running.progress.getInt("metadataTotal",0))
            }
            return if(infos.any {it.state==WorkInfo.State.ENQUEUED || it.state==WorkInfo.State.BLOCKED})MetadataSearchStatus(Phase.WAITING)
            else MetadataSearchStatus()
        }
    }
}
