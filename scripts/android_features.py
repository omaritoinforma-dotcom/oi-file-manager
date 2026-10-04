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


def menu_option(option):
    """Como more(), pero desplaza el menú si la opción no está a la vista."""
    tap_last("Más")
    for _ in range(6):
        tree = hierarchy()
        found = nodes(option, tree)
        if found:
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", found[0].get("bounds")))
            if y2 - y1 > 10:
                tap_node(found[0])
                return
        anchor = (nodes("Compartir", tree) or nodes("Abrir con…", tree) or [None])[0]
        x = (lambda b: (b[0] + b[2]) // 2)(list(map(int, re.findall(r"\d+", anchor.get("bounds"))))) if anchor is not None else 700
        adb("shell", "input", "swipe", str(x), "1500", str(x), "700", "400")
        time.sleep(0.5)
    raise AssertionError(f"Opción del menú no encontrada: {option}")


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
    # launch_home() cierra la app a la fuerza. Android guarda los ajustes en disco en segundo
    # plano: si el paso anterior acaba de cambiar uno, se espera a que quede guardado.
    time.sleep(2)
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
        # Android guarda los ajustes en disco en segundo plano; se espera antes de cerrar la app a la fuerza.
        time.sleep(2)
        ui.launch()
        time.sleep(2)
        evidence("ajustes-ventana-inicial-al-abrir")
        # El título muestra la carpeta abierta (la lista puede no mostrar todos los archivos).
        wait("OIPrueba")
        assert not nodes("Categorías", hierarchy()), "Se abrió Inicio en vez de la carpeta de inicio"
        settings("Ventana inicial")
        tap("Última carpeta abierta")
        time.sleep(2)
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
        time.sleep(2)
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
        time.sleep(2)


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
        # Desde Historial no hay botón de menú y «Atrás» vuelve a la carpeta: se abre la app en Inicio.
        launch_home()
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



class FakeTv:
    """TV DLNA falsa en el equipo de CI: responde a SSDP en el 1900 y a las órdenes AVTransport.
    El emulador la ve en 10.0.2.2."""

    def __init__(self, http_port=49152):
        import http.server
        import socket
        import threading
        import xml.etree.ElementTree as XML

        self.commands = []
        self.http_port = http_port
        location = f"http://10.0.2.2:{http_port}/dlna/desc.xml"
        tv = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def reply(self, body):
                data = body.encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/xml")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                self.reply(
                    '<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>'
                    "<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>"
                    "<friendlyName>TV de prueba</friendlyName><serviceList><service>"
                    "<serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>"
                    "<controlURL>/dlna/control/avt</controlURL></service></serviceList></device></root>")

            def do_POST(self):
                body = self.rfile.read(int(self.headers["Content-Length"])).decode()
                action = self.headers["SOAPACTION"].strip('"').split("#")[1]
                root = XML.fromstring(body)
                call = next(e for e in root.iter() if e.tag.endswith("}" + action))
                tv.commands.append((action, {child.tag: child.text or "" for child in call}))
                extra = "<RelTime>0:01:05</RelTime><TrackDuration>0:10:00</TrackDuration>" if action == "GetPositionInfo" else ""
                self.reply(
                    '<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>'
                    f'<u:{action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">{extra}'
                    f"</u:{action}Response></s:Body></s:Envelope>")

        self.http = http.server.ThreadingHTTPServer(("0.0.0.0", http_port), Handler)
        threading.Thread(target=self.http.serve_forever, daemon=True).start()
        self.ssdp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.ssdp.bind(("0.0.0.0", 1900))
        answer = (f"HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: {location}\r\n"
                  "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n").encode()

        def ssdp_loop():
            while True:
                try:
                    data, sender = self.ssdp.recvfrom(4096)
                except OSError:
                    return
                if b"M-SEARCH" in data:
                    self.ssdp.sendto(answer, sender)

        threading.Thread(target=ssdp_loop, daemon=True).start()

    def actions(self):
        return [action for action, _ in self.commands]

    def close(self):
        self.http.shutdown()
        self.ssdp.close()


