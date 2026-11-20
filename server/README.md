# Server MQTT (Mosquitto) para el STC-1000

El broker corre en el server Debian de casa (Debian 13 "trixie", usuario `server`).
El ESP32 publica ahí la temperatura y la app Android la lee.

| Desde dónde | Quién | Host | Puerto | Cifrado |
|---|---|---|---|---|
| LAN de casa | ESP32, y el celular en el WiFi de casa | `IP_LAN_DEL_SERVER` (fija) | 1883 | Ninguno (solo red local) |
| Tailscale | Celular con la app Tailscale | `100.x.y.z` | 1883 | El túnel de Tailscale |
| Internet (Tailscale Funnel) | **Solo la app** (usuario `celular`) | `SERVIDOR.TU-TAILNET.ts.net` | 8443 | TLS, certificado público Let's Encrypt |

El ESP32 habla **solo por la LAN**. Desde internet se llega únicamente por Funnel, a un listener
aparte que solo conoce al usuario `celular`: la clave del ESP32 no sirve desde afuera. No se abrió
ningún puerto en el router.

Las contraseñas **no** están en este repo. Están en `C:\Users\marti\server-debian\mqtt-credenciales.txt`
(en esta PC) y en `include/secrets.h` (ignorado por git).

## Qué hay instalado

- **Mosquitto 2.0.21** (`mosquitto` y `mosquitto-clients`), habilitado en systemd: arranca solo
  después de un corte de luz.
  - `/etc/mosquitto/conf.d/stc1000.conf` (copia en [mosquitto/stc1000.conf](mosquitto/stc1000.conf)):
    `per_listener_settings true`, `persistence true` y `autosave_interval 60`. Los retenidos
    sobreviven a un reinicio y, si se corta la luz, se pierden como mucho los últimos 60 s. Tiene dos listeners:
    - `1883` (todas las interfaces: LAN y Tailscale): sin anónimos, `/etc/mosquitto/passwd` y
      `/etc/mosquitto/acl` (copia en [mosquitto/acl](mosquitto/acl)).
    - `127.0.0.1:1884` (solo para Funnel): sin anónimos, `/etc/mosquitto/passwd-funnel` (solo
      `celular`), `/etc/mosquitto/acl-funnel` (copia en [mosquitto/acl-funnel](mosquitto/acl-funnel))
      y `max_connections 10`.
  - `/etc/mosquitto/passwd*`: usuarios y claves (hasheadas). No se copian al repo.
  - Los respaldos de las configuraciones anteriores están en `/etc/mosquitto/backup-*/`.
- **Tailscale 1.104.6** (repo oficial pkgs.tailscale.com), habilitado en systemd. Nodo `debian`, IP `100.x.y.z`.
  - **Funnel**: `tailscale funnel --bg --tls-terminated-tcp=8443 tcp://127.0.0.1:1884`. Tailscale
    presenta el certificado TLS y le pasa la conexión ya descifrada a Mosquitto. La config queda
    guardada en tailscaled y vuelve sola después de un reinicio. Se ve con `tailscale funnel status`.
- **Límite de conexiones** (`mqtt-funnel-limit.service`, habilitado): carga
  `/etc/nftables.d/mqtt-funnel.nft` (copia en [nftables/mqtt-funnel.nft](nftables/mqtt-funnel.nft)),
  que corta las conexiones nuevas a 127.0.0.1:1884 que pasen de 30 por minuto (con ráfaga de 20).
  Usa una tabla propia, así que no toca las reglas de Docker ni las de Tailscale.

### Usuarios y permisos

| Usuario | Quién | Por dónde | Puede publicar | Puede leer |
|---|---|---|---|---|
| `stc1000` | ESP32 | solo 1883 (LAN/Tailscale) | `stc1000/#`, `homeassistant/#` | solo `stc1000/comando` |
| `celular` | App Android | 1883 y Funnel (8443) | solo `stc1000/comando` | `stc1000/#` |

No se permite el acceso anónimo. Todo lo que no esté en la ACL está prohibido.

### Seguridad frente a internet

- Tailscale termina el TLS con un certificado público válido, así que la app valida el certificado
  normalmente (sin certificados autofirmados).
