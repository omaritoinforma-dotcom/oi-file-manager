"""Comprueba en Android, una a una, las funciones de OI Archivos de COMPARACION_ES.md.

Cada comprobación es independiente: si falla se guarda una captura y la jerarquía de
la pantalla, y se sigue con la siguiente, así una sola pasada informa de todas.
Los resultados se verifican en el disco del dispositivo, no solo en pantalla. Se
ejecuta después de smoke_android.py, con el APK instalado y el permiso concedido.
"""

import hashlib
import json
import math
import os
import re
import struct
import wave
import pathlib
import subprocess
import tempfile
import time
import traceback
import xml.etree.ElementTree as ET

import smoke_android as ui
from smoke_android import adb, fill, hierarchy, nodes, tap, tap_node, wait

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUTPUT = ui.OUTPUT / "features"
OUTPUT.mkdir(parents=True, exist_ok=True)
DIR = "/sdcard/Download/OIPrueba"
RESULTS = {}
PASSWORD = "clave123"


def sh(*command, check=True):
    return adb("shell", *command, check=check)


def q(path):
    return "'" + path + "'"


def read(path):
    return sh("cat", q(path), check=False)


def read_bytes(path):
    return subprocess.check_output(["adb", "exec-out", "cat", q(path)], timeout=30)


def exists(path):
    return sh("test", "-e", q(path), "&&", "echo", "yes", check=False).strip() == "yes"


def until(condition, message, timeout=30):
    deadline = time.monotonic() + timeout
    while not condition():
        assert time.monotonic() < deadline, message
        time.sleep(0.5)


def evidence(name):
    try:
        ET.ElementTree(hierarchy()).write(OUTPUT / f"{name}.xml", encoding="utf-8")
        png = subprocess.check_output(["adb", "exec-out", "screencap", "-p"], timeout=30)
        (OUTPUT / f"{name}.png").write_bytes(png)
    except Exception:
        pass


def check(name):
    def register(function):
        def run():
            print(f"... {name}", flush=True)
            try:
                function()
                RESULTS[name] = "PASS"
                print(f"PASS: {name}", flush=True)
            except Exception as error:
                RESULTS[name] = f"FAIL: {error}"
                print(f"FAIL: {name}: {error}", flush=True)
                traceback.print_exc()
            evidence(name)
            # Se guarda tras cada comprobación para que, si CI corta por tiempo, queden los resultados.
            (OUTPUT / "results.json").write_text(
                json.dumps(RESULTS, indent=2, ensure_ascii=False), encoding="utf-8")

        CHECKS.append(run)
        return function

    return register


CHECKS = []


def seed():
    sh("rm", "-rf", q(DIR), q("/sdcard/Download/prueba"), check=False)
    sh("mkdir", "-p", q(DIR))
    with tempfile.TemporaryDirectory() as tmp:
        folder = pathlib.Path(tmp)
        for name in ("a", "b", "c", "l1", "l2", "t"):
            (folder / f"{name}.txt").write_text(f"contenido {name}", encoding="utf-8")
        for file in folder.iterdir():
            adb("push", str(file), f"{DIR}/{file.name}")
        (folder / "buscar_me.txt").write_text("aguja-unica-oi", encoding="utf-8")
        duplicate = bytes(range(256)) * 4096
        (folder / "dup1.bin").write_bytes(duplicate)
        (folder / "dup2.bin").write_bytes(duplicate)
        (folder / "grande.bin").write_bytes(b"\x07" * 3 * 1024 * 1024)
        with wave.open(str(folder / "tono.wav"), "wb") as audio:
            audio.setnchannels(1)
            audio.setsampwidth(2)
            audio.setframerate(16000)
            audio.writeframes(b"".join(
                struct.pack("<h", int(8000 * math.sin(2 * math.pi * 440 * i / 16000)))
                for i in range(16000 * 30)))
        for file in folder.iterdir():
            if file.suffix != ".txt" or file.name == "buscar_me.txt":
                adb("push", str(file), f"{DIR}/{file.name}")
    rar = ROOT / "app/src/test/resources/archives/test_read_format_rar5_encrypted_filenames.rar"
    adb("push", str(rar), f"{DIR}/cifrado.rar")
    adb("push", str(next(pathlib.Path("apk").glob("*.apk"))), f"{DIR}/oi.apk")
    # Las categorías leen MediaStore, que no indexa por sí solo lo copiado con adb.
    sh("content", "call", "--uri", "content://media", "--method", "scan_volume", "--arg", "external_primary", check=False)


def launch_home():
    """Abre la app y llega a Inicio, aunque la ventana inicial sea otra o pida la contraseña."""
    ui.launch()
    for _ in range(4):
        tree = wait_any("Categorías", "Menú", "OI Archivos está protegido")
        if nodes("Categorías", tree):
            return
        if nodes("OI Archivos está protegido", tree):
            fill("Contraseña", PASSWORD_APP, verify=False)
            tap("Desbloquear")
        else:
            adb("shell", "input", "keyevent", "4")
        time.sleep(1)
    wait("Categorías")


