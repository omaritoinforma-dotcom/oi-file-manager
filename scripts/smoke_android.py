"""Exercise the built APK on a disposable Android 15 emulator, saving visible evidence.

Uses seeded files, the app's own HTTP server and a real SFTP server on the runner
(scripts/remote_servers.py, reached at 10.0.2.2). Cloud accounts, USB and root still
need separate device/account verification. Run with an APK in ./apk/ and adb ready.
"""

import base64
import hashlib
import json
import os
import pathlib
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

PACKAGE = "com.omaritoinforma.oiarchivos"
OUTPUT = pathlib.Path("smoke-output")
OUTPUT.mkdir(exist_ok=True)
CHECKS = []


def adb(*args, check=True):
    return subprocess.run(
        ["adb", *args], capture_output=True, text=True, check=check, timeout=30
    ).stdout


def hierarchy():
    adb("shell", "uiautomator", "dump", "/sdcard/oi-smoke.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/oi-smoke.xml"))


def nodes(label, tree):
    return [
        n for n in tree.iter("node")
        if label in (n.get("text"), n.get("content-desc"))
        and n.get("enabled") == "true"
    ]


def dismiss_system_anr(tree):
    """A slow emulator can show "<other app> isn't responding" over the app; keep waiting."""
    if any("isn't responding" in (n.get("text") or "") for n in tree.iter("node")):
        for n in nodes("Wait", tree):
            if n.get("package") == "android":
                tap_node(n)
                return True
    return False


