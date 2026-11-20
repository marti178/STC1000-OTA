package com.marti.stc1000.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.marti.stc1000.domain.Format
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Exporta las lecturas que la app tiene en memoria a un CSV y abre el menú de compartir. */
object CsvExporter {
    fun toCsv(samples: List<Sample>): String = buildString {
        // Separador ';' y coma decimal: asi lo abre bien el Excel en espanol (con ',' lo mete todo en una columna).
        append("fecha_hora_local;epoch_utc;temperatura_c\n")
        for (s in samples) {
            append(Format.dateTimeFull(s.ts)).append(';')
                .append(s.ts).append(';')
                .append(String.format(Locale.US, "%.1f", s.temp).replace('.', ',')).append('\n')
        }
    }

    /** Escribe el archivo en el caché de la app (se borra solo) y devuelve el intent de compartir. */
    fun shareIntent(context: Context, samples: List<Sample>): Intent {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val name = "stc1000-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".csv"
        val file = File(dir, name)
        file.writeText(toCsv(samples), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Temperaturas STC-1000")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Compartir CSV").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
