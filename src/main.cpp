// ESPIA del display charlieplexado del clon STC-1000 (placa XDHY, micro FMD)
// con un ESP32-C3 Super Mini. Lee la temperatura del display y la publica por MQTT.
//
// QUE HACE
//  - Mira las 7 patas del display y detecta en cada una si el chip FMD la pone
//    en ALTO (H), en BAJO (L) o la deja FLOTANDO (Z).
//  - Un LED esta prendido cuando su pata + esta en H y su pata - esta en L.
//  - Con el mapa relevado arma el texto del display y la temperatura.
//  - Solo acepta una lectura cuando sale igual varias ventanas seguidas
//    (filtra el "?" que aparece cuando un digito cambia en medio de la lectura).
//  - Publica por MQTT y guarda un historial de 24 h (una muestra cada 5 min).
//  - Se actualiza solo desde GitHub Pages (OTA) y, si el firmware nuevo no
//    llega a publicar, vuelve solo a la version anterior (rollback).
//  - Watchdog: si loop() se cuelga, se reinicia.
//
// TOPICOS MQTT (prefijo "stc1000", configurable)
//  stc1000/estado       online / offline (retenido, con "last will")
//  stc1000/temperatura  ej. 27.3 (retenido). Solo cuando el display muestra un numero
//  stc1000/display      texto del display, ej. "27.3 C", "LLL" o "sin lectura" (retenido)
//  stc1000/firmware     version del firmware (retenido)
//  stc1000/info         JSON: ip, rssi, uptime, version, reconexiones, pares desconocidos (retenido)
//  stc1000/historial    JSON con las ultimas 24 h (retenido, cada 5 min):
//                       {"paso_s":300,"fin":<epoch de la ultima muestra>,"t":[..., null, ...]}
//  stc1000/ota          avance de la actualizacion (no retenido)
//  stc1000/comando      ENTRADA: CHECK (buscar version nueva), OTA (forzar), REBOOT, MATRIZ
//  stc1000/salidas      JSON {"calor":"on|parpadea|off","frio":...} segun LEDs Heat/Cool (retenido)
//                       comando "TX <dBm>": potencia WiFi (se guarda en la flash)
//  stc1000/log          respuesta a LOG: ultimas ~6 KB del log persistente (LOGBORRAR lo borra)
//  stc1000/diag         respuesta a MATRIZ: por mil de cada par activo (5 tandas de 1 s)
//  + autodescubrimiento de Home Assistant (homeassistant/sensor/...)
//
// CONFIGURACION (WiFi y MQTT): se guarda en la flash del ESP32 (NVS), que el OTA
// no borra. Asi el firmware que compila GitHub NO lleva ninguna clave.
//  - Grabando por USB con include/secrets.h presente, se copia sola a la flash.
//  - O por el monitor serie: "set ssid MiRed", "set pass ...", "set mqtt_host ...",
//    "set mqtt_port 1883", "set mqtt_user ...", "set mqtt_pass ...", "set prefijo stc1000".
//
// COMANDOS POR SERIE (115200, terminar con Enter)
//  s = estado     m = matriz de pares activos     r = captura cruda
//  v = verbose    cfg = ver configuracion          set <clave> <valor>
//  check / ota / reboot
//
// CONEXION (el FMD tiene que estar alimentado con 3,3 V, NUNCA 5 V)
//  pata 1 del display -> GPIO5      pata 5 -> GPIO3
//  pata 2             -> GPIO6      pata 6 -> GPIO10
//  pata 3             -> GPIO7      pata 7 -> GPIO20
//  pata 4             -> GPIO4
//  No usar GPIO2, 8, 9 (pines de arranque) ni GPIO21 (salida de log al arrancar).

#include <Arduino.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <Update.h>
#include <Preferences.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <esp_ota_ops.h>
#include <esp_phy_init.h>
#include <LittleFS.h>
#include <time.h>
#include "mbedtls/md.h"
#include "driver/gpio.h"
#include "soc/gpio_reg.h"
#include "soc/soc.h"
#include "ca_letsencrypt.h"

// secrets.h es opcional (no esta en git). Si existe, sus valores se copian a la flash.
#if __has_include("secrets.h")
#include "secrets.h"
#endif

// La version viene de version.txt (la inyecta scripts/version.py).
#ifndef FIRMWARE_VERSION
#define FIRMWARE_VERSION "0.0.0"
#endif

// --- OTA desde GitHub Pages ---
#define OTA_URL_VERSION "https://marti178.github.io/STC1000-OTA/version.json"
#define OTA_URL_BIN     "https://marti178.github.io/STC1000-OTA/firmware.bin"
const uint32_t OTA_PRIMERA_MS = 60UL * 1000;            // primera consulta: 1 min despues de arrancar
const uint32_t OTA_CADA_MS    = 6UL * 60 * 60 * 1000;   // despues, cada 6 h
const uint32_t OTA_REINTENTO_MS = 10UL * 60 * 1000;     // si fallo: reintentar en 10 min
const uint8_t  MAX_ARRANQUES_SIN_CONFIRMAR = 3;         // despues de esto, rollback
const uint32_t CONFIRMAR_TRAS_MS = 2UL * 60 * 1000;     // el firmware nuevo se confirma si publico y lleva 2 min

// La ESP32-C3 Super Mini tiene una antena mala y muchas no conectan con la
// potencia maxima, pero con 8,5 dBm las descargas largas (OTA) se traban.
// Potencia por defecto, en cuartos de dBm (44 = 11 dBm: con 15 dBm esta placa no
// conecta); se cambia por serie con "set tx_dbm 8.5" y queda en la flash.
#ifndef TX_CUARTOS_DBM
#define TX_CUARTOS_DBM 44
#endif

const uint8_t PIN_GPIO[7] = {5, 6, 7, 4, 3, 10, 20};  // patas 1..7 del display

enum { ST_L = 0, ST_H = 1, ST_Z = 2 };

// ---------------- Mapa del display (anodo +, catodo -), patas 1..7 ----------------
struct Led { uint8_t an, ca; const char* nombre; };

// Indices 0..6 = segmentos A..G de D1, 7..13 de D2, 14..20 de D3, despues auxiliares.
// A=superior B=sup.der C=inf.der D=inferior E=inf.izq F=sup.izq G=central
const Led MAPA[] = {
  {2,1,"D1 A"}, {3,1,"D1 B"}, {1,4,"D1 C"}, {1,5,"D1 D"}, {4,1,"D1 E"}, {1,2,"D1 F"}, {1,3,"D1 G"},
  {3,2,"D2 A"}, {4,2,"D2 B"}, {2,5,"D2 C"}, {6,2,"D2 D"}, {5,2,"D2 E"}, {2,3,"D2 F"}, {2,4,"D2 G"},
  {4,5,"D3 A"}, {5,3,"D3 B"}, {5,4,"D3 C"}, {1,6,"D3 D"}, {3,6,"D3 E"}, {3,4,"D3 F"}, {3,5,"D3 G"},
  {6,3,"punto D1-D2"},          // 21
  {4,3,"punto D2-D3"},          // 22
  {4,6,"signo -"},              // 23
  {6,4,"punto de grados"},      // 24
  {5,6,"C"},                    // 25
  {6,5,"Heat"},                 // 26 (LED Heat: prendido a 26,8 y 27,0 C con el calor andando)
  {5,7,"punto extremo der."},   // 27
  {7,6,"punto sup. der. izq."}, // 28
  {6,7,"Cool"}                  // 29 (LED Cool: prendido a 28,3 y 29,2 C, enfriando)
};
const int N_LEDS = sizeof(MAPA) / sizeof(MAPA[0]);
const int IDX_DP12 = 21, IDX_DP23 = 22, IDX_MENOS = 23, IDX_C = 25;
const int IDX_HEAT = 26;
const int IDX_COOL = 29;

// Resultado de decodificar una ventana
struct Lectura {
  String texto;          // como se ve el display, ej. "27.3 C"
  bool esNumero = false;
  float temp = NAN;
  bool dudosa = false;   // tiene algun glifo no reconocido ('?')
  String desconocidos;   // pares activos que no estan en el mapa, ej. "+7/-5"
  uint8_t g[3] = {0, 0, 0};
  bool indicador = false; // punto D1-D2 prendido junto con el decimal (parpadea como indicador)
};