def wait_any(*labels, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        tree = hierarchy()
        if any(nodes(label, tree) for label in labels):
            return tree
        time.sleep(0.5)
    raise AssertionError(f"No se ve ninguno de: {labels}")


def open_test_folder():
    launch_home()
    ui.drawer("Descargas")
    tap("OIPrueba")
    wait("a.txt")


def long_press(label):
    node, _ = wait(label)
    import re

    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    x, y = (x1 + x2) // 2, (y1 + y2) // 2
    adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "900")
    wait("Más")


def tap_last(label):
    """Toca la última coincidencia visible, p. ej. el botón de un diálogo cuyo título dice lo mismo."""
    wait(label)
    tap_node(nodes(label, hierarchy())[-1])


def wait_text(fragment, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if any(fragment.lower() in (n.get("text") or "").lower() for n in hierarchy().iter("node")):
            return
        time.sleep(0.5)
    raise AssertionError(f"Text not shown: {fragment}")


def find(label, swipes=8):
    """Desplaza la lista hasta que aparece el control (las listas perezosas solo crean lo visible)."""
    for _ in range(swipes):
        found = nodes(label, hierarchy())
        if found:
            return found[0]
        adb("shell", "input", "swipe", "540", "1500", "540", "700", "400")
        time.sleep(0.5)
    raise AssertionError(f"Not found after scrolling: {label}")


def brightness():
    raw = subprocess.check_output(["adb", "exec-out", "screencap"], timeout=30)
    width, height = struct.unpack("<II", raw[:8])
    pixels = raw[len(raw) - width * height * 4:]
    samples = [pixels[i] + pixels[i + 1] + pixels[i + 2] for i in range(0, len(pixels), 4 * 997)]
    return sum(samples) / len(samples) / 3


def more(option):
    # With a selection there is a "Más" in the top bar too; the actions are in the bottom one.
    tap_last("Más")
    tap(option)


@check("crear-carpeta")
def create_folder():
    open_test_folder()
    tap("Crear")
    tap("Carpeta")
    wait("Nueva carpeta")
    adb("shell", "input", "text", "Nueva")
    tap("Crear")
    until(lambda: exists(f"{DIR}/Nueva"), "La carpeta no se creó")


@check("copiar-y-pegar")
def copy_paste():
    open_test_folder()
    long_press("a.txt")
    tap("Copiar")
    tap("Nueva")
    tap("Pegar aquí")
    until(lambda: read(f"{DIR}/Nueva/a.txt") == "contenido a", "La copia no llegó")
    assert read(f"{DIR}/a.txt") == "contenido a", "El original desapareció al copiar"


@check("cortar-y-pegar")
def cut_paste():
    open_test_folder()
    long_press("b.txt")
    tap("Cortar")
    tap("Nueva")
    tap("Pegar aquí")
    until(lambda: read(f"{DIR}/Nueva/b.txt") == "contenido b", "El archivo no se movió")
    until(lambda: not exists(f"{DIR}/b.txt"), "El original sigue tras mover")


@check("renombrar")
def rename():
    open_test_folder()
    long_press("c.txt")
    tap("Renombrar")
    wait("Cancelar")
    # El diálogo selecciona el nombre sin extensión, así al escribir se conserva la extensión.
    adb("shell", "input", "text", "renombrado")
    tap_last("Renombrar")
    until(lambda: read(f"{DIR}/renombrado.txt") == "contenido c", "No se renombró")


def move_to_trash():
    tap("Eliminar")
    wait("Mover a la papelera (se puede restaurar)")
    # La opción es una casilla (marcada por defecto); se confirma con el botón «Eliminar» del diálogo.
    box = [n for n in hierarchy().iter("node") if n.get("checkable") == "true"]
    assert box and box[0].get("checked") == "true", "La papelera no está marcada por defecto"
    tap_last("Eliminar")


@check("papelera-eliminar-restaurar-vaciar")
def trash():
    open_test_folder()
    long_press("t.txt")
    move_to_trash()
    until(lambda: not exists(f"{DIR}/t.txt"), "No se movió a la papelera")
    ui.drawer("Papelera")
    wait("t.txt")
    tap("Restaurar")
    until(lambda: read(f"{DIR}/t.txt") == "contenido t", "No se restauró")
    open_test_folder()
    long_press("t.txt")
    move_to_trash()
    until(lambda: not exists(f"{DIR}/t.txt"), "No se movió a la papelera")
    ui.drawer("Papelera")
    wait("t.txt")
    tap("Vaciar papelera")
    tap("Vaciar")
    wait("La papelera está vacía")


@check("renombrar-en-lote")
def batch_rename():
    open_test_folder()
    long_press("l1.txt")
    tap("l2.txt")
    wait("2 seleccionado(s)")
    tap("Renombrar")
    fill("Agregar al inicio", "x_")
    tap("Renombrar")
    until(
        lambda: exists(f"{DIR}/x_l1.txt") and exists(f"{DIR}/x_l2.txt"),
        "El renombrado en lote no se aplicó")


@check("propiedades-y-hashes")
def properties():
    open_test_folder()
    long_press("a.txt")
    more("Propiedades")
    tap("Calcular MD5 / SHA-1 / SHA-256")
    wait_text(hashlib.sha256(b"contenido a").hexdigest())


@check("cifrar-y-descifrar")
def encrypt_decrypt():
    open_test_folder()
    long_press("a.txt")
    more("Cifrar con contraseña")
    fill("Contraseña", PASSWORD, verify=False)
    tap("Continuar")
    until(lambda: exists(f"{DIR}/a.txt.oienc"), "No se creó el archivo cifrado")
    assert b"contenido a" not in read_bytes(f"{DIR}/a.txt.oienc"), "El cifrado contiene el texto"
    open_test_folder()
    long_press("a.txt.oienc")
    more("Descifrar con contraseña")
    fill("Contraseña", PASSWORD, verify=False)
    tap("Continuar")
    until(lambda: read(f"{DIR}/a (1).txt") == "contenido a", "El descifrado no coincide")


@check("7z-cifrado-crear-y-extraer-en-android")
def seven_zip():
    open_test_folder()
    long_press("a.txt")
    more("Comprimir en ZIP")
    fill("Nombre: .zip, .7z, .tar o .tar.gz", "prueba.7z", clear=True)
    fill("Contraseña opcional (AES)", PASSWORD, verify=False)
    tap("Comprimir")
    until(lambda: exists(f"{DIR}/prueba.7z"), "No se creó el 7z", timeout=60)
    head = sh("head", "-c", "6", q(f"{DIR}/prueba.7z"), "|", "od", "-An", "-tx1").split()
    assert head == ["37", "7a", "bc", "af", "27", "1c"], f"No es un 7z: {head}"
    open_test_folder()
    tap("prueba.7z")
    fill("Contraseña (si corresponde)", PASSWORD, verify=False)
    tap("Abrir")
    wait("a.txt")
    tap("Extraer en carpeta nueva")
    until(lambda: read(f"{DIR}/prueba/a.txt") == "contenido a", "El 7z no se extrajo", 60)


@check("rar5-cifrado-extraer-en-android")
def rar():
    open_test_folder()
    tap("cifrado.rar")
    fill("Contraseña (si corresponde)", "password", verify=False)
    tap("Abrir")
    wait("d.txt")
    tap("Extraer en carpeta nueva")
    for name in ("a", "b", "c", "d"):
        until(
            lambda: read(f"{DIR}/cifrado/{name}.txt").strip() == f"This is from {name}.txt",
            f"RAR: {name}.txt no se extrajo",
            60)


@check("busqueda-avanzada-por-contenido")
def search_contents():
    open_test_folder()
    tap("Más opciones")
    tap("Búsqueda avanzada")
    fill("Texto dentro del archivo", "aguja-unica-oi")
    evidence("busqueda-avanzada-formulario")
    tap("Buscar")
    wait("buscar_me.txt")
    assert not nodes("a.txt", hierarchy()), "La búsqueda devolvió archivos que no coinciden"


@check("analizar-espacio-grandes-y-duplicados")
def analysis():
    open_test_folder()
    tap("Más opciones")
    tap("Analizar esta carpeta")
    tap("Analizar")
    find("Archivos más grandes")
    find("grande.bin")
    find("Duplicados exactos")
    find("dup1.bin")
    find("dup2.bin")


@check("marcadores")
def bookmarks():
    open_test_folder()
    long_press("Nueva")
    more("Agregar a marcadores")
    tap("Cancelar selección")
    tap("Menú")
    wait("Nueva")
    # Un marcador debe seguir ahí tras cerrar la app.
    time.sleep(2)
    launch_home()
    tap("Menú")
    wait("Nueva")


@check("pestañas")
def tabs():
    open_test_folder()
    long_press("Nueva")
    more("Abrir en pestaña nueva")
    # La pestaña nueva muestra «Nueva» en el título; la fila de pestañas sigue mostrando la primera.
    wait("OIPrueba")
    wait("Nueva")


@check("categoria-documentos")
def category():
    launch_home()
    tap("Documentos")
    find("buscar_me.txt")


@check("gestos-configurables")
def gestures():
    settings("Gestos")
    tap("Deslizar a la derecha")
    tap("Carpeta superior")
    wait("Carpeta superior")
    open_test_folder()
    tap("Nueva")
    time.sleep(1)
    assert not nodes("buscar_me.txt", hierarchy()), "No se entró en la carpeta Nueva"
    adb("shell", "input", "swipe", "150", "1000", "950", "1000", "250")
    wait("buscar_me.txt")


@check("tema-claro-y-oscuro")
def theme():
    settings("Pantalla")
    tap(find("Claro").get("text"))
    time.sleep(1)
    light = brightness()
    tap("Oscuro")
    time.sleep(1)
    dark = brightness()
    tap("Según el sistema")
    assert dark < light - 60, f"El tema oscuro no oscurece la pantalla ({light:.0f} → {dark:.0f})"


@check("apps-respaldar-apk")
def apps_backup():
    sh("rm", "-rf", q("/sdcard/OI Archivos/Apps"), check=False)
    launch_home()
    ui.drawer("Aplicaciones")
    fill("Buscar app…", "OI Arch")
    wait("OI Archivos")
    tap("Opciones")
    tap("Respaldar APK")
    until(
        lambda: "OI Archivos_" in sh("ls", q("/sdcard/OI Archivos/Apps"), check=False),
        "No se guardó el APK", 60)
    size = sh("stat", "-c", "%s", q("/sdcard/OI Archivos/Apps/") + "*", check=False).split()
    assert size and int(size[0]) > 1_000_000, f"Respaldo de tamaño sospechoso: {size}"


@check("inspeccionar-apk")
def inspect_apk():
    open_test_folder()
    long_press("oi.apk")
    more("Inspeccionar APK")
    wait_text("com.omaritoinforma.oiarchivos")


@check("audio-en-segundo-plano")
def background_audio():
    open_test_folder()
    tap(find("tono.wav").get("text"))

    def playing(stage):
        sessions = sh("dumpsys", "media_session")
        (OUTPUT / f"audio-media-session-{stage}.txt").write_text(sessions, encoding="utf-8")
        owner = [block for block in sessions.split("\n\n") if ui.PACKAGE in block]
        # Según la versión de Android, el estado sale como «state=3» o «PLAYING(3)».
        return owner and any("state=3" in b or "PLAYING" in b for b in owner)

    until(lambda: playing("en-la-app"), "El audio no empezó a reproducirse en la app", 20)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(3)
    assert playing("tras-salir"), "La reproducción se detuvo al salir de la app"
    assert ui.PACKAGE in sh("dumpsys", "notification", "--noredact"), "Sin notificación de reproducción"
    ui.launch()


@check("ordenar-por-tamaño")
def sort_size():
    open_test_folder()
    tap("Más opciones")
    tap("Ordenar…")
    tap("Tamaño")
    tap("Descendente (Z→A, nuevo→antiguo)")
    tap("Aplicar")
    time.sleep(1)
    tree = hierarchy()
    y = {}
    for n in tree.iter("node"):
        if n.get("text") in ("grande.bin", "dup1.bin", "buscar_me.txt"):
            y[n.get("text")] = int(re.findall(r"\d+", n.get("bounds"))[1])
    # 3 MiB antes que 1 MiB; el texto pequeño, si se ve, va después de ambos.
    assert "grande.bin" in y and "dup1.bin" in y, f"No se ven los archivos: {y}"
    assert y["grande.bin"] < y["dup1.bin"] < y.get("buscar_me.txt", 10**9), f"Orden incorrecto: {y}"
    tap("Más opciones")
    tap("Ordenar…")
    tap("Nombre")
    tap("Ascendente (A→Z, antiguo→nuevo)")
    tap("Aplicar")


@check("red-local-encontrar-servidor")
def lan_scan():
    """El equipo de CI escucha como FTP en el 21; el emulador debe encontrarlo como 10.0.2.2:21."""
    launch_home()
    ui.drawer("Red, nube y USB")
    tap("Buscar en la red local")
    tap(wait("FTP · 10.0.2.2:21", timeout=120)[0].get("text"))
    wait("Nueva conexión")
    texts = ui.field_texts()
    assert "10.0.2.2" in texts and "21" in texts, f"El formulario no se rellenó: {texts}"
    tap("Cancelar")


def nearby_peer_server(received):
    """Receptor en el equipo de CI con el protocolo de OI Archivos; el emulador lo ve en 10.0.2.2."""
    import http.server
    import threading

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def reply(self, code, body):
            data = json.dumps(body).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if self.path == "/oi-enviar/v1/hola":
                self.reply(200, {"app": "OI Archivos", "name": "PC de prueba"})
            else:
                self.reply(404, {})

        def do_POST(self):
            offer = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            received["offer"] = offer
            self.reply(200, {"token": "prueba"})

        def do_PUT(self):
            data = self.rfile.read(int(self.headers["Content-Length"]))
            index = int(self.path.split("/")[-1].split("?")[0])
            received[index] = data
            self.reply(200, {})

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 42137), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


