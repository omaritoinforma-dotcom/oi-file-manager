#!/usr/bin/env python3
"""Servidor FTPS real (pyftpdlib) para probar el cliente FTPS de la app.

    python3 scripts/ftps_server.py explicito|implicito CARPETA USUARIO CONTRASEÑA PUERTO CERT CLAVE

«explicito»: se conecta en claro y pasa a TLS con AUTH TLS (puerto 21 en la práctica).
«implicito»: TLS desde el primer byte, antes del saludo (puerto 990 en la práctica).
Necesita `pip install pyftpdlib pyopenssl`. Se queda en primer plano.
"""
import logging
import sys

from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import TLS_FTPHandler
from pyftpdlib.servers import FTPServer


class Implicit(TLS_FTPHandler):
    """FTPS implícito: se activa TLS antes de que el servidor diga nada."""

    def handle(self):
        self.secure_connection(self.get_ssl_context())
        super().handle()


def main():
    mode, folder, user, password, port, cert, key = sys.argv[1:8]
    handler = Implicit if mode == "implicito" else TLS_FTPHandler
    authorizer = DummyAuthorizer()
    authorizer.add_user(user, password, folder, perm="elradfmwMT")
    handler.certfile = cert
    handler.keyfile = key
    handler.authorizer = authorizer
    handler.tls_control_required = True
    handler.tls_data_required = True
    handler.passive_ports = range(30400, 30450) if mode == "explicito" else range(30460, 30510)
    handler.masquerade_address = "127.0.0.1"
    logging.basicConfig(level=logging.WARNING)
    FTPServer(("127.0.0.1", int(port)), handler).serve_forever()


if __name__ == "__main__":
    main()