// ---------------- Log persistente (flash) ----------------
// Eventos importantes en /log.txt (particion spiffs, LittleFS). Sobrevive a reinicios y
// cortes de luz. Al pasar LOG_MAX bytes se rota a /log1.txt (se guardan los dos).
// Se lee con "log" por serie o con el comando LOG por MQTT (topico <prefijo>/log).
// Pocas escrituras por hora: no gasta la flash.
const size_t LOG_MAX = 24 * 1024;
bool     logOk = false;
uint32_t numArranque = 0;

static void logEvento(const char* fmt, ...) {
  char b[160];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(b, sizeof(b), fmt, ap);
  va_end(ap);
  char linea[220];
  time_t t = time(nullptr);
  if (t > 1700000000) {
    struct tm tm;
    gmtime_r(&t, &tm);
    snprintf(linea, sizeof(linea), "%04d-%02d-%02d %02d:%02d:%02dZ a%lu t%lu %s\n",
             tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday, tm.tm_hour, tm.tm_min, tm.tm_sec,
             (unsigned long)numArranque, (unsigned long)(millis() / 1000), b);
  } else {
    snprintf(linea, sizeof(linea), "(sin hora) a%lu t%lu %s\n",
             (unsigned long)numArranque, (unsigned long)(millis() / 1000), b);
  }
  Serial.print("LOG ");
  Serial.print(linea);
  if (!logOk) return;
  File f = LittleFS.open("/log.txt", FILE_APPEND);
  if (!f) return;
  f.print(linea);
  size_t tam = f.size();
  f.close();
  if (tam > LOG_MAX) {
    LittleFS.remove("/log1.txt");
    LittleFS.rename("/log.txt", "/log1.txt");
  }
}

static const char* motivoArranque() {
  switch (esp_reset_reason()) {
    case ESP_RST_POWERON:   return "encendido (corte de luz o enchufado)";
    case ESP_RST_BROWNOUT:  return "BROWNOUT (cayo la tension de alimentacion)";
    case ESP_RST_SW:        return "reinicio por software";
    case ESP_RST_PANIC:     return "PANIC (excepcion del firmware)";
    case ESP_RST_INT_WDT:   return "watchdog de interrupciones";
    case ESP_RST_TASK_WDT:  return "watchdog de tareas (loop colgado)";
    case ESP_RST_WDT:       return "watchdog";
    case ESP_RST_EXT:       return "pin de reset";
    case ESP_RST_DEEPSLEEP: return "deep sleep";
    default: {
      static char b[32];
      snprintf(b, sizeof(b), "codigo %d (ej. reset por USB)", (int)esp_reset_reason());
      return b;
    }
  }
}

static void iniciarLog() {
  logOk = LittleFS.begin(true);   // true: formatear si esta vacia (primera vez)
  Preferences p;
  p.begin("log", false);
  numArranque = p.getULong("n", 0) + 1;
  p.putULong("n", numArranque);
  p.end();
  logEvento("ARRANQUE v%s, motivo: %s%s", FIRMWARE_VERSION, motivoArranque(),
            logOk ? "" : " (sin LittleFS)");
}

// Ultimos 'maxBytes' del log (incluye el final de log1 si hace falta)
static String leerLog(size_t maxBytes) {
  String out;
  if (!logOk) return "sin log";
  const char* archivos[2] = {"/log1.txt", "/log.txt"};
  for (int k = 0; k < 2; k++) {
    if (!LittleFS.exists(archivos[k])) continue;
    File f = LittleFS.open(archivos[k], FILE_READ);
    if (!f) continue;
    out += f.readString();
    f.close();
  }
  if (out.length() > maxBytes) {
    out = out.substring(out.length() - maxBytes);
    int nl = out.indexOf('\n');
    if (nl >= 0) out = out.substring(nl + 1);
  }
  return out;
}

// ---------------- Configuracion persistente (NVS) ----------------
struct Config {
  String ssid, pass, mqttHost, mqttUser, mqttPass, prefijo;
  uint16_t mqttPort = 1883;
  uint8_t  txCuartos = TX_CUARTOS_DBM;   // potencia WiFi en cuartos de dBm (8..80)
};
Config cfg;

static void cargarConfig() {
  Preferences p;
  p.begin("cfg", true);
  cfg.ssid     = p.getString("ssid", "");
  cfg.pass     = p.getString("pass", "");
  cfg.mqttHost = p.getString("mhost", "");
  cfg.mqttPort = p.getUShort("mport", 1883);
  cfg.mqttUser = p.getString("muser", "");
  cfg.mqttPass = p.getString("mpass", "");
  cfg.prefijo  = p.getString("pref", "stc1000");
  cfg.txCuartos = constrain(p.getUChar("txq", TX_CUARTOS_DBM), 8, 80);
  p.end();
}

static void guardarConfig() {
  Preferences p;
  p.begin("cfg", false);
  p.putString("ssid", cfg.ssid);
  p.putString("pass", cfg.pass);
  p.putString("mhost", cfg.mqttHost);
  p.putUShort("mport", cfg.mqttPort);
  p.putString("muser", cfg.mqttUser);
  p.putString("mpass", cfg.mqttPass);
  p.putString("pref", cfg.prefijo);
  p.putUChar("txq", cfg.txCuartos);
  p.end();
}

// Build local con include/secrets.h: copiar sus valores a la flash si cambiaron.
static void aplicarSecrets() {
#if defined(WIFI_SSID) && defined(WIFI_PASS) && defined(MQTT_HOST)
  Config n = cfg;
  n.ssid = WIFI_SSID;
  n.pass = WIFI_PASS;
  n.mqttHost = MQTT_HOST;
#ifdef MQTT_PORT
  n.mqttPort = MQTT_PORT;
#endif
#ifdef MQTT_USER
  n.mqttUser = MQTT_USER;
#endif
#ifdef MQTT_PASS
  n.mqttPass = MQTT_PASS;
#endif
#ifdef MQTT_BASE
  n.prefijo = MQTT_BASE;
#endif
  if (n.ssid != cfg.ssid || n.pass != cfg.pass || n.mqttHost != cfg.mqttHost ||
      n.mqttPort != cfg.mqttPort || n.mqttUser != cfg.mqttUser ||
      n.mqttPass != cfg.mqttPass || n.prefijo != cfg.prefijo) {
    cfg = n;
    guardarConfig();
    Serial.println("Config: copiada de secrets.h a la flash");
  }
#endif
}

// ---------------- Rollback por software ----------------
// El bootloader de Arduino no trae rollback, asi que se hace a mano: despues de
// un OTA se marca "pend"; si el firmware nuevo arranca MAX_ARRANQUES_SIN_CONFIRMAR
// veces sin confirmarse (no llego a publicar), se vuelve a la particion anterior.
static void marcarFirmwarePendiente() {
  Preferences p;
  p.begin("ota", false);
  p.putUChar("pend", 1);
  p.putUChar("boots", 0);
  p.end();
}

static bool firmwarePendiente = false;

static void confirmarFirmware() {
  if (!firmwarePendiente) return;
  Preferences p;
  p.begin("ota", false);
  p.putUChar("pend", 0);
  p.putUChar("boots", 0);
  p.end();
  firmwarePendiente = false;
  Serial.println("OTA: firmware nuevo confirmado");
}

static void revisarRollback() {
  Preferences p;
  p.begin("ota", false);
  if (p.getUChar("pend", 0)) {
    uint8_t boots = p.getUChar("boots", 0) + 1;
    if (boots > MAX_ARRANQUES_SIN_CONFIRMAR) {
      p.putUChar("pend", 0);
      p.putUChar("boots", 0);
      p.end();
      const esp_partition_t* otra = esp_ota_get_next_update_partition(NULL);
      if (otra && esp_ota_set_boot_partition(otra) == ESP_OK) {
        Serial.println("OTA: el firmware nuevo no se confirmo, volviendo al anterior");
        delay(200);
        ESP.restart();
      }
      Serial.println("OTA: rollback fallo, no hay particion anterior valida");
      return;
    }
    p.putUChar("boots", boots);
    firmwarePendiente = true;
    Serial.printf("OTA: firmware sin confirmar, arranque %u de %u\n", boots, MAX_ARRANQUES_SIN_CONFIRMAR);
  }
  p.end();
}

