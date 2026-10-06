"""Exercise the built APK on a disposable Android 15 emulator, saving visible evidence.

Uses only seeded files and a local HTTP server. Cloud accounts, USB and root still
need separate device/account verification. Run with an APK in ./apk/ and adb ready.
"""

import base64
import hashlib
import json
import math
import pathlib
import re
import shutil
import shlex
import socket
import struct
import subprocess
import sys
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
WEBDAV_PORT = 18080
WEBDAV_PROCESS = None
WEBDAV_LOG = None
WEBDAV_SHA256 = None
WEBDAV_SIZE = 16 * 1024 * 1024


def start_webdav():
    global WEBDAV_PROCESS, WEBDAV_LOG, WEBDAV_SHA256
    root = OUTPUT / "webdav-root"
    shutil.rmtree(root, ignore_errors=True)
    root.mkdir(parents=True)
    payload = root / "big.bin"
    digest = hashlib.sha256()
    block = bytes((i * 17 + 23) % 256 for i in range(64 * 1024))
    with payload.open("wb") as out:
        for _ in range(WEBDAV_SIZE // len(block)):
            out.write(block)
            digest.update(block)
    WEBDAV_SHA256 = digest.hexdigest()
    WEBDAV_LOG = (OUTPUT / "webdav.log").open("w", encoding="utf-8")
    WEBDAV_PROCESS = subprocess.Popen(
        [
            sys.executable,
            "scripts/smoke_webdav_server.py",
            "--root",
            str(root),
            "--port",
            str(WEBDAV_PORT),
            "--user",
            "",
            "--password",
            "",
        ],
        stdout=WEBDAV_LOG,
        stderr=subprocess.STDOUT,
        text=True,
    )
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if WEBDAV_PROCESS.poll() is not None:
            raise AssertionError("WebDAV fixture server exited early")
        try:
            with socket.create_connection(("127.0.0.1", WEBDAV_PORT), timeout=0.3):
                return
        except OSError:
            time.sleep(0.1)
    raise AssertionError("WebDAV fixture server did not start")


def stop_webdav():
    global WEBDAV_PROCESS, WEBDAV_LOG
    if WEBDAV_PROCESS is not None:
        WEBDAV_PROCESS.terminate()
        try:
            WEBDAV_PROCESS.wait(timeout=5)
        except subprocess.TimeoutExpired:
            WEBDAV_PROCESS.kill()
            WEBDAV_PROCESS.wait(timeout=5)
        WEBDAV_PROCESS = None
    if WEBDAV_LOG is not None:
        WEBDAV_LOG.close()
        WEBDAV_LOG = None


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


def wait_gone(label, timeout=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        tree = hierarchy()
        if not nodes(label, tree):
            return
        time.sleep(0.25)
    raise AssertionError(f"Control still visible: {label}")


def tap_node(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def tap(label):
    tap_node(wait(label)[0])


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def find_scrolling(label, attempts=8):
    tree = hierarchy()
    found = nodes(label, tree)
    if found:
        return found[0]

    # Reset toward the beginning of a scrollable view, then search forward.
    for _ in range(4):
        adb("shell", "input", "swipe", "540", "550", "540", "1550", "250")
        time.sleep(0.15)
    for _ in range(attempts):
        tree = hierarchy()
        found = nodes(label, tree)
        if found:
            return found[0]
        adb("shell", "input", "swipe", "540", "1500", "540", "500", "300")
        time.sleep(0.25)
    raise AssertionError(f"Scrollable control not found: {label}")


def tap_scrolling(label, attempts=8):
    tap_node(find_scrolling(label, attempts))


def type_into(label, value):
    tap_node(wait(label)[0])
    adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    adb("shell", "input", "text", value)


def type_into_scrolling(label, value):
    tap_node(find_scrolling(label))
    adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    adb("shell", "input", "text", value)


def long_press(label, duration=900):
    node = wait(label)[0]
    x, y = center(node)
    adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), str(duration))


def drag_row_to_right_pane(label):
    tree = hierarchy()
    row = wait(label)[0]
    _, y = center(row)
    handles = [
        node
        for node in nodes("Mantén pulsado y arrastra al otro panel", tree)
        if abs(center(node)[1] - y) < 90
    ]
    assert handles, f"Drag handle not found for {label}"
    sx, sy = center(handles[0])
    adb("shell", "input", "draganddrop", str(sx), str(sy), "820", "1050", "1200")


def remote_partial_sizes():
    directory = "/sdcard/Download/OI Archivos"
    # adb shell joins argv into a remote shell command. Quote paths that contain
    # spaces or the shell will treat "OI Archivos" as two separate arguments.
    quoted_directory = shlex.quote(directory)
    names = adb("shell", "ls", "-1A", quoted_directory, check=False).splitlines()
    sizes = []
    for name in names:
        if not (name.startswith(".oi-remote-") and name.endswith(".part")):
            continue
        quoted_file = shlex.quote(f"{directory}/{name}")
        raw = adb("shell", "stat", "-c", "%s", quoted_file, check=False).strip()
        if raw.isdigit():
            sizes.append(int(raw))
    return sizes


def add_ci_webdav_connection():
    drawer("Red, nube y USB")
    tap("Agregar")
    wait("Nueva conexión")
    type_into("Nombre de la conexión", "CI-WebDAV")
    tap_scrolling("WebDAV")
    type_into_scrolling("URL completa https://…", f"http://10.0.2.2:{WEBDAV_PORT}")
    # Unit tests already verify Basic auth with non-empty credentials. Here the
    # Android smoke focuses on durable transfer recovery, so use an empty
    # username/password to avoid IME focus errors in the long connection dialog.
    adb("shell", "input", "keyevent", "4")
    time.sleep(0.3)
    tap_scrolling("Guardar")
    wait_gone("Nueva conexión", timeout=10)
    wait("CI-WebDAV")
    tap("CI-WebDAV")
    wait("big.bin", timeout=30)


def verify_remote_recovery():
    add_ci_webdav_connection()
    long_press("big.bin")
    tap("Descargar")

    deadline = time.monotonic() + 25
    partial_size = 0
    while time.monotonic() < deadline:
        values = remote_partial_sizes()
        if values:
            partial_size = max(values)
            if 64 * 1024 <= partial_size < WEBDAV_SIZE:
                break
        time.sleep(0.25)
    assert 64 * 1024 <= partial_size < WEBDAV_SIZE, (
        f"Remote download did not create a resumable partial file: {partial_size}"
    )

    # RemoteScreen and ConnectionsScreen use back navigation, not the home drawer.
    # The foreground service keeps running while we return to Home.
    adb("shell", "input", "keyevent", "4")
    adb("shell", "input", "keyevent", "4")
    wait("Categorías")
    drawer("Transferencias")
    wait("Pausar transferencia")
    tap("Pausar transferencia")
    wait("Reanudar transferencia")
    time.sleep(0.8)
    paused_values = remote_partial_sizes()
    paused_size = max(paused_values) if paused_values else 0
    time.sleep(1.2)
    paused_values_2 = remote_partial_sizes()
    paused_size_2 = max(paused_values_2) if paused_values_2 else 0
    assert paused_size_2 == paused_size, (
        f"Remote transfer kept writing while paused: {paused_size} -> {paused_size_2}"
    )

    tap("Reanudar transferencia")
    wait("Pausar transferencia")
    deadline = time.monotonic() + 10
    resumed_size = paused_size_2
    while time.monotonic() < deadline:
        values = remote_partial_sizes()
        resumed_size = max(values) if values else 0
        if paused_size_2 < resumed_size < WEBDAV_SIZE:
            break
        time.sleep(0.2)
    assert paused_size_2 < resumed_size < WEBDAV_SIZE, (
        f"Remote transfer did not resume before completion: {paused_size_2} -> {resumed_size}"
    )
    CHECKS.append("remote-pause-resume")
    print("PASS: remote-pause-resume", flush=True)

    adb("shell", "am", "force-stop", PACKAGE)
    time.sleep(0.5)
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
    wait("Categorías")
    drawer("Transferencias")
    checkpoint("19-remote-recovery-queued", "Reanudar")
    tap("Reanudar")

    final = "/sdcard/Download/OI Archivos/big.bin"
    deadline = time.monotonic() + 60
    size = 0
    while time.monotonic() < deadline:
        raw = adb("shell", "stat", "-c", "%s", final, check=False).strip()
        size = int(raw) if raw.isdigit() else 0
        if size == WEBDAV_SIZE:
            break
        time.sleep(0.5)
    assert size == WEBDAV_SIZE, f"Resumed remote file has wrong size: {size}"

    digest = adb("shell", "sha256sum", final).split()[0]
    assert digest == WEBDAV_SHA256, (
        f"Resumed remote file hash mismatch: {digest} != {WEBDAV_SHA256}"
    )
    checkpoint("20-remote-recovery-complete", "Descargando · Completado")
    CHECKS.append("remote-process-death-recovery")
    print("PASS: remote-process-death-recovery", flush=True)
    adb("shell", "input", "keyevent", "4")
    wait("Categorías")


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

        (folder / "smoke.mp4").write_bytes(
            base64.b64decode("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" + "=" * (-len("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") % 4))
        )

        rar_source = pathlib.Path(
            "app/src/test/resources/archives/test_read_format_rar5_encrypted_filenames.rar"
        )
        assert rar_source.is_file(), "RAR5 smoke fixture is missing"
        shutil.copyfile(rar_source, folder / "smoke-encrypted.rar")

        adb("shell", "mkdir", "-p", "/sdcard/Download")
        for file in folder.iterdir():
            adb("push", str(file), f"/sdcard/Download/{file.name}")


def ftp_read_reply(control):
    line = control.stdout.readline()
    if not line:
        stderr = control.stderr.read().decode("utf-8", "replace")
        raise AssertionError("FTP control connection closed: " + stderr)
    text = line.decode("utf-8", "replace").strip()
    assert re.match(r"^\\d{3}[ -]", text), "Invalid FTP reply: " + text
    return text


def ftp_command(control, command, expected):
    control.stdin.write((command + "\\r\\n").encode())
    control.stdin.flush()
    reply = ftp_read_reply(control)
    assert reply.startswith(str(expected)), f"FTP {command}: {reply}"
    return reply


def ftp_data_command(control, address, command, payload=None):
    passive = ftp_command(control, "EPSV", 229)
    match = re.search(r"\\(\\|\\|\\|(\\d+)\\|\\)", passive)
    assert match, "Invalid EPSV reply: " + passive
    port = match.group(1)

    control.stdin.write((command + "\\r\\n").encode())
    control.stdin.flush()
    opening = ftp_read_reply(control)
    assert opening.startswith("150"), f"FTP {command}: {opening}"

    result = subprocess.run(
        ["adb", "shell", "toybox", "nc", "-w", "15", address, port],
        input=payload,
        capture_output=True,
        timeout=25,
        check=True,
    )
    finished = ftp_read_reply(control)
    assert finished.startswith("226"), f"FTP {command}: {finished}"
    return result.stdout


def verify_ftp():
    tap("Servidor FTP")
    _, tree = wait("Detener servidor")
    text = "\\n".join(n.get("text", "") for n in tree.iter("node"))
    address, port = re.search(r"ftp://([^:]+):(\\d+)/", text).groups()
    password = re.search(r"Contraseña: (\\S+)", text).group(1)

    control = subprocess.Popen(
        ["adb", "shell", "toybox", "nc", "-w", "30", address, port],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    try:
        assert ftp_read_reply(control).startswith("220")
        ftp_command(control, "USER oi", 331)
        ftp_command(control, "PASS incorrecta", 530)
        ftp_command(control, "PASS " + password, 230)
        ftp_command(control, "TYPE I", 200)
        assert ftp_command(control, "PWD", 257).startswith('257 "')

        listing = ftp_data_command(control, address, "NLST")
        assert b"smoke.txt" in listing and b"smoke.zip" in listing

        expected = adb("shell", "cat", "/sdcard/Download/smoke.txt").encode()
        downloaded = ftp_data_command(control, address, "RETR smoke.txt")
        assert downloaded == expected, (downloaded, expected)

        ftp_command(control, "REST 6", 350)
        resumed = ftp_data_command(control, address, "RETR smoke.txt")
        assert resumed == expected[6:], (resumed, expected[6:])

        uploaded = b"FTP upload from OI Archivos"
        ftp_data_command(control, address, "STOR ftp-upload.txt", uploaded)
        assert adb("shell", "cat", "/sdcard/Download/ftp-upload.txt").encode() == uploaded

        ftp_command(control, "RNFR ftp-upload.txt", 350)
        ftp_command(control, "RNTO ftp-renamed.txt", 250)
        assert adb("shell", "cat", "/sdcard/Download/ftp-renamed.txt").encode() == uploaded

        ftp_command(control, "MKD ftp-smoke-dir", 257)
        ftp_command(control, "RMD ftp-smoke-dir", 250)
        rejected = ftp_command(control, "SIZE ../oi-smoke.xml", 550)
        assert rejected.startswith("550")

        ftp_command(control, "DELE ftp-renamed.txt", 250)
        assert (
            adb(
                "shell",
                "sh",
                "-c",
                '[ ! -e "/sdcard/Download/ftp-renamed.txt" ] && echo missing',
                check=False,
            ).strip()
            == "missing"
        )

        CHECKS.append("ftp-auth-list-download-resume-upload-confinement")
        print("PASS: ftp-auth-list-download-resume-upload-confinement", flush=True)
        ftp_command(control, "QUIT", 221)
    finally:
        if control.poll() is None:
            control.terminate()
            try:
                control.wait(timeout=3)
            except subprocess.TimeoutExpired:
                control.kill()
        tap("Detener servidor")
        wait("Servidor FTP")


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
    start_webdav()
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

    # Dual-pane native drag/drop: drag smoke.txt from Downloads into internal storage root.
    drawer("Descargas")
    tap("Más opciones")
    tap("Doble panel")
    checkpoint("07-dual-pane", "Doble panel")
    drag_row_to_right_pane("smoke.txt")
    tap("Copiar aquí")
    deadline = time.monotonic() + 20
    while adb("shell", "cat", "/sdcard/smoke.txt", check=False).strip() != "smoke_original":
        assert time.monotonic() < deadline, "Dual-pane drag/drop copy did not finish"
        time.sleep(0.5)
    CHECKS.append("dual-pane-drag-drop")
    print("PASS: dual-pane-drag-drop", flush=True)
    adb("shell", "input", "keyevent", "4")

    tap("Más opciones")
    tap("Búsqueda avanzada")
    checkpoint("08-advanced-search", "Nombre (opcional)")
    adb("shell", "input", "keyevent", "4")

    # Persist a gesture preference and exercise the real horizontal swipe detector.
    drawer("Ajustes")
    tap("Deslizar a la izquierda")
    tap("Mostrar / ocultar archivos ocultos")
    tap("Atrás")
    wait("smoke.txt")
    adb("shell", "input", "swipe", "930", "1050", "120", "1050", "450")
    checkpoint("09-gesture-hidden", ".smoke-hidden.txt")

    tap("smoke.txt")
    checkpoint("10-editor", "smoke_original")
    tap("smoke_original")
    adb("shell", "input", "text", "_changed")
    tap("Guardar")
    deadline = time.monotonic() + 20
    while "_changed" not in adb("shell", "cat", "/sdcard/Download/smoke.txt"):
        assert time.monotonic() < deadline, "Editor did not save"
        time.sleep(0.5)
    tap("Atrás")

    tap("smoke.pdf")
    checkpoint("11-pdf", "Página 1")
    tap("Atrás")
    tap("smoke.png")
    checkpoint("12-gallery", "Restablecer zoom")
    tap("Atrás")

    tap("smoke.zip")
    checkpoint("13-archive", "alpha.txt")
    tap("Extraer en carpeta nueva")
    deadline = time.monotonic() + 30
    while "archive payload" not in adb(
        "shell", "cat", "/sdcard/Download/smoke/alpha.txt", check=False
    ):
        assert time.monotonic() < deadline, "ZIP extraction did not finish"
        time.sleep(0.5)
    checkpoint("14-archive-extracted", "alpha.txt")
    tap("Atrás")

    # Exercise the bundled Android 7-Zip binary with an encrypted RAR5 fixture.
    tap("smoke-encrypted.rar")
    type_into("Contraseña (si corresponde)", "password")
    tap("Abrir")
    checkpoint("15-rar5-encrypted", "a.txt")
    tap("Extraer en carpeta nueva")
    deadline = time.monotonic() + 30
    while "This is from a.txt" not in adb(
        "shell", "cat", "/sdcard/Download/smoke-encrypted/a.txt", check=False
    ):
        assert time.monotonic() < deadline, "Encrypted RAR5 extraction did not finish"
        time.sleep(0.5)
    CHECKS.append("rar5-android-extraction")
    print("PASS: rar5-android-extraction", flush=True)
    tap("Atrás")

    # Open the actual video viewer, enter the editor and export an MP4 copy.
    tap("smoke.mp4")
    checkpoint("16-video-viewer", "Editar")
    tap("Editar")
    checkpoint("17-video-editor", "Exporta una copia MP4. El video original se conserva.")
    tap_scrolling("Exportar MP4")
    deadline = time.monotonic() + 60
    while int(adb(
        "shell",
        "stat",
        "-c",
        "%s",
        "/sdcard/Download/smoke-editado.mp4",
        check=False,
    ).strip() or "0") <= 0:
        assert time.monotonic() < deadline, "Video export did not finish"
        time.sleep(1)
    CHECKS.append("video-export")
    print("PASS: video-export", flush=True)
    tap("Atrás")
    tap("Atrás")

    # MediaSessionService must keep audio playback alive after the Activity goes to Home.
    tap("smoke.wav")
    checkpoint("18-audio-viewer", "smoke.wav")
    time.sleep(2)
    before = adb("shell", "dumpsys", "media_session")
    assert PACKAGE in before and ("state=3" in before or "state=PLAYING" in before), before
    adb("shell", "input", "keyevent", "3")
    time.sleep(2)
    background = adb("shell", "dumpsys", "media_session")
    assert PACKAGE in background and (
        "state=3" in background or "state=PLAYING" in background
    ), background
    CHECKS.append("audio-background-playback")
    print("PASS: audio-background-playback", flush=True)
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
    wait("smoke.wav")
    tap("Atrás")

    verify_remote_recovery()

    drawer("Red, nube y USB")
    tap("Compartir por Wi-Fi / FTP")
    verify_http()
    verify_ftp()
    adb("shell", "input", "keyevent", "4")
    adb("shell", "input", "keyevent", "4")

    # Verify leaving/re-entering the Activity keeps normal file browsing usable.
    adb("shell", "input", "keyevent", "3")
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
    checkpoint("21-resume", "smoke.txt")
    crash = adb("logcat", "-d", "-b", "crash")
    assert f"Process: {PACKAGE}" not in crash, crash


try:
    main()
finally:
    stop_webdav()
    (OUTPUT / "logcat.txt").write_text(adb("logcat", "-d"), encoding="utf-8")
    (OUTPUT / "checks.json").write_text(json.dumps(CHECKS, indent=2), encoding="utf-8")
    try:
        ET.ElementTree(hierarchy()).write(OUTPUT / "last.xml", encoding="utf-8")
        (OUTPUT / "last.png").write_bytes(subprocess.check_output(["adb", "exec-out", "screencap", "-p"], timeout=30))
    except Exception:
        pass
