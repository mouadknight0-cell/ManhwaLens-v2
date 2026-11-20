package com.manhwalens.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.manhwalens.app.R
import com.manhwalens.app.phrasebook.Correction
import com.manhwalens.app.phrasebook.PhraseLibrary
import com.manhwalens.app.pipeline.CaptureResult
import kotlinx.coroutines.*

@Composable fun PhraseSettings(){
    val ctx=LocalContext.current;val scope=rememberCoroutineScope()
    val saved=stringResource(R.string.phrase_done);val failed=stringResource(R.string.phrase_error)
    var rows by remember { mutableStateOf(emptyList<Correction>()) };var packs by remember { mutableStateOf(PhraseLibrary.enabled) }
    var series by remember { mutableStateOf(PhraseLibrary.series) };var en by remember { mutableStateOf("") };var ar by remember { mutableStateOf("") };var status by remember { mutableStateOf("") }
    LaunchedEffect(Unit){rows=PhraseLibrary.rows()}
    fun run(action:suspend()->Unit){scope.launch {try {action();rows=PhraseLibrary.rows();status=saved}catch(c:CancellationException){throw c}catch(_:Exception){status=failed}}}
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)run {withContext(Dispatchers.IO){val text=requireNotNull(ctx.contentResolver.openInputStream(uri)).use { input->val out=java.io.ByteArrayOutputStream();val buf=ByteArray(8192);while(true){val n=input.read(buf);if(n<0)break;require(out.size()+n<=2_000_000);out.write(buf,0,n)};out.toString("UTF-8") };PhraseLibrary.import(text)}}}
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->if(uri!=null)run {withContext(Dispatchers.IO){val text=PhraseLibrary.export();requireNotNull(ctx.contentResolver.openOutputStream(uri,"wt")).bufferedWriter().use {it.write(text)}}}}
    SettingsGroup(stringResource(R.string.phrase_settings),series){
        val labels=listOf(R.string.phrase_general,R.string.phrase_murim,R.string.phrase_hunter,R.string.phrase_regression,R.string.phrase_fantasy,R.string.phrase_romance,R.string.phrase_school,R.string.phrase_business)
        PhraseLibrary.packs.forEachIndexed {i,g->Row {Checkbox(g in packs,{on->val next=if(on)packs+g else packs-g;packs=next;run {PhraseLibrary.configure(next,series)}});Text(stringResource(labels[i]))} }
        OutlinedTextField(series,{series=it},label={Text(stringResource(R.string.phrase_series))});Button(onClick={run {PhraseLibrary.configure(packs,series)}}){Text(stringResource(R.string.phrase_save))}
        OutlinedTextField(en,{en=it},label={Text(stringResource(R.string.phrase_en))});OutlinedTextField(ar,{ar=it},label={Text(stringResource(R.string.phrase_ar))})
        Button(enabled=en.isNotBlank()&&ar.isNotBlank(),onClick={run {PhraseLibrary.save(listOf(Correction(series,en,ar)));en="";ar=""}}){Text(stringResource(R.string.phrase_save))}
        rows.filter { it.series==series && (en.isBlank() || it.en.contains(en,true)) }.take(30).forEach { r->Row { TextButton(onClick={en=r.en;ar=r.ar},modifier=Modifier.weight(1f)){Text("${r.en} · ${r.ar}")};TextButton(onClick={run {PhraseLibrary.delete(r)}}){Text(stringResource(R.string.phrase_del))} } }
        Row { TextButton(onClick={importer.launch(arrayOf("application/json","text/plain"))}){Text(stringResource(R.string.phrase_import))};TextButton(onClick={exporter.launch("manhwalens-dictionary.json")}){Text(stringResource(R.string.phrase_export))} };Text(status)
    }
}
@Composable fun HistoryCorrection(entry:CaptureResult){
    var open by remember {mutableStateOf(false)};var en by remember {mutableStateOf("")};var ar by remember {mutableStateOf("")};var failed by remember {mutableStateOf(false)};val scope=rememberCoroutineScope()
    TextButton(onClick={open=true;failed=false;en=entry.blocks.firstOrNull()?.original.orEmpty();ar=entry.blocks.firstOrNull()?.translated.orEmpty()}){Text(stringResource(R.string.phrase_report))}
    if(open)AlertDialog(onDismissRequest={open=false},title={Text(stringResource(R.string.phrase_report))},text={Column(Modifier.verticalScroll(rememberScrollState())){entry.blocks.forEach {b->TextButton(onClick={en=b.original;ar=b.translated}){Text(b.original)}};Text(en);OutlinedTextField(ar,{ar=it},label={Text(stringResource(R.string.phrase_ar))});if(failed)Text(stringResource(R.string.phrase_error))}},confirmButton={TextButton(onClick={scope.launch {try{PhraseLibrary.save(listOf(Correction(PhraseLibrary.series,en,ar)));open=false}catch(c:CancellationException){throw c}catch(_:Exception){failed=true}}}){Text(stringResource(R.string.phrase_save))}},dismissButton={TextButton(onClick={open=false}){Text(stringResource(R.string.phrase_cancel))}})
}
