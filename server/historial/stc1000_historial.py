#!/usr/bin/env python3
# Guarda en SQLite el historial que publica el ESP32 (stc1000/historial, 24 h cada 5 min)
# y publica retenido stc1000/historial_largo con los ultimos 30 dias.
# La base es una ventana movil: se borra lo de mas de 31 dias y una vez por dia se
# compacta el archivo (VACUUM), asi no crece sin fin.
#
# Formato de los dos topicos: {"paso_s":300,"fin":<epoch UTC>,"t":[...]}
# La muestra i tiene timestamp fin-(len-1-i)*paso_s. Los null no se guardan.
#
# Los mensajes que llegan con la marca de RETENIDO se ignoran a proposito: el
# retenido puede ser viejo o de prueba, y el ESP32 vuelve a publicar las 24 h
# completas cada 5 min, asi que no se pierde nada.
#
# Configuracion por variables de entorno (ver /etc/stc1000-historial.env):
#   MQTT_HOST, MQTT_PORT, MQTT_USER, MQTT_PASS, MQTT_BASE, DB_PATH

import json
import logging
import os
import sqlite3
import threading
import time

import paho.mqtt.client as mqtt

HOST = os.environ.get("MQTT_HOST", "localhost")
PORT = int(os.environ.get("MQTT_PORT", "1883"))
USER = os.environ["MQTT_USER"]
PASS = os.environ["MQTT_PASS"]
BASE = os.environ.get("MQTT_BASE", "stc1000")
DB_PATH = os.environ.get("DB_PATH", "/var/lib/stc1000-historial/historial.db")

T_ENTRADA = f"{BASE}/historial"
T_SALIDA = f"{BASE}/historial_largo"
PASO_S = 300
VENTANA = 30 * 24 * 3600 // PASO_S       # 8640 muestras = 30 dias
RETENCION_S = 31 * 24 * 3600             # ventana movil: lo mas viejo se borra
CADA_S = 300                             # publicar cada 5 min
VACUUM_CADA_S = 24 * 3600                # compactar el archivo una vez por dia

log = logging.getLogger("stc1000-historial")
lock = threading.Lock()


def abrir_db():
    db = sqlite3.connect(DB_PATH, check_same_thread=False)
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("CREATE TABLE IF NOT EXISTS muestras (ts INTEGER PRIMARY KEY, temp REAL NOT NULL)")
    db.commit()
    return db


def guardar(db, payload):
    """Guarda las muestras de un historial. Devuelve cuantas se escribieron."""
    h = json.loads(payload)
    paso = int(h["paso_s"])
    fin = int(h["fin"])
    t = h["t"]
    n = len(t)
    filas = [(fin - (n - 1 - i) * paso, float(v)) for i, v in enumerate(t) if v is not None]
    with lock:
        # mismo timestamp: gana el valor mas nuevo
        db.executemany("INSERT OR REPLACE INTO muestras (ts, temp) VALUES (?, ?)", filas)
        db.commit()
    return len(filas)


def podar(db, compactar=False):
    """Borra lo de mas de 31 dias; con compactar, achica el archivo (VACUUM)."""
    with lock:
        n = db.execute("DELETE FROM muestras WHERE ts < ?", (int(time.time()) - RETENCION_S,)).rowcount
        db.commit()
        if compactar:
            db.execute("VACUUM")
            db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    if n or compactar:
        log.info("poda: %d muestras viejas borradas%s", n, ", archivo compactado" if compactar else "")


def armar_largo(db):
    """Arma el JSON de los ultimos 30 dias, o None si no hay datos."""
    with lock:
        fila = db.execute("SELECT MAX(ts) FROM muestras").fetchone()
        fin = fila[0]
        if fin is None:
            return None
        desde = fin - (VENTANA - 1) * PASO_S
        filas = db.execute("SELECT ts, temp FROM muestras WHERE ts >= ? ORDER BY ts", (desde,)).fetchall()
    primero = filas[0][0]
    n = (fin - primero) // PASO_S + 1
    t = [None] * n
    for ts, temp in filas:
        i = n - 1 - round((fin - ts) / PASO_S)
        if 0 <= i < n:
            t[i] = round(temp, 1)
    return json.dumps({"paso_s": PASO_S, "fin": fin, "t": t}, separators=(",", ":"))


def publicar(cli, db):
    msg = armar_largo(db)
    if msg is None:
        log.info("sin datos todavia, no publico %s", T_SALIDA)
        return
    info = cli.publish(T_SALIDA, msg, qos=1, retain=True)
    log.info("publicado %s (%d bytes, rc=%s)", T_SALIDA, len(msg), info.rc)


def main():
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    db = abrir_db()
    podar(db, compactar=True)
    ultimo_vacuum = time.monotonic()

    def on_connect(cli, userdata, flags, rc, props):
        log.info("conectado a %s:%d (rc=%s)", HOST, PORT, rc)
        if not rc.is_failure:
            cli.subscribe(T_ENTRADA, qos=1)
            publicar(cli, db)

    def on_message(cli, userdata, m):
        if m.retain:
            log.info("ignoro el retenido de %s (puede ser viejo o de prueba)", m.topic)
            return
        try:
            n = guardar(db, m.payload)
        except (ValueError, KeyError, TypeError) as e:
            log.warning("historial invalido: %s", e)
            return
        log.info("guardadas %d muestras", n)
        publicar(cli, db)

    cli = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="stc1000-historial")
    cli.username_pw_set(USER, PASS)
    cli.on_connect = on_connect
    cli.on_message = on_message
    cli.reconnect_delay_set(min_delay=2, max_delay=60)
    cli.connect_async(HOST, PORT, keepalive=60)
    cli.loop_start()

    while True:
        time.sleep(CADA_S)
        compactar = time.monotonic() - ultimo_vacuum >= VACUUM_CADA_S
        podar(db, compactar)
        if compactar:
            ultimo_vacuum = time.monotonic()
        if cli.is_connected():
            publicar(cli, db)


if __name__ == "__main__":
    main()
