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
    # Las carpetas de otras pruebas van antes en la lista: a.txt puede estar más abajo.
    find("a.txt")


def drawer_find(label, swipes=6):
    """Busca [label] en el menú lateral abierto, desplazándolo hacia abajo dentro del propio menú."""
    for _ in range(swipes):
        found = nodes(label, hierarchy())
        if found:
            return found[0]
        adb("shell", "input", "swipe", "280", "1600", "280", "600", "400")
        time.sleep(0.5)
    raise AssertionError(f"No está en el menú lateral: {label}")


def selected(label):
    """Si la ficha o botón con el texto [label] está marcado: el estado lo lleva el nodo pulsable, que
    suele ser el padre del texto, así que se mira el nodo y sus antepasados."""
    tree = hierarchy()
    parents = {child: parent for parent in tree.iter("node") for child in parent}
    for node in tree.iter("node"):
        if node.get("text") != label:
            continue
        current = node
        while current is not None:
            if current.get("checked") == "true" or current.get("selected") == "true":
                return True
            current = parents.get(current)
    return False


def long_press(label):
    node = find(label)
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


def find(label, swipes=10):
    """Desplaza la lista hasta que aparece el control (las listas perezosas solo crean lo visible):
    primero hacia abajo y, si no estaba, de vuelta hacia arriba."""
    for direction in ((1500, 700), (700, 1500)):
        for _ in range(swipes):
            found = nodes(label, hierarchy())
            if found:
                return found[0]
            adb("shell", "input", "swipe", "540", str(direction[0]), "540", str(direction[1]), "400")
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


@check("busqueda-avanzada-tipo-ocultos-y-subcarpetas")
def search_types_hidden_subfolders():
    folder = f"{DIR}/buscar-tipo"
    sh("rm", "-rf", q(folder), check=False)
    sh("mkdir", "-p", q(f"{folder}/carpeta-bt"))
    push_bytes(png((0, 0, 255)), f"{folder}/foto-bt.png")
    push_bytes(b"texto", f"{folder}/nota-bt.txt")
    push_bytes(b"oculto", f"{folder}/.secreto-bt.txt")
    push_bytes(b"dentro", f"{folder}/carpeta-bt/profundo-bt.txt")

    def search(types, hidden=False, subfolders=True):
        """Abre la búsqueda avanzada en la carpeta de prueba, la rellena y devuelve el texto de los resultados."""
        open_test_folder()
        tap_node(find("buscar-tipo"))
        wait("nota-bt.txt")
        tap("Más opciones")
        tap("Búsqueda avanzada")
        for kind in types:
            tap_node(find(kind))
        set_switch_found("Buscar en las subcarpetas", subfolders)
        set_switch_found("Incluir archivos y carpetas ocultos", hidden)
        tap_node(find("Buscar"))
        wait("Buscar: Filtros avanzados", 40)
        return {n.get("text") for n in hierarchy().iter("node") if n.get("text")}

    def set_switch_found(title, on):
        find(title)
        set_switch(title, on)

    # Imágenes y carpetas a la vez; el texto no.
    shown = search(["Imágenes", "Carpetas"])
    assert {"foto-bt.png", "carpeta-bt"} <= shown, f"Faltan resultados: {shown}"
    assert "nota-bt.txt" not in shown and "profundo-bt.txt" not in shown, f"Sobran resultados: {shown}"
    evidence("busqueda-avanzada-tipo-imagenes-y-carpetas")
    # Texto sin ocultos: el oculto no sale.
    shown = search(["Texto y código"])
    assert {"nota-bt.txt", "profundo-bt.txt"} <= shown, f"Faltan resultados: {shown}"
    assert ".secreto-bt.txt" not in shown and "foto-bt.png" not in shown, f"Sobran resultados: {shown}"
    # Con ocultos sí.
    shown = search(["Texto y código"], hidden=True)
    assert ".secreto-bt.txt" in shown, f"El archivo oculto no apareció: {shown}"
    evidence("busqueda-avanzada-con-ocultos")
    # Sin subcarpetas: solo lo que hay en la carpeta elegida.
    shown = search(["Texto y código"], subfolders=False)
    assert "nota-bt.txt" in shown and "profundo-bt.txt" not in shown, f"Entró en las subcarpetas: {shown}"


@check("editar-imagen-girar-recortar-y-guardar")
def edit_image():
    folder = f"{DIR}/editar-img"
    sh("rm", "-rf", q(folder), check=False)
    sh("mkdir", "-p", q(folder))
    # 64×32: mitad izquierda roja y mitad derecha azul.
    push_bytes(png_image(64, 32, lambda x, y: (255, 0, 0) if x < 32 else (0, 0, 255)), f"{folder}/mitad.png")

    def open_editor():
        open_test_folder()
        tap_node(find("editar-img"))
        long_press("mitad.png")
        menu_option("Recortar o girar imagen")
        wait_text("Recorte: 64 × 32 px de 64 × 32")

    open_editor()
    evidence("editar-imagen-editor")
    # Girar a la derecha: el lado izquierdo (rojo) pasa arriba y la imagen queda de 32 × 64.
    tap("Girar a la derecha")
    wait_text("de 32 × 64")
    # Recuadro 1:1: el cuadrado más grande y centrado, de 32 × 32.
    tap("1:1")
    wait_text("Recorte: 32 × 32 px de 32 × 64")
    evidence("editar-imagen-recorte-cuadrado")
    tap("Guardar copia")
    copy = f"{folder}/mitad (editada).png"
    until(lambda: exists(copy), "No se guardó la copia editada", 40)
    time.sleep(1)
    width, height, rows = decode_png(read_bytes(copy))
    assert (width, height) == (32, 32), f"Tamaño de la copia: {width}×{height}"
    top, bottom = rows[2][16], rows[29][16]
    assert top == (255, 0, 0), f"Arriba debía ser rojo y es {top}"
    assert bottom == (0, 0, 255), f"Abajo debía ser azul y es {bottom}"
    # El original no se tocó.
    width, height, _ = decode_png(read_bytes(f"{folder}/mitad.png"))
    assert (width, height) == (64, 32), "La copia modificó el original"
    # Reemplazar: girar a la izquierda y sustituir la original (pide confirmación).
    open_editor()
    tap("Girar a la izquierda")
    wait_text("de 32 × 64")
    tap("Reemplazar")
    wait("¿Reemplazar la imagen?")
    tap_last("Reemplazar")
    until(lambda: decode_png(read_bytes(f"{folder}/mitad.png"))[:2] == (32, 64), "No se reemplazó la imagen", 40)
    _, _, rows = decode_png(read_bytes(f"{folder}/mitad.png"))
    # A la izquierda: el lado izquierdo (rojo) pasa abajo y el derecho (azul) arriba.
    assert rows[2][16] == (0, 0, 255), f"Arriba debía ser azul y es {rows[2][16]}"
    assert rows[60][16] == (255, 0, 0), f"Abajo debía ser rojo y es {rows[60][16]}"
    assert not exists(f"{folder}/.mitad.png.oi-tmp"), "Quedó un temporal"


@check("editor-de-video-recortar-girar-velocidad-gif-y-unir")
def video_editor():
    folder = f"{DIR}/oivideo"
    sh("rm", "-rf", q(folder), check=False)
    launch_home()
    source = record_clip(f"{folder}/clip.mp4", 6)
    second = record_clip(f"{folder}/clip2.mp4", 3)
    assert source["width"] and source["height"], f"Sin tamaño de vídeo: {source}"

    def exported(name, timeout=240):
        """Espera a que el MP4 exista y esté terminado (la caja «moov» se escribe al final)."""
        path = f"{folder}/{name}"
        until(lambda: exists(path) and mp4_info(read_bytes(path)), f"No se creó {name}", timeout)
        return mp4_info(read_bytes(path))

    open_test_folder()
    tap_node(find("oivideo"))
    tap("clip.mp4")
    tap("Editar")
    wait("Exportar MP4")
    evidence("editor-de-video-formulario")
    # Recortar de 1 s a 3 s, girar 90° y velocidad doble: 2 s de vídeo a doble velocidad, ≈ 1 s y de lado.
    fill("Inicio en segundos", "1", clear=True)
    fill("Fin en segundos (vacío: hasta el final)", "3")
    tap("Rotación: 0°")
    tap("Velocidad: 1.0x")
    tap("Velocidad: 1.5x")
    tap_node(find("Exportar MP4"))
    edited = exported("clip-editado.mp4")
    assert 0.4 <= edited["duration"] <= 1.8, f"Recorte y velocidad: dura {edited['duration']} s en vez de ≈ 1 s"
    assert abs(edited["width"] - source["height"]) <= 16 and abs(edited["height"] - source["width"]) <= 16, (
        f"Girado 90°: {edited['width']}×{edited['height']} desde {source['width']}×{source['height']}")
    evidence("editor-de-video-exportado")
    # GIF de los 2 s elegidos.
    tap_node(find("Crear GIF (máx. 10 s)"))
    until(lambda: exists(f"{folder}/clip.gif"), "No se creó el GIF", 120)
    time.sleep(1)
    gif = read_bytes(f"{folder}/clip.gif")
    assert gif[:6] == b"GIF89a" and gif[-1] == 0x3B, "El GIF no está bien formado"
    width, height = struct.unpack("<HH", gif[6:10])
    assert (width, height) == (source["width"], source["height"]), f"GIF de {width}×{height}"
    frames = gif.count(b"\x21\xf9\x04\x08")
    assert frames >= 10, f"El GIF solo tiene {frames} fotogramas"
    # Unir: sin giro ni velocidad, el primero recortado (2 s) y el segundo entero (≈ 3 s).
    for _ in range(8):  # volver arriba: los controles de giro y velocidad están al principio del formulario
        adb("shell", "input", "swipe", "540", "700", "540", "1700", "250")
    for label in ("Rotación: 90°", "Rotación: 180°", "Rotación: 270°", "Velocidad: 2.0x", "Velocidad: 0.5x"):
        tap(label)
    fill("Rutas de videos a unir, una por línea", f"{folder}/clip2.mp4")
    tap_node(find("Exportar MP4"))
    joined = exported("clip-editado (1).mp4")
    assert 3.8 <= joined["duration"] <= 6.5, f"Unir: dura {joined['duration']} s en vez de ≈ 5 s"
    assert abs(joined["width"] - source["width"]) <= 16, f"Unir: {joined['width']}×{joined['height']}"
    evidence("editor-de-video-unido")