// ---------------- Lectura de 3 estados ----------------
// Se lee cada pata dos veces: con pull-up y con pull-down internos.
// Si da lo mismo las dos veces, el FMD la esta manejando (H o L).
// Si sigue al pull, esta flotando (Z).
static void setPulls(gpio_pull_mode_t m) {
  for (int i = 0; i < 7; i++) gpio_set_pull_mode((gpio_num_t)PIN_GPIO[i], m);
}

static void leerEstados(uint8_t st[7]) {
  setPulls(GPIO_PULLUP_ONLY);
  delayMicroseconds(5);
  uint32_t a = REG_READ(GPIO_IN_REG);
  setPulls(GPIO_PULLDOWN_ONLY);
  delayMicroseconds(5);
  uint32_t b = REG_READ(GPIO_IN_REG);
  for (int i = 0; i < 7; i++) {
    bool ha = (a >> PIN_GPIO[i]) & 1;
    bool hb = (b >> PIN_GPIO[i]) & 1;
    if (ha && hb)        st[i] = ST_H;
    else if (!ha && !hb) st[i] = ST_L;
    else                 st[i] = ST_Z;   // sigue al pull (o cambio justo entre lecturas)
  }
}

// ---------------- Acumulacion por ventana ----------------
uint32_t cuenta[8][8];   // cuenta[anodo][catodo], patas 1..7
uint32_t muestras = 0;

static void acumular(uint32_t ms) {
  memset(cuenta, 0, sizeof(cuenta));
  muestras = 0;
  uint32_t t0 = millis();
  uint8_t st[7];
  while (millis() - t0 < ms) {
    leerEstados(st);
    muestras++;
    for (int a = 0; a < 7; a++) {
      if (st[a] != ST_H) continue;
      for (int c = 0; c < 7; c++)
        if (c != a && st[c] == ST_L) cuenta[a + 1][c + 1]++;
    }
    if ((muestras & 63) == 0) yield();   // dejar correr al WiFi
  }
}

// Umbral en por mil de las lecturas. Medido a 220 V (comando MATRIZ): los segmentos
// prendidos dan 25 a 66 por mil (los grados ~120) y los "fantasmas" de las transiciones
// entre slots del barrido llegan a 13 por mil. Con el umbral viejo (10) un fantasma del
// LED Heat parecia el punto D1-D2 y el segmento G del ultimo digito aparecia y
// desaparecia (27.0 <-> 27.8).
const uint32_t UMBRAL_PM = 18;

static bool activo(uint8_t an, uint8_t ca) {
  return muestras > 0 && cuenta[an][ca] * 1000 >= muestras * UMBRAL_PM;
}

// ---------------- Decodificacion ----------------
static char glifoAChar(uint8_t g) {
  switch (g) {
    case 0x00: return ' ';
    case 0x3F: return '0'; case 0x06: return '1'; case 0x5B: return '2';
    case 0x4F: return '3'; case 0x66: return '4'; case 0x6D: return '5';
    case 0x7D: return '6'; case 0x07: return '7'; case 0x7F: return '8';
    case 0x6F: return '9';
    case 0x79: return 'E'; case 0x76: return 'H'; case 0x38: return 'L';
    case 0x71: return 'F'; case 0x50: return 'r'; case 0x39: return 'C';
    case 0x73: return 'P'; case 0x40: return '-';
    default:   return '?';
  }
}

static uint8_t glifoDigito(int d) {   // d = 0,1,2
  uint8_t g = 0;
  for (int s = 0; s < 7; s++) {
    const Led& L = MAPA[d * 7 + s];
    if (activo(L.an, L.ca)) g |= (1 << s);
  }
  return g;
}

static bool estaEnMapa(uint8_t an, uint8_t ca) {
  for (int i = 0; i < N_LEDS; i++) if (MAPA[i].an == an && MAPA[i].ca == ca) return true;
  return false;
}

static void decodificar(Lectura& L) {
  char ch[3];
  for (int d = 0; d < 3; d++) { L.g[d] = glifoDigito(d); ch[d] = glifoAChar(L.g[d]); }

  String txt;
  if (activo(MAPA[IDX_MENOS].an, MAPA[IDX_MENOS].ca)) txt += '-';
  txt += ch[0];
  // El punto D1-D2 parpadea (~1 s) como indicador mientras se muestra "25.9": con el
  // decimal D2-D3 prendido no puede ser parte del numero (el STC-1000 no pasa de 99.9).
  bool dp12 = activo(MAPA[IDX_DP12].an, MAPA[IDX_DP12].ca);
  bool dp23 = activo(MAPA[IDX_DP23].an, MAPA[IDX_DP23].ca);
  L.indicador = dp12 && dp23;
  if (dp12 && !dp23) txt += '.';
  txt += ch[1];
  if (dp23) txt += '.';
  txt += ch[2];
  if (activo(MAPA[IDX_C].an, MAPA[IDX_C].ca)) txt += " C";
  L.texto = txt;
  L.dudosa = txt.indexOf('?') >= 0;

  // Temperatura: solo si son todos numeros
  String num = txt;
  num.replace(" C", "");
  num.replace(" ", "");
  bool esNumero = num.length() > 0 && num != "-" && num != ".";
  for (unsigned i = 0; i < num.length(); i++) {
    char c = num[i];
    if (!(isdigit(c) || c == '.' || (c == '-' && i == 0))) esNumero = false;
  }
  if (esNumero) {
    float t = num.toFloat();
    // rango del STC-1000: -50 a 99.9 (margen por las dudas)
    if (t >= -55.0f && t <= 120.0f) { L.esNumero = true; L.temp = t; }
  }

  // Pares activos que no estan en el mapa = segmentos o puntos sin identificar
  for (int a = 1; a <= 7; a++)
    for (int c = 1; c <= 7; c++)
      if (a != c && activo(a, c) && !estaEnMapa(a, c)) {
        if (L.desconocidos.length()) L.desconocidos += ',';
        L.desconocidos += "+" + String(a) + "/-" + String(c);
      }
}

// ---------------- Filtro: aceptar solo lecturas estables ----------------
const int VENTANA_MS      = 150;  // el barrido del FMD dura ~4 ms: entran ~35 barridos
const int IGUALES_NORMAL  = 3;    // ventanas seguidas iguales para aceptar (con WiFi hubo
                                  // glifos mezclados que duraban 2 ventanas)
const int IGUALES_DUDOSA  = 20;   // si tiene '?', tiene que durar ~3 s (si no, es un cambio)

String   candidato;
int      iguales = 0;
Lectura  estable;                 // ultima lectura aceptada
bool     hayEstable = false;
uint32_t tEstable = 0;            // millis() de la ultima lectura aceptada

static bool filtrar(const Lectura& L) {   // devuelve true si L quedo como estable
  if (L.texto == candidato) iguales++;
  else { candidato = L.texto; iguales = 1; }
  int necesarias = L.dudosa ? IGUALES_DUDOSA : IGUALES_NORMAL;
  if (iguales < necesarias) return false;
  estable = L;
  hayEstable = true;
  tEstable = millis();
  return true;
}

// ---------------- LEDs auxiliares (Heat, Cool, Set, puntos) ----------------
// Cada LED se clasifica en apagado / prendido / parpadea (el STC-1000 hace parpadear
// Heat o Cool mientras espera la demora del compresor). Ventana de 3 s.
const int AUX_INI = 21;
const int N_AUX = N_LEDS - AUX_INI;
const uint32_t AUX_VENTANA_MS = 3000;
// Estado actual de cada LED y hora de sus dos ultimos cambios. Parpadea = cambio dos
// veces en la ventana (un cambio solo, ej. el rele pasando de calor a frio, no cuenta).
bool     auxActual[N_AUX];
uint32_t auxCambio1[N_AUX], auxCambio2[N_AUX];   // ultimo y anteultimo cambio
uint8_t  auxCambios[N_AUX];                      // cuantos cambios hubo (hasta 2)

