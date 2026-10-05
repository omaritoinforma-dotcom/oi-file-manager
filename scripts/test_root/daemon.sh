#!/system/bin/sh
# Demonio root del su de prueba (se arranca con «adb root»): ejecuta en orden las peticiones que deja
# scripts/test_root/su y guarda la salida y el código de salida. Termina al crear el archivo «stop».
D=/data/local/tmp/oi-su
umask 022
while [ ! -e "$D/stop" ]; do
  for ready in "$D"/q/*.ready; do
    [ -e "$ready" ] || continue
    id="${ready%.ready}"
    rm -f "$ready"
    cmd="$(cat "$id.cmd")"
    echo "$cmd" >> "$D/log"
    sh -c "$cmd" < "$id.in" > "$id.out.tmp" 2>&1
    echo $? > "$id.rc.tmp"
    mv "$id.out.tmp" "$id.out"
    mv "$id.rc.tmp" "$id.rc"
  done
  sleep 0.05
done
