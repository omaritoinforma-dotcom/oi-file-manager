#!/usr/bin/env bash
# Servidor SMB real (Samba) para probar el cliente SMB de la app, con SMB 2/3 y firma obligatoria.
#
#   scripts/smb_samba.sh start CARPETA USUARIO CONTRASEÑA   # imprime las variables que hay que exportar
#   scripts/smb_samba.sh stop
#
# El cliente de la app solo usa el puerto 445: hace falta sudo. Instala samba si no está (Ubuntu).
set -euo pipefail
WORK=/tmp/oismb

case "${1:-start}" in
  start)
    folder="$2"; user="$3"; password="$4"
    command -v smbd >/dev/null || { sudo apt-get update -qq; sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends samba >/dev/null; }
    sudo pkill -x smbd 2>/dev/null || true
    sudo rm -rf "$WORK"; sudo mkdir -p "$WORK"/{private,lock,state,cache,pid,ncalrpc} /run/samba/ncalrpc
    id "$user" >/dev/null 2>&1 || sudo useradd -M -s /usr/sbin/nologin "$user"
    sudo chmod 777 "$folder"
    sudo tee "$WORK/smb.conf" >/dev/null <<CONF
[global]
  workgroup = WORKGROUP
  server role = standalone server
  security = user
  map to guest = never
  passdb backend = tdbsam:$WORK/passdb.tdb
  private dir = $WORK/private
  lock directory = $WORK/lock
  state directory = $WORK/state
  cache directory = $WORK/cache
  pid directory = $WORK/pid
  ncalrpc dir = $WORK/ncalrpc
  log file = $WORK/smb.log
  logging = file
  interfaces = 127.0.0.1
  bind interfaces only = yes
  smb ports = 445
  server min protocol = SMB2_02
  server signing = mandatory
  disable netbios = yes
  load printers = no
  printing = bsd
  printcap name = /dev/null
  disable spoolss = yes
[datos]
  path = $folder
  read only = no
  valid users = $user
  create mask = 0666
  directory mask = 0777
CONF
    (echo "$password"; echo "$password") | sudo smbpasswd -c "$WORK/smb.conf" -s -a "$user" >/dev/null
    sudo smbd -s "$WORK/smb.conf" -D
    for _ in $(seq 50); do (exec 3<>/dev/tcp/127.0.0.1/445) 2>/dev/null && break; sleep 0.2; done
    (exec 3<>/dev/tcp/127.0.0.1/445) 2>/dev/null || { echo "Samba no arrancó:" >&2; sudo tail -20 "$WORK/smb.log" >&2; exit 1; }
    echo "OI_REMOTE_TEST_SMB_FOLDER=$folder"
    echo "OI_REMOTE_TEST_SMB_USER=$user"
    echo "OI_REMOTE_TEST_SMB_PASSWORD=$password"
    ;;
  stop)
    sudo pkill -x smbd 2>/dev/null || true
    ;;
esac