static void auxTick() {
  uint32_t ahora = millis();
  for (int i = 0; i < N_AUX; i++) {
    bool on = activo(MAPA[AUX_INI + i].an, MAPA[AUX_INI + i].ca);
    if (on == auxActual[i]) continue;
    auxActual[i] = on;
    auxCambio2[i] = auxCambio1[i];
    auxCambio1[i] = ahora;
    if (auxCambios[i] < 2) auxCambios[i]++;
  }
}

// "on", "parpadea" u "off"
static const char* auxEstado(int idx) {
  if (idx < AUX_INI) return "off";
  int i = idx - AUX_INI;
  // resta con signo por si el cambio quedo registrado en este mismo milisegundo
  if (auxCambios[i] >= 2 && (int32_t)(millis() - auxCambio2[i]) < (int32_t)AUX_VENTANA_MS)
    return "parpadea";
  return auxActual[i] ? "on" : "off";
}

// ---------------- Historial de 24 h ----------------
// Una muestra cada 5 min alineada al reloj (NTP). Cada muestra es el promedio de las
// lecturas validas de ese intervalo; si no hubo ninguna, queda null.
const uint32_t PASO_S = 300;
const int      N_HIST = 24 * 3600 / PASO_S;   // 288
const int16_t  HIST_VACIO = INT16_MIN;
int16_t  hist[N_HIST];            // decimas de grado, buffer circular
int      histIni = 0, histLen = 0;
time_t   histFin = 0;             // epoch de la ultima muestra guardada
time_t   slotActual = 0;          // inicio del intervalo que se esta acumulando
float    sumaSlot = 0;
uint32_t nSlot = 0;
bool     histNuevo = false;       // hay una muestra nueva para publicar

static bool horaValida() { return time(nullptr) > 1700000000; }  // NTP ya sincronizado

static void histAgregar(time_t t, int16_t v) {
  // si hubo un hueco (equipo sin hora, reinicio...), rellenar con null
  if (histLen > 0 && t > histFin + (time_t)PASO_S) {
    time_t faltan = (t - histFin) / PASO_S - 1;
    if (faltan > N_HIST) { histLen = 0; }   // hueco de mas de 24 h: empezar de cero
    else for (time_t i = 0; i < faltan; i++) histAgregar(histFin + PASO_S, HIST_VACIO);
  }
  if (histLen > 0 && t <= histFin) return;  // el reloj fue para atras: ignorar
  int pos = (histIni + histLen) % N_HIST;
  hist[pos] = v;
  if (histLen < N_HIST) histLen++;
  else histIni = (histIni + 1) % N_HIST;
  histFin = t;
}

static void histTick() {
  if (!horaValida()) return;
  time_t ahora = time(nullptr);
  time_t slot = ahora - (ahora % PASO_S);
  if (slotActual == 0) { slotActual = slot; sumaSlot = 0; nSlot = 0; }
  if (slot != slotActual) {
    // cerro el intervalo anterior: la muestra lleva la hora de cierre
    int16_t v = nSlot ? (int16_t)lroundf(sumaSlot / nSlot * 10.0f) : HIST_VACIO;
    histAgregar(slotActual + PASO_S, v);
    histNuevo = true;
    slotActual = slot;
    sumaSlot = 0;
    nSlot = 0;
  }
  bool vigente = hayEstable && millis() - tEstable < 30000;
  if (vigente && estable.esNumero) { sumaSlot += estable.temp; nSlot++; }
}

static String histJson() {
  String j;
  j.reserve(16 + histLen * 6);
  j = "{\"paso_s\":" + String(PASO_S) + ",\"fin\":" + String((uint32_t)histFin) + ",\"t\":[";
  for (int i = 0; i < histLen; i++) {
    int16_t v = hist[(histIni + i) % N_HIST];
    if (i) j += ',';
    if (v == HIST_VACIO) j += "null";
    else {
      char b[10];
      snprintf(b, sizeof(b), "%.1f", v / 10.0f);
      j += b;
    }
  }
  j += "]}";
  return j;
}

// ---------------- WiFi / MQTT ----------------
// Puerto 8883 u 8443 = MQTT con TLS (ej. Tailscale Funnel), validando el certificado
// contra las raices de Let's Encrypt. Cualquier otro puerto = MQTT sin cifrar (LAN).
WiFiClient       wifiClient;
WiFiClientSecure wifiTls;
PubSubClient     mqtt(wifiClient);

static bool mqttConTls() { return cfg.mqttPort == 8883 || cfg.mqttPort == 8443; }
String       clientId;
String       tEstado, tTemp, tDisplay, tInfo, tFirmware, tHist, tOta, tComando, tSalidas;

bool     sinWifi = true;          // no hay WiFi (al arrancar todavia no conecto)
uint32_t tWifiPerdido = 0;        // desde cuando no hay WiFi
uint32_t tProxMqtt = 0;           // proximo intento de conexion MQTT
uint32_t esperaMqtt = 2000;       // backoff exponencial, hasta 60 s
uint32_t reconexionesWifi = 0, reconexionesMqtt = 0;
bool     publicoAlgo = false;     // para confirmar un firmware nuevo

// Autocurado de la radio. A 220 V se vio que, despues de un reinicio por software
// (ej. el del OTA), la radio puede quedar mala: conecta al broker y a los ~45 s se
// corta por keepalive, una y otra vez; cortando la alimentacion se arreglaba.
// Cortes "rapidos" seguidos (menos de 3 min conectado):
//   3 -> rehacer la conexion WiFi de cero
//   6 -> borrar la calibracion de la radio guardada en NVS y reiniciar (calibra de cero)
const uint32_t CORTE_RAPIDO_MS = 3UL * 60 * 1000;
uint32_t tConexionMqtt = 0;
bool     mqttEstabaConectado = false;
uint8_t  cortesRapidos = 0;

String   pubTexto;                // ultimo display publicado
float    pubTemp = NAN;           // ultima temperatura publicada
uint32_t tPubInfo = 0, tPubValores = 0, tPubCambio = 0, tPubSalidas = 0;
String   pubSalidas;              // ultimo JSON de salidas publicado
const uint32_t CAMBIO_MIN_MS = 5000;   // no publicar cambios mas seguido que esto
                                       // (en el limite, ej. 29.15, el display salta 29.1/29.2)

// comando recibido por MQTT: se ejecuta en loop(), nunca dentro del callback
volatile bool cmdPendiente = false;
char     cmdBuf[32];

const uint32_t REINICIO_SIN_WIFI_MS = 10UL * 60 * 1000;  // 10 min sin WiFi -> reinicio
const uint32_t REHACER_WIFI_MS      = 3UL * 60 * 1000;   // cada 3 min sin WiFi -> rehacer la conexion
uint32_t tProxRehacerWifi = REHACER_WIFI_MS;            // (relativo a tWifiPerdido)
const uint32_t REPUBLICAR_MS        = 60UL * 1000;       // re-publicar aunque no cambie
const uint32_t LECTURA_VIEJA_MS     = 30UL * 1000;       // sin lectura estable -> "sin lectura"

static void publicarDescubrimientoHA() {
  String dev = "\"dev\":{\"ids\":[\"" + clientId + "\"],\"name\":\"STC-1000\",\"mf\":\"XDHY\","
               "\"mdl\":\"STC-1000 clon (espia ESP32-C3)\",\"sw\":\"" FIRMWARE_VERSION "\"}";
  String avty = "\"avty_t\":\"" + tEstado + "\"";

  String cfgTemp = "{\"name\":\"Temperatura\",\"uniq_id\":\"" + clientId + "_temp\","
                   "\"stat_t\":\"" + tTemp + "\",\"unit_of_meas\":\"\xC2\xB0" "C\","
                   "\"dev_cla\":\"temperature\",\"stat_cla\":\"measurement\"," + avty + "," + dev + "}";
  mqtt.publish(("homeassistant/sensor/" + clientId + "_temp/config").c_str(), cfgTemp.c_str(), true);

  String cfgDisp = "{\"name\":\"Display\",\"uniq_id\":\"" + clientId + "_display\","
                   "\"stat_t\":\"" + tDisplay + "\",\"icon\":\"mdi:numeric\"," + avty + "," + dev + "}";
  mqtt.publish(("homeassistant/sensor/" + clientId + "_display/config").c_str(), cfgDisp.c_str(), true);

  String cfgRssi = "{\"name\":\"WiFi RSSI\",\"uniq_id\":\"" + clientId + "_rssi\","
                   "\"stat_t\":\"" + tInfo + "\",\"val_tpl\":\"{{ value_json.rssi }}\","
                   "\"unit_of_meas\":\"dBm\",\"dev_cla\":\"signal_strength\",\"ent_cat\":\"diagnostic\","
                   + avty + "," + dev + "}";
  mqtt.publish(("homeassistant/sensor/" + clientId + "_rssi/config").c_str(), cfgRssi.c_str(), true);
}