- Mosquitto ve todas las conexiones de Funnel como si vinieran de `127.0.0.1`. La versión 2.0 no
  soporta PROXY protocol, así que no puede saber la IP real del cliente y **fail2ban por IP no
  serviría**: bloquearía a todos a la vez. En su lugar hay:
  - una clave de 32 caracteres aleatorios para `celular`, imposible de adivinar por fuerza bruta;
  - un listener que solo conoce a `celular`, con ACL mínima;
  - un máximo de 10 conexiones simultáneas y de 30 conexiones nuevas por minuto. Si alguien
    intentara saturarlo, la app también podría tardar en conectar mientras dure el ataque.
- El puerto 1883 no es accesible desde internet: el router no lo reenvía, y en el server no hay
  ufw y el servicio `nftables` está inactivo.

## Agregar o cambiar usuarios

```bash
# Cambiar la clave de un usuario (la pide por teclado)
sudo mosquitto_passwd /etc/mosquitto/passwd celular

# Clave aleatoria
CLAVE=$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32); echo "$CLAVE"
sudo mosquitto_passwd -b /etc/mosquitto/passwd nuevo_usuario "$CLAVE"

# Borrar un usuario
sudo mosquitto_passwd -D /etc/mosquitto/passwd nuevo_usuario
```

Si cambiás la clave de `celular`, copiá también la línea al archivo del listener de internet:

```bash
sudo sh -c "grep '^celular:' /etc/mosquitto/passwd > /etc/mosquitto/passwd-funnel"
```

Los permisos van en `/etc/mosquitto/acl` (y en `acl-funnel` para internet). Después hay que dejar
los archivos con el dueño que espera el broker y reiniciar:

```bash
sudo chown mosquitto:mosquitto /etc/mosquitto/passwd* /etc/mosquitto/acl*
sudo chmod 600 /etc/mosquitto/passwd* /etc/mosquitto/acl*
sudo systemctl restart mosquitto
```

`mosquitto_passwd` avisa que el dueño "no es root", y el broker avisa lo contrario si se lo cambia a root.
Es una contradicción conocida de Mosquitto 2.0: hay que dejar `mosquitto:mosquitto`, que es lo que pide el broker.

## Apagar o volver a prender el acceso por internet

```bash
sudo tailscale funnel reset                                              # apagar
sudo tailscale funnel --bg --tls-terminated-tcp=8443 tcp://127.0.0.1:1884 # prender
```

## Ver logs

```bash
sudo journalctl -u mosquitto -f                 # arranques y paradas del servicio
sudo tail -f /var/log/mosquitto/mosquitto.log   # conexiones, errores de login, etc.
tailscale funnel status                         # que el Funnel siga activo
sudo nft list table inet mqtt_funnel            # contador de conexiones cortadas por el límite
```

## Probar con mosquitto_sub / mosquitto_pub

```bash
# En el server: ver todo lo que publica el termostato (retenidos incluidos)
mosquitto_sub -h localhost -u celular -P 'CLAVE_CELULAR' -t 'stc1000/#' -v

# Mandar un comando como la app
mosquitto_pub -h localhost -u celular -P 'CLAVE_CELULAR' -t stc1000/comando -m '{"accion":"test"}'

# Probar un permiso: con -V 5 -q 1 el broker contesta "Not authorized" si no está permitido
mosquitto_pub -h localhost -u celular -P 'CLAVE_CELULAR' -V 5 -q 1 -d -t stc1000/temperatura -m 99

# Desde otra máquina de la LAN o de Tailscale
mosquitto_sub -h IP_LAN_DEL_SERVER -u celular -P 'CLAVE_CELULAR' -t 'stc1000/#' -v

# Desde internet, por Funnel (TLS)
mosquitto_sub -h SERVIDOR.TU-TAILNET.ts.net -p 8443 --capath /etc/ssl/certs \
  -u celular -P 'CLAVE_CELULAR' -t 'stc1000/#' -v
```

## Mantenimiento

- **Vencimiento de la clave de Tailscale**: en https://login.tailscale.com/admin/machines, en el
  nodo `debian`, elegí "Disable key expiry". Si no, el nodo se desloguea solo cuando vence la clave
  y se caen Funnel y Tailscale.
- **IP de LAN fija**: está reservada en el DHCP del router por la MAC del WiFi del server. El ESP32
  la tiene grabada (`MQTT_HOST`). Si alguna vez cambia, se corrige en el ESP32 por el monitor serie
  con `set mqtt_host NUEVA_IP`.
