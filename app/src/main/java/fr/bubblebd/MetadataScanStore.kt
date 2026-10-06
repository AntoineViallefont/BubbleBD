package fr.bubblebd

import android.content.Context
import org.json.JSONArray

/** Persistent scope: only a manual request or newly imported albums can add targets. */
class MetadataScanStore(context:Context,namespace:String="production") {
    companion object {private val lock=Any()}
    private val prefs=context.getSharedPreferences("metadata-scan-v1-$namespace",Context.MODE_PRIVATE)
    private fun ids(key:String):Set<String> = runCatching {
        val a=JSONArray(prefs.getString(key,"[]"));(0 until a.length()).map {a.getString(it)}.toSet()
    }.getOrDefault(emptySet())
    fun targets():Set<String> = synchronized(lock) {ids("targets")}
    fun pending(kind:String):Set<String> = synchronized(lock) {ids("targets")-ids(kind)}
    fun request(targets:Set<String>,replace:Boolean=false)=synchronized(lock) {
        if(targets.isEmpty())return@synchronized
        val reset=replace || (pending("catalog").isEmpty() && pending("service").isEmpty())
        val edit=prefs.edit().putString("targets",JSONArray((if(reset)emptySet() else ids("targets"))+targets).toString())
        if(reset)edit.remove("catalog").remove("service")
        else for(kind in listOf("catalog","service"))edit.putString(kind,JSONArray(ids(kind)-targets).toString())
        edit.commit();Unit
    }
    fun complete(kind:String,id:String)=synchronized(lock) {
        prefs.edit().putString(kind,JSONArray(ids(kind)+id).toString()).commit();Unit
    }
    fun prune(retained:Set<String>)=synchronized(lock) {
        val edit=prefs.edit()
        for(key in listOf("targets","catalog","service"))edit.putString(key,JSONArray(ids(key).intersect(retained)).toString())
        edit.commit();Unit
    }
}
