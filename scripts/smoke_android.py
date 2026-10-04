"""Exercise the built APK on a disposable Android 15 emulator, saving visible evidence.

Uses only seeded files and a local HTTP server. Cloud accounts, USB and root still
need separate device/account verification. Run with an APK in ./apk/ and adb ready.
"""

import base64
import hashlib
import http.server
import json
import pathlib
import re
import subprocess
import shlex
import socket
import tempfile
import time
import threading
import urllib.error
import urllib.request
import urllib.parse
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


def visible_after_scroll(label):
    for _ in range(8):
        found = nodes(label, hierarchy())
        if found:
            return found[0]
        adb("shell", "input", "swipe", "540", "1250", "540", "550", "400")
    raise AssertionError(f"Scrollable control not found: {label}")


def fill_connection(label, value):
    tap_node(visible_after_scroll(label))
    adb("shell", "input", "text", value)
    adb("shell", "input", "keyevent", "4")  # dismiss keyboard


class UploadDavServer(http.server.ThreadingHTTPServer):
    """Filesystem-backed DAV fixture; interrupted PUTs deliberately remain visible."""

    daemon_threads = True

    def __init__(self, directory):
        self.directory = pathlib.Path(directory)
        self.lock = threading.RLock()
        self.stopping = threading.Event()
        self.puts = []
        self.moves = []
        self.deletes = []
        super().__init__(("0.0.0.0", 0), UploadDavHandler)

    def snapshot(self):
        with self.lock:
            return {
                "files": {"/" + file.relative_to(self.directory).as_posix(): file.stat().st_size
                          for file in self.directory.rglob("*") if file.is_file()},
                "puts": [dict(attempt) for attempt in self.puts],
                "moves": [dict(move) for move in self.moves],
                "deletes": list(self.deletes),
            }


class UploadDavHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def setup(self):
        # Keep the sender blocked on real network I/O while the fixture reads slowly.
        self.request.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 65536)
        self.request.settimeout(5)
        super().setup()

    def target(self, value=None):
        path = urllib.parse.unquote(urllib.parse.urlsplit(value or self.path).path)
        parts = path.strip("/").split("/") if path.strip("/") else []
        if any(part in (".", "..") or "\\" in part or "\x00" in part for part in parts):
            raise ValueError("Invalid DAV path")
        return self.server.directory.joinpath(*parts)

    def reply(self, status, body=b"", content_type=None):
        try:
            self.send_response(status)
            self.send_header("Content-Length", str(len(body)))
            if content_type:
                self.send_header("Content-Type", content_type)
            self.end_headers()
            if body:
                self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass  # expected after pause or force-stop

    def do_PROPFIND(self):
        self.rfile.read(int(self.headers.get("Content-Length", "0")))
        target = self.target()
        with self.server.lock:
            if not target.exists():
                self.reply(404)
                return
            listing = [target]
            if target.is_dir() and self.headers.get("Depth", "1") != "0":
                listing += sorted(target.iterdir())
            root = ET.Element("{DAV:}multistatus")
            for entry in listing:
                response = ET.SubElement(root, "{DAV:}response")
                path = "/" + entry.relative_to(self.server.directory).as_posix()
                if entry == self.server.directory:
                    path = "/"
                ET.SubElement(response, "{DAV:}href").text = urllib.parse.quote(path)
                propstat = ET.SubElement(response, "{DAV:}propstat")
                prop = ET.SubElement(propstat, "{DAV:}prop")
                kind = ET.SubElement(prop, "{DAV:}resourcetype")
                if entry.is_dir():
                    ET.SubElement(kind, "{DAV:}collection")
                stat = entry.stat()
                ET.SubElement(prop, "{DAV:}getcontentlength").text = str(
                    stat.st_size if entry.is_file() else 0)
                ET.SubElement(prop, "{DAV:}getetag").text = f'"{stat.st_size}-{stat.st_mtime_ns}"'
                ET.SubElement(propstat, "{DAV:}status").text = "HTTP/1.1 200 OK"
            body = ET.tostring(root, encoding="utf-8", xml_declaration=True)
        self.reply(207, body, "application/xml")

    def do_PUT(self):
        target = self.target()
        length = self.headers.get("Content-Length")
        if length is None:
            self.reply(411)
            return
        length = int(length)
        with self.server.lock:
            if not target.parent.is_dir():
                self.reply(409)
                return
            if target.exists() and self.headers.get("If-None-Match") == "*":
                self.reply(412)
                return
            output = target.open("wb")
            attempt = {
                "path": "/" + target.relative_to(self.server.directory).as_posix(),
                "expected": length, "received": 0, "complete": False, "active": True,
                "if_none_match": self.headers.get("If-None-Match"),
            }
            self.server.puts.append(attempt)
        try:
            with output:
                while attempt["received"] < length:
                    chunk = self.rfile.read(min(65536, length - attempt["received"]))
                    if not chunk:
                        break
                    with self.server.lock:
                        output.write(chunk)
                        output.flush()
                        attempt["received"] += len(chunk)
                    if length >= 16 * 1024 * 1024 and self.server.stopping.wait(0.08):
                        break
            with self.server.lock:
                attempt["complete"] = attempt["received"] == length
            if attempt["complete"]:
                self.reply(201)
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            pass
        finally:
            with self.server.lock:
                attempt["active"] = False

    def do_GET(self):
        target = self.target()
        with self.server.lock:
            if not target.is_file():
                self.reply(404)
                return
            source = target.open("rb")
            length = target.stat().st_size
        try:
            with source:
                self.send_response(200)
                self.send_header("Content-Length", str(length))
                self.end_headers()
                while chunk := source.read(131072):
                    self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_MOVE(self):
        target = self.target()
        destination = self.headers.get("Destination")
        if not destination:
            self.reply(400)
            return
        final = self.target(destination)
        with self.server.lock:
            if not target.exists():
                self.reply(404)
                return
            if not final.parent.is_dir():
                self.reply(409)
                return
            if final.exists() and self.headers.get("Overwrite") == "F":
                self.reply(412)
                return
            target.replace(final)
            self.server.moves.append({
                "source": "/" + target.relative_to(self.server.directory).as_posix(),
                "destination": "/" + final.relative_to(self.server.directory).as_posix(),
                "overwrite": self.headers.get("Overwrite"),
            })
        self.reply(201)

    def do_MKCOL(self):
        target = self.target()
        with self.server.lock:
            if target.exists():
                self.reply(405)
                return
            if not target.parent.is_dir():
                self.reply(409)
                return
            target.mkdir()
        self.reply(201)

    def do_DELETE(self):
        target = self.target()
        with self.server.lock:
            if not target.exists():
                self.reply(404)
                return
            if target.is_dir():
                target.rmdir()
            else:
                target.unlink()
            self.server.deletes.append("/" + target.relative_to(self.server.directory).as_posix())
        self.reply(204)


