#!/usr/bin/env python3
"""Runtime smoke tests against an isolated PHP built-in server and sandbox tree."""
import json
import os
import pathlib
import re
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

cloud = pathlib.Path(__file__).resolve().parent


def req(url, token=None, method="GET", payload=None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {"X-Forwarded-Proto": "https"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if data is not None:
        headers["Content-Type"] = "application/json"
    call = urllib.request.Request(url, method=method, data=data, headers=headers)
    try:
        with urllib.request.urlopen(call, timeout=6) as result:
            return result.status, json.loads(result.read())
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read())


with tempfile.TemporaryDirectory() as temp:
    base = pathlib.Path(temp)
    private = base / "private"
    private.mkdir()
    root = base / "files"
    root.mkdir()
    (root / "example.txt").write_text("hello", encoding="utf-8")
    (root / "subfolder").mkdir()
    (root / "escape").symlink_to(private)
    php = (
        "<?php return ["
        "'device_token'=>'" + "a" * 64 + "',"
        "'mcp_token'=>'" + "b" * 64 + "',"
        "'cloud_web_password_hash'=>'" + "$2y$10$abcdefghijklmnopqrstuvabcdefghijklmnopqrstuvabcdefghijk" + "',"
        "'cloud_root'=>'" + str(root).replace("\\", "/").replace("'", "\\'") + "',"
        "];"
    )
    (private / "config.php").write_text(php, encoding="utf-8")
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    env = os.environ.copy()
    env["OI_BRIDGE_PRIVATE"] = str(private)
    env["OI_CLOUD_TRUST_PROXY"] = "1"
    proc = subprocess.Popen(
        ["php", "-S", "127.0.0.1:" + str(port), "-t", str(cloud)],
        stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, env=env,
    )
    try:
        url = "http://127.0.0.1:" + str(port) + "/index.php"
        for _ in range(25):
            try:
                status, _ = req(url + "?action=session")
                if status == 200:
                    break
            except Exception:
                time.sleep(0.1)
        else:
            raise RuntimeError("PHP server failed to start")

        assert req(url + "?action=list&path=/")[0] == 401
        assert req(url + "?action=list&path=/", "x" * 64)[0] == 401
        authorized = "a" * 64
        code, data = req(url + "?action=list&path=/", authorized)
        assert code == 200 and any(x["name"] == "example.txt" for x in data["items"]), data
        assert req(url + "?action=list&path=/../private", authorized)[0] == 400
        assert req(url + "?action=list&path=/escape", authorized)[0] == 403
        assert req(url + "?action=mkdir", authorized, "POST", {"path": "/new"})[0] == 200
        assert (root / "new").is_dir()
        assert req(url + "?action=delete", authorized, "POST", {"path": "/subfolder"})[0] == 409
        assert req(url + "?action=delete", authorized, "POST", {"path": "/example.txt"})[0] == 200
        assert not (root / "example.txt").exists()
        initialize = {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
        code, _ = req(url + "?action=mcp", "b" * 64, "POST", initialize)
        assert code == 200
        # Android must not depend on a separately configured browser password.
        without_web = re.sub(r"'cloud_web_password_hash'=>'[^']*',", "", php)
        (private / "config.php").write_text(without_web, encoding="utf-8")
        assert req(url + "?action=status", authorized)[0] == 200
        assert req(url + "?action=list&path=/", authorized)[0] == 200
        assert req(url + "?action=login", method="POST", payload={"password":"unused"})[0] == 503
        print("OI Cloud: auth, list, traversal, symlink, write, delete, MCP, Android without web password: PASS")
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=4)
        except subprocess.TimeoutExpired:
            proc.kill()
