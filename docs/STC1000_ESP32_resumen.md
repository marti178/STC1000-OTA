# Proyecto: lectura WiFi de un termostato STC-1000 (clon) con ESP32-C3 Super Mini

## Objetivo
Leer la temperatura que muestra el display del STC-1000 y publicarla por WiFi.
El ESP32 **espía** las patas del display: no lo maneja y no reemplaza el micro original.
Por ahora la meta es solo leer. Control remoto, en una etapa posterior.

## Hardware (relevado por fotos)
- Termostato clon **XDHY_STC1000_220V**.
  - Placa de display: `XDHY_STC1000_220V_XSB_21326`.
  - Placa de potencia: `XDHY_STC1000_220V_DYB_2`.
- Micro de la placa de display: **FMD** (Fremont Micro Devices), TSSOP-20, marcado `B2ePEKH`. Modelo exacto desconocido.
  - **No es la versión A400_P con PIC16F1828.** El firmware STC-1000+ y su pinout NO aplican.
- La placa de potencia tiene inductor 471, electrolíticos de 330 µF/16 V y un chip SO-8, y no se ve transformador. **Probablemente es una fuente sin aislación** (la masa estaría referida a la red).
- Botones (serigrafía en chino): 开关 = encendido, 加 = +, 设置 = set, 减 = −.
- Conector de programación del FMD: 5 pines, serigrafía 烧录口. No sirve para leer la temperatura.

## Display: charlieplexado de 7 pines
- 7 pines, 42 pares dirigidos posibles. No hay ánodos ni cátodos comunes.
- Tiene 3 dígitos (D3 más chico), signo −, °C y varios puntos.
- Mapa relevado a mano, corregido. Formato: (pata +, pata −). Segmentos: A sup, B sup der, C inf der, D inf, E inf izq, F sup izq, G centro.

| Elemento | + / − |
|---|---|
| D1 A,B,C,D,E,F,G | 2/1, 3/1, 1/4, 1/5, 4/1, 1/2, 1/3 |
| D2 A,B,C,D,E,F,G | 3/2, 4/2 (confirmado con un 7), 2/5, **6/2** (corregido: el relevamiento decía 2/6), 5/2, 2/3, 2/4 |
| D3 A,B,C,D,E,F,G | 4/5, 5/3, 5/4, 1/6, 3/6, 3/4, 3/5 |
| Punto D1-D2 | 6/3 |
| Punto D2-D3 | 4/3 |
| Signo − | 4/6 |
| Punto de grados (°) | 6/4 |
| C | 5/6 |
| Punto inf. izq. | 6/5 |
| Punto extremo der. | 5/7 |
| Punto sup. der. zona izq. | 7/6 |
| Punto adicional sup. izq. | 6/7 |

Pendiente:
- **D2-B** se dedujo porque D2 repite el patrón de D1 corrido una pata. Falta confirmarlo.
- Falta identificar **un punto superior**.
- Algunas lecturas del relevamiento original eran **caminos fantasma**: dos LEDs en serie cuando se prueba con demasiada tensión. Por ejemplo +1/−7, +7/−5 y +6/−1. Probablemente no tienen LED propio.

## Montaje de prueba (SIN 220 V)
- Placa del display **separada de la de potencia**, alimentada desde el ESP32. Nunca se enchufa a la red mientras el ESP32 esté conectado a la PC.
- Alimentación desde los extremos del capacitor **C3**, junto al FMD:
  - lado VDD → 3V3 del ESP32
  - lado GND → GND del ESP32
  - El lado GND se identificó por continuidad con la pata 20 del FMD (supuesto, no confirmado por datasheet).
- Las 7 patas del display van **directo** a los GPIO, sin resistencias. Es válido porque ambos chips comparten el mismo 3V3. Los GPIO deben quedar siempre como INPUT.

| Pata display | GPIO ESP32-C3 |
|---|---|
| 1 | 5 |
| 2 | 6 |
| 3 | 7 |
| 4 | 4 |
| 5 | 3 |
| 6 | 10 |
| 7 | 20 |

- No usar GPIO2, 8 ni 9 (pines de arranque) ni GPIO21 (lo usa el log de arranque).

## Firmware actual: `stc1000_espia_charlieplex.ino` (adjunto)
- Arduino IDE: placa "ESP32C3 Dev Module", USB CDC On Boot activado, monitor serie a 115200.
- **Lectura de 3 estados por pata:** lee con pull-up interno y después con pull-down.
  - Si las dos lecturas coinciden, la pata está manejada (H o L).
  - Si la lectura sigue al pull, está flotando (Z).
- Un LED está prendido si su pata + está en H y su pata − en L. Se acumula en ventanas de 300 ms con umbral del 3 %.
- Comandos por serie:
  - `d`: muestra el display decodificado y la temperatura.
  - `m`: muestra la matriz de pares activos en %.
  - `r`: hace una captura cruda de cambios H/L/Z con tiempo en µs.
- Avisa cuando aparece un "par desconocido activo", para descubrir lo que falta del mapa.
- **No se compiló ni se probó en hardware.**

## Riesgos y dudas abiertas
- No se sabe si el FMD arranca con 3,3 V. Si no arranca, hay que alimentarlo a 5 V y rediseñar la lectura, porque el ESP32-C3 no tolera 5 V.
- La detección de Z con los pulls internos puede fallar en patas flotantes conectadas a LEDs, por la corriente baja a través del LED. Si pasa, mirar la captura `r`.
- Sin la sonda NTC, el display probablemente muestre un error. Para probar números, usar los menús con los botones.

## Próximos pasos
1. Compilar el sketch y corregir errores.
2. Cargarlo y analizar la salida de `r` y `m`: orden de barrido, duración de cada slot y si los 3 estados se distinguen bien.
3. Completar el mapa (D2-B y el punto que falta) con los "pares desconocidos" que reporte.
4. Ajustar umbrales y la lectura según la captura.
5. Publicar la temperatura por WiFi (MQTT, HTTP o ESPHome, a definir con el usuario).
6. Instalación final: se resuelve aparte, por la fuente sin aislación. El ESP32 tiene que ir en caja cerrada, alimentado con fuente aislada y sin conexión USB a la PC mientras esté enchufado a la red.
