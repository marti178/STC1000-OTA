# STC1000-OTA

Lectura por WiFi de un termostato clon **STC-1000** (placa XDHY, micro FMD) con un
**ESP32-C3 Super Mini** que espía el display charlieplexado. La temperatura se publica
por MQTT y el firmware se actualiza solo desde GitHub Pages.

- `src/main.cpp`: firmware (lectura del display, MQTT, historial de 24 h, OTA con rollback).
- `docs/STC1000_ESP32_resumen.md`: relevamiento del hardware y mapa del display.
- `server/`: configuración de Mosquitto en el server Debian.
- `android/`: app para el celular (gráfico y estadísticas).

## Cómo funciona el OTA

1. Cambiás el código y subís el número en `version.txt` (ej. `1.0.0` → `1.0.1`).
2. Hacés push a `main`. GitHub Actions compila y publica en GitHub Pages:
   - `https://marti178.github.io/STC1000-OTA/version.json` (versión, SHA-256 y tamaño)
   - `https://marti178.github.io/STC1000-OTA/firmware.bin`
3. El ESP32 consulta `version.json` un minuto después de arrancar y después cada 6 horas.
   Si hay una versión más nueva, la baja, verifica el SHA-256 y reinicia.
4. Si el firmware nuevo no llega a publicar por MQTT en 3 arranques (no cuentan los cortes de
   luz ni las caídas de tensión), el ESP32 vuelve solo a la versión anterior y no reinstala
   esa versión salvo con `OTA`.

Para no esperar las 6 horas, publicá **retenido** en `stc1000/comando`:
`CHECK` (instala si hay versión nueva), `OTA` (reinstala aunque sea la misma) o `REBOOT`.

```bash
mosquitto_pub -h IP_DEL_SERVER -u celular -P '...' -t stc1000/comando -m CHECK -r -q 1
```

**El firmware publicado no lleva ninguna clave.** El WiFi y el MQTT se guardan en la flash
del ESP32 (NVS), que el OTA no borra. Se cargan una sola vez:
- grabando por USB con `include/secrets.h` (copiá `include/secrets.example.h`), o
- por el monitor serie: `set ssid ...`, `set pass ...`, `set mqtt_host ...`,
  `set mqtt_user ...`, `set mqtt_pass ...` y después `reboot`.

## Primera grabación por USB

```bash
pio run -t upload
```

```bash
pio device monitor
```

Comandos del monitor serie: `s` (estado), `m` (matriz de pares), `r` (captura cruda),
`v` (cada lectura), `cfg`, `log` (log persistente), `set <clave> <valor>`, `check`, `ota`, `reboot`.

## Tópicos MQTT

| Tópico | Contenido | Retenido |
|---|---|---|
| `stc1000/estado` | `online` / `offline` (last will) | sí |
| `stc1000/temperatura` | ej. `27.3`, solo si el display muestra un número; si no (LLL, HHH, sin lectura) se publica **vacío** (borra el retenido) | sí |
| `stc1000/display` | ej. `27.3 C`, `LLL`, `sin lectura` | sí |
| `stc1000/firmware` | versión | sí |
| `stc1000/info` | JSON: ip, rssi, uptime, versión, reconexiones, se publica cada **20 s** (latido, junto con `estado` `online`); `tx_dbm`, `cortes_rapidos` (autocurado de la radio), `leds` (LEDs auxiliares prendidos o parpadeando) | sí |
| `stc1000/historial` | JSON con 24 h, una muestra cada 5 min (lo publica el ESP32) | sí |
| `stc1000/historial_largo` | mismo formato, 30 días (lo publica el server, `server/historial/`) | sí |
| `stc1000/salidas` | JSON `{"calor":"on\|parpadea\|off","frio":...}` según los LEDs Heat y Cool; `parpadea` = esperando la demora del compresor; `{"calor":null,"frio":null}` si la lectura del display está vieja | sí |
| `stc1000/ota` | avance de la actualización | no |
| `stc1000/log` | respuesta a `LOG`: últimas ~6 KB del log persistente en la flash (arranques con su motivo, WiFi, MQTT, autocurado, OTA) | no |
| `stc1000/diag` | respuesta a `MATRIZ`: por mil de cada par del display (5 tandas de 1 s) | no |
| `stc1000/comando` | entrada: `CHECK`, `OTA`, `REBOOT`, `MATRIZ`, `LOG` (o `LOG <bytes>`, 200 a 12000; por defecto 6000, en pedazos de 400), `LOGBORRAR`, `TX <dBm>` (8,5 a 15; si con más de 13 pasa 10 min sin WiFi vuelve a 13; se guarda solo si el MQTT sigue andando 2 min, si no vuelve a la anterior), `PROTO B`, `PROTO BG` o `PROTO BGN` (modo WiFi a prueba 3 min; si queda estable se guarda; vuelve a BGN recién tras 3 reinicios seguidos por 10 min sin WiFi). Opcional `@<epoch UTC>` al final (ej. `REBOOT@1795219181`): con más de 5 min se ignora | — |

`historial`: `{"paso_s":300,"fin":<epoch UTC de la última muestra>,"t":[26.8,27.0,null,...]}`,
de la más vieja a la más nueva; `null` = sin lectura en ese intervalo.

## Seguridad eléctrica

La fuente del termostato **no tiene aislación**: con el aparato enchufado a 220 V, todo
lo conectado al display (el ESP32 incluido) queda a potencial de red. El ESP32 va en una
caja cerrada, alimentado aparte, y **nunca** conectado por USB a una PC mientras el
termostato esté enchufado. El FMD tiene que trabajar a 3,3 V: el ESP32-C3 no tolera
los 5 V de la fuente original. Más detalles en `docs/`.

## Red

El ESP32 publica al Mosquitto del server **solo por la LAN** (puerto 1883). La app del celular
llega desde afuera de casa por **Tailscale Funnel** (puerto 8443, TLS), a un listener aparte que
solo acepta al usuario `celular`, o por Tailscale. No hay puertos abiertos en el router.
Configuración del server en `server/`.

El firmware también soporta MQTT con **TLS**: si `MQTT_PORT` es 8443 u 8883, valida el certificado
contra las raíces de Let's Encrypt (`include/ca_letsencrypt.h`), después de tomar la hora por NTP.
Hoy el ESP32 no lo usa: el listener de Funnel no acepta al usuario `stc1000`.

Estado del proyecto y pendientes: [`docs/ESTADO.md`](docs/ESTADO.md).