@check("enviar-a-otro-telefono")
def nearby_send():
    received = {}
    server = nearby_peer_server(received)
    try:
        payload = "enviado desde OI Archivos ñ"
        with tempfile.TemporaryDirectory() as tmp:
            local = pathlib.Path(tmp) / "enviar_me.txt"
            local.write_text(payload, encoding="utf-8")
            adb("push", str(local), f"{DIR}/enviar_me.txt")
        open_test_folder()
        long_press(find("enviar_me.txt").get("text"))
        more("Enviar a otro teléfono")
        tap("Buscar teléfonos")
        tap(wait("PC de prueba", timeout=120)[0].get("text"))
        until(lambda: 0 in received, "El archivo no llegó al otro equipo", 60)
        offer = received["offer"]["files"][0]
        data = received[0]
        assert offer["name"] == "enviar_me.txt", offer
        assert data == payload.encode("utf-8"), data
        assert offer["sha256"] == hashlib.sha256(data).hexdigest(), "SHA-256 de la oferta no coincide"
    finally:
        server.shutdown()


def device_sha256(path):
    """SHA-256 calculado en el propio emulador, sin depender de cómo adb transporta los bytes."""
    out = sh("sha256sum", q(path), check=False).split()
    return out[0] if out else ""


def receive_from_computer(name):
    """Recibe [name] desde el equipo de CI por «Enviar a otro teléfono», aceptando en el diálogo.
    Devuelve los datos enviados."""
    import threading
    import urllib.request

    launch_home()
    ui.drawer("Red, nube y USB")
    tap("Enviar a otro teléfono")
    tap("Empezar a recibir")
    wait("Dejar de recibir")
    subprocess.run(["adb", "forward", "tcp:42199", "tcp:42137"], check=True, timeout=30)
    data = ("contenido recibido " * 5000).encode()
    base = "http://127.0.0.1:42199/oi-enviar/v1"
    offer = {"from": "PC de prueba", "files": [{"name": name, "size": len(data),
                                                 "sha256": hashlib.sha256(data).hexdigest()}]}
    result = {}

    def offer_and_send():
        try:
            request = urllib.request.Request(f"{base}/oferta", json.dumps(offer).encode(), method="POST")
            token = json.loads(urllib.request.urlopen(request, timeout=150).read())["token"]
            put = urllib.request.Request(f"{base}/archivo/0?token={token}", data, method="PUT")
            result["code"] = urllib.request.urlopen(put, timeout=60).status
        except Exception as error:
            result["error"] = repr(error)

    sender = threading.Thread(target=offer_and_send)
    sender.start()
    try:
        wait("Archivos entrantes", timeout=30)
        wait_text("PC de prueba")
        tap("Aceptar")
        sender.join(90)
        assert result.get("code") == 200, result
        tap("Dejar de recibir")
    finally:
        subprocess.run(["adb", "forward", "--remove", "tcp:42199"], timeout=30)
    return data


