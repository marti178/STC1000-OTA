# Estado del proyecto

Última actualización: 2026-11-20 (noche).

## Hecho y verificado

### Lectura del display (en banco, sin 220 V)
- Placa del display separada, alimentada desde el 3V3 del ESP32; las 7 patas van directo a
  GPIO 5, 6, 7, 4, 3, 10, 20.
- El FMD arranca y funciona a 3,3 V, y lee bien la sonda NTC (la medición es ratiométrica).
- El barrido dura unos 4 ms. El FMD maneja **un par de patas por vez** (las demás quedan en Z),
  con slots de 43 a 740 µs. Hay segmentos que están prendidos solo el ~2 % del tiempo: el umbral
  es 1 %.
- Mapa confirmado contra el display real ("LLL", "26.x", "27.x"): los 21 segmentos de los tres
  dígitos, punto D2-D3, punto de grados, "C" y punto inferior izquierdo.
  Correcciones respecto del relevamiento a mano: **D2 D = +6/−2** (no +2/−6) y D2 B = +4/−2
  (confirmado con un 7).
- Lectura verificada: el ESP32 leyó 26,2 a 27,6 °C, igual que el display.

### Firmware
- v1.0.0: MQTT, filtro de lecturas estables, historial 24 h (NTP), OTA desde Pages con SHA-256
  y rollback por software, configuración en NVS, autodescubrimiento de Home Assistant.
- v1.0.1: MQTT con TLS si el puerto es 8443/8883. El ESP32 no lo usa (va por la LAN); queda por
  si algún día tiene que publicar desde afuera de casa.
- v1.0.2: el punto D1-D2 parpadea como indicador (~1 s) mientras se muestra "25.9"; con el
  decimal D2-D3 prendido ya no entra en el número (antes publicaba "2.5.9 C" y 2,5 °C). Va en
  `info.indicador`. Filtro: 3 ventanas iguales; con '?' tiene que durar ~3 s.
- v1.0.3: OTA con timeouts de 20-30 s, log del motivo TLS y del heap, hasta 3 intentos.
- v1.0.4: `WiFi.setSleep(false)` (el modem sleep trababa la descarga del OTA a los ~96 KB) y
  potencia configurable (`set tx_dbm`, NVS).
- v1.0.5: no repite el chequeo de OTA mientras espera confirmar; 11 dBm por defecto (con 15 dBm
  esta Super Mini no conecta al WiFi).
- v1.0.6: corrige un reinicio falso al arrancar ("10 min sin conexión"): `millis() | 1` podía
  quedar en el futuro y la resta sin signo daba un número enorme; después de un OTA eso contaba
  como arranque sin confirmar.
- **En el equipo: v1.0.6, en la LAN, publicando.** OTA automático verificado dos veces
  (1.0.4 → 1.0.5 y 1.0.5 → 1.0.6 con `CHECK` por MQTT): baja ~975 KB en ~36 s, reinicia, se
  confirma a los 2 min. Señal −57 a −74 dBm con 11 dBm de potencia.
- GitHub Pages a veces falla al publicar ("Fetching artifact metadata failed"). Re-ejecutar la
  misma corrida NO sirve (queda un artefacto duplicado): lanzar una corrida nueva
  (workflow_dispatch).

### Server Debian
- Mosquitto, puerto 1883 (LAN y Tailscale), usuarios `stc1000` (ESP32) y `celular` (app), ACL,
  sin anónimos, persistencia con autosave cada 60 s. Probado por LAN y por Tailscale.
- **Tailscale Funnel activo, solo para la app**: `:8443` con TLS (Let's Encrypt) → listener
  `127.0.0.1:1884` con su propio archivo de claves (solo `celular`) y su ACL, `max_connections 10`
  y un límite de conexiones nuevas por minuto con nftables. Probado desde internet: entra
  `celular`; anónimo, clave mala y `stc1000` son rechazados. El ESP32 sigue solo por la LAN.
  No hay puertos abiertos en el router. Detalles en `server/README.md`.
- IP fija del server reservada en el DHCP del router. El router reparte DNS 8.8.8.8 / 1.1.1.1.

- **Historial en el server**: `stc1000-historial.service` (Python + paho-mqtt, usuario MQTT
  `historial`) guarda cada muestra del `historial` del ESP32 en SQLite (400 días) y publica
  retenido `stc1000/historial_largo` con 30 días. Ignora los retenidos (pueden ser viejos o de
  prueba). Código en `server/historial/`; credenciales en `/etc/stc1000-historial.env` del server.
- Datos de prueba que se publicaron para probar la app: ya limpiados del broker y de la base.
- Actualizaciones de seguridad automáticas activadas; server reiniciado con el kernel nuevo y
  todo volvió solo. La revisión de seguridad del server está en un PDF fuera del repo (no se publica).

### App Android
- `android/`, auditada. 23 tests unitarios pasan y `assembleDebug` compila.
- APK publicado como release `app-v1.0.0` en GitHub.
- Corrección de la auditoría: las lecturas en vivo se guardan en el casillero del **cierre** del
  intervalo de 5 min, igual que el historial del ESP32.

## Pendiente (en orden)

1. **Instalación final con 220 V** (hardware, sin hacer):
   - Revisar el circuito del relé en la placa de potencia: si el transistor es NPN con emisor a
     masa, anda con 3,3 V; si es PNP a 5 V, no apagaría. Faltan fotos de los dos lados de la
     placa de potencia, del relé y del conector entre placas.
   - Alimentar la placa del display y el ESP32 con un HLK-PM03 (220 V → 3,3 V); **no** conectar el
     pin VDD que viene de la placa de potencia; unir todas las masas.
   - Caja cerrada. Nunca USB con el termostato enchufado; actualizar solo por OTA.
2. **Un punto superior del display sin identificar.** Cuando se prenda, aparece en
   `stc1000/info` → `desconocidos` (o con `m` por serie).
3. Tailscale: en el panel de administración, "Disable key expiry" para el nodo del server (si no,
   vence en marzo de 2027). En el celular, la app Tailscale con la misma cuenta.
4. Ver qué indica el punto D1-D2 que parpadea (¿demora del compresor?, ¿relé?).
5. Detalles menores de la app: la selección del gráfico se borra con cada lectura nueva; si el
   Keystore pierde la clave, la app dice "usuario o clave incorrectos" en vez de pedirla.

## Decisiones tomadas
- Credenciales en NVS, no en el firmware: así el repo y el `firmware.bin` pueden ser públicos.
- OTA por HTTPS sin validar el certificado de GitHub Pages; la integridad la da el SHA-256 de
  `version.json`.
- El ESP32 guarda 24 h en RAM (se pierden al reiniciar). El server las acumula en SQLite
  (servicio `stc1000-historial`, 400 días) y publica 30 días en `stc1000/historial_largo`; así la
  app no depende de quedar abierta ni de tareas en segundo plano (que Xiaomi frena).
- Sin hosting pago: desde afuera, la app entra por Tailscale Funnel (o por Tailscale). El ESP32
  no se expone: publica por la LAN.
