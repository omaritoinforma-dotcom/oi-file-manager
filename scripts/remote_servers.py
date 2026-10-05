#!/usr/bin/env python3
"""Arranca servidores SFTP, FTP y WebDAV reales para las pruebas de los clientes de red.

Los tres comparten una carpeta temporal, así las pruebas comprueban en disco lo que los
clientes de la app escribieron de verdad. Necesita rclone (RCLONE o PATH) y el paquete
de Python `cryptography` para la clave del servidor SFTP.

    python3 scripts/remote_servers.py start   # muestra las variables que hay que exportar
    python3 scripts/remote_servers.py stop

OI_REMOTE_BWLIMIT (por ejemplo 256k) limita la velocidad de los servidores.
OI_REMOTE_FTPS=1 arranca además dos servidores FTPS (explícito e implícito, con pyftpdlib) con un
certificado de prueba, y un almacén de confianza (OI_REMOTE_TEST_TRUSTSTORE) con los certificados
del sistema y el de prueba, que es lo que el cliente de la app necesita para validarlos. Pide
`pip install pyftpdlib pyopenssl` y `keytool` (JDK).
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
# Segunda cuenta SFTP (usuario «ana», otra contraseña y otra carpeta): varias cuentas del mismo servicio.
SECOND_SFTP_PORT = 2224
FTPS_PORTS = {"ftps": 2123, "ftps_implicit": 2122}


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


def test_certificate(folder):
    """Certificado de prueba para 127.0.0.1 y localhost, y un almacén de confianza que lo incluye."""
    import datetime
    import ipaddress

    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "localhost")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(minutes=5))
        .not_valid_after(now + datetime.timedelta(days=2))
        .add_extension(
            x509.SubjectAlternativeName([x509.DNSName("localhost"), x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]),
            critical=False,
        )
        .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )
    cert_file, key_file, store = folder / "ftps-cert.pem", folder / "ftps-key.pem", folder / "truststore.jks"
    cert_file.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    key_file.write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.TraditionalOpenSSL, serialization.NoEncryption()
        )
    )
    keytool = shutil.which("keytool")
    if not keytool:
        raise SystemExit("keytool no encontrado (hace falta un JDK)")
    java_home = pathlib.Path(os.path.realpath(keytool)).parent.parent
    system_store = java_home / "lib/security/cacerts"
    shutil.copy(system_store, store)
    store.chmod(0o644)
    subprocess.run(
        [keytool, "-importcert", "-noprompt", "-alias", "oi-prueba", "-file", str(cert_file),
         "-keystore", str(store), "-storepass", "changeit"],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    return cert_file, key_file, store


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
    # Sin caché de carpetas: las pruebas también crean archivos directamente en el disco y el
    # servidor debe verlos al momento (con la caché de 5 minutos de rclone no aparecían).
    common = ["--user", "oi", "--pass", password, "--dir-cache-time", "0s", "--log-file", None]
    pids = []
    for name, extra in (
        ("sftp", ["--key", str(WORK / "host_rsa")]),
        ("ftp", ["--passive-port", "30000-30100"]),
        ("webdav", []),
    ):
        args = [rclone, "serve", name, str(data), "--addr", f"127.0.0.1:{PORTS[name]}", *extra]
        args += [a if a is not None else str(WORK / f"{name}.log") for a in common]
        if os.environ.get("OI_REMOTE_BWLIMIT"):
            # Con transferencias lentas, la prueba de Android puede interrumpir una descarga a medias.
            args += ["--bwlimit", os.environ["OI_REMOTE_BWLIMIT"]]
        pids.append(subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True).pid)
    # Segunda cuenta en un servidor SFTP aparte, con su propia carpeta.
    data2 = WORK / "data-ana"
    data2.mkdir(parents=True)
    password2 = secrets.token_urlsafe(16)
    pids.append(
        subprocess.Popen(
            [rclone, "serve", "sftp", str(data2), "--addr", f"127.0.0.1:{SECOND_SFTP_PORT}", "--key", str(WORK / "host_rsa"),
             "--user", "ana", "--pass", password2, "--dir-cache-time", "0s", "--log-file", str(WORK / "sftp-ana.log")],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True,
        ).pid
    )
    ftps_env = {}
    if os.environ.get("OI_REMOTE_FTPS"):
        cert, key, store = test_certificate(WORK)
        for mode, port in (("explicito", FTPS_PORTS["ftps"]), ("implicito", FTPS_PORTS["ftps_implicit"])):
            pids.append(
                subprocess.Popen(
                    [sys.executable, str(ROOT / "scripts/ftps_server.py"), mode, str(data), "oi", password, str(port), str(cert), str(key)],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True,
                ).pid
            )
        for port in FTPS_PORTS.values():
            wait(port)
        ftps_env = {
            "OI_REMOTE_TEST_FTPS_PORT": str(FTPS_PORTS["ftps"]),
            "OI_REMOTE_TEST_FTPS_IMPLICIT_PORT": str(FTPS_PORTS["ftps_implicit"]),
            "OI_REMOTE_TEST_TRUSTSTORE": str(store),
        }
    for port in [*PORTS.values(), SECOND_SFTP_PORT]:
        wait(port)
    (WORK / "state.json").write_text(json.dumps({"pids": pids}))
    env = {
        "OI_REMOTE_TEST_ROOT": str(data),
        "OI_REMOTE_TEST_PASSWORD": password,
        "OI_REMOTE_TEST_SFTP_FINGERPRINT": fingerprint,
        **{f"OI_REMOTE_TEST_{k.upper()}_PORT": str(v) for k, v in PORTS.items()},
        "OI_REMOTE_TEST_ROOT2": str(data2),
        "OI_REMOTE_TEST_PASSWORD2": password2,
        "OI_REMOTE_TEST_SFTP2_PORT": str(SECOND_SFTP_PORT),
        **ftps_env,
    }
    for key, value in env.items():
        print(f"{key}={value}")


if __name__ == "__main__":
    {"start": start, "stop": stop}[sys.argv[1] if len(sys.argv) > 1 else "start"]()