@check("recibir-de-otro-telefono")
def nearby_receive():
    sh("rm", "-rf", q("/sdcard/Download/OI Archivos/Recibidos"), check=False)
    data = receive_from_computer("recibido ñ.txt")
    target = "/sdcard/Download/OI Archivos/Recibidos/recibido ñ.txt"
    until(lambda: exists(target), "El archivo recibido no está en Recibidos")
    # Si no coincide, el mensaje muestra tamaño y huella para saber qué llegó.
    expected = hashlib.sha256(data).hexdigest()
    size = sh("stat", "-c", "%s", q(target), check=False).strip()
    assert device_sha256(target) == expected, (
        f"El archivo recibido no coincide: {size} bytes (esperados {len(data)}), "
        f"SHA-256 {device_sha256(target)} (esperado {expected})")


# ---------------- Ajustes al estilo de ES ----------------

PASSWORD_APP = "clave123"
INTERNAL = "/storage/emulated/0"


def settings(section):
    """Abre Ajustes y una de sus secciones (Pantalla, Limpieza, Carpetas…)."""
    launch_home()
    ui.drawer("Ajustes")
    tap(find(section).get("text"))
    time.sleep(1)


def replace_field(value):
    """Sustituye el texto del primer campo visible (por ejemplo, la ruta del selector de carpetas)."""
    field = next(n for n in hierarchy().iter("node") if n.get("class") == "android.widget.EditText")
    tap_node(field)
    adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    for _ in range(len(field.get("text") or "") + 5):
        adb("shell", "input", "keyevent", "KEYCODE_DEL")
    adb("shell", "input", "text", "'" + value.replace(" ", "%s") + "'")
    time.sleep(0.5)


