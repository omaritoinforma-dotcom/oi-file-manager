#!/usr/bin/env python3
"""Tiny authenticated WebDAV fixture server used only by the Android CI smoke test."""

import argparse
import base64
import html
import os
import pathlib
import shutil
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class DavHandler(BaseHTTPRequestHandler):
    server_version = "OI-Smoke-WebDAV/1"

    def log_message(self, fmt, *args):
        print("webdav:", fmt % args, flush=True)

    @property
    def root(self):
        return pathlib.Path(self.server.root).resolve()

    def authorized(self):
        expected = "Basic " + base64.b64encode(
            f"{self.server.user}:{self.server.password}".encode()
        ).decode()
        if self.headers.get("Authorization") == expected:
            return True
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="OI smoke"')
        self.end_headers()
        return False

    def resolve(self, raw=None):
        path = urllib.parse.unquote(urllib.parse.urlparse(raw or self.path).path)
        rel = path.lstrip("/")
        target = (self.root / rel).resolve()
        if target != self.root and self.root not in target.parents:
            raise ValueError("path escapes fixture root")
        return target

    def href(self, target):
        rel = target.resolve().relative_to(self.root).as_posix()
        path = "/" + urllib.parse.quote(rel, safe="/")
        if target.is_dir() and path != "/":
            path += "/"
        return path

    def body(self):
        length = int(self.headers.get("Content-Length", "0") or 0)
        return self.rfile.read(length)

    def empty(self, code=204):
        self.send_response(code)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_PROPFIND(self):
        if not self.authorized():
            return
        try:
            target = self.resolve()
        except ValueError:
            return self.empty(403)
        if not target.exists():
            return self.empty(404)
        items = [target]
        if target.is_dir() and self.headers.get("Depth", "1") != "0":
            items += sorted(target.iterdir(), key=lambda p: p.name.lower())

        def response(item):
            resource = "<d:collection/>" if item.is_dir() else ""
            size = 0 if item.is_dir() else item.stat().st_size
            return (
                "<d:response>"
                f"<d:href>{html.escape(self.href(item))}</d:href>"
                "<d:propstat><d:prop>"
                f"<d:resourcetype>{resource}</d:resourcetype>"
                f"<d:getcontentlength>{size}</d:getcontentlength>"
                "</d:prop></d:propstat>"
                "</d:response>"
            )

        payload = (
            '<?xml version="1.0" encoding="utf-8"?>'
            '<d:multistatus xmlns:d="DAV:">'
            + "".join(response(item) for item in items)
            + "</d:multistatus>"
        ).encode()
        self.send_response(207)
        self.send_header("Content-Type", "application/xml; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        if not self.authorized():
            return
        try:
            target = self.resolve()
        except ValueError:
            return self.empty(403)
        if not target.is_file():
            return self.empty(404)
        size = target.stat().st_size
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(size))
        self.end_headers()
        with target.open("rb") as src:
            while True:
                chunk = src.read(64 * 1024)
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                    self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError):
                    break
                if target.name == "big.bin":
                    time.sleep(0.025)

    def do_PUT(self):
        if not self.authorized():
            return
        try:
            target = self.resolve()
        except ValueError:
            return self.empty(403)
        if self.headers.get("If-None-Match") == "*" and target.exists():
            return self.empty(412)
        target.parent.mkdir(parents=True, exist_ok=True)
        temp = target.with_name("." + target.name + ".upload")
        try:
            with temp.open("wb") as out:
                remaining = int(self.headers.get("Content-Length", "0") or 0)
                while remaining:
                    chunk = self.rfile.read(min(64 * 1024, remaining))
                    if not chunk:
                        break
                    out.write(chunk)
                    remaining -= len(chunk)
                if remaining:
                    return self.empty(400)
            os.replace(temp, target)
            self.empty(201)
        finally:
            if temp.exists():
                temp.unlink()

    def do_MKCOL(self):
        if not self.authorized():
            return
        try:
            target = self.resolve()
        except ValueError:
            return self.empty(403)
        if target.exists():
            return self.empty(405)
        target.mkdir(parents=True)
        self.empty(201)

    def do_MOVE(self):
        if not self.authorized():
            return
        try:
            source = self.resolve()
            destination_header = self.headers.get("Destination")
            if not destination_header:
                return self.empty(400)
            destination = self.resolve(destination_header)
        except ValueError:
            return self.empty(403)
        if not source.exists():
            return self.empty(404)
        if destination.exists() and self.headers.get("Overwrite", "F").upper() == "F":
            return self.empty(412)
        destination.parent.mkdir(parents=True, exist_ok=True)
        os.replace(source, destination)
        self.empty(201)

    def do_DELETE(self):
        if not self.authorized():
            return
        try:
            target = self.resolve()
        except ValueError:
            return self.empty(403)
        if not target.exists():
            return self.empty(404)
        if target.is_dir():
            shutil.rmtree(target)
        else:
            target.unlink()
        self.empty(204)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True)
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--user", default="oi")
    parser.add_argument("--password", default="test")
    args = parser.parse_args()
    root = pathlib.Path(args.root)
    root.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(("0.0.0.0", args.port), DavHandler)
    server.root = str(root)
    server.user = args.user
    server.password = args.password
    print(f"READY {args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