def wait(label, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        tree = hierarchy()
        found = nodes(label, tree)
        if found:
            return found[0], tree
        if dismiss_system_anr(tree):
            continue
        time.sleep(0.5)
    raise AssertionError(f"Visible control not found: {label}")


def tap_node(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def tap(label):
    tap_node(wait(label)[0])


def checkpoint(name, label):
    _, tree = wait(label)
    ET.ElementTree(tree).write(OUTPUT / f"{name}.xml", encoding="utf-8")
    png = subprocess.check_output(["adb", "exec-out", "screencap", "-p"], timeout=30)
    (OUTPUT / f"{name}.png").write_bytes(png)
    CHECKS.append(name)
    print(f"PASS: {name}", flush=True)


def launch():
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")


def drawer(label):
    tap("Menú")
    for _ in range(5):
        tree = hierarchy()
        found = nodes(label, tree)
        if found:
            tap_node(found[0])
            return
        adb("shell", "input", "swipe", "280", "1600", "280", "500", "400")
    raise AssertionError(f"Drawer item not found: {label}")


def back_home():
    adb("shell", "input", "keyevent", "4")
    wait("Categorías")


def seed_files():
    with tempfile.TemporaryDirectory() as tmp:
        folder = pathlib.Path(tmp)
        (folder / "smoke.txt").write_text("smoke_original", encoding="utf-8")
        with zipfile.ZipFile(folder / "smoke.zip", "w") as archive:
            archive.writestr("alpha.txt", "archive payload")
            archive.writestr("nested/beta.txt", "nested payload")
        (folder / "smoke.png").write_bytes(base64.b64decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aYV0AAAAASUVORK5CYII="
        ))
        stream = b"BT /F1 18 Tf 30 100 Td (OI PDF smoke) Tj ET"
        objects = [
            b"<< /Type /Catalog /Pages 2 0 R >>",
            b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 250 150] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream",
        ]
        pdf = bytearray(b"%PDF-1.4\n")
        offsets = [0]
        for i, obj in enumerate(objects, 1):
            offsets.append(len(pdf))
            pdf.extend(f"{i} 0 obj\n".encode() + obj + b"\nendobj\n")
        xref = len(pdf)
        pdf.extend(b"xref\n0 6\n0000000000 65535 f \n")
        for offset in offsets[1:]:
            pdf.extend(f"{offset:010} 00000 n \n".encode())
        pdf.extend(f"trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
        (folder / "smoke.pdf").write_bytes(pdf)
        adb("shell", "mkdir", "-p", "/sdcard/Download")
        for file in folder.iterdir():
            adb("push", str(file), f"/sdcard/Download/{file.name}")


def keyboards(enable):
    """The soft keyboard covers lower fields and is missing from the app's hierarchy dump, so
    taps meant for a field type keys instead. `input text` sends hardware key events, which
    Compose fields accept without a keyboard app, so the keyboard apps are disabled meanwhile."""
    global KEYBOARD_PACKAGES
    if not enable:
        KEYBOARD_PACKAGES = sorted({i.split("/")[0] for i in adb("shell", "ime", "list", "-s").split()})
    for package in KEYBOARD_PACKAGES:
        adb("shell", "pm", "enable" if enable else "disable-user", "--user", "0", package)
    time.sleep(1)


KEYBOARD_PACKAGES = []


def field_texts():
    return [n.get("text") for n in hierarchy().iter("node") if n.get("class") == "android.widget.EditText"]


def fill(label, value, current=None):
    """Types into the dialog field found by label, hint or current value, scrolling if needed."""
    for _ in range(6):
        tree = hierarchy()
        found = nodes(label, tree) or [
            n for n in tree.iter("node")
            if n.get("enabled") == "true"
            and (n.get("hint") == label or (current is not None and n.get("text") == current))
        ]
        if found:
            tap_node(found[0])
            if current is not None:
                adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
                for _ in range(8):
                    adb("shell", "input", "keyevent", "KEYCODE_DEL")
            adb("shell", "input", "text", "'" + value + "'")
            return
        adb("shell", "input", "swipe", "540", "1300", "540", "700", "300")
    raise AssertionError(f"Field not found: {label}")


def remote_size(path):
    out = adb("shell", "stat", "-c", "%s", path, check=False).strip()
    return int(out) if out.isdigit() else -1


def verify_network_resume():
    """Real SFTP server on the runner (10.0.2.2): kill the app mid-download, then resume."""
    root = pathlib.Path(os.environ["OI_REMOTE_TEST_ROOT"])
    payload = os.urandom(24 * 1024 * 1024)
    (root / "reanudar.bin").write_bytes(payload)
    expected = hashlib.sha256(payload).hexdigest()
    target = "/sdcard/Download/OI Archivos/reanudar.bin"
    adb("shell", "rm", "-rf", "'/sdcard/Download/OI Archivos'")
    launch()
    wait("Categorías")
    drawer("Red, nube y USB")
    tap("Agregar")
    keyboards(enable=False)
    fill("Nombre de la conexión", "SFTP prueba")
    fill("Servidor", "10.0.2.2")
    fill("Puerto", os.environ["OI_REMOTE_TEST_SFTP_PORT"], current="22")
    fill("Usuario", "oi")
    fill("Contraseña", os.environ["OI_REMOTE_TEST_PASSWORD"])
    fill("Huella del servidor SHA256:…", os.environ["OI_REMOTE_TEST_SFTP_FINGERPRINT"])
    texts = field_texts()
    for expected_text in (
        "SFTP prueba",
        "10.0.2.2",
        os.environ["OI_REMOTE_TEST_SFTP_PORT"],
        "oi",
        os.environ["OI_REMOTE_TEST_SFTP_FINGERPRINT"],
    ):
        assert expected_text in texts, f"Field not filled as expected: {expected_text} in {texts}"
    tap("Guardar")
    deadline = time.monotonic() + 10
    while nodes("Nueva conexión", hierarchy()):
        assert time.monotonic() < deadline, "Connection dialog did not close after saving"
        time.sleep(0.5)
    keyboards(enable=True)
    tap("SFTP prueba")
    node, _ = wait("reanudar.bin")
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "swipe", *[str(v) for v in ((x1 + x2) // 2, (y1 + y2) // 2) * 2], "900")
    tap("Descargar")
    deadline = time.monotonic() + 90
    part = ""
    while not part:
        assert time.monotonic() < deadline, "Download did not start"
        listing = adb("shell", "ls", "-a", "'/sdcard/Download/OI Archivos/'", check=False).split()
        names = [n for n in listing if n.startswith(".oi-resume-") and n.endswith(".part")]
        if names and remote_size("'/sdcard/Download/OI Archivos/" + names[0] + "'") > 1024 * 1024:
            part = names[0]
        time.sleep(0.5)
    checkpoint("15-network-download-running", "reanudar.bin")
    # Process death in the middle of the transfer, as when Android reclaims the app.
    adb("shell", "am", "force-stop", PACKAGE)
    partial = remote_size("'/sdcard/Download/OI Archivos/" + part + "'")
    assert 0 < partial < len(payload), partial
    assert remote_size("'" + target + "'") < 0, "Final file must not exist before completion"
    launch()
    wait("Categorías")
    drawer("Transferencias")
    checkpoint("16-network-download-pending", "Reanudar")
    tap("Reanudar")
    deadline = time.monotonic() + 240
    while remote_size("'" + target + "'") != len(payload):
        assert time.monotonic() < deadline, "Resumed download did not finish"
        time.sleep(1)
    digest = adb("shell", "sha256sum", "'" + target + "'").split()[0]
    assert digest == expected, "Resumed download differs from the server file"
    assert (root / "reanudar.bin").exists(), "A copy must keep the server original"
    leftovers = set(adb("shell", "ls", "-a", "'/sdcard/Download/OI Archivos/'").split()) - {".", ".."}
    assert leftovers == {"reanudar.bin"}, leftovers
    CHECKS.append("network-download-survives-process-death")
    print(f"PASS: network-download-survives-process-death (partial {partial} bytes)", flush=True)
    back_home()


def verify_http():
    tap("Navegador / Wi-Fi")
    _, tree = wait("Detener servidor")
    text = "\n".join(n.get("text", "") for n in tree.iter("node"))
    address, port = re.search(r"http://([^:]+):(\d+)/", text).groups()
    password = re.search(r"Contraseña: (\S+)", text).group(1)
    auth = "Basic " + base64.b64encode(f"oi:{password}".encode()).decode()

    # Request the actual selected interface from inside Android. Emulator console
    # redirection targets eth0; the server may intentionally bind the Wi-Fi IP.
    def request(path, authorized=True, body=None, content_type=None):
        method = "POST" if body is not None else "GET"
        headers = [f"{method} {path} HTTP/1.1", f"Host: {address}:{port}", "Connection: close"]
        if authorized:
            headers.append("Authorization: " + auth)
        if body is not None:
            headers += [f"Content-Length: {len(body)}", "Content-Type: " + content_type]
        raw = ("\r\n".join(headers) + "\r\n\r\n").encode() + (body or b"")
        response = subprocess.run(["adb", "shell", "toybox", "nc", "-w", "10", address, port], input=raw, capture_output=True, timeout=30, check=True).stdout
        header, payload = response.split(b"\r\n\r\n", 1)
        return int(header.split(b" ", 2)[1]), payload

    try:
        assert request("/", authorized=False)[0] == 401
        status, payload = request("/smoke.txt")
        assert status == 200 and b"_changed" in payload
        assert request("/../oi-smoke.xml")[0] in (400, 403, 404)
        boundary = "OI-Android-Smoke"
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"http-upload.txt\"\r\nContent-Type: text/plain\r\n\r\nHTTP upload payload\r\n--{boundary}--\r\n").encode()
        # Tras subir, el servidor responde 303 (Post/Redirect/Get) para que el navegador recargue la carpeta.
        assert request("/?csrf=" + password, body=body, content_type=f"multipart/form-data; boundary={boundary}")[0] == 303
        assert adb("shell", "cat", "/sdcard/Download/http-upload.txt") == "HTTP upload payload"
        CHECKS.append("http-auth-download-upload-confinement")
        print("PASS: http-auth-download-upload-confinement", flush=True)
    finally:
        tap("Detener servidor")
        wait("Navegador / Wi-Fi")


def main():
    apk = next(pathlib.Path("apk").glob("*.apk"))
    adb("install", "-r", str(apk))
    adb("shell", "wm", "size", "1080x1920")
    adb("shell", "wm", "density", "320")
    adb("shell", "input", "keyevent", "82")
    adb("logcat", "-c")
    launch()
    checkpoint("01-storage-permission", "Conceder permiso")
    adb("shell", "appops", "set", PACKAGE, "MANAGE_EXTERNAL_STORAGE", "allow")
    adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
    seed_files()
    launch()
    checkpoint("02-home", "Categorías")
    for label, title, name in [
        ("Red, nube y USB", "Agregar", "03-connections"),
        ("Transferencias", "Transferencias", "04-transfers"),
        ("Historial", "Historial de carpetas", "05-history"),
    ]:
        drawer(label)
        checkpoint(name, title)
        back_home()
    drawer("Red, nube y USB")
    tap("Compartir por Wi-Fi / FTP")
    checkpoint("06-sharing-controls", "Navegador / Wi-Fi")
    adb("shell", "input", "keyevent", "4")
    back_home()
    drawer("Descargas")
    tap("Más opciones")
    tap("Doble panel")
    checkpoint("07-dual-pane", "Doble panel")
    adb("shell", "input", "keyevent", "4")
    tap("Más opciones")
    tap("Búsqueda avanzada")
    checkpoint("08-advanced-search", "Nombre (opcional)")
    adb("shell", "input", "keyevent", "4")
    tap("smoke.txt")
    checkpoint("09-editor", "smoke_original")
    tap("smoke_original")
    adb("shell", "input", "text", "_changed")
    tap("Guardar")
    deadline = time.monotonic() + 20
    while "_changed" not in adb("shell", "cat", "/sdcard/Download/smoke.txt"):
        assert time.monotonic() < deadline, "Editor did not save"
        time.sleep(0.5)
    tap("Atrás")
    tap("smoke.pdf")
    checkpoint("10-pdf", "Página 1")
    tap("Atrás")
    tap("smoke.png")
    checkpoint("11-gallery", "Restablecer zoom")
    tap("Atrás")
    tap("smoke.zip")
    checkpoint("12-archive", "alpha.txt")
    tap("Extraer en carpeta nueva")
    deadline = time.monotonic() + 30
    while "archive payload" not in adb("shell", "cat", "/sdcard/Download/smoke/alpha.txt", check=False):
        assert time.monotonic() < deadline, "Archive extraction did not finish"
        time.sleep(0.5)
    checkpoint("13-archive-extracted", "alpha.txt")
    tap("Atrás")
    drawer("Red, nube y USB")
    tap("Compartir por Wi-Fi / FTP")
    verify_http()
    adb("shell", "input", "keyevent", "4")
    adb("shell", "input", "keyevent", "4")
    # Verify leaving/re-entering the Activity keeps normal file browsing usable.
    adb("shell", "input", "keyevent", "3")
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
    checkpoint("14-resume", "smoke.txt")
    verify_network_resume()
    crash = adb("logcat", "-d", "-b", "crash")
    assert f"Process: {PACKAGE}" not in crash, crash


try:
    main()
finally:
    (OUTPUT / "logcat.txt").write_text(adb("logcat", "-d"), encoding="utf-8")
    (OUTPUT / "checks.json").write_text(json.dumps(CHECKS, indent=2), encoding="utf-8")
    try:
        ET.ElementTree(hierarchy()).write(OUTPUT / "last.xml", encoding="utf-8")
        (OUTPUT / "last.png").write_bytes(subprocess.check_output(["adb", "exec-out", "screencap", "-p"], timeout=30))
    except Exception:
        pass
