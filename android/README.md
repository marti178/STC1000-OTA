# App Android STC-1000

App nativa (Kotlin + Jetpack Compose + Material 3) que muestra la temperatura del termostato STC-1000 leída por MQTT desde el ESP32. Incluye gráfico (Hoy / 24 h / 7 días), estadísticas, estado del equipo y comandos (`CHECK`, `OTA`, `REBOOT`).

## Cómo funciona

- La app se conecta al Mosquitto de tu server casero (puerto 1883, o 8883 con TLS) con usuario y clave.
- Desde cualquier lado, sin Tailscale en el celular: el server publica el broker por **Tailscale Funnel** (TLS, puerto 8443). Ver `server/README.md`. También se puede usar la IP de la LAN o la de Tailscale.
- Está conectada **mientras la app está abierta** (30 s de gracia al salir). Reconecta sola con espera creciente (2, 4, 8… hasta 60 s) y reacciona a cambios de red (Wi-Fi ↔ datos).
- Cada lectura en vivo y cada `historial` recibido se guardan en una base local (Room), deduplicando por timestamp redondeado al paso (`paso_s`). Se conservan 90 días.
- **El historial largo lo guarda y publica el server**, no el celular: un servicio en el Debian anota cada muestra y publica retenido `<prefijo>/historial_largo` (hasta 30 días, mismo formato que `historial`). Al abrir la app llega al instante, aunque no la hayas abierto en días. La app no hace nada en segundo plano.
- La contraseña se guarda cifrada con AES/GCM, con una clave del Android Keystore.

## Abrir el proyecto en Android Studio

1. Android Studio → **File → Open** → elegí la carpeta `android/`.
2. Esperá el *Gradle sync* (usa el wrapper incluido, Gradle 9.6; necesita internet la primera vez). Requiere JDK 17+ (el que trae Android Studio sirve) y el SDK de Android con la plataforma 37.1 (Android Studio la descarga si falta).
3. Conectá el celular con depuración USB y apretá **Run ▶**.

Por línea de comandos (Windows, usando el JDK de Android Studio):

```
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew.bat assembleDebug
gradlew.bat testDebugUnitTest
```

Si no existe `local.properties`, Android Studio lo crea; a mano sería `sdk.dir=C:/Users/<vos>/AppData/Local/Android/Sdk` (con barras `/`).

## Instalar el APK

El APK de debug queda en `app/build/outputs/apk/debug/app-debug.apk`.

- Con el celular conectado: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- O copialo al celular y abrilo (hay que permitir «instalar apps de origen desconocido» para el explorador de archivos).

## Configurar la conexión

La primera vez la app abre en **Ajustes**:

| Campo | Qué poner |
|---|---|
| Servidor | el nombre público del Funnel (`debian.tailc89921.ts.net`), o la IP de la LAN (`192.168.x.x`) |
| Puerto | `8443` (Funnel, con TLS) o `1883` (LAN, sin TLS) |
| Usuario / Contraseña | los del Mosquitto (`password_file`) |
| Prefijo | `stc1000` (el mismo que el firmware) |
| Usar TLS | solo si Mosquitto tiene un certificado válido (no sirve un autofirmado) |

Tocá **Probar conexión** (conecta y lee `stc1000/estado`) y después **Guardar**. De ahí en adelante la app conecta sola al abrirla.

Mensajes de error que vas a ver: «Usuario o clave incorrectos», «No se llega al server: ¿está activo Tailscale…?», «El server rechazó la conexión…», «No se encuentra el servidor…», errores de TLS. Con usuario/clave incorrectos la app **no** reintenta sola (para no ser bloqueada por fail2ban): corregí los datos o apretá *Reintentar*.

## Pantallas

- **Inicio**: temperatura grande, estado del equipo (en línea / desconectado), hace cuánto llegó el último dato (en rojo si pasaron más de 15 min), texto del display si no es número (`LLL` = error de sonda, `HHH`), selector de período, gráfico táctil (tocá o arrastrá para ver valor y hora) y estadísticas: mínima y máxima con su hora, promedio y cantidad de muestras.
- **Equipo**: IP, señal Wi-Fi, uptime, firmware, reconexiones, progreso de OTA, y botones con confirmación: *Buscar actualización* (`CHECK`), *Forzar actualización* (`OTA`), *Reiniciar* (`REBOOT`). Se publican en `<prefijo>/comando`, retenido y QoS 1.
- **Ajustes**: conexión.

## Estructura

```
android/
  gradle/libs.versions.toml        catálogo de versiones
  app/src/main/java/com/marti/stc1000/
    Stc1000App.kt, MainActivity.kt
    data/    Room (Sample, AppDatabase), SettingsStore (DataStore), SecretBox (Keystore AES/GCM)
    domain/  History(Parser), Samples (redondeo/fusión), Stats, Period, Format  <- lógica pura, con tests
    mqtt/    MqttRepository (conexión mantenida), MqttSupport (cliente HiveMQ, errores), NetworkWatcher
    ui/      MainViewModel, HomeScreen, DeviceScreen, SettingsScreen, TemperatureChart, Theme
  app/src/test/...                 tests unitarios (parseo, fusión/dedupe, estadísticas, formatos)
```

## Notas y límites

- Una lectura **retenida** (la que llega al conectar) se muestra pero no se guarda como muestra, porque no dice cuándo se midió; el historial real viene del tópico `historial`. Por eso «hace cuánto llegó el último dato» usa lo recibido en vivo o la última muestra guardada.
- Si el reloj del ESP32 no está sincronizado (`fin` en 1970 o en el futuro), el `historial` se descarta.
- Gráfico propio con Canvas (sin librería), con cortes donde faltan más de 15 min de datos.
- `targetSdk = 36`. No se subió a 37 porque Android 17 exige un permiso en tiempo de ejecución para acceder a la red local (LAN) y no pude probarlo; con Tailscale (`100.x`) no aplica, pero para uso en LAN habría que revisarlo.
- El release no está minificado (HiveMQ/Netty necesitan reglas de R8 propias).
