package com.manhwalens.app.phrasebook

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray

@Entity(tableName="reading_dictionary",primaryKeys=["series","en"])
data class Correction(val series:String,val en:String,val ar:String)
@Dao interface CorrectionDao {
    @Query("SELECT * FROM reading_dictionary ORDER BY series,en") suspend fun all():List<Correction>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun save(rows:List<Correction>)
    @Delete suspend fun delete(row:Correction)
}
@Database(entities=[Correction::class],version=1,exportSchema=false)
abstract class PhraseDb:RoomDatabase(){abstract fun dictionary():CorrectionDao}
object PhraseLibrary {
    val packs=listOf("general","murim","hunter","regression","fantasy","romance","school","business")
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);private val lock=Mutex()
    private lateinit var ctx:Context;private val prefs by lazy {ctx.getSharedPreferences("phrase_packs",0)}
    private val dao by lazy {Room.databaseBuilder(ctx,PhraseDb::class.java,"reading-phrases.db").build().dictionary()}
    val enabled:Set<String> get()=prefs.getStringSet("enabled",packs.toSet())!!.toSet()
    val series:String get()=prefs.getString("series","").orEmpty()
    private lateinit var base:List<Entry>;private lateinit var loading:Deferred<Unit>
    @Volatile var index:PhraseIndex?=null;private set
    @Volatile var version=0L;private set
    @Volatile var startupMs=0L;private set
    internal fun loadAssets(ctx:Context):List<Entry> {
        val bytes=ctx.assets.open("phrase-index.bin").use {it.readBytes()};val b=java.nio.ByteBuffer.wrap(bytes);val size=b.int
        val strings=String(bytes,4,size,Charsets.UTF_8).split('\u0000');b.position(4+size);val ints=b.asIntBuffer();val genres=HashMap<String,Set<String>>()
        fun str()=strings[ints.get()]
        return List(ints.get()){val en=str();val ar=str();val type=str();val g=str();val priority=ints.get();val key=str();val words=List(ints.get()){str()};Entry(en,ar,type,genres.getOrPut(g){g.split(',').toSet()},priority,key,words)}
    }
    fun start(context:Context){ctx=context.applicationContext;loading=scope.async {val tm=System.nanoTime();base=loadAssets(ctx);rebuild();startupMs=(System.nanoTime()-tm)/1_000_000;com.manhwalens.app.diagnostics.Diagnostics.info("phrasebook","load","entries=${base.size}",durationMs=(System.nanoTime()-tm)/1000000) }}
    suspend fun ready():PhraseIndex? {if(!::loading.isInitialized)return null;loading.await();return index}
    private suspend fun rebuild(){val user=(if(ctx.getDatabasePath("reading-phrases.db").exists())dao.all() else emptyList()).filter {it.series.isEmpty()||it.series==series}.map { Entry(it.en,it.ar,"term",setOf("user"),if(it.series.isEmpty())100 else 110) };index=PhraseIndex(user.sortedByDescending {it.priority}+base,enabled,presorted=true);version++}
    suspend fun configure(p:Set<String>,s:String)=withContext(Dispatchers.IO){ready();lock.withLock {prefs.edit().putStringSet("enabled",p.intersect(packs.toSet())).putString("series",s.trim().take(100)).apply();rebuild()}}
    suspend fun rows()=withContext(Dispatchers.IO){dao.all()}
    suspend fun save(rows:List<Correction>)=withContext(Dispatchers.IO){ready();require(rows.size<=5000);val normalized=rows.map { r->val en=PhraseIndex.key(r.en);require(en.length in 1..350 && r.ar.length in 1..500 && com.manhwalens.app.translate.ArabicOutput.hasLetters(r.ar));Correction(r.series.trim().take(100),en,r.ar.trim()) };lock.withLock {dao.save(normalized);rebuild()}}
    suspend fun delete(row:Correction)=withContext(Dispatchers.IO){ready();lock.withLock {dao.delete(row);rebuild()}}
    suspend fun export():String=JSONObject().put("entries",JSONArray().also {a->rows().forEach {a.put(JSONObject().put("en",it.en).put("ar",it.ar).put("series",it.series))} }).toString(2)
    suspend fun import(raw:String){val a=JSONObject(raw).getJSONArray("entries");require(a.length()<=5000);save(List(a.length()){i->val e=a.getJSONObject(i);Correction(e.optString("series"),e.getString("en"),e.getString("ar"))})}
}