def verify_remote_upload_recovery():
    """Copy a batch by UI, pause/restart, then kill a real, slow WebDAV PUT."""
    size = 16 * 1024 * 1024
    data = (bytes(range(251)) * (size // 251 + 1))[:size]
    payloads = {"a-upload-small.bin": b"OI completed upload\n" * 256,
                "b-upload-large.bin": data}
    source = "/sdcard/Download/oi-upload-smoke"
    final_paths = {"/" + name for name in payloads}
    evidence = {"expected_sha256": {name: hashlib.sha256(body).hexdigest()
                                    for name, body in payloads.items()}}

    with tempfile.TemporaryDirectory() as tmp:
        fixture = pathlib.Path(tmp)
        local = fixture / "local"
        remote = fixture / "remote"
        local.mkdir()
        remote.mkdir()
        adb("shell", "mkdir", "-p", source)
        for name, body in payloads.items():
            file = local / name
            file.write_bytes(body)
            adb("push", str(file), source + "/" + name)
        server = UploadDavServer(remote)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()

        def until(predicate, reason, timeout=30):
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                state = server.snapshot()
                if predicate(state):
                    return state
                time.sleep(0.2)
            raise AssertionError(reason + ": " + json.dumps(server.snapshot()))

        def partial(state):
            return any(path not in final_paths and 0 < length < size
                       for path, length in state["files"].items())

        try:
            # The preceding recovery ends in Transfers, whose toolbar has a back button.
            back_home()
            drawer("Descargas")
            tap_node(visible_after_scroll("oi-upload-smoke"))
            node = wait("a-upload-small.bin")[0]
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
            x, y = str((x1 + x2) // 2), str((y1 + y2) // 2)
            adb("shell", "input", "swipe", x, y, x, y, "1000")
            tap("b-upload-large.bin")
            tap("Copiar")
            drawer("Red, nube y USB")
            tap("Agregar")
            fill_connection("Nombre de la conexión", "Smoke-Upload-WebDAV")
            tap_node(visible_after_scroll("WebDAV"))
            fill_connection("URL completa https://…", f"http://10.0.2.2:{server.server_port}")
            tap("Guardar")
            tap_node(visible_after_scroll("Smoke-Upload-WebDAV"))
            tap("Pegar aquí")
            tap("Continuar navegando")
            wait("Transferencias")
            until(lambda state: "/a-upload-small.bin" in state["files"] and partial(state),
                  "The batch never committed its small file and created a remote partial")
            tap("Pausar transferencia")
            checkpoint("19-upload-paused", "Reanudar")
            paused = until(lambda state: not any(item["active"] for item in state["puts"]),
                           "Pause did not close the remote PUT")
            assert partial(paused), "Pause removed or committed the incomplete remote file"
            assert "/b-upload-large.bin" not in paused["files"], "Incomplete upload was published"
            evidence["paused"] = paused

            launch()
            drawer("Transferencias")
            checkpoint("20-upload-journal-restored", "Reanudar")
            assert server.snapshot()["files"] == paused["files"], "Restart changed remote partials"
            tap("Reanudar")
            tap("Continuar navegando")
            until(lambda state: len(state["puts"]) > len(paused["puts"]) and partial(state)
                  and any(item["active"] and item["expected"] == size
                          and 0 < item["received"] < size for item in state["puts"]),
                  "Resuming did not restart the interrupted WebDAV PUT")
            wait("Pausar transferencia")
            # force-stop bypasses service cancellation and journal cleanup callbacks.
            launch()
            killed = until(lambda state: not any(item["active"] for item in state["puts"]),
                           "Force-stop did not interrupt the resumed PUT")
            assert partial(killed), "Process death did not preserve a recoverable remote partial"
            assert "/b-upload-large.bin" not in killed["files"], "Killed upload was published"
            evidence["process_killed"] = killed
            drawer("Transferencias")
            checkpoint("21-upload-process-killed", "Reanudar")
            tap("Reanudar")
            tap("Continuar navegando")
            until(lambda state: final_paths.issubset(state["files"])
                              and state["files"].get("/b-upload-large.bin") == size,
                  "Recovered upload did not publish both batch files", timeout=90)
            # Wait for the completion record and absence of another queued retry.
            deadline = time.monotonic() + 30
            while True:
                tree = hierarchy()
                if not nodes("Pausar transferencia", tree) and not nodes("Reanudar", tree):
                    break
                assert time.monotonic() < deadline, "Upload stayed active or queued after commit"
                time.sleep(0.5)
            checkpoint("22-upload-recovered", "Subiendo archivos remotos · Completado")
            completed = server.snapshot()
            # Ambiguous interrupted stages may stay intact. They are evidence of an
            # interrupted attempt, never a completed upload or an extra final copy.
            interrupted = {path: length for state in (paused, killed)
                           for path, length in state["files"].items() if path not in final_paths}
            residuals = {path: length for path, length in completed["files"].items()
                         if path not in final_paths}
            assert set(residuals).issubset(interrupted), "Upload created unexpected remote copies"
            assert all(length == interrupted[path] for path, length in residuals.items()), \
                "Recovery replaced an ambiguous interrupted stage"
            evidence["preserved_interrupted_stages"] = residuals
            evidence["actual_sha256"] = {}
            evidence["original_sha256"] = {}
            for name, body in payloads.items():
                expected = hashlib.sha256(body).hexdigest()
                remote_hash = hashlib.sha256((remote / name).read_bytes()).hexdigest()
                evidence["actual_sha256"][name] = remote_hash
                assert remote_hash == expected, name
                actual = adb("shell", "sha256sum", shlex.quote(source + "/" + name)).split()[0]
                evidence["original_sha256"][name] = actual
                assert actual == expected, "Upload changed or deleted the local original: " + name
            assert len(completed["moves"]) == 2, "Completed batch entries were uploaded twice"
            assert {move["destination"] for move in completed["moves"]} == final_paths
            assert all(move["overwrite"] == "F" for move in completed["moves"]), \
                "Uploads did not protect existing final destinations"
            assert all(item["if_none_match"] == "*" for item in completed["puts"]), \
                "Upload stages did not use conditional creation"
            small = [item for item in completed["puts"] if item["expected"] == len(payloads["a-upload-small.bin"])]
            large = [item for item in completed["puts"] if item["expected"] == size]
            assert len(small) == 1 and small[0]["complete"], "Recovery repeated the completed small file"
            assert len(large) >= 3 and sum(item["complete"] for item in large) == 1, \
                "The slow upload was not interrupted and restarted twice"
            assert all(item["received"] < size for item in large[:-1]), \
                "An interruption occurred after the upload had already finished"
            evidence["completed"] = completed
            CHECKS.append("webdav-upload-batch-pause-process-death-recovery-sha256")
            print("PASS: webdav-upload-batch-pause-process-death-recovery-sha256", flush=True)
        finally:
            evidence["last_server_state"] = server.snapshot()
            (OUTPUT / "upload-webdav.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")
            server.stopping.set()
            server.shutdown()
            server.server_close()


def verify_remote_recovery():
    """Pause a real WebDAV download, restart Android, then kill it while resuming."""
    size = 32 * 1024 * 1024
    data = (bytes(range(251)) * (size // 251 + 1))[:size]
    destination = "/sdcard/Download/OI Archivos"
    target = destination + "/resume.bin"

    class DavHandler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_PROPFIND(self):
            self.rfile.read(int(self.headers.get("Content-Length", "0")))
            body = (f'<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">'
                    f'<d:response><d:href>/resume.bin</d:href><d:propstat><d:prop>'
                    f'<d:resourcetype/><d:getcontentlength>{size}</d:getcontentlength>'
                    f'<d:getetag>"smoke-v1"</d:getetag></d:prop>'
                    f'<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>'
                    f'</d:multistatus>').encode()
            self.send_response(207)
            self.send_header("Content-Type", "application/xml")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Length", str(size))
            self.end_headers()
            try:
                for offset in range(0, size, 65536):
                    self.wfile.write(data[offset:offset + 65536])
                    self.wfile.flush()
                    time.sleep(0.08)
            except (BrokenPipeError, ConnectionResetError):
                pass  # expected when the app pauses or its process is killed

    server = http.server.ThreadingHTTPServer(("0.0.0.0", 0), DavHandler)
    server.daemon_threads = True
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        drawer("Red, nube y USB")
        tap("Agregar")
        fill_connection("Nombre de la conexión", "Smoke-WebDAV")
        tap_node(visible_after_scroll("WebDAV"))
        fill_connection("URL completa https://…", f"http://10.0.2.2:{server.server_port}")
        tap("Guardar")
        tap_node(visible_after_scroll("Smoke-WebDAV"))
        node = wait("resume.bin")[0]
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        x, y = str((x1 + x2) // 2), str((y1 + y2) // 2)
        adb("shell", "input", "swipe", x, y, x, y, "1000")
        tap("Descargar")
        # Each operation opens a progress dialog; expose the Transfers controls.
        tap("Continuar navegando")
        deadline = time.monotonic() + 30
        while True:
            parts = adb("shell", "find", shlex.quote(destination), "-maxdepth", "1",
                        "-type", "f", "-name", "'.oi-download-*.part'", check=False).splitlines()
            if parts and int(adb("shell", "stat", "-c", "%s", shlex.quote(parts[0]))) > 0:
                break
            assert time.monotonic() < deadline, "No persisted partial was created"
            time.sleep(0.5)
        tap("Pausar transferencia")
        checkpoint("15-remote-paused", "Reanudar")
        assert int(adb("shell", "stat", "-c", "%s", shlex.quote(parts[0]))) < size
        launch()
        drawer("Transferencias")
        checkpoint("16-remote-journal-restored", "Reanudar")
        tap("Reanudar")
        tap("Continuar navegando")
        wait("Pausar transferencia")
        # Force-stop without cancelling: the onDestroy callback will not save state.
        launch()
        drawer("Transferencias")
        checkpoint("17-remote-process-killed", "Reanudar")
        tap("Reanudar")
        tap("Continuar navegando")
        deadline = time.monotonic() + 90
        while adb("shell", "stat", "-c", "%s", shlex.quote(target), check=False).strip() != str(size):
            assert time.monotonic() < deadline, "Resumed download did not commit"
            time.sleep(1)
        actual = adb("shell", "sha256sum", shlex.quote(target)).split()[0]
        assert actual == hashlib.sha256(data).hexdigest(), "Recovered download contents differ"
        checkpoint("18-remote-recovered", "Transferencias")
        remaining = adb("shell", "find", shlex.quote(destination), "-maxdepth", "1",
                        "-name", "'.oi-download-*.part'").strip()
        assert not remaining, "Completed download left a partial"
        CHECKS.append("webdav-pause-process-death-recovery-sha256")
        print("PASS: webdav-pause-process-death-recovery-sha256", flush=True)
    finally:
        server.shutdown()
        server.server_close()


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
    verify_remote_recovery()
    verify_remote_upload_recovery()
    crash = adb("logcat", "-d", "-b", "crash")
    assert f"Process: {PACKAGE}" not in crash, crash


if __name__ == "__main__":
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
