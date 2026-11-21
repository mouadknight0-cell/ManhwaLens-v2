package benchmark.phase3b

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.*
import com.manhwalens.app.translate.*
import com.manhwalens.app.names.NameShield
import com.manhwalens.app.pipeline.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.time.Instant

class Benchmark {
    // Delegate unchanged to the real SDK. Keep the SAME task for the production caller.
    private class Tap(val sdk:Translator): Translator by sdk {
        var input:String?=null
        var task:Task<String>?=null
        override fun translate(text:String):Task<String> {
            input=text
            return sdk.translate(text).also { task=it }
        }
        fun reset(){input=null;task=null}
    }

    @Test fun cleanEnglish() = runBlocking {
        val i=InstrumentationRegistry.getInstrumentation()
        check(i.targetContext.packageName=="com.manhwalens.phase3b")
        val data=i.context.assets.open("dataset.tsv").bufferedReader().use { it.readLines().drop(1).map { line->line.split('\t') } }
        require(data.size==40 && data.all { it.size==6 && it.all(String::isNotBlank) } && data.map{it[0]}.distinct().size==40)
        val rows=data.map { d->JSONObject().apply {
            listOf("id","scene","category","original_en","context","reference_ar").forEachIndexed { n,k->put(k,d[n]) }; put("status","NOT_RUN")
        } }
        val terms=ReadingGlossary.murim+ReadingGlossary.defaults
        val report=JSONObject().apply {
            put("baseline","a0db02b822ce93edf3aa6b6d9758ebdc7db2bb2a");put("started_utc",Instant.now().toString());put("device","${Build.MANUFACTURER} ${Build.MODEL}");put("android",Build.VERSION.RELEASE);put("api",Build.VERSION.SDK_INT);put("mlkit","17.0.3");put("variant","isolated debug instrumentation v1");put("glossary",JSONObject(terms));put("confirmed_names",JSONArray());put("corrections",JSONObject());put("rows",JSONArray(rows));put("cache","cleared per arm and item, no warm pass")
        }
        val dest=File(i.targetContext.filesDir,"phase3_results.json")
        fun save(){val temp=File(dest.parentFile,"phase3.tmp");temp.writeText(report.toString(2));check(temp.renameTo(dest))};save()
        val sdk=Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.ARABIC).build())
        val tap=Tap(sdk);val client=TranslationClient()
        try {
            val model=TranslateRemoteModel.Builder(TranslateLanguage.ARABIC).build()
            val installed=withTimeout(15000){RemoteModelManager.getInstance().isModelDownloaded(model).await()}
            report.put("already_installed",installed);save();check(installed){"No installed Arabic model in benchmark host; no download attempted"}
            // Run disconnected. Production readiness verification is idempotent for installed files.
            check(LanguageModels.ensureReady(forceProbe=true)){"Installed model failed production probe"};report.put("ready",true);save()
            val field=TranslationClient::class.java.getDeclaredField("translators").apply {isAccessible=true}
            @Suppress("UNCHECKED_CAST") val clients=field.get(client) as MutableMap<Pair<String,String>,Translator>
            clients[client.source to client.target]=tap
            fun trace(arm:JSONObject){arm.put("cache","cold");tap.input?.let {arm.put("model_input",it)};tap.task?.let { t->if(t.isSuccessful)arm.put("raw_ar",t.result) else if(t.isComplete)arm.put("task_error",t.exception.toString()) else arm.put("task_pending_at_return",true) }}
            for((n,row) in rows.withIndex()){
                val source=data[n][3];val app=JSONObject();val native=JSONObject();row.put("app",app);row.put("native_only",native)
                try {
                    client.clearCache();tap.reset()
                    val prev=data.getOrNull(n-1)?.takeIf {it[1]==data[n][1] && data[n][0] !in setOf("06","08","11","12","13","14","16","21","25","39","40")}?.get(3)
                    val block=client.translateBlock(TextRegion(source,0,0,500,60,1f),previous=prev)
                    app.put("previous",prev?:JSONObject.NULL);app.put("prepared_en",block.original);app.put("route",if(block.errorCode!=null && tap.input==null) "rejected before inference" else block.method);app.put("final_ar",block.translated);app.put("status",block.errorCode?:"OK");trace(app)
                } catch(e:Exception){trace(app);app.put("status",e.toString())}
                // Independent forced-native control, even for exact, hybrid and rejected app routes.
                client.clearCache();tap.reset();native.put("route","translateText / forced native")
                try {
                    val prepared=TranslationInput.prepare(source,terms.keys.toList());native.put("prepared_en",prepared)
                    val mask=ReadingGlossary.modelMask(prepared.trim(),emptyList(),terms);native.put("masked_en",mask.maskedText);native.put("tokens",JSONArray(mask.tokens));native.put("names",JSONArray(mask.names))
                    native.put("final_ar",client.translateText(prepared,terms=terms));native.put("status","OK")
                } catch(e:Exception){native.put("status",e.toString())}
                trace(native)
                if(native.has("raw_ar")){
                    val mask=ReadingGlossary.modelMask(native.getString("prepared_en").trim(),emptyList(),terms)
                    native.put("restored_ar",NameShield.restore(native.getString("raw_ar"),mask.copy(names=mask.names.map {terms[it.lowercase()]?:it})))
                }
                row.put("status","ATTEMPTED");save()
            }
        } catch(e:Exception){report.put("run_error",e.toString());throw e} finally {client.close();sdk.close();report.put("ended_utc",Instant.now().toString());save()}
    }
}
