"""Exercise the built APK on a disposable Android 15 emulator, saving visible evidence.

Uses only seeded files and a local HTTP server. Cloud accounts, USB and root still
need separate device/account verification. Run with an APK in ./apk/ and adb ready.
"""

import base64
import json
import math
import pathlib
import re
import shutil
import struct
import subprocess
import tempfile
import time
import wave
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


def wait(label, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        tree = hierarchy()
        found = nodes(label, tree)
        if found:
            return found[0], tree
        time.sleep(0.5)
    raise AssertionError(f"Visible control not found: {label}")


def tap_node(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def tap(label):
    tap_node(wait(label)[0])


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap_scrolling(label, attempts=8):
    for _ in range(attempts):
        tree = hierarchy()
        found = nodes(label, tree)
        if found:
            tap_node(found[0])
            return
        adb("shell", "input", "swipe", "540", "1500", "540", "500", "300")
        time.sleep(0.3)
    raise AssertionError(f"Scrollable control not found: {label}")


def type_into(label, value):
    tap_node(wait(label)[0])
    adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    adb("shell", "input", "text", value)


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
        (folder / ".smoke-hidden.txt").write_text("hidden payload", encoding="utf-8")
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

        wav_path = folder / "smoke.wav"
        sample_rate = 8000
        with wave.open(str(wav_path), "wb") as audio:
            audio.setnchannels(1)
            audio.setsampwidth(2)
            audio.setframerate(sample_rate)
            frames = bytearray()
            for i in range(sample_rate * 20):
                sample = int(0.15 * 32767 * math.sin(2 * math.pi * 440 * i / sample_rate))
                frames.extend(struct.pack("<h", sample))
            audio.writeframes(frames)

        ffmpeg = shutil.which("ffmpeg")
        if not ffmpeg:
            raise AssertionError("ffmpeg is required by the Android smoke test")
        subprocess.run(
            [
                ffmpeg,
                "-hide_banner",
                "-loglevel",
                "error",
                "-f",
                "lavfi",
                "-i",
                "testsrc=size=160x120:rate=15:duration=2",
                "-c:v",
                "libx264",
                "-preset",
                "ultrafast",
                "-pix_fmt",
                "yuv420p",
                "-an",
                "-y",
                str(folder / "smoke.mp4"),
            ],
            check=True,
            capture_output=True,
            timeout=30,
        )

        rar_source = pathlib.Path(
            "app/src/test/resources/archives/test_read_format_rar5_encrypted_filenames.rar"
        )
        assert rar_source.is_file(), "RAR5 smoke fixture is missing"
        shutil.copyfile(rar_source, folder / "smoke-encrypted.rar")

        adb("shell", "mkdir", "-p", "/sdcard/Download")
        for file in folder.iterdir():
            adb("push", str(file), f"/sdcard/Download/{file.name}")


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
