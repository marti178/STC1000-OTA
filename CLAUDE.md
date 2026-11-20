# STC1000-OTA: contexto para Claude

El usuario habla español rioplatense; respondé en español. Antes de seguir, leé
`docs/ESTADO.md`: ahí está qué se hizo y qué falta.

## Qué es
Un ESP32-C3 Super Mini **espía** el display charlieplexado de un termostato clon STC-1000
(placa XDHY, micro FMD) y publica la temperatura por MQTT a un Mosquitto en un server Debian
de la casa del usuario. Una app Android muestra temperatura, gráfico y estadísticas.
El firmware se actualiza solo desde GitHub Pages (mismo esquema que el repo del usuario
`marti178/Mareografo-OTA`).

## Estructura
- `src/main.cpp`: firmware (lectura de 3 estados de 7 patas, decodificación, filtro de lecturas
  estables, MQTT, historial 24 h, OTA con SHA-256 y rollback, comandos por serie y por MQTT).
- `include/secrets.example.h`: plantilla. `include/secrets.h` está en `.gitignore`.
- `include/ca_letsencrypt.h`: raíces ISRG X1/X2 para MQTT con TLS (puerto 8443/8883).
- `version.txt`: **única** fuente de la versión del firmware (`scripts/version.py` la inyecta).
- `.github/workflows/build.yml`: en cada push a `main` (que no sea solo `android/`, `server/`,
  `docs/` o `.md`) compila y publica `version.json` + `firmware.bin` en GitHub Pages.
- `android/`: app Kotlin + Compose (HiveMQ client, DataStore + Keystore; sin base local: baja el historial del server). Usa `historial` y `historial_largo`.
- `server/`: config de Mosquitto (sin claves), Funnel/nftables, y `server/historial/` (servicio
  que guarda el historial en SQLite y publica `stc1000/historial_largo`).
- `docs/STC1000_ESP32_resumen.md`: relevamiento del hardware y mapa del display.

## Reglas
- **Repo público.** Nunca commitear claves, `include/secrets.h`, la IP de LAN o de Tailscale del
  server, el nombre de la red WiFi ni hostnames `*.ts.net`. Usar marcadores
  (`IP_LAN_DEL_SERVER`, `100.x.y.z`).
- **Cada cambio de firmware que deba llegar por OTA: subir `version.txt`** (semver) y push a `main`.
  El ESP32 consulta al minuto de arrancar y cada 6 h; para no esperar, publicar retenido
  `CHECK` en `stc1000/comando`.
- El contrato de tópicos MQTT está en `README.md`. La app depende de él: si cambia, actualizar
  los dos lados.
- Comentarios del código en español sin tildes, como el resto.
- Commits terminan con la línea `Co-Authored-By` que indique el sistema.

## Compilar
- Firmware: PlatformIO, `platform = espressif32@7.1.3` (Arduino-ESP32 2.0.17), env `esp32c3`.
  `pio run` / `pio run -t upload` / `pio device monitor` (115200). Si `pio` no está en el PATH,
  en Windows suele estar en `~/.platformio/penv/Scripts/pio.exe`.
- Android: necesita JDK 17+ (en Windows sirve el de Android Studio:
  `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`).
  `cd android && ./gradlew testDebugUnitTest assembleDebug`.

## Credenciales en una PC nueva
`include/secrets.h` no está en GitHub. Solo hace falta para grabar por **USB**; el OTA anda sin él
porque el ESP32 guarda WiFi y MQTT en su flash (NVS).
- Crearlo copiando `include/secrets.example.h`.
- WiFi: la red de 2,4 GHz de la casa (el ESP32-C3 no usa 5 GHz). El server Debian la tiene
  guardada en NetworkManager (`sudo nmcli -s -g 802-11-wireless-security.psk connection show "<nombre>"`).
- MQTT: usuario `stc1000`. Las claves de Mosquitto están hasheadas en el server; si no se tienen,
  se regeneran con `sudo mosquitto_passwd /etc/mosquitto/passwd stc1000` (ver `server/README.md`)
  y se cargan en el ESP32 por serie (`set mqtt_pass ...`, `reboot`).
- Server: `ssh server@IP_LAN_DEL_SERVER` (pedirle la IP al usuario).

## Seguridad eléctrica (no negociable)
- La fuente del termostato (OB2222, buck sin aislación) **no está aislada**: con 220 V todo lo
  conectado al display queda a potencial de red. Nunca USB a la PC con el termostato enchufado.
- El FMD original trabaja a **5 V** (medido). El ESP32-C3 **no tolera 5 V** (hoja de datos: máx.
  3,6 V en los pines). En la instalación final el FMD se alimenta a 3,3 V (fuente aparte tipo
  HLK-PM03) y no se conecta el VDD de la placa de potencia.
- Las pruebas de banco se hacen con la placa del display separada y alimentada desde el 3V3 del
  ESP32.
