#!/usr/bin/env python3
# Guarda en SQLite el historial que publica el ESP32 (stc1000/historial, 24 h cada 5 min)
# y publica retenido stc1000/historial_largo con los ultimos 30 dias.
# La base es una ventana movil: se borra lo de mas de 400 dias y una vez por dia se
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
import math
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
RETENCION_S = 400 * 24 * 3600            # ventana movil de algo mas de 1 anio
CADA_S = 300                             # publicar cada 5 min
VACUUM_CADA_S = 24 * 3600                # compactar el archivo una vez por dia
MAX_MUESTRAS = 288                       # el ESP32 manda 24 h
FUTURO_S = 600                           # tolerancia de reloj hacia adelante
TEMP_MIN, TEMP_MAX = -50.0, 150.0        # fuera de esto es basura
REVISAR_S = 30                           # cada cuanto se revisa el hilo de red
SIN_CONEXION_MAX_S = 600                 # 10 min sin broker: salir y que systemd reinicie

log = logging.getLogger("stc1000-historial")
lock = threading.Lock()


def abrir_db():
    db = sqlite3.connect(DB_PATH, check_same_thread=False)
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("CREATE TABLE IF NOT EXISTS muestras (ts INTEGER PRIMARY KEY, temp REAL NOT NULL)")
    db.commit()
    return db


def _sin_nan(valor):
    raise ValueError(f"valor no numerico: {valor}")


def guardar(db, payload):
    """Valida y guarda las muestras de un historial. Devuelve cuantas se escribieron.
    Lanza ValueError si el mensaje no cumple el contrato."""
    h = json.loads(payload, parse_constant=_sin_nan)   # rechaza NaN e Infinity
    if not isinstance(h, dict):
        raise ValueError("no es un objeto JSON")
    paso, fin, t = h.get("paso_s"), h.get("fin"), h.get("t")
    if paso != PASO_S:
        raise ValueError(f"paso_s={paso!r}, se esperaba {PASO_S}")
    if not isinstance(fin, int) or isinstance(fin, bool):
        raise ValueError(f"fin={fin!r} no es entero")
    ahora = int(time.time())
    if not ahora - RETENCION_S <= fin <= ahora + FUTURO_S:
        raise ValueError(f"fin={fin} fuera de rango (ahora={ahora})")
    if not isinstance(t, list) or not 1 <= len(t) <= MAX_MUESTRAS:
        raise ValueError(f"t debe ser una lista de 1 a {MAX_MUESTRAS} valores")
    n = len(t)
    filas, malas = [], 0
    for i, v in enumerate(t):
        if v is None:
            continue
        if (isinstance(v, (int, float)) and not isinstance(v, bool)
                and math.isfinite(v) and TEMP_MIN <= v <= TEMP_MAX):
            filas.append((fin - (n - 1 - i) * PASO_S, float(v)))
        else:
            malas += 1
    if malas:
        log.warning("descarto %d valores invalidos del historial", malas)
    with lock:
        # mismo timestamp: gana el valor mas nuevo
        db.executemany("INSERT OR REPLACE INTO muestras (ts, temp) VALUES (?, ?)", filas)
        db.commit()
    return len(filas)


def podar(db, compactar=False):
    """Borra lo de mas de 400 dias; con compactar, achica el archivo (VACUUM)."""
    with lock:
        n = db.execute("DELETE FROM muestras WHERE ts < ?", (int(time.time()) - RETENCION_S,)).rowcount
        db.commit()
        if compactar:
            db.execute("VACUUM")
            db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    if n or compactar:
        log.info("poda: %d muestras viejas borradas%s", n, ", archivo compactado" if compactar else "")


def armar_largo(db):
    """Arma el JSON de los ultimos 30 dias, o None si no hay datos.
    Ignora muestras con timestamp en el futuro (la app las rechazaria)."""
    tope = int(time.time()) + FUTURO_S
    with lock:
        fila = db.execute("SELECT MAX(ts) FROM muestras WHERE ts <= ?", (tope,)).fetchone()
        fin = fila[0]
        if fin is None:
            return None
        desde = fin - (VENTANA - 1) * PASO_S
        filas = db.execute("SELECT ts, temp FROM muestras WHERE ts BETWEEN ? AND ? ORDER BY ts",
                           (desde, fin)).fetchall()
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
        # nada de lo que llegue puede tirar abajo el hilo de red
        try:
            n = guardar(db, m.payload)
        except ValueError as e:
            log.warning("historial invalido: %s", e)
            return
        except Exception:
            log.exception("error guardando el historial")
            return
        log.info("guardadas %d muestras", n)
        try:
            publicar(cli, db)
        except Exception:
            log.exception("error publicando %s", T_SALIDA)

    cli = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="stc1000-historial")
    cli.suppress_exceptions = True       # un error en un callback se loguea, no mata el hilo
    cli.username_pw_set(USER, PASS)
    cli.on_connect = on_connect
    cli.on_message = on_message
    cli.reconnect_delay_set(min_delay=2, max_delay=60)
    cli.connect_async(HOST, PORT, keepalive=60)
    cli.loop_start()

    ultima_publicacion = time.monotonic()
    ultima_conexion = time.monotonic()
    while True:
        time.sleep(REVISAR_S)
        # si el hilo de red murio o no hay broker hace rato, salir: systemd reinicia
        hilo = getattr(cli, "_thread", None)
        if hilo is None or not hilo.is_alive():
            log.error("murio el hilo de red de MQTT, salgo para que systemd reinicie")
            os._exit(1)
        if cli.is_connected():
            ultima_conexion = time.monotonic()
        elif time.monotonic() - ultima_conexion > SIN_CONEXION_MAX_S:
            log.error("%d s sin conexion al broker, salgo para que systemd reinicie", SIN_CONEXION_MAX_S)
            os._exit(1)
        if time.monotonic() - ultima_publicacion < CADA_S:
            continue
        ultima_publicacion = time.monotonic()
        try:
            compactar = time.monotonic() - ultimo_vacuum >= VACUUM_CADA_S
            podar(db, compactar)
            if compactar:
                ultimo_vacuum = time.monotonic()
            if cli.is_connected():
                publicar(cli, db)
        except Exception:
            log.exception("error en el ciclo periodico")


if __name__ == "__main__":
    main()