static void publicarInfo() {
  String j = "{\"ip\":\"" + WiFi.localIP().toString() + "\",\"rssi\":" + String(WiFi.RSSI()) +
             ",\"uptime\":" + String(millis() / 1000) +
             ",\"version\":\"" FIRMWARE_VERSION "\"" +
             ",\"reconexiones_wifi\":" + String(reconexionesWifi) +
             ",\"reconexiones_mqtt\":" + String(reconexionesMqtt) +
             ",\"desconocidos\":\"" + (hayEstable ? estable.desconocidos : String("")) + "\"" +
             ",\"indicador\":" + (hayEstable && estable.indicador ? "true" : "false") +
             ",\"tx_dbm\":" + String(cfg.txCuartos / 4.0f, 2) +
             ",\"cortes_rapidos\":" + String(cortesRapidos) +
             ",\"leds\":{";
  bool primero = true;
  for (int i = AUX_INI; i < N_LEDS; i++) {
    const char* e = auxEstado(i);
    if (strcmp(e, "off") == 0) continue;
    if (!primero) j += ',';
    primero = false;
    j += "\"" + String(MAPA[i].nombre) + " +" + String(MAPA[i].an) + "/-" + String(MAPA[i].ca) + "\":\"" + e + "\"";
  }
  j += "}}";
  mqtt.publish(tInfo.c_str(), j.c_str(), true);
  tPubInfo = millis();
}

static void publicarHistorial() {
  if (!mqtt.connected() || histLen == 0) return;
  String j = histJson();
  if (mqtt.publish(tHist.c_str(), j.c_str(), true)) histNuevo = false;
}

static void publicarValores(bool forzar) {
  if (!mqtt.connected()) return;
  bool vieja = !hayEstable || millis() - tEstable > LECTURA_VIEJA_MS;
  String texto = vieja ? String("sin lectura") : estable.texto;
  bool cambio = texto != pubTexto ||
                (!vieja && estable.esNumero && !(estable.temp == pubTemp));
  if (!cambio && !forzar) return;
  if (!forzar && millis() - tPubCambio < CAMBIO_MIN_MS) return;
  tPubCambio = millis();

  bool ok = mqtt.publish(tDisplay.c_str(), texto.c_str(), true);
  pubTexto = texto;
  if (!vieja && estable.esNumero) {
    char b[12];
    snprintf(b, sizeof(b), "%.1f", estable.temp);
    ok = mqtt.publish(tTemp.c_str(), b, true) && ok;
    pubTemp = estable.temp;
  } else {
    pubTemp = NAN;   // display en error (LLL, HHH...) o apagado: no se publica temperatura
  }
  if (ok) publicoAlgo = true;
  tPubValores = millis();
}

// Que esta haciendo el termostato, segun los LEDs Heat y Cool:
// {"calor":"on|parpadea|off","frio":"on|parpadea|off"|null}. "parpadea" = esperando la
// demora del compresor.
static void publicarSalidas(bool forzar) {
  if (!mqtt.connected()) return;
  String j = String("{\"calor\":\"") + auxEstado(IDX_HEAT) + "\",\"frio\":" +
             (IDX_COOL < 0 ? String("null") : "\"" + String(auxEstado(IDX_COOL)) + "\"") + "}";
  if (j == pubSalidas && !forzar) return;
  // con un cable flojo o el display reiniciando, el estado puede saltar: no mas de un
  // cambio cada 5 s (el retenido siempre queda con el ultimo)
  if (!forzar && millis() - tPubSalidas < CAMBIO_MIN_MS) return;
  tPubSalidas = millis();
  if (mqtt.publish(tSalidas.c_str(), j.c_str(), true)) pubSalidas = j;
}

static void alRecibir(char* topic, byte* payload, unsigned int len) {
  if (String(topic) != tComando || len == 0) return;   // vacio = comando ya borrado
  len = min<unsigned>(len, sizeof(cmdBuf) - 1);
  memcpy(cmdBuf, payload, len);
  cmdBuf[len] = 0;
  cmdPendiente = true;
}

static void conectarMqtt() {
  if (millis() < tProxMqtt) return;
  if (cfg.mqttHost.length() == 0) { tProxMqtt = millis() + 60000; return; }
  bool tls = mqttConTls();
  Serial.printf("MQTT: conectando a %s:%u%s ...\n", cfg.mqttHost.c_str(), cfg.mqttPort, tls ? " (TLS)" : "");
  if (tls && !horaValida()) {
    // sin hora no se puede validar la vigencia del certificado: esperar al NTP
    Serial.println("MQTT: esperando la hora (NTP) para validar el certificado");
    tProxMqtt = millis() + 2000;
    return;
  }
  disableLoopWDT();             // el handshake TLS puede tardar varios segundos
  mqtt.setClient(tls ? (Client&)wifiTls : (Client&)wifiClient);
  mqtt.setServer(cfg.mqttHost.c_str(), cfg.mqttPort);
  bool ok = cfg.mqttUser.length()
    ? mqtt.connect(clientId.c_str(), cfg.mqttUser.c_str(), cfg.mqttPass.c_str(), tEstado.c_str(), 1, true, "offline")
    : mqtt.connect(clientId.c_str(), tEstado.c_str(), 1, true, "offline");
  if (ok) {
    logEvento("MQTT: conectado");
    reconexionesMqtt++;
    tConexionMqtt = millis();
    mqttEstabaConectado = true;
    esperaMqtt = 2000;
    mqtt.publish(tEstado.c_str(), "online", true);
    mqtt.publish(tFirmware.c_str(), FIRMWARE_VERSION, true);
    mqtt.subscribe(tComando.c_str(), 1);
    publicarDescubrimientoHA();
    publicarInfo();
    publicarValores(true);
    publicarSalidas(true);
    publicarHistorial();
  } else {
    // estado 4/5 = usuario o clave incorrectos / no autorizado
    Serial.printf("MQTT: fallo (estado %d), reintento en %lu s\n", mqtt.state(),
                  (unsigned long)(esperaMqtt / 1000));
    tProxMqtt = millis() + esperaMqtt;
    esperaMqtt = min<uint32_t>(esperaMqtt * 2, 60000);
  }
  enableLoopWDT();
}

// ---------------- OTA desde GitHub ----------------
static void otaLog(const char* fmt, ...) {
  char b[160];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(b, sizeof(b), fmt, ap);
  va_end(ap);
  size_t lb = strlen(b);
  if (lb && b[lb - 1] == '%') Serial.printf("OTA: %s\n", b);
  else logEvento("OTA: %s", b);
  if (mqtt.connected()) { mqtt.publish(tOta.c_str(), b); mqtt.loop(); }
}

static bool versionMasNueva(const String& nueva, const String& actual) {
  int n[3] = {0, 0, 0}, a[3] = {0, 0, 0};
  sscanf(nueva.c_str(), "%d.%d.%d", &n[0], &n[1], &n[2]);
  sscanf(actual.c_str(), "%d.%d.%d", &a[0], &a[1], &a[2]);
  for (int i = 0; i < 3; i++) {
    if (n[i] > a[i]) return true;
    if (n[i] < a[i]) return false;
  }
  return false;
}

// GitHub Pages es HTTPS. No se valida el certificado (no hay CA en el firmware);
// la integridad del binario la garantiza el SHA-256 de version.json.
// Con senal debil el saludo TLS tarda mas que los 5 s por defecto de HTTPClient.
static void prepararHttp(WiFiClientSecure& cli, HTTPClient& http) {
  cli.setInsecure();
  cli.setHandshakeTimeout(30);
  http.setConnectTimeout(20000);
  http.setTimeout(20000);
  http.setFollowRedirects(HTTPC_STRICT_FOLLOW_REDIRECTS);
}

