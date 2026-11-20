# Server MQTT (Mosquitto) para el STC-1000

El broker corre en el server Debian de casa (Debian 13 "trixie", usuario `server`).
El ESP32 publica ahí la temperatura y la app Android la lee.

| Desde dónde | Host | Puerto | Cifrado |
|---|---|---|---|
| Internet (ESP32, o el celular sin Tailscale) | `SERVIDOR.TU-TAILNET.ts.net` | 8443 | TLS (Let's Encrypt, vía Tailscale Funnel) |
| Celular con Tailscale | `100.x.y.z` | 1883 | El túnel de Tailscale |
| LAN de casa | `IP_LAN_DEL_SERVER` | 1883 | Ninguno |

Las contraseñas **no** están en este repo. Están en `C:\Users\marti\server-debian\mqtt-credenciales.txt`
(en esta PC) y en `include/secrets.h` (ignorado por git).

## Qué hay instalado

- **Mosquitto 2.0.21** (`mosquitto` y `mosquitto-clients`), habilitado en systemd: arranca solo
  después de un corte de luz.
  - `/etc/mosquitto/conf.d/stc1000.conf`: listener 1883, sin anónimos, archivo de usuarios y ACL,
    `persistence true` y `autosave_interval 60`. Los mensajes retenidos sobreviven a un reinicio y,
    si se corta la luz, se pierden como mucho los últimos 60 s. Copia en [mosquitto/stc1000.conf](mosquitto/stc1000.conf).
  - `/etc/mosquitto/acl`: permisos por usuario. Copia en [mosquitto/acl](mosquitto/acl).
  - `/etc/mosquitto/passwd`: usuarios y claves (hasheadas). No se copia al repo.
  - La configuración anterior (`local.conf` y el usuario viejo `esp32`) quedó respaldada en
    `/etc/mosquitto/backup-20261120-000735/`.
- **Tailscale 1.104.6** (repo oficial pkgs.tailscale.com), habilitado en systemd. Nodo `debian`, IP `100.x.y.z`.
  - **Funnel**: publica el broker en internet en `SERVIDOR.TU-TAILNET.ts.net:8443`. Tailscale
    termina el TLS y reenvía a `localhost:1883`. La config de Funnel queda guardada en tailscaled
    y se restablece sola al reiniciar. Ver con `tailscale funnel status`.
  - No se abrió ningún puerto en el router.

### Usuarios y permisos

| Usuario | Quién | Puede publicar | Puede leer |
|---|---|---|---|
| `stc1000` | ESP32 | `stc1000/#`, `homeassistant/#` | solo `stc1000/comando` |
| `celular` | App Android | solo `stc1000/comando` | `stc1000/#` |

No se permite el acceso anónimo. Todo lo que no esté en la ACL está prohibido.

### Firewall

En el server no hay ufw. El servicio `nftables` está inactivo y la cadena INPUT acepta todo; las
únicas reglas son las de Docker y Tailscale. El puerto 1883 solo se ve desde la LAN y desde
Tailscale porque el router no lo reenvía. Desde internet solo se llega por Funnel (8443, con TLS y
usuario/clave).

## Agregar o cambiar usuarios

```bash
# Cambiar la clave de un usuario existente (la pide por teclado)
sudo mosquitto_passwd /etc/mosquitto/passwd celular

# Agregar un usuario con clave aleatoria
CLAVE=$(openssl rand -base64 33 | tr -dc 'A-Za-z0-9' | head -c 24); echo "$CLAVE"
sudo mosquitto_passwd -b /etc/mosquitto/passwd nuevo_usuario "$CLAVE"

# Borrar un usuario
sudo mosquitto_passwd -D /etc/mosquitto/passwd nuevo_usuario
```

Después hay que darle permisos en `/etc/mosquitto/acl` (bloque `user ...` con líneas `topic read|write ...`),
dejar los archivos con el dueño que espera el broker y reiniciar:

```bash
sudo chown mosquitto:mosquitto /etc/mosquitto/passwd /etc/mosquitto/acl
sudo chmod 600 /etc/mosquitto/passwd /etc/mosquitto/acl
sudo systemctl restart mosquitto
```

`mosquitto_passwd` avisa que el dueño "no es root", y el broker avisa lo contrario si se lo cambia a root.
Es una contradicción conocida de Mosquitto 2.0: hay que dejar `mosquitto:mosquitto`, que es lo que pide el broker.

## Ver logs

```bash
sudo journalctl -u mosquitto -f            # arranques y paradas del servicio
sudo tail -f /var/log/mosquitto/mosquitto.log   # conexiones, errores de login, etc.
tailscale funnel status                     # que el Funnel siga activo
```

## Probar con mosquitto_sub / mosquitto_pub

En el server (o en cualquier máquina con `mosquitto-clients`):

```bash
# Ver todo lo que publica el termostato (retenidos incluidos)
mosquitto_sub -h localhost -u celular -P 'CLAVE_CELULAR' -t 'stc1000/#' -v

# Mandar un comando como la app
mosquitto_pub -h localhost -u celular -P 'CLAVE_CELULAR' -t stc1000/comando -m '{"accion":"test"}'

# Probar un permiso: con -V 5 -q 1 el broker contesta "Not authorized" si no está permitido
mosquitto_pub -h localhost -u celular -P 'CLAVE_CELULAR' -V 5 -q 1 -d -t stc1000/temperatura -m 99

# Desde internet, por el Funnel (TLS)
mosquitto_sub -h SERVIDOR.TU-TAILNET.ts.net -p 8443 --capath /etc/ssl/certs \
  -u celular -P 'CLAVE_CELULAR' -t 'stc1000/#' -v
```

## Mantenimiento

- **Vencimiento de la clave de Tailscale**: en https://login.tailscale.com/admin/machines, en el
  nodo `debian`, elegí "Disable key expiry". Si no, el nodo se desloguea solo cuando vence la clave
  y se caen Funnel y Tailscale.
- **IP de LAN**: conviene reservar `IP_LAN_DEL_SERVER` para el server en el DHCP del router. El ESP32
  entra por el nombre de Funnel, así que no depende de esa IP, pero los accesos por LAN sí.