def fetch_from_host(url, host_port=48080):
    """Pide [url] (del servidor de la app en el emulador) desde el equipo de CI, como haría la TV:
    la consola del emulador redirige el puerto y la app ve llegar la conexión desde 10.0.2.2."""
    import urllib.parse
    import urllib.request

    parsed = urllib.parse.urlparse(url)
    adb("emu", "redir", "add", f"tcp:{host_port}:{parsed.port}")
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{host_port}{parsed.path}", timeout=30) as reply:
            return reply.status, reply.read()
    finally:
        adb("emu", "redir", "del", f"tcp:{host_port}", check=False)


@check("enviar-a-la-tv-dlna")
def cast_to_tv():
    import urllib.parse

    tv = FakeTv()
    try:
        open_test_folder()
        find("tono.wav")
        long_press("tono.wav")
        menu_option("Enviar a la TV")
        wait("Añadir por dirección", timeout=30)
        tap("Añadir por dirección")
        fill("Dirección de la TV", "10.0.2.2")
        tap("Buscar")
        tap(wait("TV de prueba", timeout=30)[0].get("text"))
        until(lambda: "Play" in tv.actions(), "La TV no recibió la orden de reproducir", 30)
        load = dict(tv.commands)["SetAVTransportURI"]
        url = load["CurrentURI"]
        assert "<dc:title>tono.wav</dc:title>" in load["CurrentURIMetaData"], load
        # La TV (equipo de CI, 10.0.2.2) puede leer el archivo y llega completo.
        status, data = fetch_from_host(url)
        assert status == 200, status
        assert hashlib.sha256(data).hexdigest() == device_sha256(f"{DIR}/tono.wav"), "La TV recibió otro archivo"
        # Cualquier otra dirección (aquí, el propio teléfono) recibe 403 aunque conozca el enlace.
        parsed = urllib.parse.urlparse(url)
        raw = f"GET {parsed.path} HTTP/1.1\r\nHost: {parsed.hostname}\r\nConnection: close\r\n\r\n".encode()
        other = subprocess.run(["adb", "shell", "toybox", "nc", "-w", "5", parsed.hostname, str(parsed.port)],
                               input=raw, capture_output=True, timeout=30).stdout
        assert b" 403 " in other.split(b"\r\n", 1)[0], other[:200]
        wait_text("Reproduciendo en «TV de prueba»")
        wait_text("1:05 / 10:00")
        tap("Pausa")
        until(lambda: "Pause" in tv.actions(), "La TV no recibió la pausa", 15)
        tap("Detener")
        until(lambda: "Stop" in tv.actions(), "La TV no recibió la orden de detener", 15)
    finally:
        tv.close()