def pick_folder(setting, path):
    """Elige una carpeta en el selector escribiendo su ruta."""
    tap(find(setting).get("text"))
    wait("Elegir esta carpeta")
    replace_field(path)
    tap("Ir")
    time.sleep(1)
    tap("Elegir esta carpeta")
    wait_text(path.replace("/sdcard", INTERNAL))


def switch_state(title):
    """Estado del interruptor de la fila cuyo título es [title]."""
    tree = hierarchy()
    row = nodes(title, tree)[0]
    row_y = int(re.findall(r"\d+", row.get("bounds"))[1])
    switches = [n for n in tree.iter("node") if n.get("checkable") == "true"]
    switch = min(switches, key=lambda n: abs(int(re.findall(r"\d+", n.get("bounds"))[1]) - row_y))
    return switch.get("checked") == "true"


def set_switch(title, on):
    if switch_state(title) != on:
        tap(title)
        time.sleep(1)
    assert switch_state(title) == on, f"«{title}» no quedó {'activado' if on else 'desactivado'}"


def png(rgb, size=64):
    """PNG de un solo color, sin bibliotecas externas."""
    import zlib

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xffffffff)

    row = b"\x00" + bytes(rgb) * size
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(row * size)) + chunk(b"IEND", b""))


def push_bytes(data, path):
    with tempfile.TemporaryDirectory() as tmp:
        local = pathlib.Path(tmp) / "f"
        local.write_bytes(data)
        adb("push", str(local), path)


