# Estado del proyecto

Última actualización: 2026-11-21 (noche).

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
- v1.0.7: comando `MATRIZ` por MQTT (por mil de cada par en `stc1000/diag`): diagnóstico sin USB.
- v1.0.8: umbral 1,8 % (a 220 V los "fantasmas" de las transiciones llegan a 1,3 %: con el 1 %
  inventaban el punto D1-D2 y el segmento G del último dígito, 27.0 ↔ 27.8); tópico
  `stc1000/salidas`; comando `TX <dBm>`; `info.leds`.
- v1.0.9 a v1.1.1: LEDs identificados (**Heat = +6/−5, Cool = +6/−7**); republica "online" cada
  60 s; no publica cambios más seguido que cada 5 s.
- v1.1.0: **OTA con reanudación** (`Range: bytes=N-`, Pages responde 206): un corte del WiFi ya
  no reinicia la descarga; si falla, reintenta en 10 min.
- v1.1.2: **autocurado de la radio**: 3 cortes rápidos del MQTT (< 3 min conectado) → rehace el
  WiFi; 6 → borra la calibración de la radio (`esp_phy_erase_cal_data_in_nvs`) y reinicia. El OTA
  también borra la calibración antes de reiniciar (tras un reinicio por software la radio quedaba
  mala hasta cortar la alimentación). `info.cortes_rapidos`.
- v1.1.3: "parpadea" solo con dos cambios del LED en 3 s.
- v1.1.4-1.1.5: **log persistente en la flash** (LittleFS, ~48 KB): cada arranque con su motivo
  (encendido, BROWNOUT, watchdog, software...), WiFi, MQTT, autocurado y OTA. Se lee con `LOG`
  por MQTT (`stc1000/log`) o `log` por serie. Autocurado si no asocia al WiFi.
- v1.1.6: rehace la conexión WiFi cada 45 s sin red (al enchufar a 220 V el primer intento
  quedaba trabado y la reconexión automática de Arduino no reintenta ciertos motivos).
- v1.1.7: el log guarda el motivo de cada desconexión del WiFi.
- **Potencia: 11 dBm.** Con 8,5 dBm el router no escuchaba bien al ESP32 (AUTH_EXPIRE al
  arrancar, 42 % de pings perdidos); con 11 dBm, 3 % y sin reinicios ni brownout aunque
  conmuten los relés (prueba 2026-11-21 23:17-23:19 UTC).
- v1.1.8: TX por MQTT solo de 8,5 a 13 dBm y a prueba (se guarda si el MQTT aguanta 2 min);
  latido `info` + "online" cada 20 s; comandos con vencimiento `@<epoch>`; temperatura retenida
  vacía cuando el display no muestra número; `millis()` con resta con signo (49 días).
- v1.1.9: el rollback no cuenta los arranques por corte de luz o brownout y no reinstala sola la
  versión descartada (`ota/bad_ver`); lo que pasa justo después de un OTA no cuenta como corte
  rápido; `salidas` en null si la lectura está vieja.
- v1.2.0: watchdog del loop a 30 s (un publish con el enlace muriendo bloqueaba ~10 s).
- v1.2.1: cada vez que rehace el WiFi escanea y anota con qué RSSI ve al router.
- v1.2.2: comando `PROTO B|BG|BGN` (modo WiFi a prueba 3 min); cada corte MQTT
  anota el estado de los relés; el error HTTP del OTA con su nombre (el "PADLOCK" era un error
  TLS viejo: en realidad fue un timeout de lectura, -11).
