package fr.bubblebd

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.openid.appauth.*
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Personal OneDrive, delegated Files.Read only. No writes, uploads or account sync. */
class OneDrive private constructor(private val context:Context) {
    companion object {
        @Volatile private var instance:OneDrive?=null
        fun get(context:Context)=instance ?: synchronized(this) {instance ?: OneDrive(context.applicationContext).also {instance=it}}
        private const val graph="https://graph.microsoft.com/v1.0"
        fun uri(drive:String,item:String):String=Uri.Builder().scheme("onedrive").authority(drive).appendPath(item).build().toString()
        fun resource(value:String):String {val u=Uri.parse(value);require(u.scheme=="onedrive" && !u.authority.isNullOrBlank() && u.pathSegments.size==1);return "/drives/${Uri.encode(u.authority)}/items/${Uri.encode(u.pathSegments[0])}"}
    }
    data class Item(val uri:String,val name:String,val folder:Boolean)
    private val service=AuthorizationService(context)
    private val storage=context.getSharedPreferences("onedrive-auth",Context.MODE_PRIVATE)
    private val mutex=Mutex()
    private var auth:AuthState=load()
    val configured get()=BuildConfig.ONEDRIVE_CLIENT_ID.isNotBlank()
    val connected get()=auth.isAuthorized
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        return (store.getKey("bubblebd-onedrive",null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("bubblebd-onedrive",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun load():AuthState=runCatching {
        val data=storage.getString("state",null) ?: return AuthState()
        val parts=data.split(':');val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))
        AuthState.jsonDeserialize(String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),Charsets.UTF_8))
    }.getOrElse {AuthState()}
    private fun save() {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        val data=cipher.doFinal(auth.jsonSerializeString().toByteArray(Charsets.UTF_8))
        storage.edit().putString("state",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(data,Base64.NO_WRAP)).apply()
    }
    internal fun authorizationRequest():AuthorizationRequest {
        check(configured) {"La connexion OneDrive attend l’enregistrement de BubbleBD chez Microsoft. Aucun accès à votre compte n’a encore été demandé."}
        val config=AuthorizationServiceConfiguration(Uri.parse("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize"),Uri.parse("https://login.microsoftonline.com/consumers/oauth2/v2.0/token"))
        return AuthorizationRequest.Builder(config,BuildConfig.ONEDRIVE_CLIENT_ID,ResponseTypeValues.CODE,Uri.parse("fr.bubblebd.auth://oauth2redirect"))
            .setScope("https://graph.microsoft.com/Files.Read offline_access").setPrompt("select_account").build()
    }
    fun loginIntent():Intent=service.getAuthorizationRequestIntent(authorizationRequest())
    suspend fun finishLogin(intent:Intent)=mutex.withLock {
        val response=AuthorizationResponse.fromIntent(intent)
        val error=AuthorizationException.fromIntent(intent)
        require(response!=null && error==null) {"Connexion OneDrive annulée ou refusée."}
        val state=AuthState(response,error)
        suspendCancellableCoroutine<Unit> {continuation ->
            service.performTokenRequest(response.createTokenExchangeRequest()) {token,ex ->
                if(!continuation.isActive)return@performTokenRequest
                if(token==null || ex!=null)continuation.resumeWithException(IllegalStateException("Microsoft n’a pas pu terminer la connexion."))
                else {state.update(token,ex);auth=state;save();continuation.resume(Unit)}
            }
        }
    }
    suspend fun disconnect()=mutex.withLock {auth=AuthState();storage.edit().clear().apply()}
    private suspend fun accessToken():String=mutex.withLock {
        check(connected) {"Reconnectez OneDrive dans Réglages pour ouvrir cet album."}
        suspendCancellableCoroutine {continuation -> auth.performActionWithFreshTokens(service) {token,_,ex ->
            save()
            if(continuation.isActive) {
                if(token==null || ex!=null)continuation.resumeWithException(IllegalStateException("Votre connexion OneDrive a expiré. Reconnectez-vous dans Réglages."))
                else continuation.resume(token)
            }
        } }
    }
    private suspend fun get(url:String):JSONObject=withContext(Dispatchers.IO) {
        val u=URL(url);require(u.protocol=="https" && u.host=="graph.microsoft.com")
        val c=(u.openConnection() as HttpURLConnection).apply {instanceFollowRedirects=false;connectTimeout=15000;readTimeout=25000;setRequestProperty("Authorization","Bearer ${accessToken()}")}
        try {
            check(c.responseCode==200) {when(c.responseCode) {401->"Reconnectez OneDrive dans Réglages.";403->"Microsoft refuse l’accès à ce dossier.";429->"OneDrive est temporairement occupé. Réessayez plus tard.";else->"OneDrive indisponible (${c.responseCode})."}}
            val bytes=c.inputStream.use {it.readBytesLimited(8*1024*1024)};JSONObject(String(bytes,Charsets.UTF_8))
        } finally {c.disconnect()}
    }
    suspend fun describe(value:String):Item {
        val item=get(graph+resource(value)+"?\$select=id,name,folder,parentReference")
        return Item(value,item.getString("name"),item.has("folder"))
    }
    suspend fun root():Item {
        val drive=get("$graph/me/drive");val id=drive.getString("id")
        val root=get("$graph/drives/${Uri.encode(id)}/root")
        return Item(uri(id,root.getString("id")),"OneDrive",true)
    }
    suspend fun children(folder:String):List<Item> {
        val drive=Uri.parse(folder).authority!!;var next:String?="$graph${resource(folder)}/children?\$top=200";val items=mutableListOf<Item>();val seen=mutableSetOf<String>()
        while(next!=null) {
            check(seen.add(next) && seen.size<=100) {"Dossier OneDrive trop volumineux."}
            val data=get(next);val values=data.getJSONArray("value")
            for(i in 0 until values.length()) {
                val v=values.getJSONObject(i)
                // Remote shortcuts need an explicit remote drive identity, never a guessed path.
                if(v.has("remoteItem"))continue
                items.add(Item(uri(drive,v.getString("id")),v.getString("name"),v.has("folder")))
            }
            next=data.optString("@odata.nextLink").takeIf {it.isNotBlank()}
        }
        return items.sortedWith(compareBy<Item> {!it.folder}.thenComparator {a,b->BookRules.compareNatural(a.name,b.name)})
    }
    suspend fun firstZipPage(value:String):ByteArray=withContext(Dispatchers.IO) {
        val info=get("$graph${resource(value)}")
        val size=info.getLong("size")
        val address=URL(info.getString("@microsoft.graph.downloadUrl"))
        require(address.protocol=="https")
        RemoteZipCover.read(size) {offset,length ->
            coroutineContext.ensureActive()
            val c=(address.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects=false;connectTimeout=15000;readTimeout=30000
                setRequestProperty("Range","bytes=$offset-${offset+length-1}")
                setRequestProperty("Accept-Encoding","identity")
            }
            try {
                require(c.responseCode==206) {"Lecture partielle indisponible"}
                require(c.getHeaderField("Content-Range")=="bytes $offset-${offset+length-1}/$size")
                c.inputStream.use {it.readBytesLimited(length)}.also {require(it.size==length)}
            } finally {c.disconnect()}
        }
    }
    suspend fun open(value:String):InputStream=withContext(Dispatchers.IO) {
        val token=accessToken();var address="$graph${resource(value)}/content"
        repeat(5) {hop ->
            val url=URL(address);require(url.protocol=="https")
            val c=(url.openConnection() as HttpURLConnection).apply {instanceFollowRedirects=false;connectTimeout=15000;readTimeout=60000;if(hop==0)setRequestProperty("Authorization","Bearer $token")}
            if(c.responseCode in 300..399) {val location=c.getHeaderField("Location");c.disconnect();require(!location.isNullOrBlank()) {"Téléchargement OneDrive inaccessible"};address=URL(url,location).toString()}
            else {
                if(c.responseCode!=200) {c.disconnect();error("Impossible de télécharger cet album OneDrive. Réessayez ou reconnectez-vous.")}
                return@withContext object:FilterInputStream(c.inputStream) {override fun close() {try {super.close()} finally {c.disconnect()}}}
            }
        }
        error("Trop de redirections OneDrive")
    }
}
private fun InputStream.readBytesLimited(limit:Int):ByteArray {
    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(16384)
    while(true) {val n=read(buffer);if(n<0)break;check(output.size()+n<=limit);output.write(buffer,0,n)}
    return output.toByteArray()
}
