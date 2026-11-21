# App Android STC-1000

App nativa (Kotlin + Jetpack Compose + Material 3) que muestra la temperatura de un termostato STC-1000 leída por MQTT desde el ESP32: temperatura actual, gráficos (Hoy / 3 h / 24 h / 7 d / 30 d), estadísticas, estado del equipo y comandos (`CHECK`, `OTA`, `REBOOT`).

## Cómo funciona

- Se conecta al Mosquitto de tu server casero con usuario y clave: por **Tailscale Funnel** (TLS, puerto 8443, sin Tailscale en el celular, ver `server/README.md`) o por la LAN (puerto 1883).
- Está conectada **mientras la app está abierta** (30 s de gracia al salir). Reconecta sola con espera creciente (2, 4, 8… hasta 60 s, y nunca más de un intento cada 5 s) y reacciona a cambios de red. Con usuario o clave incorrectos no reintenta sola; reintenta con el botón *Reintentar* o cuando cambia la red.
- **No guarda lecturas en el celular ni hace nada en segundo plano.** El historial largo lo guarda el server (un servicio en el Debian anota cada muestra y publica retenido `<prefijo>/historial_largo`, hasta 30 días, mismo formato que `historial`). Cada vez que la app conecta lo baja y lo mantiene solo en memoria para dibujar; las lecturas en vivo se suman encima.
- La contraseña se guarda cifrada con AES/GCM, con una clave del Android Keystore. No se copia a otro celular (`allowBackup` apagado y reglas de extracción vacías); si no se puede leer, la app lo avisa y pide escribirla de nuevo.
- El APK publicado es un build **release** (no depurable), firmado con la misma clave que las versiones anteriores para poder instalar encima.

## Abrir el proyecto en Android Studio

1. Android Studio → **File → Open** → elegí la carpeta `android/`.
2. Esperá el *Gradle sync* (usa el wrapper incluido; necesita internet la primera vez). Requiere JDK 17+ (el que trae Android Studio sirve) y el SDK de Android con la plataforma 37.1 (Android Studio la descarga si falta).
3. Conectá el celular con depuración USB y apretá **Run ▶**.

Por línea de comandos (Windows, con el JDK de Android Studio):

```
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew.bat assembleDebug
gradlew.bat testDebugUnitTest
```

Si no existe `local.properties`, Android Studio lo crea; a mano sería `sdk.dir=C:/Users/<vos>/AppData/Local/Android/Sdk` (con barras `/`).

## Instalar el APK

Desde los *Releases* del repositorio (tags `app-vX.Y.Z`): se compila y publica solo con GitHub Actions al subir un tag. Bajá el `.apk`, abrilo y permití «instalar apps de origen desconocido». Se instala encima de la versión anterior sin perder la configuración.

Compilado a mano: `app/build/outputs/apk/debug/app-debug.apk` (`adb install -r ...`).

## Configurar la conexión

La primera vez la app abre en **Ajustes → Conexión**:

| Campo | Qué poner |
|---|---|
| Dirección | el nombre público del Funnel (`SERVIDOR.TU-TAILNET.ts.net`), o la IP de la LAN (`192.168.x.x`) |
| Puerto | `8443` (Funnel, con TLS) o `1883` (LAN, sin TLS) |
| TLS | activado para el Funnel (certificado público válido; no sirve uno autofirmado) |
| Usuario / Contraseña | los del Mosquitto |
| Prefijo | `stc1000` (el mismo que el firmware) |

Tocá **Probar conexión** y después **Guardar**. De ahí en adelante la app conecta sola al abrirla. Los campos de dirección, usuario y prefijo no tienen autocorrector y la dirección solo acepta letras sin tilde, números, puntos y guiones.

Mensajes de error: «Usuario o clave incorrectos», «No se llega al server: ¿está activo Tailscale…?», «El server rechazó la conexión…», «No se encuentra el servidor…», errores de TLS. La pestaña **Equipo** tiene un *Registro de conexión* para diagnosticar.

## Pantallas

- **Inicio:** temperatura grande en una tarjeta que cambia de color con la temperatura (gris si el dato es viejo, hay error de sonda o no hay conexión con el server), mini gráfico de 3 h, tendencia de 30 min, aviso si sale del rango deseado, gráfico con selector de período y estadísticas (mínima y máxima con su hora, promedio, muestras).
- **Gráficos:** gráfico grande (curva suave, banda de rango deseado, línea de promedio, marcas de mínima y máxima, táctil; zoom pellizcando con dos dedos; con zoom, un dedo arrastra para moverse y tocar elige la lectura), resumen estadístico (mediana, desvío estándar, % en rango, cobertura), resumen por día con barras mín-máx y tabla diaria en 7 y 30 días, y exportación a CSV (separador `;`, coma decimal, para Excel en español).
- **Equipo:** estado, firmware, IP, uptime, reconexiones, señal Wi-Fi, y botones con confirmación: *Buscar actualización* (`CHECK`), *Forzar actualización* (`OTA`), *Reiniciar* (`REBOOT`). Se publican en `<prefijo>/comando`, retenido y QoS 1.
- **Ajustes:** *Conexión*, *Preferencias* (tema claro/oscuro/sistema, colores dinámicos, °C/°F, opciones del gráfico, rango deseado, exportar CSV) y *Acerca de*.

## Estructura

```
android/
  gradle/libs.versions.toml        catálogo de versiones
  app/src/main/java/com/marti/stc1000/
    Stc1000App.kt, MainActivity.kt
    data/    Sample, SettingsStore (DataStore + Keystore), PrefsStore (preferencias), SecretBox (AES/GCM), CsvExporter
    domain/  History(Parser), Samples (redondeo/fusión), Stats + Summaries (estadísticas, por día, tendencia),
             Period, Format, Units       <- lógica pura, con tests
    mqtt/    MqttRepository (conexión mantenida), MqttSupport (cliente HiveMQ, errores), NetworkWatcher, State
    ui/      MainViewModel, HomeScreen, ChartsScreen, DeviceScreen, SettingsScreen (Conexión) + SettingsHost
             (Preferencias, Acerca de), TemperatureChart (gráficos con Canvas), Components, Theme
  app/src/test/...                 tests unitarios
```

## Notas y límites

- Una lectura **retenida** (la que llega al conectar) se muestra pero no se suma al gráfico, porque no dice cuándo se midió; el historial viene de `historial_largo` y de `historial`. «Hace cuánto llegó el último dato» usa lo recibido en vivo o la última muestra del historial.
- Las lecturas del historial llevan la hora de **cierre** de su intervalo de 5 min (la muestra de las 00:00 es el promedio de 23:55 a 00:00): por eso «Hoy» y el resumen por día no cuentan la de las 00:00 en el día nuevo.
- Si el reloj del ESP32 o del celular no coincide (fecha en 1970 o más de 10 min en el futuro), el historial se descarta y queda anotado en el registro de conexión.
- Gráficos propios con Canvas (sin librería), con cortes donde faltan más de 15 min de datos y reducción de puntos para 30 días.
- `targetSdk = 36`: Android 17 exige un permiso en tiempo de ejecución para acceder a la red local (LAN) y no se pudo probar; por Funnel no aplica.
- El release no está minificado (HiveMQ/Netty necesitan reglas de R8 propias).
