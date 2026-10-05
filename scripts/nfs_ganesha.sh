#!/usr/bin/env bash
# Servidor NFS real (nfs-ganesha, NFS versión 3) para probar el cliente NFS de la app.
#
#   scripts/nfs_ganesha.sh start CARPETA   # imprime las variables que hay que exportar
#   scripts/nfs_ganesha.sh stop
#
# Escucha solo en 127.0.0.1 (el emulador lo ve en 10.0.2.2). Hace falta sudo; instala nfs-ganesha y
# rpcbind si faltan (Ubuntu 24.04).
set -euo pipefail
WORK=/tmp/oinfs

case "${1:-start}" in
  start)
    folder="$2"
    if ! command -v ganesha.nfsd >/dev/null; then
      sudo apt-get update -qq
      sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends nfs-ganesha nfs-ganesha-vfs rpcbind >/dev/null
    fi
    # Los servicios que instala el paquete usan su propia configuración: se paran y se usa la nuestra.
    sudo systemctl stop nfs-ganesha nfs-ganesha-lock rpcbind rpcbind.socket 2>/dev/null || true
    sudo pkill -x ganesha.nfsd 2>/dev/null || true
    sudo pkill -x rpcbind 2>/dev/null || true
    sleep 1
    sudo rm -rf "$WORK"; sudo mkdir -p "$WORK"
    sudo chmod 777 "$folder"
    sudo tee "$WORK/ganesha.conf" >/dev/null <<CONF
NFS_CORE_PARAM {
    Protocols = 3;
    Enable_NLM = false;
    Enable_RQUOTA = false;
    Bind_addr = 127.0.0.1;
    NFS_Port = 2049;
    MNT_Port = 20048;
}
NFSV4 { Grace_Period = 5; }
EXPORT {
    Export_Id = 1;
    Path = $folder;
    Pseudo = /datos;
    Access_Type = RW;
    Squash = No_Root_Squash;
    Protocols = 3;
    Transports = TCP;
    SecType = sys;
    FSAL { Name = VFS; }
}
LOG { Default_Log_Level = EVENT; }
CONF
    sudo rpcbind -w
    sleep 1
    sudo ganesha.nfsd -f "$WORK/ganesha.conf" -L "$WORK/ganesha.log" -p "$WORK/ganesha.pid"
    for _ in $(seq 60); do
      rpcinfo -p 2>/dev/null | grep -q " nfs$" && break
      sleep 0.5
    done
    rpcinfo -p 2>/dev/null | grep -q " nfs$" || { echo "nfs-ganesha no arrancó:" >&2; sudo tail -20 "$WORK/ganesha.log" >&2; exit 1; }
    echo "OI_REMOTE_TEST_NFS_HOST=127.0.0.1"
    echo "OI_REMOTE_TEST_NFS_EXPORT=$folder"
    ;;
  stop)
    sudo pkill -x ganesha.nfsd 2>/dev/null || true
    sudo pkill -x rpcbind 2>/dev/null || true
    ;;
esac
