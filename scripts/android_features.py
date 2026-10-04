"""Check OI Archivos features one by one on Android, as listed in COMPARACION_ES.md.

Every check runs on its own: a failure is recorded with a screenshot and the UI
hierarchy, and the next check still runs, so one emulator run reports every feature.
Results are verified on the device's disk, not only on screen. Run after
smoke_android.py with the APK installed and storage permission granted.
"""

import hashlib
import json
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
    rar = ROOT / "app/src/test/resources/archives/test_read_format_rar5_encrypted_filenames.rar"
    adb("push", str(rar), f"{DIR}/cifrado.rar")


def open_test_folder():
    ui.launch()
    wait("Categorías")
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
    """Taps the last visible match, e.g. a dialog button whose title has the same text."""
    wait(label)
    tap_node(nodes(label, hierarchy())[-1])


def wait_text(fragment, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if any(fragment.lower() in (n.get("text") or "").lower() for n in hierarchy().iter("node")):
            return
        time.sleep(0.5)
    raise AssertionError(f"Text not shown: {fragment}")


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
    # The dialog selects the base name, so typing keeps the extension.
    adb("shell", "input", "text", "renombrado")
    tap_last("Renombrar")
    until(lambda: read(f"{DIR}/renombrado.txt") == "contenido c", "No se renombró")


@check("papelera-eliminar-restaurar-vaciar")
def trash():
    open_test_folder()
    long_press("t.txt")
    tap("Eliminar")
    tap("Mover a la papelera (se puede restaurar)")
    until(lambda: not exists(f"{DIR}/t.txt"), "No se movió a la papelera")
    ui.drawer("Papelera")
    wait("t.txt")
    tap("Restaurar")
    until(lambda: read(f"{DIR}/t.txt") == "contenido t", "No se restauró")
    open_test_folder()
    long_press("t.txt")
    tap("Eliminar")
    tap("Mover a la papelera (se puede restaurar)")
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
    fill("Contraseña", PASSWORD)
    tap("Continuar")
    until(lambda: exists(f"{DIR}/a.txt.oienc"), "No se creó el archivo cifrado")
    assert "contenido a" not in read(f"{DIR}/a.txt.oienc"), "El cifrado contiene el texto"
    open_test_folder()
    long_press("a.txt.oienc")
    more("Descifrar con contraseña")
    fill("Contraseña", PASSWORD)
    tap("Continuar")
    until(lambda: read(f"{DIR}/a (1).txt") == "contenido a", "El descifrado no coincide")


@check("7z-cifrado-crear-y-extraer-en-android")
def seven_zip():
    open_test_folder()
    long_press("a.txt")
    more("Comprimir en ZIP")
    fill("Nombre: .zip, .7z, .tar o .tar.gz", "prueba.7z", clear=True)
    fill("Contraseña opcional (AES)", PASSWORD)
    tap("Comprimir")
    until(lambda: exists(f"{DIR}/prueba.7z"), "No se creó el 7z", timeout=60)
    head = sh("head", "-c", "6", q(f"{DIR}/prueba.7z"), "|", "od", "-An", "-tx1").split()
    assert head == ["37", "7a", "bc", "af", "27", "1c"], f"No es un 7z: {head}"
    open_test_folder()
    tap("prueba.7z")
    fill("Contraseña (si corresponde)", PASSWORD)
    tap("Abrir")
    wait("a.txt")
    tap("Extraer en carpeta nueva")
    until(lambda: read(f"{DIR}/prueba/a.txt") == "contenido a", "El 7z no se extrajo", 60)


@check("rar5-cifrado-extraer-en-android")
def rar():
    open_test_folder()
    tap("cifrado.rar")
    fill("Contraseña (si corresponde)", "password")
    tap("Abrir")
    wait("d.txt")
    tap("Extraer en carpeta nueva")
    for name in ("a", "b", "c", "d"):
        until(
            lambda: read(f"{DIR}/cifrado/{name}.txt").strip() == f"This is from {name}.txt",
            f"RAR: {name}.txt no se extrajo",
            60)


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