def blue_share(left, top, right, bottom):
    """Proporción de píxeles azul puro en un rectángulo de la pantalla."""
    raw = subprocess.check_output(["adb", "exec-out", "screencap"], timeout=30)
    width, height = struct.unpack("<II", raw[:8])
    pixels = raw[len(raw) - width * height * 4:]
    total = blue = 0
    for y in range(top, bottom, 3):
        for x in range(left, right, 3):
            i = (y * width + x) * 4
            total += 1
            if pixels[i + 2] > 200 and pixels[i] < 80 and pixels[i + 1] < 80:
                blue += 1
    return blue / max(total, 1)


def thumbnail_blue(name):
    node = find(name)
    time.sleep(2)  # Las miniaturas se cargan en segundo plano.
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    # La miniatura está a la izquierda del nombre, a la altura de la fila (nombre y detalle).
    return blue_share(16, y1, x1 - 8, y2 + 32)


@check("ajustes-miniaturas")
def thumbnails_setting():
    push_bytes(png((0, 0, 255)), f"{DIR}/azul.png")
    try:
        open_test_folder()
        shown = thumbnail_blue("azul.png")
        assert shown > 0.3, f"Con miniaturas activadas no se ve la imagen ({shown:.2f})"
        settings("Pantalla")
        set_switch("Miniaturas", False)
        open_test_folder()
        hidden = thumbnail_blue("azul.png")
        assert hidden < 0.02, f"Con miniaturas desactivadas sigue la vista previa ({hidden:.2f})"
    finally:
        settings("Pantalla")
        set_switch("Miniaturas", True)


@check("ajustes-ventana-inicial-y-carpeta-de-inicio")
def start_window():
    sh("mkdir", "-p", q(f"{DIR}/ultima"), check=False)
    push_bytes(b"dentro", f"{DIR}/ultima/dentro.txt")
    try:
        settings("Carpetas")
        pick_folder("Carpeta de inicio", DIR)
        settings("Ventana inicial")
        tap("Carpeta de inicio")
        ui.launch()
        wait("buscar_me.txt")
        assert not nodes("Categorías", hierarchy()), "Se abrió Inicio en vez de la carpeta de inicio"
        settings("Ventana inicial")
        tap("Última carpeta abierta")
        open_test_folder()
        tap(find("ultima").get("text"))
        wait("dentro.txt")
        time.sleep(2)
        ui.launch()
        wait("dentro.txt")
    finally:
        settings("Ventana inicial")
        tap("Inicio (categorías)")
        settings("Carpetas")
        if nodes("Restablecer", hierarchy()):
            tap("Restablecer")
    launch_home()


@check("ajustes-carpeta-de-descargas")
def download_folder():
    sh("rm", "-rf", q(f"{DIR}/descargas"), check=False)
    sh("mkdir", "-p", q(f"{DIR}/descargas"))
    try:
        settings("Carpetas")
        pick_folder("Carpeta de descargas", f"{DIR}/descargas")
        data = receive_from_computer("a descargas.txt")
        target = f"{DIR}/descargas/Recibidos/a descargas.txt"
        until(lambda: exists(target), "Lo recibido no está en la carpeta de descargas elegida")
        assert device_sha256(target) == hashlib.sha256(data).hexdigest(), "El archivo recibido no coincide"
    finally:
        settings("Carpetas")
        if nodes("Restablecer", hierarchy()):
            tap("Restablecer")


def create_password():
    wait("Crear contraseña")
    fill("Contraseña nueva", PASSWORD_APP, verify=False)
    fill("Repetir contraseña", PASSWORD_APP, verify=False)
    tap("Aceptar")
    time.sleep(1)


def remove_password():
    """Como en ES, una contraseña nueva vacía quita la contraseña y todas las protecciones."""
    settings("Contraseña")
    if not any(switch_state(t) for t in (
            "Proteger al abrir la app", "Proteger las conexiones de red", "Proteger los archivos ocultos")):
        return
    tap("Cambiar la contraseña")
    fill("Contraseña actual", PASSWORD_APP, verify=False)
    tap_last("Aceptar")
    time.sleep(1)
    for title in ("Proteger al abrir la app", "Proteger las conexiones de red", "Proteger los archivos ocultos"):
        assert not switch_state(title), f"«{title}» sigue activado tras quitar la contraseña"