// Error HTTP con el motivo de TLS si lo hay (ej. "SSL - Connection timed out")
static void otaErrorHttp(const char* que, int code, WiFiClientSecure& cli) {
  char e[96] = "";
  if (code < 0) cli.lastError(e, sizeof(e));
  otaLog("%s respondio HTTP %d %s (heap libre %u, bloque max %u)", que, code, e,
         ESP.getFreeHeap(), ESP.getMaxAllocHeap());
}

static bool consultarVersion(String& version, String& sha, uint32_t& tam) {
  WiFiClientSecure cli;
  HTTPClient http;
  prepararHttp(cli, http);
  if (!http.begin(cli, OTA_URL_VERSION)) { otaLog("no pude abrir la URL de version"); return false; }
  int code = http.GET();
  if (code != 200) {
    otaErrorHttp("version.json", code, cli);
    http.end();
    return false;
  }
  String body = http.getString();
  http.end();
  JsonDocument doc;
  if (deserializeJson(doc, body)) { otaLog("version.json ilegible"); return false; }
  version = String(doc["version"] | "");
  sha     = String(doc["sha256"] | "");
  tam     = doc["size"] | 0;
  sha.toLowerCase();
  return version.length() > 0;
}

// Descarga con reanudacion: si se corta (un rele que conmuta puede tirar el WiFi unos
// segundos), espera a que vuelva la red y pide el resto con "Range: bytes=N-" en vez de
// empezar de cero. GitHub Pages acepta rangos (responde 206).
const int      OTA_MAX_CORTES   = 20;
const uint32_t OTA_SIN_DATOS_MS = 15000;   // sin datos este tiempo = conexion muerta
const uint32_t OTA_ESPERA_WIFI_MS = 90000; // esperar que vuelva el WiFi antes de reanudar

static bool descargarFirmware(const String& shaEsperado, uint32_t tamEsperado) {
  if (shaEsperado.length() != 64) { otaLog("version.json sin sha256 valido, cancelado"); return false; }

  mbedtls_md_context_t md;
  mbedtls_md_init(&md);
  mbedtls_md_setup(&md, mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), 0);
  mbedtls_md_starts(&md);

  static uint8_t buf[2048];
  int total = (int)tamEsperado, escrito = 0, ultimoPct = -1;
  bool empezado = false, fallo = false;
  uint32_t tUltimoPub = 0;

  for (int corte = 0; corte <= OTA_MAX_CORTES && !fallo; corte++) {
    if (corte > 0) {
      uint32_t t0 = millis();
      while (WiFi.status() != WL_CONNECTED && millis() - t0 < OTA_ESPERA_WIFI_MS) delay(200);
      if (WiFi.status() != WL_CONNECTED) { otaLog("sin WiFi, cancelado"); break; }
      delay(1000);
      otaLog("reanudando en %d de %d (corte %d)", escrito, total, corte);
    }
    WiFiClientSecure cli;
    HTTPClient http;
    prepararHttp(cli, http);
    if (!http.begin(cli, OTA_URL_BIN)) { otaLog("no pude abrir la URL del firmware"); continue; }
    if (escrito > 0) http.addHeader("Range", "bytes=" + String(escrito) + "-");
    int code = http.GET();
    if (code == 200 && escrito > 0) {
      // el server ignoro el rango: empezar de cero
      otaLog("el server no acepto el rango, empiezo de cero");
      Update.abort();
      empezado = false;
      escrito = 0;
      mbedtls_md_starts(&md);
    } else if (!(code == 200 || (code == 206 && escrito > 0))) {
      otaErrorHttp("firmware.bin", code, cli);
      http.end();
      continue;
    }
    if (!empezado) {
      int tam = http.getSize();
      if (tam <= 0 || (tamEsperado && (uint32_t)tam != tamEsperado)) {
        otaLog("tamano inesperado (%d)", tam);
        http.end();
        fallo = true;
        break;
      }
      total = tam;
      if (!Update.begin(total)) { otaLog("no hay espacio: %s", Update.errorString()); http.end(); fallo = true; break; }
      empezado = true;
    }

    WiFiClient* s = http.getStreamPtr();
    uint32_t tUltimoDato = millis();
    while (escrito < total) {
      size_t disp = s->available();
      if (disp) {
        int n = s->readBytes(buf, min(disp, (size_t)min(sizeof(buf), (size_t)(total - escrito))));
        if (n <= 0) continue;
        if (Update.write(buf, n) != (size_t)n) { otaLog("error escribiendo: %s", Update.errorString()); fallo = true; break; }
        mbedtls_md_update(&md, buf, n);
        escrito += n;
        tUltimoDato = millis();
        int pct = (int)((int64_t)escrito * 100 / total);
        if (pct != ultimoPct && (millis() - tUltimoPub > 2000 || escrito == total)) {
          ultimoPct = pct;
          tUltimoPub = millis();
          otaLog("%d%%", pct);
        }
      } else {
        if (!s->connected() || millis() - tUltimoDato > OTA_SIN_DATOS_MS) {
          otaLog("se corto la descarga en %d de %d bytes", escrito, total);
          break;
        }
        delay(5);
      }
    }
    http.end();
    if (empezado && escrito >= total) break;
  }

  uint8_t dig[32];
  mbedtls_md_finish(&md, dig);
  mbedtls_md_free(&md);
  if (fallo || !empezado || escrito < total) {
    if (empezado) Update.abort();
    otaLog("descarga incompleta, reintento mas tarde");
    return false;
  }

  char hex[65];
  for (int i = 0; i < 32; i++) sprintf(hex + 2 * i, "%02x", dig[i]);
  if (shaEsperado != hex) { Update.abort(); otaLog("SHA-256 no coincide, firmware descartado"); return false; }
  if (!Update.end(true)) { otaLog("error al cerrar: %s", Update.errorString()); return false; }

  marcarFirmwarePendiente();   // si el nuevo no se confirma, vuelve al anterior
  // que el arranque nuevo calibre la radio de cero: despues del reinicio por software
  // del OTA se vio la radio quedar mala hasta cortar la alimentacion
  esp_phy_erase_cal_data_in_nvs();
  otaLog("listo, reiniciando");
  if (mqtt.connected()) { mqtt.publish(tEstado.c_str(), "offline", true); mqtt.loop(); mqtt.disconnect(); }
  delay(500);
  ESP.restart();
  return true;
}

uint32_t tProxOta = OTA_PRIMERA_MS;

// forzar = true: instala aunque la version no sea mas nueva (comando OTA)
static void buscarActualizacion(bool forzar) {
  if (WiFi.status() != WL_CONNECTED) return;
  if (firmwarePendiente) {
    // esperar a que se confirme (o se vuelva atras) antes de instalar otro
    Serial.println("OTA: firmware sin confirmar todavia, no busco otro");
    tProxOta = millis() + CONFIRMAR_TRAS_MS;
    return;
  }
  disableLoopWDT();             // la descarga tarda mas que el watchdog de loop
  String version, sha;
  uint32_t tam = 0;
  uint32_t proxima = OTA_CADA_MS;
  if (!consultarVersion(version, sha, tam)) proxima = OTA_REINTENTO_MS;
  else {
    Serial.printf("OTA: version actual %s, en GitHub %s\n", FIRMWARE_VERSION, version.c_str());
    if (forzar || versionMasNueva(version, FIRMWARE_VERSION)) {
      otaLog("actualizando %s -> %s", FIRMWARE_VERSION, version.c_str());
      // si sale bien reinicia y no vuelve; si no, se reintenta en 10 min (no en 6 h)
      if (!descargarFirmware(sha, tam)) proxima = OTA_REINTENTO_MS;
    } else {
      otaLog("al dia (%s)", FIRMWARE_VERSION);
    }
  }
  enableLoopWDT();
  tProxOta = millis() + proxima;
}