- v1.2.3: el modo de `PROTO` se guarda si queda estable; con 10 min sin WiFi vuelve a b/g/n.
- v1.2.4: `LOG` en pedazos de 400 bytes, alimentando el watchdog y cortando al primer fallo (los de 1,5 KB trababan el loop: 3 reinicios por watchdog y los cortes "a los 45 s" de la noche del 21/11 fueron todos justo después de pedir un LOG). `LOG <bytes>`.
- **Router: 2,4 GHz del canal 1 (6 vecinos) al 11, 20 MHz** (2026-11-21).
- **Diagnóstico 220 V / acuario (2026-11-21):**
  - Con v1.1.9 a 220 V en la mesa no asociaba (AUTH_EXPIRE, 4WAY_HANDSHAKE_TIMEOUT); por USB
    conectaba en 3 s. Tras el OTA a 1.2.0 hecho por USB (borra y rehace la calibración con
    alimentación limpia), 3 de 3 arranques a 220 V conectaron en 2-5 s. Teoría: había quedado
    guardada una calibración mala hecha con alimentación ruidosa.
  - En el acuario con v1.2.0: 20 min sin asociar, nunca NO_AP_FOUND (escucha al router, el
    router no lo escucha a él). Con v1.2.1/1.2.2 en el acuario: asocia, RSSI −56 a −65. Los
    primeros ~4 min el MQTT se cortó 5 veces a los 45-53 s (timeout del broker: lo que manda el
    ESP32 no llega, el WiFi sigue asociado) con los dos relés apagados; después estable, con el
    de frío prendido. Ping: 0-7 % de pérdida por minuto.
  - Hipótesis abiertas (segunda opinión): ruido del equipo del acuario (driver de luces, bomba)
    por aire o por la línea; armado que desafina la antena (cables, sonda en el agua); bobinas de
    los relés colgando del 5V alimentado al revés por el LDO.
- **En el equipo: v1.2.4, instalado en el acuario, 11 dBm, 802.11b.** Noche del 21 al 22 en 802.11b: 11 h sin un corte del MQTT (01:42 a 12:47), ping 0,2-0,5 % de pérdida por hora. Los únicos cortes y reinicios fueron por pedir el LOG. El frío estuvo prendido toda la noche y la temperatura no bajó de 29 °C (revisar el equipo de frío). (Antes, v1.1.3 con 8,5 dBm:) Prueba de relés (2026-11-21 01:05-01:12):
  ~6 conmutaciones sin reinicios ni cortes del MQTT; el ping tiene cortes de 1 a 5 s (hasta 22 s)
  al conmutar. El OTA a 1.1.3 se hizo con un relé conmutando y volvió solo tras reiniciar.
- Antes (v1.0.6, en banco): **en la LAN, publicando.** OTA automático verificado dos veces
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

1. **Instalación a 220 V: hecha, con cambios forzados.** La HLK-PM03 se quemó y el OB2222 murió:
   hoy todo se alimenta con una HLK-PM03 nueva (3,3 V al pin 3V3) y las bobinas de los relés salen del pin 5V de la
   Super Mini (con 3,3 V no conmutan). Eso provoca microcortes del WiFi. Mejoras pendientes de
   hardware: capacitor de 470-1000 µF en la alimentación del ESP32 y una fuente de 5 V aparte para
   los relés. Lo que sigue es el plan original, como referencia:
   - Revisar el circuito del relé en la placa de potencia: si el transistor es NPN con emisor a
     masa, anda con 3,3 V; si es PNP a 5 V, no apagaría. Faltan fotos de los dos lados de la
     placa de potencia, del relé y del conector entre placas.
   - Alimentar la placa del display y el ESP32 con un HLK-PM03 (220 V → 3,3 V); **no** conectar el
     pin VDD que viene de la placa de potencia; unir todas las masas.
   - Caja cerrada. Nunca USB con el termostato enchufado; actualizar solo por OTA.
2. **Ya no:** el "punto D1-D2 que parpadea" era un fantasma del LED Cool/Heat. **Un punto superior del display sin identificar.** Cuando se prenda, aparece en
   `stc1000/info` → `desconocidos` (o con `m` por serie).
3. Tailscale: en el panel de administración, "Disable key expiry" para el nodo del server (si no,
   vence en marzo de 2027). En el celular, la app Tailscale con la misma cuenta.
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