@check("contrasena-al-abrir-la-app")
def password_start():
    try:
        settings("Contraseña")
        tap("Proteger al abrir la app")
        create_password()
        assert switch_state("Proteger al abrir la app"), "La protección no quedó activada"
        ui.launch()
        wait("OI Archivos está protegido")
        assert not nodes("Categorías", hierarchy()), "Se ve la app sin escribir la contraseña"
        fill("Contraseña", "equivocada", verify=False)
        tap("Desbloquear")
        wait_text("Contraseña incorrecta")
        fill("Contraseña", PASSWORD_APP, verify=False)
        tap("Desbloquear")
        wait("Categorías")
    finally:
        remove_password()



@check("contrasena-conexiones-y-ocultos")
def password_network_hidden():
    push_bytes(b"secreto", f"{DIR}/.oculto.txt")
    try:
        settings("Contraseña")
        tap("Proteger las conexiones de red")
        create_password()
        set_switch("Proteger los archivos ocultos", True)
        # Proceso nuevo: la contraseña aún no se escribió en esta sesión.
        launch_home()
        ui.drawer("Red, nube y USB")
        tap("Agregar")
        fill("Nombre de la conexión", "Protegida")
        tap("FTP")  # SFTP exige la huella del servidor; para esta prueba basta FTP.
        fill("Servidor", "10.0.2.2")
        tap("Guardar")
        time.sleep(1)
        tap("Protegida")
        wait_text("«Conexiones de red» está protegido con contraseña")
        tap("Cancelar")
        time.sleep(1)
        assert nodes("Agregar", hierarchy()), "Se abrió la conexión sin la contraseña"
        tap("Protegida")
        fill("Contraseña", PASSWORD_APP, verify=False)
        tap("Aceptar")
        time.sleep(2)
        assert not nodes("Agregar", hierarchy()), "No se abrió la conexión tras escribir la contraseña"
        # Archivos ocultos, en otro proceso para que vuelva a pedirla.
        open_test_folder()
        assert not nodes(".oculto.txt", hierarchy()), "Los ocultos ya se veían"
        tap("Más opciones")
        tap("Mostrar archivos ocultos")
        wait_text("«Archivos ocultos» está protegido con contraseña")
        fill("Contraseña", PASSWORD_APP, verify=False)
        tap("Aceptar")
        find(".oculto.txt")
        tap("Más opciones")
        tap("Ocultar archivos ocultos")
    finally:
        remove_password()
        sh("rm", "-f", q(f"{DIR}/.oculto.txt"), check=False)


@check("limpieza-al-salir")
def cleanup_on_exit():
    try:
        settings("Limpieza")
        set_switch("Borrar el historial al salir", True)
        open_test_folder()
        ui.drawer("Historial")
        wait("OIPrueba")
        ui.drawer("Salir")
        time.sleep(2)
        adb("shell", "am", "start", "-W", "-n", f"{ui.PACKAGE}/.MainActivity")
        wait("Categorías")
        ui.drawer("Historial")
        wait("El historial está vacío.")
        cached = f"/sdcard/Android/data/{ui.PACKAGE}/cache/prueba-cache.bin"
        sh("mkdir", "-p", q(f"/sdcard/Android/data/{ui.PACKAGE}/cache"), check=False)
        push_bytes(b"\x01" * 200_000, cached)
        settings("Limpieza")
        tap("Borrar la caché ahora")
        until(lambda: not exists(cached), "«Borrar la caché ahora» no borró la caché")
    finally:
        settings("Limpieza")
        set_switch("Borrar el historial al salir", False)


@check("copia-y-restauracion-de-ajustes")
def settings_backup():
    backup = "/sdcard/OI Archivos/ajustes-oi-archivos.json"
    sh("rm", "-f", q(backup), check=False)
    try:
        settings("Pantalla")
        tap(find("Oscuro").get("text"))
        time.sleep(1)
        dark = brightness()
        settings("Copia de ajustes")
        tap("Guardar copia de los ajustes")
        until(lambda: exists(backup), "No se guardó la copia de ajustes")
        content = json.loads(read(backup))
        assert content["formato"] == "OI Archivos ajustes", content
        assert content["ajustes"]["theme"] == "DARK", content
        assert "lock_hash" not in content["ajustes"], "La copia lleva la contraseña"
        settings("Pantalla")
        tap(find("Claro").get("text"))
        time.sleep(1)
        light = brightness()
        settings("Copia de ajustes")
        tap("Restaurar los ajustes")
        tap_last("Restaurar")
        time.sleep(2)
        restored = brightness()
        assert restored < light - 60, (
            f"No se restauró el tema oscuro (oscuro {dark:.0f}, claro {light:.0f}, restaurado {restored:.0f})")
    finally:
        settings("Pantalla")
        tap(find("Según el sistema").get("text"))