// Diagnostico remoto (con 220 V no se puede usar el USB): publica en <prefijo>/diag
// el porcentaje por mil de cada par activo, en 5 tandas de 1 s, y el texto decodificado.
static void publicarMatriz() {
  if (!mqtt.connected()) return;
  String tDiag = cfg.prefijo + "/diag";
  for (int k = 0; k < 5; k++) {
    feedLoopWDT();
    acumular(1000);
    Lectura L;
    decodificar(L);
    String j = "{\"n\":" + String(muestras) + ",\"texto\":\"" + L.texto + "\",\"pm\":{";
    bool primero = true;
    for (int a = 1; a <= 7; a++)
      for (int c = 1; c <= 7; c++) {
        if (a == c) continue;
        uint32_t pm = muestras ? cuenta[a][c] * 1000 / muestras : 0;
        if (pm == 0) continue;
        if (!primero) j += ',';
        primero = false;
        j += "\"+" + String(a) + "/-" + String(c) + "\":" + String(pm);
      }
    j += "}}";
    mqtt.publish(tDiag.c_str(), j.c_str());
    mqtt.loop();
  }
}

// Publica las ultimas ~6 KB del log en <prefijo>/log, en pedazos de ~1,5 KB (no retenido)
static void publicarLog() {
  if (!mqtt.connected()) return;
  String l = leerLog(6000);
  String t = cfg.prefijo + "/log";
  for (unsigned i = 0; i < l.length(); i += 1500) {
    String parte = l.substring(i, min((unsigned)l.length(), i + 1500));
    mqtt.publish(t.c_str(), parte.c_str());
    mqtt.loop();
    delay(50);
  }
  mqtt.publish(t.c_str(), "--- fin del log ---");
}

static void ejecutarComando(String c) {
  c.trim();
  c.toUpperCase();
  Serial.printf("Comando: %s\n", c.c_str());
  if (c == "MATRIZ") publicarMatriz();
  else if (c == "LOG") publicarLog();
  else if (c == "LOGBORRAR") {
    if (logOk) { LittleFS.remove("/log.txt"); LittleFS.remove("/log1.txt"); }
    logEvento("log borrado por comando");
  }
  else if (c.startsWith("TX ")) {
    // potencia WiFi en dBm, ej. "TX 8.5"; se guarda en la flash y se aplica ya
    float dbm = c.substring(3).toFloat();
    if (dbm >= 2 && dbm <= 20) {
      cfg.txCuartos = constrain((int)lroundf(dbm * 4), 8, 80);
      guardarConfig();
      WiFi.setTxPower((wifi_power_t)cfg.txCuartos);
      publicarInfo();
    }
  }
  else if (c == "CHECK") buscarActualizacion(false);
  else if (c == "OTA") buscarActualizacion(true);
  else if (c == "REBOOT") {
    if (mqtt.connected()) { mqtt.publish(tEstado.c_str(), "offline", true); mqtt.loop(); }
    delay(300);
    ESP.restart();
  }
}

static void procesarComandoMqtt() {
  if (!cmdPendiente) return;
  cmdPendiente = false;
  String c(cmdBuf);
  // Un comando RETENIDO se entrega de nuevo en cada conexion: borrarlo ANTES de
  // ejecutarlo (OTA y REBOOT no vuelven). Si no se puede borrar, se ignora.
  if (!mqtt.publish(tComando.c_str(), (const uint8_t*)"", 0, true)) return;
  mqtt.loop();
  ejecutarComando(c);
}

static void mantenerRed() {
  if (WiFi.status() != WL_CONNECTED) {
    if (!sinWifi) {
      // antes era millis() | 1, que podia quedar 1 ms en el futuro: la resta de abajo
      // daba un numero enorme y reiniciaba al toque (y contaba como arranque fallido)
      sinWifi = true;
      tWifiPerdido = millis();
      tProxRehacerWifi = REHACER_WIFI_MS;
      logEvento("WiFi: desconectado");
    }
    // setAutoReconnect se encarga. Si no vuelve: a los 3 min rehacer el WiFi de cero
    // (y cada 3 min despues); a los 10 min borrar la calibracion de la radio y reiniciar.
    uint32_t sinRed = millis() - tWifiPerdido;
    if (sinRed > REINICIO_SIN_WIFI_MS) {
      logEvento("WiFi: 10 min sin conexion, recalibro la radio y reinicio");
      esp_phy_erase_cal_data_in_nvs();
      delay(100);
      ESP.restart();
    }
    if (sinRed > tProxRehacerWifi) {
      tProxRehacerWifi += REHACER_WIFI_MS;
      logEvento("WiFi: %lu s sin conexion, rehago la conexion (estado %d)",
                (unsigned long)(sinRed / 1000), (int)WiFi.status());
      WiFi.disconnect(false, false);
      delay(300);
      WiFi.begin(cfg.ssid.c_str(), cfg.pass.c_str());
      WiFi.setTxPower((wifi_power_t)cfg.txCuartos);
    }
    return;
  }
  if (sinWifi) {
    sinWifi = false;
    reconexionesWifi++;
    logEvento("WiFi: conectado tras %lu s sin red, RSSI %d dBm",
              (unsigned long)((millis() - tWifiPerdido) / 1000), WiFi.RSSI());
    Serial.printf("WiFi: conectado, IP %s, RSSI %d dBm\n",
                  WiFi.localIP().toString().c_str(), WiFi.RSSI());
    tProxMqtt = 0;
    esperaMqtt = 2000;
  }

  if (mqttEstabaConectado && !mqtt.connected()) {
    mqttEstabaConectado = false;
    if (millis() - tConexionMqtt < CORTE_RAPIDO_MS) cortesRapidos++;
    else cortesRapidos = 0;
    logEvento("MQTT: se corto tras %lu s (estado %d, cortes rapidos seguidos %u, WiFi %s, RSSI %d)",
              (unsigned long)((millis() - tConexionMqtt) / 1000), mqtt.state(), cortesRapidos,
              WiFi.status() == WL_CONNECTED ? "ok" : "caido", WiFi.RSSI());
    if (cortesRapidos >= 6) {
      logEvento("Radio: 6 cortes rapidos, borro la calibracion y reinicio");
      esp_phy_erase_cal_data_in_nvs();
      delay(200);
      ESP.restart();
    }
    if (cortesRapidos == 3) {
      logEvento("Radio: 3 cortes rapidos, rehago la conexion WiFi");
      WiFi.disconnect(false, false);
      delay(500);
      WiFi.begin(cfg.ssid.c_str(), cfg.pass.c_str());
      WiFi.setTxPower((wifi_power_t)cfg.txCuartos);
      return;
    }
  }
  if (!mqtt.connected()) conectarMqtt();
  if (mqtt.connected()) {
    mqtt.loop();
    procesarComandoMqtt();
    if (millis() - tPubInfo > REPUBLICAR_MS) {
      // re-afirmar "online": si hubo toma de sesion, el will del cliente viejo puede
      // llegar despues del "online" nuevo y dejar "offline" retenido
      mqtt.publish(tEstado.c_str(), "online", true);
      publicarInfo();
    }
    bool republicar = millis() - tPubValores > REPUBLICAR_MS;
    publicarValores(republicar);
    publicarSalidas(republicar);
    if (histNuevo) publicarHistorial();
  }

  if (firmwarePendiente && publicoAlgo && millis() > CONFIRMAR_TRAS_MS) confirmarFirmware();
  if ((int32_t)(millis() - tProxOta) >= 0) buscarActualizacion(false);
}

// ---------------- Diagnostico por serie ----------------
static void modoMatriz() {
  acumular(1000);
  Serial.printf("\nPares activos en 1 s (%lu lecturas). Filas = pata +, columnas = pata -\n",
                (unsigned long)muestras);
  Serial.println("  +\\-     1     2     3     4     5     6     7");
  for (int a = 1; a <= 7; a++) {
    Serial.printf("   %d  ", a);
    for (int c = 1; c <= 7; c++) {
      if (a == c) { Serial.print("     ."); continue; }
      Serial.printf(" %4lu%%", (unsigned long)(cuenta[a][c] * 100 / (muestras ? muestras : 1)));
    }
    Serial.println();
  }
  Serial.println("Nombres de los pares activos:");
  for (int i = 0; i < N_LEDS; i++)
    if (activo(MAPA[i].an, MAPA[i].ca))
      Serial.printf("   +%d/-%d  %s\n", MAPA[i].an, MAPA[i].ca, MAPA[i].nombre);
}