def synthetic_video(path, seconds=4, width=360, height=640, color=(128, 128, 128)):
    """MP4 de un solo color con un tono de 440 Hz, como el de una cámara (H.264 baseline y AAC), hecho con PyAV."""
    import fractions
    import av

    with av.open(str(path), "w", format="mp4") as out:
        video = out.add_stream("libx264", rate=30)
        video.width, video.height, video.pix_fmt = width, height, "yuv420p"
        video.options = {"profile": "baseline", "crf": "20"}
        audio = out.add_stream("aac", rate=44100, layout="mono")
        frame = av.VideoFrame(width, height, "rgb24")
        stride = frame.planes[0].line_size
        frame.planes[0].update((bytes(color) * width + b"\0" * (stride - 3 * width)) * height)
        for i in range(seconds * 30):
            yuv = frame.reformat(format="yuv420p")
            yuv.pts, yuv.time_base = i, fractions.Fraction(1, 30)
            for packet in video.encode(yuv):
                out.mux(packet)
        for packet in video.encode():
            out.mux(packet)
        samples = 1024
        for n in range(seconds * 44100 // samples):
            sound = av.AudioFrame(format="s16", layout="mono", samples=samples)
            sound.planes[0].update(b"".join(
                struct.pack("<h", int(8000 * math.sin(2 * math.pi * 440 * (n * samples + k) / 44100)))
                for k in range(samples)))
            sound.rate, sound.pts, sound.time_base = 44100, n * samples, fractions.Fraction(1, 44100)
            for packet in audio.encode(sound):
                out.mux(packet)
        for packet in audio.encode():
            out.mux(packet)


def analyze_mp4(data, times=()):
    """Decodifica un MP4 con PyAV: primer y último fotograma, el más cercano a cada uno de [times] (en s)
    y el nivel del audio (tiempo, RMS de 0 a 1) de cada trozo. Cada fotograma es (ancho, alto, paso, RGB)."""
    import av

    def rgb(frame):
        image = frame.reformat(format="rgb24")
        return image.width, image.height, image.planes[0].line_size, bytes(image.planes[0])

    def level(frame):
        kind, count = frame.format.name.rstrip("p"), frame.samples
        raw = bytes(frame.planes[0])
        if kind == "flt":
            values = struct.unpack(f"<{count}f", raw[:4 * count])
        elif kind == "s16":
            values = [v / 32768 for v in struct.unpack(f"<{count}h", raw[:2 * count])]
        else:
            raise AssertionError(f"Formato de audio inesperado: {frame.format.name}")
        return math.sqrt(sum(v * v for v in values) / max(1, count))

    result = {"first": None, "last": None, "at": {}, "audio": []}
    with tempfile.TemporaryDirectory() as tmp:
        path = pathlib.Path(tmp) / "video.mp4"
        path.write_bytes(data)
        best, last = {}, None
        with av.open(str(path)) as container:
            for frame in container.decode(video=0):
                if result["first"] is None:
                    result["first"] = rgb(frame)
                last = frame
                for t in times:
                    if t not in best or abs(frame.time - t) < abs(best[t].time - t):
                        best[t] = frame
        assert last is not None, "El vídeo no tiene fotogramas"
        result["last"] = rgb(last)
        result["at"] = {t: rgb(frame) for t, frame in best.items()}
        with av.open(str(path)) as container:
            if container.streams.audio:
                result["audio"] = [(frame.time, level(frame)) for frame in container.decode(audio=0)]
    return result


def corner_colors(image):
    """Color cerca de las cuatro esquinas: no depende de si el vídeo se guardó girado."""
    width, height, stride, data = image
    colors = []
    for fx, fy in ((0.08, 0.08), (0.92, 0.08), (0.08, 0.92), (0.92, 0.92)):
        at = int(height * fy) * stride + 3 * int(width * fx)
        colors.append(tuple(data[at:at + 3]))
    return colors


def similar(color, expected, tolerance=45):
    return all(abs(a - b) <= tolerance for a, b in zip(color, expected))


def dark_share(image, limit=55):
    """Parte de los píxeles casi negros (texto negro o el fondo oscuro de un subtítulo)."""
    width, height, stride, data = image
    total = dark = 0
    for y in range(0, height, 2):
        row = y * stride
        for x in range(0, width, 2):
            at = row + 3 * x
            total += 1
            dark += max(data[at], data[at + 1], data[at + 2]) < limit
    return dark / total


def loudness(audio, start, end):
    levels = [value for t, value in audio if start <= t <= end]
    return max(levels) if levels else None


@check("editor-de-video-intro-y-outro")
def video_intro_outro():
    folder = f"{DIR}/oiintro"
    sh("rm", "-rf", q(folder), check=False)
    sh("mkdir", "-p", q(folder))
    with tempfile.TemporaryDirectory() as tmp:
        # Vídeo gris de 4 s con sonido: sobre él se distinguen bien el texto y los subtítulos.
        source = pathlib.Path(tmp) / "gris.mp4"
        synthetic_video(source)
        adb("push", str(source), f"{folder}/gris.mp4")
    # Foto del outro apaisada y roja: recortada al centro llena todo el cuadro vertical, sin bandas.
    push_bytes(png_image(400, 200, lambda x, y: (229, 57, 53)), f"{folder}/outro.png")
    # El primer subtítulo no empieza en 0: antes, el hueco sin texto hacía fallar la exportación.
    push_bytes("1\n00:00:00,500 --> 00:00:01,500\nHola\n".encode("utf-8"), f"{folder}/subtitulos.srt")
    launch_home()
    open_test_folder()
    tap_node(find("oiintro"))
    tap("gris.mp4")
    tap("Editar")
    wait("Exportar MP4")
    # 2 s de vídeo (de 1 s a 3 s) entre una intro de texto sobre azul y un outro con la foto, de 3 s cada uno.
    fill("Inicio en segundos", "1", clear=True)
    fill("Fin en segundos (vacío: hasta el final)", "3")
    fill("Texto sobre el video", "Texto")
    fill("Archivo SRT (ruta opcional)", f"{folder}/subtitulos.srt")
    fill("Texto de la intro", "Bienvenida")
    fill("Imagen del outro (ruta opcional)", f"{folder}/outro.png")
    fill("Color de la intro y el outro (#RRGGBB)", "#1565C0", clear=True)
    find("Duración de la intro y el outro: 3 s")
    evidence("editor-de-video-intro-y-outro-formulario")
    tap_node(find("Exportar MP4"))
    path = f"{folder}/gris-editado.mp4"
    until(lambda: exists(path) and mp4_info(read_bytes(path)), "No se creó el vídeo con intro y outro", 300)
    data = read_bytes(path)
    info = mp4_info(data)
    assert 7.0 <= info["duration"] <= 9.5, f"Dura {info['duration']} s en vez de ≈ 8 s (3 + 2 + 3)"
    assert sorted((info["width"], info["height"])) == [360, 640], f"Tamaño {info['width']}×{info['height']}"
    # Intro de 0 a 3 s, vídeo de 3 a 5 s (subtítulo de 3,5 a 4,5 s) y outro de 5 a 8 s.
    video = analyze_mp4(data, times=(1.5, 4.0, 4.8, 6.5))
    intro, outro = corner_colors(video["first"]), corner_colors(video["last"])
    assert all(similar(c, (21, 101, 192)) for c in intro), f"El primer fotograma no es la intro azul: {intro}"
    assert all(similar(c, (229, 57, 53)) for c in outro), f"El último fotograma no es la foto del outro: {outro}"
    dark = {t: round(100 * dark_share(image), 2) for t, image in video["at"].items()}
    assert dark[1.5] < 0.3 and dark[6.5] < 0.3, f"El texto o el subtítulo tapan la intro o el outro (% oscuro): {dark}"
    assert dark[4.8] >= 0.3, f"No se ve el texto sobre el vídeo (% oscuro): {dark}"
    assert dark[4.0] >= dark[4.8] + 0.4, f"No se ve el subtítulo entre 3,5 y 4,5 s (% oscuro): {dark}"
    # Las imágenes no tienen sonido: silencio en la intro y el outro, y el tono del vídeo en medio.
    sound = {name: loudness(video["audio"], a, b)
             for name, (a, b) in {"intro": (0.3, 2.7), "video": (3.3, 4.7), "outro": (5.3, 7.5)}.items()}
    assert sound["video"] is not None and sound["video"] > 0.05, f"El audio del vídeo se perdió: {sound}"
    assert sound["intro"] is not None and sound["intro"] < 0.02, f"La intro debería ser silencio: {sound}"
    assert sound["outro"] is not None and sound["outro"] < 0.02, f"El outro debería ser silencio: {sound}"
    evidence("editor-de-video-intro-y-outro-exportado")


@check("seleccion-por-rango-copiar-ruta-y-vistas")
def selection_copy_path_and_views():
    folder = f"{DIR}/seleccion"
    sh("rm", "-rf", q(folder), check=False)
    sh("mkdir", "-p", q(folder))
    for i in range(1, 7):
        push_bytes(b"12345", f"{folder}/s{i}.txt")
    open_test_folder()
    tap_node(find("seleccion"))
    wait("s6.txt")
    # Rango: se marcan s2 y s5 y «Seleccionar rango» marca también s3 y s4.
    long_press("s2.txt")
    wait_text("1 seleccionado(s)")
    tap("s5.txt")
    wait_text("2 seleccionado(s)")
    tap("Seleccionar rango")
    wait_text("4 seleccionado(s)")
    evidence("seleccion-por-rango")
    # Invertir deja las otras dos (s1 y s6); «Seleccionar todo» marca las seis.
    tap("Más")  # la del menú superior; la inferior es la de las acciones
    tap("Invertir selección")
    wait_text("2 seleccionado(s)")
    tap("Seleccionar todo")
    wait_text("6 seleccionado(s)")
    tap("Cancelar selección")
    # Copiar ruta: al pegar en el buscador sale la ruta completa del archivo.
    long_press("s3.txt")
    menu_option("Copiar ruta")
    tap("Cancelar selección")
    tap("Buscar")
    time.sleep(1.5)
    adb("shell", "input", "keyevent", "KEYCODE_PASTE")
    wait_text(f"{folder}/s3.txt")
    tap("Cerrar búsqueda")
    # Vistas: lista, detalle y cuadrícula; tres pulsaciones dan la vuelta y dejan la vista como estaba.
    seen = set()
    for _ in range(3):
        tap("Cambiar vista")
        time.sleep(1)
        tree = hierarchy()
        first, second = nodes("s1.txt", tree), nodes("s2.txt", tree)
        assert first and second, "No se ven los archivos tras cambiar la vista"
        top = lambda n: int(re.findall(r"\d+", n[0].get("bounds"))[1])
        left = lambda n: int(re.findall(r"\d+", n[0].get("bounds"))[0])
        if top(first) == top(second) and left(first) != left(second):
            seen.add("cuadrícula")
        elif any(" · 5 B" in (n.get("text") or "") for n in tree.iter("node")):
            seen.add("detalle")
        else:
            seen.add("lista")
        evidence(f"vista-{len(seen)}")
    assert seen == {"lista", "detalle", "cuadrícula"}, f"Las vistas distintas fueron: {seen}"


@check("abrir-con-muestra-el-selector-de-android")
def open_with_chooser():
    open_test_folder()
    long_press("a.txt")
    menu_option("Abrir con…")
    until(lambda: any(word in focused_window().lower() for word in ("chooser", "resolver")),
          f"No se abrió el selector de apps de Android: {focused_window()}", 20)
    evidence("abrir-con-selector")
    adb("shell", "input", "keyevent", "4")
    ui.launch()


@check("acceso-directo-a-una-carpeta-en-android")
def folder_shortcut():
    open_test_folder()
    tap("Más opciones")
    tap("Acceso directo en Android")
    # El lanzador pide confirmar («Add to home screen»); según el idioma, el botón dice Add, Añadir o Agregar.
    deadline = time.monotonic() + 20
    button = None
    while button is None and time.monotonic() < deadline:
        tree = hierarchy()
        button = next(
            (n for n in tree.iter("node")
             if n.get("clickable") == "true"
             and re.match(r"^(add|añadir|agregar)\b", (n.get("text") or n.get("content-desc") or "").strip(), re.I)),
            None)
        if button is None:
            time.sleep(0.5)
    shown = sorted({n.get("text") for n in hierarchy().iter("node") if n.get("text")})
    evidence("acceso-directo-peticion")
    assert button is not None, f"El lanzador no pidió confirmar el acceso directo. En pantalla: {shown}"
    tap_node(button)
    until(lambda: "folder-" in sh("dumpsys", "shortcut", check=False),
          "Android no guardó el acceso directo de la carpeta", 20)
    ui.launch()


@check("reproductor-repetir-y-aleatorio")
def player_repeat_and_shuffle():
    folder = f"{DIR}/oimusica"
    sh("rm", "-rf", q(folder), check=False)
    sh("mkdir", "-p", q(folder))
    for name, hz in (("a-corta.wav", 440), ("b-corta.wav", 660)):
        with tempfile.TemporaryDirectory() as tmp:
            local = pathlib.Path(tmp) / name
            with wave.open(str(local), "wb") as audio:
                audio.setnchannels(1)
                audio.setsampwidth(2)
                audio.setframerate(16000)
                audio.writeframes(b"".join(
                    struct.pack("<h", int(8000 * math.sin(2 * math.pi * hz * i / 16000))) for i in range(16000 * 6)))
            adb("push", str(local), f"{folder}/{name}")

    def playing():
        blocks = [b for b in sh("dumpsys", "media_session").split("\n\n") if ui.PACKAGE in b]
        return bool(blocks) and any("state=3" in b or "PLAYING" in b for b in blocks)

    def chip(label):
        """Cómo se ve la ficha: lo que cuenta la accesibilidad y el color de su borde izquierdo."""
        node = nodes(label, hierarchy())[0]
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        return node.get("selected"), node.get("checked"), pixel(x1 + 6, (y1 + y2) // 2)

    open_test_folder()
    tap_node(find("oimusica"))
    tap("a-corta.wav")
    until(playing, "El audio no empezó a sonar", 20)
    # Repetir uno: pasados los 12 s que durarían las dos pistas, sigue sonando y en la primera.
    tap("Sin repetición")
    wait("Repetir uno")
    time.sleep(14)
    assert playing(), "Con «Repetir uno» la reproducción se detuvo"
    assert not nodes("b-corta.wav", hierarchy()), "Con «Repetir uno» pasó a la otra pista"
    # Repetir todos: pasa a la segunda pista y sigue sonando al volver a empezar.
    tap("Repetir uno")
    wait("Repetir todos")
    wait("b-corta.wav", 30)
    time.sleep(9)
    assert playing(), "Con «Repetir todos» la reproducción se detuvo"
    # Aleatorio se marca y se desmarca.
    before = chip("Aleatorio")
    tap("Aleatorio")
    time.sleep(1)
    marked = chip("Aleatorio")
    assert marked != before, f"«Aleatorio» no cambió: {before} → {marked}"
    tap("Aleatorio")
    time.sleep(1)
    assert chip("Aleatorio") == before, "«Aleatorio» no se desmarcó"
    # Sin repetición: al acabar las dos pistas se para.
    tap("Repetir todos")
    wait("Sin repetición")
    until(lambda: not playing(), "Sin repetición la reproducción no terminó", 40)
    adb("shell", "input", "keyevent", "4")


@check("servidor-ftp-puerto-fijo-codificacion-y-modo-activo")
def ftp_server_options():
    port = "2299"
    launch_home()
    ui.drawer("Red, nube y USB")
    tap("Compartir por Wi-Fi / FTP")
    fill("Puerto FTP (vacío: automático)", port)
    fixed = "fija-oi-2026"
    fill("Contraseña FTP fija (opcional)", fixed, verify=False)
    tap_node(find("ISO-8859-1 (Europa occidental)"))
    evidence("servidor-ftp-opciones")
    tap_node(find("Servidor FTP"))
    try:
        _, tree = wait("Detener servidor")
        text = "\n".join(n.get("text", "") for n in tree.iter("node"))
        address, shown = re.search(r"ftp://([^:]+):(\d+)/", text).groups()
        assert shown == port, f"El servidor no usa el puerto elegido: {shown}"
        password = re.search(r"Contraseña: (\S+)", text).group(1)
        assert password == fixed, f"El servidor no usa la contraseña fija elegida: {password}"
        wrong = subprocess.run(["adb", "shell", "toybox", "nc", "-w", "10", address, port],
                               input=b"USER oi\r\nPASS incorrecta-123\r\nQUIT\r\n", capture_output=True,
                               timeout=40).stdout.decode("latin-1")
        assert "530 " in wrong and "230 " not in wrong, f"Una contraseña incorrecta no se rechazó: {wrong!r}"
        own = address.replace(".", ",")
        dialog = (f"USER oi\r\nPASS {password}\r\nFEAT\r\nOPTS UTF8 ON\r\n"
                  f"PORT {own},200,10\r\nPORT 8,8,8,8,200,10\r\nPORT {own},0,21\r\nQUIT\r\n")
        out = subprocess.run(["adb", "shell", "toybox", "nc", "-w", "10", address, port],
                             input=dialog.encode(), capture_output=True, timeout=40).stdout.decode("latin-1")
        (OUTPUT / "servidor-ftp-dialogo.txt").write_text(out, encoding="utf-8")
        assert out.startswith("220"), f"Sin saludo del servidor FTP: {out[:200]!r}"
        assert "230 " in out, "No se pudo iniciar sesión con la contraseña mostrada"
        features = out[out.index("211-Features"):out.index("211 End")]
        assert "UTF8" not in features and "EPRT" in features, f"FEAT: {features!r}"
        # Con los nombres en ISO-8859-1 no se acepta pasar a UTF-8.
        assert "504 Los nombres van en ISO-8859-1" in out, "OPTS UTF8 ON no se rechazó"
        # Modo activo: a la propia dirección sí; a otra dirección o a un puerto bajo, no.
        assert "200 PORT aceptado" in out, "PORT a la propia dirección no se aceptó"
        assert out.count("504 Solo se conecta a tu propia dirección") == 2, "No se rechazaron los PORT peligrosos"
    finally:
        tap("Detener servidor")
        wait("Servidor FTP")
        # Se deja la contraseña vacía otra vez (una nueva en cada inicio).
        tap_node(find("Contraseña FTP fija (opcional)"))
        adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
        for _ in range(20):
            adb("shell", "input", "keyevent", "KEYCODE_DEL")


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
    # El menú lateral es largo: los marcadores están al final.
    drawer_find("Nueva")
    # Un marcador debe seguir ahí tras cerrar la app.
    time.sleep(2)
    launch_home()
    tap("Menú")
    drawer_find("Nueva")


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


@check("apps-informacion-compartir-y-abrir-otra-app")
def apps_info_share_open():
    launch_home()
    ui.drawer("Aplicaciones")
    fill("Buscar app…", "OI Arch")
    wait("OI Archivos")
    # Información: abre los ajustes de Android de esa app.
    tap("Opciones")
    tap("Información de la app")
    until(lambda: any(n.get("package") == "com.android.settings" for n in hierarchy().iter("node")),
          f"No se abrió la información de la app: {focused_window()}", 20)
    evidence("apps-informacion-de-android")
    adb("shell", "input", "keyevent", "4")
    # Compartir APK: selector de apps del sistema.
    wait("OI Archivos")
    tap("Opciones")
    tap("Compartir APK")
    until(lambda: any(word in focused_window().lower() for word in ("chooser", "resolver")),
          f"No se abrió el selector para compartir el APK: {focused_window()}", 20)
    evidence("apps-compartir-apk-selector")
    adb("shell", "input", "keyevent", "4")
    # Abrir otra app (Ajustes, del sistema): se muestran las del sistema y se abre su fila.
    wait("OI Archivos")
    tap("Más")
    tap("Mostrar apps del sistema")
    fill("Buscar app…", "Settings", current="OI Arch")
    label = wait("Settings")[0]
    label_y = center(label)[1]
    options = min(nodes("Opciones", hierarchy()), key=lambda n: abs(center(n)[1] - label_y))
    tap_node(options)
    tap("Abrir")
    until(lambda: any(n.get("package") == "com.android.settings" for n in hierarchy().iter("node")),
          f"No se abrió Ajustes: {focused_window()}", 20)
    ui.launch()


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
        wait("PC de prueba", timeout=120)
        # Mientras busca hay una barra de progreso encima de la lista; al terminar desaparece y la lista
        # sube: un toque en ese momento cae fuera de la fila.
        until(lambda: not [n for n in hierarchy().iter("node") if n.get("class") == "android.widget.ProgressBar"],
              "La búsqueda de teléfonos no terminó", 120)
        time.sleep(1)
        tap("PC de prueba")
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


def nearest_switch(title):
    """El interruptor más cercano (en vertical) a la fila cuyo título es [title]."""
    tree = hierarchy()
    row = nodes(title, tree)[0]
    row_y = int(re.findall(r"\d+", row.get("bounds"))[1])
    switches = [n for n in tree.iter("node") if n.get("checkable") == "true"]
    return min(switches, key=lambda n: abs(int(re.findall(r"\d+", n.get("bounds"))[1]) - row_y))


def switch_state(title):
    """Estado del interruptor de la fila cuyo título es [title]."""
    return nearest_switch(title).get("checked") == "true"


def set_switch(title, on):
    if switch_state(title) != on:
        tap(title)
        time.sleep(1)
        # Si tocar el título no lo cambió (la fila no es pulsable), se toca el interruptor mismo.
        if switch_state(title) != on:
            tap_node(nearest_switch(title))
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


def png_image(width, height, color_at):
    """PNG RGB de [width]×[height]; [color_at](x, y) da el color de cada píxel."""
    import zlib

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xffffffff)

    rows = b"".join(b"\x00" + b"".join(bytes(color_at(x, y)) for x in range(width)) for y in range(height))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


def decode_png(data):
    """Lee un PNG de 8 bits sin entrelazar (RGB o RGBA): devuelve (ancho, alto, filas de (r, g, b))."""
    import zlib

    assert data[:8] == b"\x89PNG\r\n\x1a\n", "No es un PNG"
    pos, idat = 8, b""
    while pos < len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            width, height, depth, ctype, _, _, interlace = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            idat += body
    assert depth == 8 and interlace == 0 and ctype in (2, 6), f"PNG no admitido: {depth} {ctype} {interlace}"
    bpp = 3 if ctype == 2 else 4
    stride = width * bpp
    raw = zlib.decompress(idat)
    rows, prev, i = [], bytearray(stride), 0
    for _ in range(height):
        kind, line = raw[i], bytearray(raw[i + 1:i + 1 + stride])
        i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if kind == 1:
                line[x] = (line[x] + a) & 255
            elif kind == 2:
                line[x] = (line[x] + b) & 255
            elif kind == 3:
                line[x] = (line[x] + ((a + b) >> 1)) & 255
            elif kind == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(line)
        prev = line
    return width, height, [[tuple(r[x * bpp:x * bpp + 3]) for x in range(width)] for r in rows]


def mp4_info(data):
    """Duración (s) y tamaño del vídeo tal como se ve (ya girado) de un MP4, leyendo sus cajas; None si
    aún no está terminado. Android guarda los vídeos verticales en horizontal con una rotación de 90°."""

    def boxes(start, end):
        pos = start
        while pos + 8 <= end:
            size, kind = struct.unpack(">I4s", data[pos:pos + 8])
            header = 8
            if size == 1:
                size, header = struct.unpack(">Q", data[pos + 8:pos + 16])[0], 16
            elif size == 0:
                size = end - pos
            if size < header:
                return
            yield kind, pos + header, pos + size
            pos += size

    info = {"duration": None, "width": 0, "height": 0}
    for kind, start, end in boxes(0, len(data)):
        if kind != b"moov":
            continue
        for kind2, start2, end2 in boxes(start, end):
            if kind2 == b"mvhd":
                if data[start2] == 1:
                    scale, length = struct.unpack(">IQ", data[start2 + 20:start2 + 32])
                else:
                    scale, length = struct.unpack(">II", data[start2 + 12:start2 + 20])
                info["duration"] = length / scale
            elif kind2 == b"trak":
                for kind3, start3, end3 in boxes(start2, end2):
                    if kind3 == b"tkhd":
                        at = start3 + (88 if data[start3] == 1 else 76)
                        width, height = struct.unpack(">II", data[at:at + 8])
                        # Matriz de presentación: con a = 0 el vídeo está girado 90° o 270°.
                        a, b = struct.unpack(">ii", data[at - 36:at - 28])
                        if a == 0 and b != 0:
                            width, height = height, width
                        if width and height:
                            info["width"], info["height"] = width >> 16, height >> 16
    return info if info["duration"] is not None else None


def record_clip(path, seconds):
    """Graba la pantalla del emulador en un MP4 real; se mueve la pantalla para que haya fotogramas nuevos."""
    sh("mkdir", "-p", q(path.rsplit("/", 1)[0]))
    recorder = subprocess.Popen(
        ["adb", "shell", "screenrecord", "--time-limit", str(seconds), "--size", "360x640",
         "--bit-rate", "800000", path],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    deadline = time.monotonic() + seconds + 15
    while recorder.poll() is None and time.monotonic() < deadline:
        adb("shell", "input", "swipe", "540", "1600", "540", "700", "200")
        adb("shell", "input", "swipe", "540", "700", "540", "1600", "200")
    recorder.wait(timeout=30)
    time.sleep(1)
    info = mp4_info(read_bytes(path))
    assert info and info["duration"] >= seconds - 2, f"El vídeo grabado no sirve: {info}"
    return info


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
        # Las pistas entran en el orden en que salen en el explorador (por nombre).
        assert top("dos.wav") < top("uno.wav"), "Las pistas no están en el orden del explorador"
        tap("Bajar")  # el botón de la primera fila, dos.wav
        until(lambda: top("uno.wav") < top("dos.wav"), "«Bajar» no cambió el orden", 10)
        adb("shell", "input", "keyevent", "4")
        tap("Viaje")
        assert top("uno.wav") < top("dos.wav"), "El nuevo orden no se guardó"
        tap("Reproducir")
        until(media_playing, "La lista no empezó a reproducirse", 20)
        # Empieza por la primera pista de la lista, que ahora es uno.wav.
        wait("uno.wav")
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


def build_test_apk(folder, package, label, permissions=()):
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
        + "".join(f'<uses-permission android:name="{p}"/>' for p in permissions)
        + f'<application android:label="{label}" android:hasCode="false"/></manifest>',
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
    # Según la versión, la pantalla es de Ajustes o del controlador de permisos de Android.
    system = ("com.android.settings", "com.google.android.permissioncontroller", "com.android.permissioncontroller")
    until(lambda: any(n.get("package") in system for n in hierarchy().iter("node")),
          f"No se abrieron los ajustes de apps predeterminadas de Android: {focused_window()}", 20)
    evidence("apps-predeterminadas-ajustes-de-android")
    ui.launch()


@check("nivel-de-compresion-zip-y-7z")
def compression_levels():
    """Un archivo de 3 MB muy repetido: sin compresión el comprimido pesa lo mismo; con la máxima, casi nada."""
    folder = f"{DIR}/nivel"
    sh("rm", "-rf", q(folder), check=False)
    sh("mkdir", "-p", q(folder))
    sh("cp", q(f"{DIR}/grande.bin"), q(f"{folder}/datos.bin"))
    original = int(sh("stat", "-c", "%s", q(f"{folder}/datos.bin")).strip())

    def size(name):
        return int(sh("stat", "-c", "%s", q(f"{folder}/{name}")).strip())

    def compress(name, level):
        open_test_folder()
        tap(find("nivel").get("text"))
        long_press("datos.bin")
        more("Comprimir en ZIP")
        wait("Nivel de compresión")
        fill("Nombre: .zip, .7z, .tar o .tar.gz", name, clear=True)
        tap(level)
        tap("Comprimir")
        until(lambda: exists(f"{folder}/{name}"), f"No se creó {name}", 90)
        time.sleep(2)

    compress("sin.zip", "Sin compresión")
    compress("max.zip", "Máxima")
    compress("sin.7z", "Sin compresión")
    compress("max.7z", "Máxima")
    sizes = {n: size(n) for n in ("sin.zip", "max.zip", "sin.7z", "max.7z")}
    (OUTPUT / "nivel-de-compresion-tamanos.json").write_text(json.dumps({"original": original, **sizes}, indent=2))
    assert sizes["sin.zip"] >= original, f"ZIP sin compresión más pequeño que el original: {sizes}"
    assert sizes["sin.7z"] >= original, f"7z sin compresión más pequeño que el original: {sizes}"
    assert sizes["max.zip"] < original // 20, f"ZIP máxima no comprimió: {sizes}"
    assert sizes["max.7z"] < original // 20, f"7z máxima no comprimió: {sizes}"
    head = sh("head", "-c", "6", q(f"{folder}/sin.7z"), "|", "od", "-An", "-tx1").split()
    assert head == ["37", "7a", "bc", "af", "27", "1c"], f"No es un 7z: {head}"
    # El nivel elegido se recuerda para la próxima vez.
    open_test_folder()
    tap(find("nivel").get("text"))
    long_press("datos.bin")
    more("Comprimir en ZIP")
    wait("Nivel de compresión")
    assert selected("Máxima"), "El último nivel no se recordó"
    assert not selected("Sin compresión"), "Hay dos niveles marcados a la vez"
    tap("Cancelar")


@check("subcategorias-libros-capturas-grabaciones-y-office")
def sub_categories():
    seeds = {
        "Capturas": ("/sdcard/Pictures/Screenshots/captura-oi.png", png((0, 0, 255))),
        "Libros": (f"{DIR}/libro-oi.epub", b"PK-no-es-un-epub-de-verdad"),
        "Grabaciones": ("/sdcard/Recordings/nota-oi.wav", None),
        "Word": (f"{DIR}/informe-oi.docx", b"word"),
        "Excel": (f"{DIR}/tabla-oi.xlsx", b"excel"),
        "PowerPoint": (f"{DIR}/charla-oi.pptx", b"ppt"),
    }
    sh("mkdir", "-p", q("/sdcard/Pictures/Screenshots"), q("/sdcard/Recordings"))
    # Un segundo documento Word para comprobar que buscar dentro de la categoría filtra por nombre.
    other_word = f"{DIR}/ofertas-oi.docx"
    try:
        push_bytes(b"word2", other_word)
        for tile, (path, data) in seeds.items():
            if data is None:
                sh("cp", q(f"{DIR}/tono.wav"), q(path))
            else:
                push_bytes(data, path)
        sh("content", "call", "--uri", "content://media", "--method", "scan_volume",
           "--arg", "external_primary", check=False)
        time.sleep(3)
        for tile, (path, _) in seeds.items():
            name = path.rsplit("/", 1)[1]
            launch_home()
            tap_node(find(tile))
            wait(name)
            # Solo hay lo de su tipo: lo de las otras subcategorías no se mezcla.
            others = [p.rsplit("/", 1)[1] for t, (p, _) in seeds.items() if t != tile]
            tree = hierarchy()
            mixed = [o for o in others if nodes(o, tree)]
            assert not mixed, f"«{tile}» muestra archivos de otra categoría: {mixed}"
            if tile == "Word":
                wait("ofertas-oi.docx")
                # Buscar dentro de la categoría: solo entre los documentos Word, por nombre.
                tap("Buscar")
                time.sleep(1.5)  # el campo recibe el foco solo
                adb("shell", "input", "text", "informe")
                time.sleep(0.5)
                tap("Buscar")
                wait_text("«informe» en Word")
                tree = hierarchy()
                assert nodes(name, tree) and not nodes("ofertas-oi.docx", tree), "La búsqueda no filtró por nombre"
                assert not nodes("buscar_me.txt", tree), "La búsqueda salió de la categoría"
                evidence("busqueda-dentro-de-una-categoria")
    finally:
        sh("rm", "-f", q(other_word), check=False)
        for path, _ in seeds.values():
            sh("rm", "-f", q(path), check=False)
        sh("content", "call", "--uri", "content://media", "--method", "scan_volume",
           "--arg", "external_primary", check=False)


def pixel(x, y):
    raw = subprocess.check_output(["adb", "exec-out", "screencap"], timeout=30)
    width, height = struct.unpack("<II", raw[:8])
    pixels = raw[len(raw) - width * height * 4:]
    i = (y * width + x) * 4
    return pixels[i], pixels[i + 1], pixels[i + 2]


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


@check("tema-color-y-fondo-negro")
def theme_color_and_black():
    settings("Pantalla")
    try:
        tap(find("Claro").get("text"))
        tap(find("Rojo").get("text"))
        time.sleep(1.5)
        # El botón de opción marcado se dibuja con el color principal: debe ser rojo.
        selected = next(n for n in hierarchy().iter("node")
                        if n.get("class") == "android.widget.RadioButton" and n.get("checked") == "true")
        r, g, b = pixel(*center(selected))
        (OUTPUT / "tema-color-rojo.json").write_text(json.dumps({"rgb": [r, g, b]}))
        assert r > 150 and g < 100 and b < 100, f"El color principal no es rojo: {(r, g, b)}"
        tap("Verde")
        time.sleep(1.5)
        selected = next(n for n in hierarchy().iter("node")
                        if n.get("class") == "android.widget.RadioButton" and n.get("checked") == "true")
        r, g, b = pixel(*center(selected))
        assert g > r + 40 and g > b + 40, f"Al elegir verde, el color principal no es verde: {(r, g, b)}"
        # Fondo negro puro: con el tema oscuro, el fondo es exactamente negro.
        tap("Oscuro")
        time.sleep(1.5)
        normal = brightness()
        if not switch_state("Fondo negro puro"):
            tap("Fondo negro puro")
        time.sleep(1.5)
        black = brightness()
        r, g, b = pixel(540, 1750)
        assert (r, g, b) == (0, 0, 0), f"El fondo no es negro puro: {(r, g, b)}"
        assert black < normal - 8, f"El negro puro no oscurece más que el oscuro normal ({normal:.0f} → {black:.0f})"
    finally:
        settings("Pantalla")
        if switch_state("Fondo negro puro"):
            tap("Fondo negro puro")
        tap(find("Según el sistema").get("text"))
        tap(find("Colores del sistema").get("text"))
        time.sleep(1)


@check("barra-lateral-ocultar-y-reordenar")
def drawer_customize():
    try:
        settings("Barra lateral")
        set_switch("Historial", False)
        tap("Bajar Descargas")
        time.sleep(1)
        # Lo elegido se aplica en el menú lateral (también tras reiniciar la app).
        launch_home()
        tap("Menú")
        wait("Descargas")
        wait("Raíz del sistema")
        assert row_top("Raíz del sistema") < row_top("Descargas"), "El orden elegido no se aplicó al menú"
        for _ in range(4):
            adb("shell", "input", "swipe", "280", "1600", "280", "500", "400")
            time.sleep(0.4)
        tree = hierarchy()
        assert not nodes("Historial", tree), "«Historial» sigue en el menú aunque se ocultó"
        assert nodes("Ajustes", tree) and nodes("Salir", tree), "«Ajustes» y «Salir» deben verse siempre"
        evidence("barra-lateral-personalizada")
    finally:
        settings("Barra lateral")
        if nodes("Restablecer", hierarchy()):
            tap("Restablecer")
        time.sleep(2)


@check("pantalla-de-inicio-ocultar-y-reordenar")
def home_layout():
    try:
        settings("Pantalla de inicio")
        # «Accesos rápidos» sube un puesto (queda antes que «Categorías») y se oculta el icono de Música.
        tap("Subir Accesos rápidos")
        time.sleep(1)
        find("Música")
        set_switch("Música", False)
        launch_home()
        wait("Categorías")
        wait("Accesos rápidos")
        assert row_top("Accesos rápidos") < row_top("Categorías"), "El orden de las secciones no se aplicó a Inicio"
        tree = hierarchy()
        assert not nodes("Música", tree), "El icono de Música sigue en Inicio aunque se ocultó"
        assert nodes("Imágenes", tree) and nodes("Videos", tree), "Se ocultaron más iconos de los elegidos"
        evidence("inicio-personalizado")
    finally:
        settings("Pantalla de inicio")
        if nodes("Restablecer Inicio", hierarchy()):
            tap("Restablecer Inicio")
        time.sleep(2)
    launch_home()
    wait("Categorías")
    assert row_top("Categorías") < row_top("Accesos rápidos"), "Al restablecer no volvió el orden de fábrica"
    wait("Música")


def is_landscape():
    raw = subprocess.check_output(["adb", "exec-out", "screencap"], timeout=30)
    width, height = struct.unpack("<II", raw[:8])
    return width > height


@check("ajustes-pantalla-orientacion-nombre-seleccion-y-diseno-grande")
def display_toolbar_orientation_large():
    launch_home()
    tap_node(find("Videos"))
    wait_text("archivos")
    assert nodes("Videos", hierarchy()), "Sin el ajuste, el título de la categoría debía verse en la barra"
    try:
        # Orientación: los botones están arriba del todo, así que también se ven en horizontal.
        settings("Pantalla")
        tap("Horizontal")
        until(is_landscape, "La pantalla no pasó a horizontal", 25)
        evidence("orientacion-horizontal")
        tap("Automática")
        until(lambda: not is_landscape(), "La pantalla no volvió a vertical", 25)
        # Sin el nombre en la barra y con el botón de selección.
        set_switch("Mostrar el nombre en la barra de herramientas", False)
        set_switch("Mostrar botón de selección", True)
        launch_home()
        tap_node(find("Videos"))
        wait_text("archivos")
        assert not nodes("Videos", hierarchy()), "El título sigue en la barra aunque se ocultó"
        open_test_folder()
        tap("Seleccionar")
        wait_text("0 seleccionado(s)")
        tap("a.txt")
        wait_text("1 seleccionado(s)")
        evidence("boton-de-seleccion")
        tap("Cancelar selección")
        until(lambda: not nodes("Cancelar selección", hierarchy()), "No se salió de la selección", 10)
        # Diseño grande: el título «Categorías» de Inicio crece.
        launch_home()
        small = row_height("Categorías")
        settings("Pantalla")
        set_switch("Diseño grande", True)
        launch_home()
        big = row_height("Categorías")
        assert big > small * 1.1, f"El diseño grande no agrandó la interfaz: {small} → {big} px"
    finally:
        if is_landscape():
            tap("Automática")
            until(lambda: not is_landscape(), "No se pudo volver a vertical", 25)
        settings("Pantalla")
        set_switch("Mostrar el nombre en la barra de herramientas", True)
        set_switch("Mostrar botón de selección", False)
        set_switch("Diseño grande", False)
        time.sleep(2)


def row_height(label):
    node = wait(label)[0]
    top, bottom = re.findall(r"\d+", node.get("bounds"))[1::2]
    return int(bottom) - int(top)


def region_pixels(x1, y1, x2, y2, step=3):
    """Colores (r, g, b) de un rectángulo de la pantalla, tomando un píxel de cada [step]."""
    raw = subprocess.check_output(["adb", "exec-out", "screencap"], timeout=30)
    width, height = struct.unpack("<II", raw[:8])
    pixels = raw[len(raw) - width * height * 4:]
    out = []
    for y in range(max(0, y1), min(height, y2), step):
        for x in range(max(0, x1), min(width, x2), step):
            i = (y * width + x) * 4
            out.append((pixels[i], pixels[i + 1], pixels[i + 2]))
    return out


@check("estilo-de-carpetas-clasica-y-gris")
def folder_style():
    folder = f"{DIR}/estilo-carpeta"
    sh("mkdir", "-p", q(folder))

    def counts():
        open_test_folder()
        _, y = center(find("estilo-carpeta"))
        pixels = region_pixels(50, y - 30, 150, y + 30)
        amber = sum(1 for r, g, b in pixels if r > 200 and 120 < g < 210 and b < 90)
        grey = sum(1 for r, g, b in pixels
                   if abs(r - g) < 14 and abs(g - b) < 14 and 110 < r < 170)
        return amber, grey

    try:
        amber, grey = counts()
        assert amber > 30, f"La carpeta clásica debía ser amarilla (píxeles ámbar: {amber})"
        settings("Pantalla")
        tap(find("Gris").get("text"))
        time.sleep(1)
        amber, grey = counts()
        evidence("carpetas-grises")
        assert grey > 30 and amber < 5, f"La carpeta debía ser gris (grises {grey}, ámbar {amber})"
    finally:
        settings("Pantalla")
        tap(find("Clásica (amarilla)").get("text"))
        time.sleep(2)


@check("barra-de-herramientas-elegir-y-ordenar")
def toolbar_customize():
    folder = f"{DIR}/barra"
    sh("mkdir", "-p", q(folder))
    push_bytes(b"x", f"{folder}/barra.txt")
    try:
        settings("Barra de herramientas")
        # Se quita Renombrar, se añade Compartir y se sube hasta el segundo puesto.
        set_switch("Renombrar", False)
        find("Compartir")
        set_switch("Compartir", True)
        tap("Subir Compartir")
        time.sleep(0.5)
        tap("Subir Compartir")
        time.sleep(1)
        open_test_folder()
        tap_node(find("barra"))
        wait("barra.txt")
        long_press("barra.txt")
        wait("Compartir")
        evidence("barra-de-herramientas-personalizada")
        tree = hierarchy()
        assert not [n for n in nodes("Renombrar", tree) if center(n)[1] > 1800], "Renombrar sigue en la barra"
        x = {label: center(nodes(label, tree)[-1])[0] for label in ("Copiar", "Compartir", "Cortar", "Eliminar")}
        assert x["Copiar"] < x["Compartir"] < x["Cortar"] < x["Eliminar"], f"Orden de los botones: {x}"
        # Renombrar no se perdió: está en «Más» y abre su diálogo.
        more("Renombrar")
        time.sleep(1.5)
        evidence("barra-renombrar-desde-mas")
        adb("shell", "input", "keyevent", "4")
        wait("Compartir")
        # El botón Compartir de la barra abre el selector del sistema.
        tap_last("Compartir")
        until(lambda: any(word in focused_window().lower() for word in ("chooser", "resolver")),
              f"El botón Compartir no abrió el selector: {focused_window()}", 20)
        adb("shell", "input", "keyevent", "4")
    finally:
        ui.launch()
        settings("Barra de herramientas")
        if nodes("Restablecer barra", hierarchy()):
            tap("Restablecer barra")
        time.sleep(2)


def blue_tint():
    """Cuánto más azul que rojo es el margen izquierdo de la pantalla (donde solo hay fondo)."""
    pixels = region_pixels(2, 900, 14, 1900, step=6)
    return sum(p[2] for p in pixels) / len(pixels) - sum(p[0] for p in pixels) / len(pixels)


@check("fondo-de-la-app-con-imagen")
def background_image():
    path = f"{DIR}/fondo-azul.png"
    push_bytes(png((0, 0, 255), size=64), path)
    try:
        launch_home()
        wait("Categorías")
        before = blue_tint()
        settings("Pantalla")
        fill("Ruta de la imagen (JPG, PNG…)", path)
        tap_node(find("Usar como fondo"))
        wait_text("Visibilidad de la imagen")
        evidence("fondo-ajustes")
        launch_home()
        wait("Categorías")
        time.sleep(2)
        after = blue_tint()
        evidence("fondo-con-imagen")
        assert after - before > 25, f"La imagen azul no se nota en el fondo: {before:.0f} → {after:.0f}"
        # Inicio sigue completo y el fondo sobrevivió al reinicio (launch_home cierra la app a la fuerza).
        wait("Accesos rápidos")
    finally:
        settings("Pantalla")
        try:
            tap_node(find("Quitar el fondo"))
        except AssertionError:
            pass
        time.sleep(2)


@check("inicio-muestra-los-archivos-nuevos")
def home_new_files():
    name = "llegado-hoy-oi.txt"
    push_bytes(b"recien llegado", f"{DIR}/{name}")
    sh("content", "call", "--uri", "content://media", "--method", "scan_volume",
       "--arg", "external_primary", check=False)
    time.sleep(3)
    launch_home()
    wait("Categorías")
    find(name)
    evidence("inicio-archivos-nuevos")
    assert nodes("Archivos nuevos", hierarchy()) or find("Archivos nuevos"), "Falta el título de la sección"
    tap_node(find(name))
    wait_text("recien llegado")
    adb("shell", "input", "keyevent", "4")


@check("servidor-ftp-se-detiene-al-salir-si-se-pide")
def ftp_stops_on_exit():
    def running():
        return "ShareService" in sh("dumpsys", "activity", "services", ui.PACKAGE, check=False)

    launch_home()
    ui.drawer("Red, nube y USB")
    tap("Compartir por Wi-Fi / FTP")
    find("Detener el servidor al salir de la app")
    set_switch("Detener el servidor al salir de la app", True)
    try:
        tap_node(find("Servidor FTP"))
        wait("Detener servidor")
        until(running, "El servidor FTP no figura como servicio en marcha", 15)
        # «Salir» del menú lateral cierra la app: con el ajuste, el servidor se detiene con ella.
        adb("shell", "input", "keyevent", "4")
        ui.drawer("Salir")
        until(lambda: not running(), "El servidor FTP siguió en marcha tras salir de la app", 20)
    finally:
        ui.launch()
        ui.drawer("Red, nube y USB")
        tap("Compartir por Wi-Fi / FTP")
        if nodes("Detener servidor", hierarchy()):
            tap("Detener servidor")
            wait("Servidor FTP")
        find("Detener el servidor al salir de la app")
        set_switch("Detener el servidor al salir de la app", False)


@check("documentos-elegir-tipos")
def document_types():
    def count():
        launch_home()
        tap("Documentos")
        wait_text("archivos")
        time.sleep(2)
        text = next(n.get("text") for n in hierarchy().iter("node")
                    if re.match(r"^Documentos · \d+ archivos$", n.get("text") or ""))
        return int(re.search(r"(\d+)", text).group(1))

    try:
        before = count()
        assert before >= 2, f"La categoría Documentos casi no tiene archivos de prueba: {before}"
        settings("Documentos")
        set_switch("Texto (.txt y .md)", False)
        after = count()
        evidence("documentos-sin-texto")
        assert after < before, f"Quitar el texto no quitó nada de Documentos: {before} → {after}"
    finally:
        settings("Documentos")
        set_switch("Texto (.txt y .md)", True)
        time.sleep(2)


@check("inicio-buscador-de-archivos")
def home_search():
    try:
        launch_home()
        wait("Categorías")
        fill("Buscar archivos…", "buscar_me")
        tap("Buscar en todo el almacenamiento")
        wait_text("«buscar_me»")
        wait("buscar_me.txt", 40)
        evidence("inicio-buscador-resultados")
        # Ajustes → «Pantalla de inicio» lo puede ocultar.
        settings("Pantalla de inicio")
        set_switch("Mostrar el buscador en Inicio", False)
        launch_home()
        wait("Categorías")
        assert not nodes("Buscar archivos…", hierarchy()), "El buscador sigue en Inicio aunque se ocultó"
    finally:
        settings("Pantalla de inicio")
        set_switch("Mostrar el buscador en Inicio", True)
        time.sleep(2)


@check("aviso-de-permisos-al-instalar-una-app")
def install_permission_notice():
    package, label = "com.omaritoinforma.prueba.permisos", "Prueba permisos"
    folder = f"{DIR}/apk-permisos"
    sh("pm", "uninstall", package, check=False)
    sh("rm", "-rf", q(folder), check=False)
    with tempfile.TemporaryDirectory() as tmp:
        apk = build_test_apk(pathlib.Path(tmp), package, label,
                             permissions=("android.permission.CAMERA", "android.permission.ACCESS_FINE_LOCATION"))
        adb("push", str(apk), f"{folder}/permisos.apk")
    adb("shell", "appops", "set", ui.PACKAGE, "REQUEST_INSTALL_PACKAGES", "allow")
    adb("shell", "pm", "grant", ui.PACKAGE, "android.permission.POST_NOTIFICATIONS")
    try:
        open_test_folder()
        tap_node(find("apk-permisos"))
        long_press("permisos.apk")
        menu_option("Instalar APK")
        answer_system_dialogs(["Install", "INSTALL", "Install anyway", "Don't send"],
                              lambda: installed(package), "No se instaló la app de prueba")
        until(lambda: "Permisos de una app nueva" in sh("dumpsys", "notification", "--noredact", check=False),
              "No salió el aviso de permisos de la app instalada", 30)
        shown = sh("dumpsys", "notification", "--noredact", check=False)
        assert "Prueba permisos" in shown, "El aviso no nombra la app"
        assert "Cámara" in shown and "Ubicación" in shown, "El aviso no nombra los permisos"
        evidence("aviso-permisos-instalacion")
    finally:
        sh("pm", "uninstall", package, check=False)


@check("notificacion-fija-con-el-uso-del-almacenamiento")
def storage_notification():
    def shown():
        out = sh("dumpsys", "notification", "--noredact", check=False)
        return "usado)" in out and "libres de" in out and "Almacenamiento" in out

    adb("shell", "pm", "grant", ui.PACKAGE, "android.permission.POST_NOTIFICATIONS")
    try:
        settings("Notificaciones")
        set_switch("Mostrar el uso del almacenamiento", True)
        until(shown, "No salió la notificación del uso del almacenamiento", 45)
        (OUTPUT / "notificacion-almacenamiento.txt").write_text(
            "\n".join(line for line in sh("dumpsys", "notification", "--noredact", check=False).splitlines()
                      if "libres de" in line or "Almacenamiento" in line), encoding="utf-8")
        set_switch("Mostrar el uso del almacenamiento", False)
        until(lambda: not shown(), "La notificación siguió al apagar el ajuste", 45)
    finally:
        settings("Notificaciones")
        set_switch("Mostrar el uso del almacenamiento", False)
        time.sleep(2)


@check("boton-de-pestanas-en-la-barra")
def tabs_button():
    def close_buttons():
        return [n for n in hierarchy().iter("node")
                if (n.get("content-desc") or "").startswith("Cerrar la pestaña")]

    try:
        settings("Pantalla")
        set_switch("Mostrar el botón de pestañas", True)
        open_test_folder()
        tap("Pestañas")
        wait("Pestañas abiertas")
        assert len(close_buttons()) == 1, "Con una sola pestaña, la lista debía tener una"
        tap("Nueva pestaña")
        until(lambda: nodes("Pestañas", hierarchy()), "No volvió la pantalla tras abrir otra pestaña", 15)
        tap("Pestañas")
        wait("Pestañas abiertas")
        assert len(close_buttons()) == 2, "Tras «Nueva pestaña» la lista debía tener dos"
        evidence("boton-de-pestanas-dos")
        tap_node(close_buttons()[0])
        time.sleep(1)
        assert len(close_buttons()) == 1, "La X no cerró la pestaña"
        tap("Cerrar")
    finally:
        settings("Pantalla")
        set_switch("Mostrar el botón de pestañas", False)
        time.sleep(2)


@check("informe-diario-de-archivos-nuevos")
def daily_report():
    folder = "/sdcard/DCIM/OIInforme"
    sh("rm", "-rf", q(folder), check=False)
    settings("Notificaciones")
    try:
        set_switch("Informe diario de archivos nuevos", True)
        time.sleep(3)
        # Tres archivos nuevos: dos imágenes y uno de otro tipo.
        push_bytes(png((255, 0, 0)), f"{folder}/uno.png")
        push_bytes(png((0, 255, 0)), f"{folder}/dos.png")
        push_bytes(b"\x00" * 2000, f"{folder}/raro.oi")
        sh("content", "call", "--uri", "content://media", "--method", "scan_volume",
           "--arg", "external_primary", check=False)
        time.sleep(3)
        tap("Ver el informe ahora")
        until(lambda: notification_shown(33), "No llegó el informe de archivos nuevos", 90)
        out = sh("dumpsys", "notification", "--noredact", check=False)
        block = out[out.index(f"|{ui.PACKAGE}|33|"):][:3000]
        (OUTPUT / "informe-diario-notificacion.txt").write_text(block, encoding="utf-8")
        assert "Informe de archivos nuevos" in block, "La notificación no tiene el título del informe"
        assert re.search(r"\b[3-9]\d* archivos nuevos", block), f"El informe no cuenta los archivos nuevos: {block[:600]}"
        assert "2 imágenes" in block or re.search(r"[2-9]\d* imágenes", block), "El informe no cuenta las imágenes"
        assert "uno.png" not in block, "El informe no debe nombrar los archivos"
        # Tocar la notificación abre «Recientes».
        adb("shell", "cmd", "statusbar", "expand-notifications")
        time.sleep(1)
        tap(find_text("Informe de archivos nuevos"))
        wait("Recientes")
    finally:
        adb("shell", "cmd", "statusbar", "collapse", check=False)
        settings("Notificaciones")
        set_switch("Informe diario de archivos nuevos", False)
        sh("rm", "-rf", q(folder), check=False)


@check("doble-panel-arrastrar-entre-paneles")
def dual_pane_drag():
    origin, target = f"{DIR}/arrastre-origen", f"{DIR}/arrastre-destino"
    for folder in (origin, target):
        sh("rm", "-rf", q(folder), check=False)
        sh("mkdir", "-p", q(folder))
    push_bytes(b"arrastrado", f"{origin}/mover.txt")
    open_test_folder()
    tap(find("arrastre-origen").get("text"))
    tap("Más opciones")
    tap("Doble panel")
    wait("mover.txt")
    # El panel derecho empieza en el almacenamiento: se baja hasta la carpeta de destino.
    for step in ("Download", "OIPrueba", "arrastre-destino"):
        tap(find(step).get("text"))
        time.sleep(1)
    handle = nodes("Mantén pulsado y arrastra al otro panel", hierarchy())[0]
    x1, y1 = center(handle)
    # Se suelta sobre el panel derecho.
    adb("shell", "input", "draganddrop", str(x1), str(y1), "810", "1200", "1800")
    wait("Mover aquí", timeout=15)
    evidence("doble-panel-soltado")
    tap("Mover aquí")
    until(lambda: read(f"{target}/mover.txt") == "arrastrado", "El archivo arrastrado no llegó al otro panel", 30)
    until(lambda: not exists(f"{origin}/mover.txt"), "Al mover, el original debe desaparecer", 15)


if os.environ.get("OI_REMOTE_TEST_ROOT"):

    @check("archivo-remoto-editado-se-sube-solo")
    def remote_edit_syncs_back():
        """Abre un archivo del SFTP de CI, lo edita y comprueba en el disco del servidor que el cambio
        subió solo; después provoca un conflicto (el servidor cambia mientras se edita) y elige «Subir como copia»."""
        server = pathlib.Path(os.environ["OI_REMOTE_TEST_ROOT"])
        server.mkdir(parents=True, exist_ok=True)
        remote = server / "editar-oi.txt"
        for leftover in server.glob("editar-oi*"):
            leftover.unlink()
        remote.write_text("original")

        def open_and_edit(text):
            launch_home()
            ui.drawer("Red, nube y USB")
            tap("SFTP prueba")
            tap(find("editar-oi.txt").get("text"))
            field, _ = wait_any_node("original", "cambiado en el servidor")
            tap_node(field)
            adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
            adb("shell", "input", "text", text)
            time.sleep(1)
            for _ in range(4):
                if nodes("Cambios sin guardar", hierarchy()):
                    break
                adb("shell", "input", "keyevent", "4")
                time.sleep(1)
            tap_last("Guardar")

        def wait_any_node(*texts):
            deadline = time.monotonic() + 40
            while time.monotonic() < deadline:
                for n in hierarchy().iter("node"):
                    if n.get("class") == "android.widget.EditText" and any(t in (n.get("text") or "") for t in texts):
                        return n, None
                time.sleep(0.5)
            raise AssertionError(f"El editor no mostró el archivo remoto: {texts}")

        def remote_text(path):
            # La app sustituye el archivo del servidor sin perderlo nunca (sube aparte, borra y renombra):
            # justo entre medias el nombre no existe un instante.
            try:
                return path.read_text()
            except FileNotFoundError:
                return None

        open_and_edit("-editado")
        until(lambda: remote_text(remote) == "original-editado", "El cambio no subió solo al servidor", 90)
        assert not list(server.glob("editar-oi (*")), "Quedó un archivo temporal en el servidor"
        # Conflicto: el servidor cambia mientras se edita.
        launch_home()
        ui.drawer("Red, nube y USB")
        tap("SFTP prueba")
        tap(find("editar-oi.txt").get("text"))
        wait_any_node("original-editado")
        remote.write_text("cambiado en el servidor por otra persona")
        field, _ = wait_any_node("original-editado")
        tap_node(field)
        adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
        adb("shell", "input", "text", "-v2")
        time.sleep(1)
        for _ in range(4):
            if nodes("Cambios sin guardar", hierarchy()):
                break
            adb("shell", "input", "keyevent", "4")
            time.sleep(1)
        tap_last("Guardar")
        wait_text("cambió en el servidor", timeout=60)
        evidence("archivo-remoto-conflicto")
        assert remote.read_text() == "cambiado en el servidor por otra persona", "Se pisó el archivo sin preguntar"
        tap("Subir como copia")
        copy = server / "editar-oi (editado).txt"
        until(lambda: remote_text(copy) == "original-editado-v2", "No se subió la copia", 90)
        assert remote.read_text() == "cambiado en el servidor por otra persona", "La copia no debe tocar el original"


if os.environ.get("OI_REMOTE_TEST_NFS_EXPORT"):

    @check("conexion-nfs-real-listar-y-abrir")
    def nfs_connection():
        """Conexión NFS desde la interfaz contra nfs-ganesha en el equipo de CI (10.0.2.2): listar y abrir un archivo."""
        server = pathlib.Path(os.environ["OI_REMOTE_TEST_ROOT"])
        (server / "nfs-hola-oi.txt").write_text("hola-desde-nfs-oi")
        launch_home()
        ui.drawer("Red, nube y USB")
        tap("Agregar")
        ui.keyboards(enable=False)
        try:
            fill("Nombre de la conexión", "NFS prueba")
            tap("NFS")
            fill("Servidor", "10.0.2.2")
            fill("Usuario y grupo uid:gid", "0:0")
            fill("Ruta exportada /srv/datos", os.environ["OI_REMOTE_TEST_NFS_EXPORT"], current="/")
            evidence("conexion-nfs-formulario")
            tap("Guardar")
            until(lambda: not nodes("Nueva conexión", hierarchy()), "El diálogo no se cerró al guardar", 15)
        finally:
            ui.keyboards(enable=True)
        tap("NFS prueba")
        find("nfs-hola-oi.txt")
        evidence("conexion-nfs-listado")
        tap(find("nfs-hola-oi.txt").get("text"))
        wait_text("hola-desde-nfs-oi", 60)


@check("analizar-apps-permisos-delicados")
def app_analysis():
    package = "com.omaritoinforma.prueba.permisos"
    sh("pm", "uninstall", package, check=False)
    with tempfile.TemporaryDirectory() as tmp:
        apk = build_test_apk(pathlib.Path(tmp), package, "Prueba permisos",
                             ("android.permission.CAMERA", "android.permission.ACCESS_FINE_LOCATION",
                              "android.permission.READ_SMS", "android.permission.INTERNET"))
        adb("install", "-r", str(apk))
    try:
        launch_home()
        ui.drawer("Aplicaciones")
        tap("Más")
        tap("Analizar permisos")
        wait("Analizar apps")
        # Sin filtro: la app de prueba aparece con lo que pide (INTERNET no es delicado y no se nombra).
        wait("Prueba permisos")
        tree = hierarchy()
        row = next(n for n in tree.iter("node") if (n.get("text") or "").startswith("Solicitado:") and "Cámara" in n.get("text"))
        text = row.get("text")
        assert "Ubicación" in text and "SMS" in text, f"Faltan permisos en el análisis: {text}"
        assert "Internet" not in text, f"INTERNET no es delicado: {text}"
        # Filtrar por «Cámara»: sigue estando; por «Calendario»: ya no (nadie lo pide).
        tap(find_text("Cámara ("))
        wait("Prueba permisos")
        evidence("analizar-apps-camara")
        assert not nodes("OI Archivos", hierarchy()), "OI Archivos no pide la cámara y no debe salir en ese filtro"
        tap(find_text("Cámara ("))  # quitar el filtro
        tap(find_text("SMS ("))
        wait("Prueba permisos")
    finally:
        sh("pm", "uninstall", package, check=False)


@check("ocultar-y-lista-de-ocultos-con-contrasena")
def hide_and_hidden_list():
    for name in ("ocultar-oi.txt", ".ocultar-oi.txt"):
        sh("rm", "-f", q(f"{DIR}/{name}"), check=False)
    push_bytes(b"secreto", f"{DIR}/ocultar-oi.txt")
    try:
        open_test_folder()
        find("ocultar-oi.txt")
        long_press("ocultar-oi.txt")
        menu_option("Ocultar")
        until(lambda: exists(f"{DIR}/.ocultar-oi.txt") and not exists(f"{DIR}/ocultar-oi.txt"),
              "El archivo no se ocultó (nombre con punto)", 20)
        assert read(f"{DIR}/.ocultar-oi.txt") == "secreto", "Ocultar no debe cambiar el contenido"
        # Ya no sale en el explorador, pero sí en la lista de ocultos, con su nombre de siempre.
        open_test_folder()
        assert not nodes("ocultar-oi.txt", hierarchy()) and not nodes(".ocultar-oi.txt", hierarchy()), \
            "Un archivo oculto sigue saliendo en el explorador"
        launch_home()
        ui.drawer("Lista de ocultos")
        wait("ocultar-oi.txt")
        # Con «Proteger los archivos ocultos», la lista pide la contraseña.
        settings("Contraseña")
        tap("Proteger los archivos ocultos")
        create_password()
        launch_home()
        ui.drawer("Lista de ocultos")
        wait_text("«Lista de ocultos» está protegido con contraseña")
        assert not nodes("ocultar-oi.txt", hierarchy()), "La lista de ocultos se veía sin la contraseña"
        fill("Contraseña", "equivocada", verify=False)
        tap("Aceptar")
        wait_text("Contraseña incorrecta")
        fill("Contraseña", PASSWORD_APP, current="equivocada", verify=False)
        tap("Aceptar")
        wait("ocultar-oi.txt")
        # Mostrar de nuevo.
        tap("Mostrar")
        until(lambda: exists(f"{DIR}/ocultar-oi.txt") and not exists(f"{DIR}/.ocultar-oi.txt"),
              "«Mostrar» no devolvió el nombre", 20)
    finally:
        remove_password()
        for name in ("ocultar-oi.txt", ".ocultar-oi.txt"):
            sh("rm", "-f", q(f"{DIR}/{name}"), check=False)


@check("enviar-por-codigo-qr")
def nearby_qr():
    # 1) El teléfono que recibe muestra su código QR y el enlace que contiene.
    launch_home()
    ui.drawer("Red, nube y USB")
    tap("Enviar a otro teléfono")
    tap("Empezar a recibir")
    wait("Código QR")
    link = find_text("oiarchivos://enviar?")
    evidence("codigo-qr-del-receptor")
    match = re.match(r"oiarchivos://enviar\?host=([\d.]+)&port=(\d+)&nombre=", link)
    assert match, f"El enlace del QR no tiene el formato esperado: {link}"
    assert match.group(1).startswith("10.0.2."), f"El QR no lleva la dirección del emulador: {link}"
    assert match.group(2) == "42137", link
    # El QR dibujado es un cuadrado con módulos negros: se comprueba que hay contraste (no está en blanco).
    qr = next(n for n in hierarchy().iter("node") if n.get("content-desc") == "Código QR")
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", qr.get("bounds")))
    dark = sum(1 for dx in range(8) for dy in range(8)
               if sum(pixel(x1 + (x2 - x1) * (2 * dx + 1) // 16, y1 + (y2 - y1) * (2 * dy + 1) // 16)) < 150)
    assert 8 <= dark <= 56, f"El código QR no parece un QR ({dark} de 64 puntos oscuros)"
    # 2) El otro teléfono, tras leerlo con su cámara, abre este enlace: envía a quien lo mostró.
    received = {}
    server = nearby_peer_server(received)
    try:
        payload = "enviado tras leer un QR ñ"
        with tempfile.TemporaryDirectory() as tmp:
            local = pathlib.Path(tmp) / "qr_envio.txt"
            local.write_text(payload, encoding="utf-8")
            adb("push", str(local), f"{DIR}/qr_envio.txt")
        open_test_folder()
        long_press(find("qr_envio.txt").get("text"))
        more("Enviar a otro teléfono")
        wait("Empezar a recibir")
        adb("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d",
            "oiarchivos://enviar?host=10.0.2.2\\&port=42137\\&nombre=PC%20por%20QR", ui.PACKAGE)
        wait_text("Enviar a «PC por QR»")
        evidence("codigo-qr-confirmar-envio")
        tap_last("Enviar")
        until(lambda: 0 in received, "El archivo no llegó tras leer el QR", 60)
        assert received[0] == payload.encode("utf-8"), received[0]
        assert received["offer"]["files"][0]["name"] == "qr_envio.txt"
        # 3) Un QR con una dirección de Internet no abre nada.
        adb("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d",
            "oiarchivos://enviar?host=8.8.8.8\\&port=42137\\&nombre=Malo", ui.PACKAGE)
        time.sleep(3)
        assert not any("Malo" in (n.get("text") or "") for n in hierarchy().iter("node")), "Se aceptó un QR de fuera de la red local"
    finally:
        server.shutdown()


def center_y(node):
    y1, y2 = (int(v) for v in re.findall(r"\d+", node.get("bounds"))[1::2])
    return (y1 + y2) // 2


@check("limpiar-carpetas-que-deja-una-app")
def clean_leftover_folders():
    package, label = "com.omaritoinforma.prueba.limpia", "Prueba limpia"
    # Lo que una app crea por su cuenta en la raíz del almacenamiento: Android no lo borra al desinstalarla.
    leftover, other = "/sdcard/Prueba limpia", "/sdcard/Prueba limpia vieja"
    sh("pm", "uninstall", package, check=False)
    sh("rm", "-rf", q(leftover), q(other), check=False)
    with tempfile.TemporaryDirectory() as tmp:
        adb("install", "-r", str(build_test_apk(pathlib.Path(tmp), package, label)), timeout=180)
    assert installed(package), "No se instaló la app de prueba"
    sh("mkdir", "-p", q(leftover), q(other))
    sh("echo", "datos", ">", q(f"{leftover}/datos.txt"))
    sh("echo", "otra", ">", q(f"{other}/otra.txt"))
    try:
        settings("Aplicaciones")
        set_switch("Copia antes de desinstalar", False)
        set_switch("Limpiar carpetas al desinstalar", True)
        launch_home()
        ui.drawer("Aplicaciones")
        fill("Buscar app…", "Prueba limp")
        wait(label)
        tap("Opciones")
        tap("Desinstalar")
        answer_system_dialogs(["OK"], lambda: not installed(package), "No se desinstaló la app de prueba")
        # OI Archivos propone solo la carpeta con el nombre exacto de la app, no la que se le parece.
        wait(f"Carpetas que dejó «{label}»", timeout=60)
        tree = hierarchy()
        assert nodes(f"{INTERNAL}/Prueba limpia", tree), "No se propuso la carpeta de la app"
        assert not nodes(f"{INTERNAL}/Prueba limpia vieja", tree), "Se propuso una carpeta que no es de la app"
        evidence("carpetas-que-deja-una-app")
        tap("Mover a la papelera")
        until(lambda: not exists(leftover), "La carpeta no se movió a la papelera", 60)
        assert read(f"{other}/otra.txt").strip() == "otra", "Se tocó una carpeta que no era de la app"
        # Va a la papelera y desde ahí se recupera entera.
        time.sleep(2)
        launch_home()
        ui.drawer("Papelera")
        row, tree = wait(label)
        tap_node(min(nodes("Restaurar", tree), key=lambda n: abs(center_y(n) - center_y(row))))
        until(lambda: read(f"{leftover}/datos.txt").strip() == "datos",
              "La carpeta no se recuperó desde la papelera", 30)
    finally:
        sh("pm", "uninstall", package, check=False)
        sh("rm", "-rf", q(leftover), q(other), check=False)


def restore_spanish():
    """Deja la app en español aunque la comprobación del idioma haya fallado a medias."""
    for _ in range(3):
        try:
            ui.launch()
            tree = wait_any("Categorías", "Menú", "Categories", "Menu", timeout=60)
            if nodes("Categorías", tree) or nodes("Menú", tree):
                return
            tap("Menu")
            wait("Settings")
            tap("Settings")
            tap(find("Display").get("text"))
            tap("Español")
            wait("Idioma:", timeout=30)
            return
        except Exception:
            traceback.print_exc()
    raise AssertionError("No se pudo volver a poner la app en español")


@check("idioma-de-la-app-ingles-y-vuelta")
def app_language():
    settings("Pantalla")
    wait("Idioma:")
    tap("English")
    try:
        # Al cambiar el idioma la pantalla se vuelve a crear y sigue en Ajustes → Pantalla, ya en inglés.
        wait("Language:", timeout=30)
        wait("Screen orientation:")
        assert not nodes("Idioma:", hierarchy()), "Siguen textos en español"
        evidence("idioma-ingles-ajustes")
        adb("shell", "input", "keyevent", "4")
        wait("Display")
        wait("Security")
        adb("shell", "input", "keyevent", "4")
        # Inicio y el menú lateral (sus nombres vienen de listas fijas que se traducen al mostrarse).
        wait("Categories", timeout=30)
        tap("Menu")
        wait("Settings")
        wait("Trash")
        evidence("idioma-ingles-menu")
        # Tras reiniciar la app sigue en inglés: el idioma se aplica al arrancar el proceso.
        ui.launch()
        tree = wait_any("Categories", "Menu", "Categorías", "Menú", timeout=60)
        assert not (nodes("Categorías", tree) or nodes("Menú", tree)), "Al reiniciar volvió al español"
    finally:
        restore_spanish()
    settings("Pantalla")
    wait("Idioma:")
    wait("Orientación de la pantalla:")


def main():
    adb("shell", "appops", "set", ui.PACKAGE, "MANAGE_EXTERNAL_STORAGE", "allow")
    seed()
    ui.keyboards(enable=False)
    # OI_SHARD="k/n": solo se ejecuta el k-ésimo de n bloques contiguos de comprobaciones (cada uno en un emulador nuevo).
    selected = CHECKS
    shard = os.environ.get("OI_SHARD")
    if shard:
        k, n = (int(v) for v in shard.split("/"))
        size = -(-len(CHECKS) // n)
        selected = CHECKS[(k - 1) * size:k * size]
        print(f"Bloque {k} de {n}: {len(selected)} de {len(CHECKS)} comprobaciones", flush=True)
    try:
        for run in selected:
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
