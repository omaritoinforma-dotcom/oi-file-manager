#!/usr/bin/env python3
"""Start real SFTP, FTP and WebDAV servers for the network client tests.

The three servers share one temporary folder, so the tests can check on disk what
the app's clients really wrote. Requires rclone (RCLONE or PATH) and the Python
`cryptography` package for the SFTP host key.

    python3 scripts/remote_servers.py start   # prints the environment to export
    python3 scripts/remote_servers.py stop

Set OI_REMOTE_BWLIMIT (for example 256k) to throttle the servers.
"""
import base64
import hashlib
import json
import os
import pathlib
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
WORK = ROOT / "build/remote-servers"
PORTS = {"sftp": 2222, "ftp": 2121, "webdav": 8088}


def host_key(path):
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import rsa

    key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
    path.write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.OpenSSH,
            serialization.NoEncryption(),
        )
    )
    path.chmod(0o600)
    blob = base64.b64decode(
        key.public_key().public_bytes(serialization.Encoding.OpenSSH, serialization.PublicFormat.OpenSSH).split()[1]
    )
    return "SHA256:" + base64.b64encode(hashlib.sha256(blob).digest()).decode().rstrip("=")


def wait(port):
    for _ in range(100):
        with socket.socket() as s:
            if s.connect_ex(("127.0.0.1", port)) == 0:
                return
        time.sleep(0.1)
    raise SystemExit(f"Server on port {port} did not start")


def stop():
    state = WORK / "state.json"
    if state.exists():
        for pid in json.loads(state.read_text())["pids"]:
            try:
                os.kill(pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        state.unlink()


def start():
    stop()
    rclone = os.environ.get("RCLONE") or shutil.which("rclone")
    if not rclone:
        raise SystemExit("rclone not found; set RCLONE")
    shutil.rmtree(WORK, ignore_errors=True)
    data = WORK / "data"
    data.mkdir(parents=True)
    password = secrets.token_urlsafe(16)
    fingerprint = host_key(WORK / "host_rsa")
    common = ["--user", "oi", "--pass", password, "--log-file", None]
    pids = []
    for name, extra in (
        ("sftp", ["--key", str(WORK / "host_rsa")]),
        ("ftp", ["--passive-port", "30000-30100"]),
        ("webdav", []),
    ):
        args = [rclone, "serve", name, str(data), "--addr", f"127.0.0.1:{PORTS[name]}", *extra]
        args += [a if a is not None else str(WORK / f"{name}.log") for a in common]
        if os.environ.get("OI_REMOTE_BWLIMIT"):
            # Slow transfers let the Android test interrupt a download halfway.
            args += ["--bwlimit", os.environ["OI_REMOTE_BWLIMIT"]]
        pids.append(subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True).pid)
    for port in PORTS.values():
        wait(port)
    (WORK / "state.json").write_text(json.dumps({"pids": pids}))
    env = {
        "OI_REMOTE_TEST_ROOT": str(data),
        "OI_REMOTE_TEST_PASSWORD": password,
        "OI_REMOTE_TEST_SFTP_FINGERPRINT": fingerprint,
        **{f"OI_REMOTE_TEST_{k.upper()}_PORT": str(v) for k, v in PORTS.items()},
    }
    for key, value in env.items():
        print(f"{key}={value}")


if __name__ == "__main__":
    {"start": start, "stop": stop}[sys.argv[1] if len(sys.argv) > 1 else "start"]()