static void modoCrudo() {
  const int N = 1500;
  static uint8_t buf[N][7];
  static uint32_t tus[N];
  uint32_t t0 = micros();
  for (int i = 0; i < N; i++) { leerEstados(buf[i]); tus[i] = micros() - t0; }

  Serial.println("\nCaptura cruda (solo cambios). Patas 1..7: H=alto L=bajo Z=flotando");
  const char S[3] = {'L', 'H', 'Z'};
  for (int i = 0; i < N; i++) {
    if (i > 0 && memcmp(buf[i], buf[i - 1], 7) == 0) continue;
    char s[8];
    for (int p = 0; p < 7; p++) s[p] = S[buf[i][p]];
    s[7] = 0;
    Serial.printf("%7lu us  %s\n", (unsigned long)tus[i], s);
    if ((i & 31) == 0) feedLoopWDT();
  }
  Serial.printf("Fin (%lu us en total)\n", (unsigned long)tus[N - 1]);
}

static void modoEstado() {
  Serial.printf("\nFirmware %s%s\n", FIRMWARE_VERSION, firmwarePendiente ? " (sin confirmar)" : "");
  Serial.printf("WiFi: %s", WiFi.status() == WL_CONNECTED ? "conectado" : "DESCONECTADO");
  if (WiFi.status() == WL_CONNECTED)
    Serial.printf(" (IP %s, RSSI %d dBm)", WiFi.localIP().toString().c_str(), WiFi.RSSI());
  Serial.printf("\nMQTT: %s (%s:%u, estado %d)\n", mqtt.connected() ? "conectado" : "DESCONECTADO",
                cfg.mqttHost.c_str(), cfg.mqttPort, mqtt.state());
  if (hayEstable)
    Serial.printf("Ultima lectura: [%s] hace %lu ms%s%s\n", estable.texto.c_str(),
                  (unsigned long)(millis() - tEstable),
                  estable.desconocidos.length() ? "  desconocidos: " : "",
                  estable.desconocidos.c_str());
  else
    Serial.println("Todavia sin lectura estable");
  Serial.printf("Hora %s, historial %d muestras\n", horaValida() ? "sincronizada" : "SIN sincronizar", histLen);
  Serial.printf("Heap libre %u bytes, bloque maximo %u\n", ESP.getFreeHeap(), ESP.getMaxAllocHeap());
  Serial.printf("Uptime %lu s, reconexiones WiFi %lu, MQTT %lu\n", (unsigned long)(millis() / 1000),
                (unsigned long)reconexionesWifi, (unsigned long)reconexionesMqtt);
}

static void modoConfig() {
  Serial.printf("\nssid=%s pass=%s\nmqtt_host=%s mqtt_port=%u mqtt_user=%s mqtt_pass=%s prefijo=%s\n",
                cfg.ssid.c_str(), cfg.pass.length() ? "(oculta)" : "(vacia)",
                cfg.mqttHost.c_str(), cfg.mqttPort, cfg.mqttUser.c_str(),
                cfg.mqttPass.length() ? "(oculta)" : "(vacia)", cfg.prefijo.c_str());
  Serial.printf("tx_dbm=%.2f\n", cfg.txCuartos / 4.0f);
}

static void comandoSet(const String& resto) {
  int sp = resto.indexOf(' ');
  String clave = sp < 0 ? resto : resto.substring(0, sp);
  String valor = sp < 0 ? String("") : resto.substring(sp + 1);
  if      (clave == "ssid")      cfg.ssid = valor;
  else if (clave == "pass")      cfg.pass = valor;
  else if (clave == "mqtt_host") cfg.mqttHost = valor;
  else if (clave == "mqtt_port") cfg.mqttPort = valor.toInt() ? valor.toInt() : 1883;
  else if (clave == "mqtt_user") cfg.mqttUser = valor;
  else if (clave == "mqtt_pass") cfg.mqttPass = valor;
  else if (clave == "prefijo")   cfg.prefijo = valor.length() ? valor : String("stc1000");
  else if (clave == "tx_dbm")    cfg.txCuartos = constrain((int)lroundf(valor.toFloat() * 4), 8, 80);
  else { Serial.println("Clave desconocida (ssid, pass, mqtt_host, mqtt_port, mqtt_user, mqtt_pass, prefijo, tx_dbm)"); return; }
  guardarConfig();
  Serial.println("Guardado. Escribi 'reboot' para aplicar.");
}

bool verbose = false;
String lineaSerie;

static void comandosSerie() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\r') continue;
    if (c != '\n') { if (lineaSerie.length() < 120) lineaSerie += c; continue; }
    String l = lineaSerie;
    lineaSerie = "";
    l.trim();
    if (l == "m") modoMatriz();
    else if (l == "r") modoCrudo();
    else if (l == "s") modoEstado();
    else if (l == "v") { verbose = !verbose; Serial.printf("verbose %s\n", verbose ? "ON" : "OFF"); }
    else if (l == "cfg") modoConfig();
    else if (l == "log") { Serial.println(leerLog(30000)); Serial.println("--- fin del log ---"); }
    else if (l.startsWith("set ")) comandoSet(l.substring(4));
    else if (l == "check" || l == "ota" || l == "reboot") ejecutarComando(l);
    else if (l.length()) Serial.println("Comandos: s m r v cfg log set check ota reboot");
  }
}

// ---------------- Arduino ----------------
void setup() {
  Serial.begin(115200);
  for (int i = 0; i < 7; i++) pinMode(PIN_GPIO[i], INPUT);
  delay(1500);
  Serial.printf("Espia STC-1000 v%s. Comandos: s m r v cfg log set check ota reboot\n", FIRMWARE_VERSION);

  iniciarLog();
  revisarRollback();
  cargarConfig();
  aplicarSecrets();
  if (cfg.ssid.length() == 0)
    Serial.println("Sin configuracion de WiFi: usa 'set ssid ...' y 'set pass ...'");

  uint8_t mac[6];
  WiFi.macAddress(mac);
  char id[24];
  snprintf(id, sizeof(id), "stc1000_%02x%02x%02x", mac[3], mac[4], mac[5]);
  clientId  = id;
  tEstado   = cfg.prefijo + "/estado";
  tTemp     = cfg.prefijo + "/temperatura";
  tDisplay  = cfg.prefijo + "/display";
  tInfo     = cfg.prefijo + "/info";
  tFirmware = cfg.prefijo + "/firmware";
  tHist     = cfg.prefijo + "/historial";
  tOta      = cfg.prefijo + "/ota";
  tComando  = cfg.prefijo + "/comando";
  tSalidas  = cfg.prefijo + "/salidas";

  WiFi.mode(WIFI_STA);
  WiFi.setHostname("stc1000");
  WiFi.setAutoReconnect(true);
  // Sin ahorro de energia: el equipo va enchufado, y el modem sleep traba las
  // descargas TCP largas (el OTA se frenaba a los ~96 KB)
  WiFi.setSleep(false);
  if (cfg.ssid.length()) WiFi.begin(cfg.ssid.c_str(), cfg.pass.c_str());
  WiFi.setTxPower((wifi_power_t)cfg.txCuartos);
  tWifiPerdido = millis();

  // Hora por NTP (UTC): para el historial
  configTime(0, 0, "pool.ntp.org", "time.google.com");

  wifiTls.setCACert(CA_LETSENCRYPT);
  wifiTls.setHandshakeTimeout(15);
  mqtt.setCallback(alRecibir);
  mqtt.setBufferSize(2560);      // el historial de 24 h ocupa ~1,8 KB
  mqtt.setKeepAlive(30);
  mqtt.setSocketTimeout(4);

  enableLoopWDT();               // si loop() se cuelga mas de 5 s, reinicia
}

void loop() {
  feedLoopWDT();
  comandosSerie();
  mantenerRed();

  Lectura L;
  acumular(VENTANA_MS);
  decodificar(L);
  bool cambio = filtrar(L) && (L.texto != pubTexto || verbose);
  auxTick();
  histTick();
  if (verbose)
    Serial.printf("[%s] glifos %02X %02X %02X, %lu lecturas%s%s\n", L.texto.c_str(),
                  L.g[0], L.g[1], L.g[2], (unsigned long)muestras,
                  L.desconocidos.length() ? "  desconocidos: " : "", L.desconocidos.c_str());
  else if (cambio && hayEstable)
    Serial.printf("Display: [%s]%s\n", estable.texto.c_str(),
                  estable.esNumero ? (String("  T = ") + String(estable.temp, 1)).c_str() : "");
}
