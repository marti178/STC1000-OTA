// Copiar a include/secrets.h y completar. secrets.h esta en .gitignore: NO subirlo.
//
// Solo se usa al grabar por USB desde tu PC: el ESP32 copia estos valores a su
// flash (NVS) y los conserva en las actualizaciones OTA. El firmware que compila
// GitHub NO lleva claves; tambien se pueden cargar por serie con "set ...".
#pragma once

// ---- WiFi de tu casa ----
#define WIFI_SSID   "TU_RED_WIFI"
#define WIFI_PASS   "TU_CLAVE_WIFI"

// ---- Broker MQTT (Mosquitto en el server Debian) ----
#define MQTT_HOST   "192.168.1.10"   // IP del server en tu red (no la de Tailscale)
#define MQTT_PORT   1883
#define MQTT_USER   "stc1000"        // usuario creado con mosquitto_passwd
#define MQTT_PASS   "CAMBIAR"
#define MQTT_BASE   "stc1000"        // prefijo de los topicos
