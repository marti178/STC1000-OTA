package com.marti.stc1000.domain

enum class ThemeMode(val label: String) {
    SYSTEM("Sistema"),
    LIGHT("Claro"),
    DARK("Oscuro"),
}

/** Los datos siempre se guardan y se manejan en °C; la unidad solo cambia cómo se muestran. */
enum class TempUnit(val label: String, val symbol: String) {
    C("Celsius", "°C"),
    F("Fahrenheit", "°F"),
    ;

    fun convert(celsius: Double): Double = if (this == F) celsius * 9.0 / 5.0 + 32.0 else celsius

    /** Diferencia de temperatura (no lleva el +32). */
    fun convertDelta(celsiusDelta: Double): Double = if (this == F) celsiusDelta * 9.0 / 5.0 else celsiusDelta

    /** Inversa de [convert]: lo que escribe el usuario en su unidad -> °C. */
    fun toCelsius(value: Double): Double = if (this == F) (value - 32.0) * 5.0 / 9.0 else value
}
