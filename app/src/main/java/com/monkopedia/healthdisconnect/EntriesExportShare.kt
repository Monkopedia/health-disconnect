package com.monkopedia.healthdisconnect

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

fun writeEntriesCsvToCache(
    context: Context,
    viewName: String,
    mode: EntriesExportMode,
    csvText: String
): File {
    val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
    val safeViewName = viewName
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "entries" }
    val fileName =
        "health_disconnect_${safeViewName}_${mode.name.lowercase(Locale.US)}_${System.currentTimeMillis()}.csv"
    return File(exportDir, fileName).apply { writeText(csvText) }
}

fun shareEntriesCsv(
    context: Context,
    viewName: String,
    file: File
) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.data_view_export_subject, viewName))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(
            shareIntent,
            context.getString(R.string.data_view_export_share_chooser)
        )
    )
}