@check("descargar-desde-una-url")
def download_from_url():
    """El equipo de CI sirve un archivo por HTTP (10.0.2.2 para el emulador)."""
    import functools
    import http.server
    import threading

    data = os.urandom(2 * 1024 * 1024)
    target = "/sdcard/Download/OI Archivos/descarga-prueba.bin"
    sh("rm", "-f", q(target), check=False)
    with tempfile.TemporaryDirectory() as tmp:
        (pathlib.Path(tmp) / "descarga-prueba.bin").write_bytes(data)
        handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=tmp)
        server = http.server.ThreadingHTTPServer(("0.0.0.0", 8099), handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            launch_home()
            ui.drawer("Red, nube y USB")
            tap(find("Descargar desde una URL").get("text"))
            fill("Dirección (URL)", "http://10.0.2.2:8099/descarga-prueba.bin")
            tap("Descargar")
            until(lambda: exists(target), "La descarga no llegó a la carpeta de descargas", 60)
            assert device_sha256(target) == hashlib.sha256(data).hexdigest(), "El archivo descargado no coincide"
        finally:
            server.shutdown()



@check("portapapeles-de-varias-carpetas")
def clipboard_from_several_folders():
    for path in ("clipA", "clipB", "clipDestino"):
        sh("rm", "-rf", q(f"{DIR}/{path}"), check=False)
        sh("mkdir", "-p", q(f"{DIR}/{path}"))
    push_bytes(b"uno", f"{DIR}/clipA/uno.txt")
    push_bytes(b"dos", f"{DIR}/clipB/dos.txt")
    push_bytes(b"tres", f"{DIR}/clipB/tres.txt")
    open_test_folder()
    tap(find("clipA").get("text"))
    long_press("uno.txt")
    tap("Copiar")
    adb("shell", "input", "keyevent", "4")
    tap(find("clipB").get("text"))
    long_press("dos.txt")
    tap("tres.txt")
    menu_option("Añadir al portapapeles")
    tap(wait("3 elemento(s) para copiar · Ver")[0].get("text"))
    wait("uno.txt")
    tap("Quitar tres.txt")
    # La barra queda detrás del diálogo (no sale en la jerarquía): se comprueba la lista del diálogo.
    until(lambda: not nodes("tres.txt", hierarchy()), "tres.txt sigue en el portapapeles", 10)
    tree = hierarchy()
    assert nodes("uno.txt", tree) and nodes("dos.txt", tree), "Faltan elementos que no se quitaron"
    tap("Cerrar")
    wait_text("2 elemento(s) para copiar")
    adb("shell", "input", "keyevent", "4")
    tap(find("clipDestino").get("text"))
    tap("Pegar aquí")
    until(lambda: exists(f"{DIR}/clipDestino/uno.txt") and exists(f"{DIR}/clipDestino/dos.txt"),
          "No se pegaron los archivos de las dos carpetas", 30)
    time.sleep(1)
    assert not exists(f"{DIR}/clipDestino/tres.txt"), "Se pegó el archivo quitado del portapapeles"
    assert read(f"{DIR}/clipA/uno.txt") == "uno", "Copiar no debe mover el original"



@check("poner-como-tono")
def set_ringtone():
    adb("shell", "appops", "set", ui.PACKAGE, "WRITE_SETTINGS", "allow")
    before = sh("settings", "get", "system", "ringtone").strip()
    try:
        open_test_folder()
        find("tono.wav")
        long_press("tono.wav")
        menu_option("Poner como tono")
        tap("Tono de llamada")
        until(lambda: sh("settings", "get", "system", "ringtone").strip() not in (before, ""),
              "No cambió el tono de llamada", 30)
        uri = sh("settings", "get", "system", "ringtone").strip()
        # Android guarda el tono con el usuario delante: content://0@media/...
        assert re.match(r"content://(\d+@)?media/", uri), uri
        plain = re.sub(r"^content://\d+@", "content://", uri).split("?")[0]
        name = sh("content", "query", "--uri", plain, "--projection", "_display_name", check=False)
        assert "tono.wav" in name, f"El tono no es tono.wav: {uri} → {name}"
    finally:
        if before and before != "null":
            sh("settings", "put", "system", "ringtone", before, check=False)



def checkbox_near(text):
    """Casilla de la fila cuyo texto empieza por [text]."""
    tree = hierarchy()
    row = next(n for n in tree.iter("node") if (n.get("text") or "").startswith(text))
    row_y = int(re.findall(r"\d+", row.get("bounds"))[1])
    boxes = [n for n in tree.iter("node") if n.get("checkable") == "true"]
    return min(boxes, key=lambda n: abs(int(re.findall(r"\d+", n.get("bounds"))[1]) - row_y))


@check("limpiar-basura")
def junk_cleaner():
    leftover = f"/sdcard/Android/media/com.oi.prueba.desinstalada"
    thumbnail = "/sdcard/DCIM/.thumbnails/mini-prueba.jpg"
    sh("mkdir", "-p", q(leftover), q("/sdcard/DCIM/.thumbnails"))
    push_bytes(b"\x02" * 50_000, f"{leftover}/resto.bin")
    push_bytes(b"\xff\xd8" + b"\x00" * 3000, thumbnail)
    launch_home()
    ui.drawer("Limpiar basura")
    tap("Buscar basura")
    wait_text("Se pueden liberar", timeout=120)
    for kind in ("Restos de apps desinstaladas", "Miniaturas guardadas", "APK ya instalados"):
        find_text = next((n for n in hierarchy().iter("node") if (n.get("text") or "").startswith(kind)), None)
        if find_text is None:
            adb("shell", "input", "swipe", "540", "1500", "540", "900", "400")
            time.sleep(0.5)
        wait_text(kind)
    # Los temporales y vacíos de todo el almacenamiento se dejan: pueden ser de otras pruebas.
    temp_rows = [n for n in hierarchy().iter("node") if (n.get("text") or "").startswith("Temporales y vacíos")]
    if temp_rows:
        box = checkbox_near("Temporales y vacíos")
        if box.get("checked") == "true":
            tap_node(box)
            time.sleep(0.5)
    button = None
    for _ in range(8):
        button = next((n for n in hierarchy().iter("node")
                       if (n.get("text") or "").startswith("Limpiar ") and n.get("text") != "Limpiar basura"), None)
        if button is not None:
            break
        adb("shell", "input", "swipe", "540", "1500", "540", "700", "400")
        time.sleep(0.5)
    assert button is not None, "No aparece el botón Limpiar"
    tap_node(button)
    tap_last("Limpiar")
    until(lambda: not exists(leftover) and not exists(thumbnail) and not exists(f"{DIR}/oi.apk"),
          "No se limpió todo lo elegido", 60)



@check("listas-de-reproduccion")
def playlists():
    sh("rm", "-rf", q(f"{DIR}/lista"), check=False)
    sh("mkdir", "-p", q(f"{DIR}/lista"))
    for name in ("uno.wav", "dos.wav"):
        sh("cp", q(f"{DIR}/tono.wav"), q(f"{DIR}/lista/{name}"))

    def top(label):
        node = wait(label)[0]
        return int(re.findall(r"\d+", node.get("bounds"))[1])

    try:
        open_test_folder()
        tap(find("lista").get("text"))
        long_press("uno.wav")
        tap("dos.wav")
        menu_option("Añadir a lista de reproducción")
        tap("Nueva lista…")
        fill("Nombre de la lista", "Viaje")
        tap_last("Aceptar")
        # El aviso «2 añadido(s)» es un toast, que no sale en la jerarquía de la pantalla: lo que
        # cuenta es que la lista exista con sus dos pistas tras volver a abrir la app.
        time.sleep(2)
        launch_home()
        ui.drawer("Listas de reproducción")
        wait("2 pista(s)")
        tap("Viaje")
        assert top("uno.wav") < top("dos.wav"), "Las pistas no están en el orden en que se añadieron"
        tap("Bajar")
        until(lambda: top("dos.wav") < top("uno.wav"), "«Bajar» no cambió el orden", 10)
        adb("shell", "input", "keyevent", "4")
        tap("Viaje")
        assert top("dos.wav") < top("uno.wav"), "El nuevo orden no se guardó"
        tap("Reproducir")
        until(media_playing, "La lista no empezó a reproducirse", 20)
        # Empieza por la primera pista de la lista, que ahora es dos.wav.
        wait("dos.wav")
        evidence("listas-de-reproduccion-sonando")
    finally:
        adb("shell", "am", "force-stop", ui.PACKAGE)


def app_jobs():
    """Trabajos de WorkManager de la app en JobScheduler: {id: bloque de dumpsys}."""
    out = sh("dumpsys", "jobscheduler", ui.PACKAGE, check=False)
    jobs = {}
    for block in out.split("JOB #")[1:]:
        match = re.match(r"u\d+a\d+/(\d+): \S+ " + re.escape(ui.PACKAGE) + r"/androidx\.work", block)
        if match:
            jobs[match.group(1)] = block
    return jobs


def notification_shown(notification_id):
    out = sh("dumpsys", "notification", "--noredact", check=False)
    return f"|{ui.PACKAGE}|{notification_id}|" in out


@check("aviso-de-archivos-nuevos")
def new_files_notice():
    folder = "/sdcard/DCIM/OINuevos"
    sh("rm", "-rf", q(folder), check=False)
    settings("Notificaciones")
    set_switch("Avisar de archivos nuevos", True)
    wait("Tipos de archivo:")
    try:
        # Se espera a que el trabajo quede programado con su disparador de MediaStore.
        until(lambda: any("TRIGGER" in b.upper() or "content" in b for b in app_jobs().values()),
              "No se programó la vigilancia de archivos nuevos", 30)
        (OUTPUT / "jobs-archivos-nuevos.txt").write_text(
            sh("dumpsys", "jobscheduler", ui.PACKAGE, check=False), encoding="utf-8")
        time.sleep(2)
        push_bytes(png((0, 0, 255)), f"{folder}/foto_nueva.png")
        # Lo que hace la cámara o una descarga: el archivo entra en MediaStore.
        sh("content", "call", "--uri", "content://media", "--method", "scan_volume",
           "--arg", "external_primary", check=False)
        # Android lanza el trabajo cuando cambia MediaStore (máx. 1 min de espera).
        until(lambda: notification_shown(31), "No llegó el aviso de archivo nuevo", 120)
        out = sh("dumpsys", "notification", "--noredact", check=False)
        assert "foto_nueva.png" in out, "El aviso no nombra el archivo nuevo"
        # Tocar el aviso abre la carpeta del archivo.
        adb("shell", "cmd", "statusbar", "expand-notifications")
        time.sleep(1)
        tap(find_text("archivo nuevo"))
        wait("foto_nueva.png")
    finally:
        adb("shell", "cmd", "statusbar", "collapse", check=False)
        settings("Notificaciones")
        set_switch("Avisar de archivos nuevos", False)
        sh("rm", "-rf", q(folder), check=False)


def find_text(fragment):
    """Texto completo del primer nodo que contiene [fragment]."""
    for _ in range(20):
        for n in hierarchy().iter("node"):
            if fragment in (n.get("text") or ""):
                return n.get("text")
        time.sleep(0.5)
    raise AssertionError(f"No se ve: {fragment}")


@check("aviso-de-espacio-bajo")
def low_space_notice():
    filler = "/sdcard/Download/relleno-espacio.bin"
    sh("rm", "-f", q(filler), check=False)
    free_kb = int(sh("df", "-k", "/sdcard").splitlines()[-1].split()[3])
    gb = 1024 * 1024
    # Se elige el umbral más bajo que esté por encima del espacio libre del emulador; si hay más
    # de 10 GB libres se ocupa espacio con un archivo grande.
    choices = [(1, "1 GB"), (2, "2 GB"), (5, "5 GB"), (10, "10 GB")]
    target = next(((n, label) for n, label in choices if free_kb < n * gb), None)
    if target is None:
        sh("fallocate", "-l", f"{free_kb - 8 * gb}K", q(filler))
        target = (10, "10 GB")
    settings("Notificaciones")
    try:
        set_switch("Advertencia de espacio bajo", True)
        # Al elegir el umbral, la app revisa el espacio en ese momento (además de cada hora).
        tap(target[1])
        until(lambda: notification_shown(30), "No llegó la advertencia de espacio bajo", 90)
        (OUTPUT / "jobs-espacio.txt").write_text(
            sh("dumpsys", "jobscheduler", ui.PACKAGE, check=False), encoding="utf-8")
        assert "Espacio insuficiente" in sh("dumpsys", "notification", "--noredact", check=False)
        # Tocar el aviso lleva a «Limpiar basura».
        adb("shell", "cmd", "statusbar", "expand-notifications")
        time.sleep(1)
        tap("Espacio insuficiente")
        wait("Limpiar basura")
    finally:
        adb("shell", "cmd", "statusbar", "collapse", check=False)
        sh("rm", "-f", q(filler), check=False)
        settings("Notificaciones")
        tap("1 GB")


def android_sdk():
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        path = os.environ.get(var)
        if path and pathlib.Path(path, "build-tools").is_dir():
            return pathlib.Path(path)
    props = ROOT / "local.properties"
    if props.exists():
        for line in props.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                return pathlib.Path(line.split("=", 1)[1].strip())
    raise AssertionError("No se encontró el SDK de Android para crear los APK de prueba")


def version_key(name):
    return [int(part) for part in re.findall(r"\d+", name)]


def build_test_apk(folder, package, label):
    """APK mínimo y firmado (sin código), creado con las herramientas del SDK del equipo de CI."""
    sdk = android_sdk()
    tools = max((d for d in (sdk / "build-tools").iterdir()
                 if (d / "aapt2").exists() and (d / "apksigner").exists()),
                key=lambda d: version_key(d.name))
    jar = max((sdk / "platforms").glob("android-*/android.jar"),
              key=lambda p: version_key(p.parent.name))
    manifest = folder / f"{package}.xml"
    manifest.write_text(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
        f'package="{package}" android:versionCode="1" android:versionName="1.0">'
        '<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34"/>'
        f'<application android:label="{label}" android:hasCode="false"/></manifest>',
        encoding="utf-8")
    unsigned, aligned, out = (folder / f"{package}-{kind}.apk" for kind in ("sin-firmar", "alineado", "firmado"))
    subprocess.check_call([str(tools / "aapt2"), "link", "-o", str(unsigned), "-I", str(jar),
                           "--manifest", str(manifest)])
    subprocess.check_call([str(tools / "zipalign"), "-f", "4", str(unsigned), str(aligned)])
    keystore = folder / "prueba.jks"
    if not keystore.exists():
        subprocess.check_call(["keytool", "-genkeypair", "-keystore", str(keystore), "-storepass", "prueba123",
                               "-keypass", "prueba123", "-alias", "prueba", "-keyalg", "RSA", "-keysize", "2048",
                               "-validity", "3650", "-dname", "CN=Prueba OI"],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.check_call([str(tools / "apksigner"), "sign", "--ks", str(keystore), "--ks-pass", "pass:prueba123",
                           "--key-pass", "pass:prueba123", "--out", str(out), str(aligned)])
    return out


def installed(package):
    return f"package:{package}" in sh("pm", "list", "packages", package, check=False).split()


def answer_system_dialogs(buttons, done, message, timeout=120):
    """Toca los botones de los diálogos de Android (el emulador está en inglés) hasta que [done]."""
    deadline = time.monotonic() + timeout
    while not done():
        assert time.monotonic() < deadline, message
        tree = hierarchy()
        for label in buttons:
            found = [n for n in nodes(label, tree) if n.get("package") != ui.PACKAGE]
            if found:
                tap_node(found[0])
                time.sleep(1.5)
                break
        else:
            time.sleep(1)


def press_and_hold(label):
    node, _ = wait(label)
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    x, y = (x1 + x2) // 2, (y1 + y2) // 2
    adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "900")
    time.sleep(1)


@check("instalar-y-desinstalar-apps-por-lotes")
def batch_apps():
    packages = {"com.omaritoinforma.prueba.uno": "Prueba uno", "com.omaritoinforma.prueba.dos": "Prueba dos"}
    folder = f"{DIR}/apks"
    for package in packages:
        sh("pm", "uninstall", package, check=False)
    sh("rm", "-rf", q(folder), check=False)
    with tempfile.TemporaryDirectory() as tmp:
        for (package, label), name in zip(packages.items(), ("uno.apk", "dos.apk")):
            adb("push", str(build_test_apk(pathlib.Path(tmp), package, label)), f"{folder}/{name}")
    # Lo que el usuario concede en «Instalar apps desconocidas».
    adb("shell", "appops", "set", ui.PACKAGE, "REQUEST_INSTALL_PACKAGES", "allow")
    try:
        # Instalar: dos APK elegidos en el explorador; Android pide confirmar cada uno.
        open_test_folder()
        tap(find("apks").get("text"))
        long_press("uno.apk")
        tap("dos.apk")
        menu_option("Instalar 2 APK")
        answer_system_dialogs(
            ["Install", "INSTALL", "Install anyway", "Don't send"],
            lambda: all(installed(p) for p in packages),
            "No se instalaron los dos APK")
        time.sleep(2)
        # Desinstalar: las dos apps elegidas en Aplicaciones.
        launch_home()
        ui.drawer("Aplicaciones")
        fill("Buscar app…", "Prueba")
        wait("Prueba uno")
        wait("Prueba dos")
        press_and_hold("Prueba uno")
        wait("1 seleccionada(s)")
        tap("Prueba dos")
        wait("2 seleccionada(s)")
        tap("Desinstalar seleccionadas")
        answer_system_dialogs(
            ["OK"], lambda: not any(installed(p) for p in packages), "No se desinstalaron las dos apps")
        assert installed(ui.PACKAGE), "Se desinstaló OI Archivos"
    finally:
        for package in packages:
            sh("pm", "uninstall", package, check=False)


if os.environ.get("OI_REMOTE_TEST_ROOT"):

    @check("copia-automatica-a-sftp")
    def auto_backup():
        """Copia a la conexión «SFTP prueba» (servidor SFTP real del equipo de CI): se comprueba el
        disco del servidor, primero con «Copiar ahora» y luego con una foto nueva que se sube sola."""
        import shutil

        server = pathlib.Path(os.environ["OI_REMOTE_TEST_ROOT"]) / "Copias OI"
        local = "/sdcard/DCIM/OICopia"
        sh("rm", "-rf", q(local), check=False)
        shutil.rmtree(server, ignore_errors=True)
        first, second = png((255, 0, 0)), png((0, 160, 0))
        push_bytes(first, f"{local}/foto1.png")
        try:
            settings("Copia automática")
            tap("Destino")
            tap("SFTP prueba")
            tap("Carpeta en el destino")
            replace_field("Copias OI")
            tap_last("Aceptar")
            wait("Copias OI")
            set_switch("Copiar automáticamente", True)
            tap("Copiar ahora")
            time.sleep(3)
            evidence("copia-automatica-tras-copiar-ahora")
            target = server / "DCIM/OICopia/foto1.png"
            until(lambda: target.exists() and target.read_bytes() == first,
                  "«Copiar ahora» no dejó la foto en el servidor", 120)
            # Una foto nueva se sube sola: Android lanza la copia cuando cambia MediaStore.
            push_bytes(second, f"{local}/foto2.png")
            sh("content", "call", "--uri", "content://media", "--method", "scan_volume",
               "--arg", "external_primary", check=False)
            target = server / "DCIM/OICopia/foto2.png"
            until(lambda: target.exists() and target.read_bytes() == second,
                  "La foto nueva no se subió sola", 300)
            (OUTPUT / "copia-automatica-jobs.txt").write_text(
                sh("dumpsys", "jobscheduler", ui.PACKAGE, check=False), encoding="utf-8")
        finally:
            settings("Copia automática")
            set_switch("Copiar automáticamente", False)
            sh("rm", "-rf", q(local), check=False)


@check("editor-sangria-y-guardado-automatico")
def editor_options():
    push_bytes(b"  hola", f"{DIR}/codigo.txt")
    try:
        settings("Editor de texto")
        set_switch("Sangría automática", True)
        set_switch("Guardado automático", True)
        open_test_folder()
        tap(find("codigo.txt").get("text"))
        field, _ = wait("  hola")
        tap_node(field)
        adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
        adb("shell", "input", "keyevent", "KEYCODE_ENTER")
        adb("shell", "input", "text", "x")
        time.sleep(1)
        adb("shell", "input", "keyevent", "4")
        until(lambda: read(f"{DIR}/codigo.txt") == "  hola\n  x",
              f"No se guardó con sangría al salir: {read(f'{DIR}/codigo.txt')!r}", 20)
        assert not nodes("Cambios sin guardar", hierarchy()), "Con guardado automático no debe preguntar"
    finally:
        settings("Editor de texto")
        set_switch("Guardado automático", False)


@check("editor-tabulador-simbolos-mayusculas-duplicar")
def editor_tools():
    push_bytes(b"", f"{DIR}/simbolos.txt")
    try:
        settings("Editor de texto")
        set_switch("Usar espacios en lugar de tabuladores", True)
        set_switch("Barra de símbolos", True)
        open_test_folder()
        tap(find("simbolos.txt").get("text"))
        wait("Tab")
        field = next(n for n in hierarchy().iter("node") if n.get("class") == "android.widget.EditText")
        tap_node(field)
        adb("shell", "input", "text", "abc")
        time.sleep(0.5)
        tap("Tab")
        tap("{")
        tap("Más")
        tap("Convertir a mayúsculas")
        tap("Más")
        tap("Duplicar línea")
        time.sleep(0.5)
        tap("Guardar")
        expected = "ABC    {\nABC    {"
        until(lambda: read(f"{DIR}/simbolos.txt") == expected,
              f"El archivo no quedó como se esperaba: {read(f'{DIR}/simbolos.txt')!r}", 20)
    finally:
        settings("Editor de texto")
        set_switch("Usar espacios en lugar de tabuladores", False)


def row_top(label):
    """Altura en pantalla de un elemento visible: sirve para comprobar el orden de una lista."""
    node = wait(label)[0]
    return int(re.findall(r"\d+", node.get("bounds"))[1])


def focused_window():
    out = sh("dumpsys", "window", check=False)
    return next((line.strip() for line in out.splitlines() if "mCurrentFocus" in line), "")


@check("fijar-arriba-y-abrir-como")
def pin_and_open_as():
    folder = f"{DIR}/fijar"
    sh("rm", "-rf", q(folder), check=False)
    sh("mkdir", "-p", q(folder))
    for name in ("aaa.txt", "bbb.txt", "zzz.txt"):
        push_bytes(name.encode(), f"{folder}/{name}")
    push_bytes(b"contenido-xyz", f"{folder}/datos.xyz")
    open_test_folder()
    tap(find("fijar").get("text"))
    wait("zzz.txt")
    assert row_top("aaa.txt") < row_top("zzz.txt"), "El orden de partida no es el alfabético"
    # Fijar: zzz.txt sube al principio y lleva su alfiler.
    long_press("zzz.txt")
    menu_option("Fijar arriba")
    until(lambda: row_top("zzz.txt") < row_top("aaa.txt"), "El archivo fijado no subió arriba", 15)
    wait("Fijado")
    # Sigue fijado al cerrar y volver a abrir la app.
    time.sleep(2)
    open_test_folder()
    tap(find("fijar").get("text"))
    wait("zzz.txt")
    assert row_top("zzz.txt") < row_top("aaa.txt"), "Lo fijado no se conservó al reabrir la app"
    # Quitar de fijados: vuelve a su sitio.
    long_press("zzz.txt")
    menu_option("Quitar de fijados")
    until(lambda: row_top("aaa.txt") < row_top("zzz.txt"), "Al quitar el alfiler no volvió a su sitio", 15)
    assert not nodes("Fijado", hierarchy()), "Sigue el alfiler tras quitarlo"
    # Abrir como texto un archivo con una extensión que no es de texto.
    long_press("datos.xyz")
    menu_option("Abrir como…")
    tap("Texto (editor de OI Archivos)")
    wait_text("contenido-xyz")
    adb("shell", "input", "keyevent", "4")


@check("apps-predeterminadas-de-android")
def default_apps():
    settings("Aplicaciones")
    tap("Apps predeterminadas")
    until(lambda: "settings" in focused_window().lower(),
          f"No se abrieron los ajustes de apps predeterminadas de Android: {focused_window()}", 20)
    evidence("apps-predeterminadas-ajustes-de-android")
    ui.launch()


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