@check("aviso-al-terminar-una-tarea")
def done_notification():
    def notified():
        out = sh("dumpsys", "notification", "--noredact", check=False)
        return f"|{ui.PACKAGE}|12|" in out

    def copy(name):
        push_bytes(b"aviso", f"{DIR}/{name}")
        sh("mkdir", "-p", q(f"{DIR}/aviso-destino"), check=False)
        open_test_folder()
        find(name)
        long_press(name)
        tap("Copiar")
        tap(find("aviso-destino").get("text"))
        tap("Pegar aquí")
        until(lambda: exists(f"{DIR}/aviso-destino/{name}"), "La copia no llegó")
        time.sleep(2)

    settings("Notificaciones")
    set_switch("Cerrar la notificación al terminar", False)
    copy("aviso1.txt")
    assert notified(), "No quedó el aviso de tarea terminada"
    ui.launch()  # Detener la app borra sus avisos.
    try:
        settings("Notificaciones")
        set_switch("Cerrar la notificación al terminar", True)
        copy("aviso2.txt")
        assert not notified(), "Quedó un aviso aunque se pidió cerrarlo al terminar"
    finally:
        settings("Notificaciones")
        set_switch("Cerrar la notificación al terminar", False)


@check("apps-copia-antes-de-desinstalar")
def backup_before_uninstall():
    folder = f"{DIR}/copias-apk"
    sh("rm", "-rf", q(folder), check=False)
    sh("mkdir", "-p", q(folder))
    try:
        settings("Aplicaciones")
        set_switch("Copia antes de desinstalar", True)
        pick_folder("Carpeta de copias de apps", folder)
        launch_home()
        ui.drawer("Aplicaciones")
        fill("Buscar app…", "OI Arch")
        wait("OI Archivos")
        tap("Opciones")
        tap("Desinstalar")
        until(lambda: "OI Archivos_" in sh("ls", q(folder), check=False), "No se guardó el APK antes de desinstalar", 60)
        # Se cancela el diálogo de Android: la prueba no debe desinstalar la app que se está probando.
        node, _ = wait("Cancel", timeout=30)
        assert node.get("package") != ui.PACKAGE, "El diálogo no es el de Android"
        tap_node(node)
        time.sleep(1)
        assert ui.PACKAGE in sh("pm", "list", "packages", ui.PACKAGE), "La app se desinstaló"
    finally:
        settings("Aplicaciones")
        set_switch("Copia antes de desinstalar", False)
        if nodes("Restablecer", hierarchy()):
            tap("Restablecer")



def media_playing():
    sessions = sh("dumpsys", "media_session")
    owner = [block for block in sessions.split("\n\n") if ui.PACKAGE in block]
    # Según la versión de Android, el estado sale como «state=3» o «PLAYING(3)».
    return bool(owner) and any("state=3" in b or "PLAYING" in b for b in owner)


if os.environ.get("OI_REMOTE_TEST_ROOT"):

    @check("reproducir-desde-red-sin-descargar")
    def stream_from_network():
        """Servidor SFTP de CI limitado a 256 KB/s: descargar 19 MB tardaría más de un minuto, así
        que si suena en menos de 25 s se está reproduciendo sin descargar."""
        root = pathlib.Path(os.environ["OI_REMOTE_TEST_ROOT"])
        with wave.open(str(root / "largo.wav"), "wb") as audio:
            audio.setnchannels(1)
            audio.setsampwidth(2)
            audio.setframerate(16000)
            second = b"".join(
                struct.pack("<h", int(6000 * math.sin(2 * math.pi * 330 * i / 16000))) for i in range(16000))
            audio.writeframes(second * 600)
        launch_home()
        ui.drawer("Red, nube y USB")
        tap("SFTP prueba")
        node = find("largo.wav")
        started = time.monotonic()
        tap_node(node)
        wait("Desde la red, sin descargar")
        until(media_playing, "El audio de la red no empezó a sonar", 25)
        elapsed = time.monotonic() - started
        assert elapsed < 25, f"Tardó {elapsed:.0f} s: parece que se descargó antes de sonar"
        print(f"  sonó a los {elapsed:.1f} s", flush=True)
        ui.launch()  # Detiene la reproducción.


def main():
    adb("shell", "appops", "set", ui.PACKAGE, "MANAGE_EXTERNAL_STORAGE", "allow")
    seed()
    ui.keyboards(enable=False)
    try:
        for run in CHECKS:
            run()
    finally:
        ui.keyboards(enable=True)
    crash = adb("logcat", "-d", "-b", "crash")
    if f"Process: {ui.PACKAGE}" in crash:
        RESULTS["sin-cierres-inesperados"] = "FAIL: la app se cerró"
        (OUTPUT / "crash.txt").write_text(crash, encoding="utf-8")
    (OUTPUT / "results.json").write_text(json.dumps(RESULTS, indent=2, ensure_ascii=False), encoding="utf-8")
    failed = {k: v for k, v in RESULTS.items() if v != "PASS"}
    print(f"\n{len(RESULTS) - len(failed)}/{len(RESULTS)} funciones comprobadas", flush=True)
    for name, result in failed.items():
        print(f"  {name}: {result}", flush=True)
    raise SystemExit(1 if failed else 0)


if __name__ == "__main__":
    main()
