package com.marti.stc1000.domain

import org.json.JSONException
import org.json.JSONObject

/**
 * Contenido del tópico `historial`: `{"paso_s":300,"fin":1795142400,"t":[26.8,27.0,null,...]}`.
 * [values] va de la más vieja a la más nueva; `null` = sin lectura.
 * La muestra i tiene timestamp `endEpoch - (size-1-i) * stepSec`.
 */
data class History(
    val stepSec: Int,
    val endEpoch: Long,
    val values: List<Double?>,
) {
    fun timestampOf(index: Int): Long = endEpoch - (values.size - 1 - index).toLong() * stepSec
}

object HistoryParser {
    /** Devuelve null si el texto no es un historial válido (nunca tira excepción). */
    fun parse(text: String): History? {
        return try {
            val obj = JSONObject(text.trim())
            val step = obj.optInt("paso_s", 0)
            val end = obj.optLong("fin", 0L)
            val arr = obj.optJSONArray("t") ?: return null
            if (step <= 0 || end <= 0L) return null
            val values = ArrayList<Double?>(arr.length())
            for (i in 0 until arr.length()) {
                if (arr.isNull(i)) {
                    values.add(null)
                } else {
                    val v = arr.optDouble(i, Double.NaN)
                    values.add(if (v.isNaN() || v.isInfinite()) null else v)
                }
            }
            History(step, end, values)
        } catch (e: JSONException) {
            null
        }
    }
}
