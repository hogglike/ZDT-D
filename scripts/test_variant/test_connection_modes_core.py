"""Exercise the production mode core template with real HTTP CONNECT/TLS packets.

All destinations are local fixtures. A SOCKS server forwards bytes to an HTTPS
origin; a dead SOCKS endpoint must fail instead of reaching that origin directly.
The TEST selector must not change MODE. Does not modify iptables or Android state.
"""
from pathlib import Path
import http.client
import json
import select
import socket
import ssl
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]


def port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def exact(s, n):
    data = b""
    while len(data) < n:
        part = s.recv(n - len(data))
        if not part:
            raise ConnectionError("truncated SOCKS reply")
        data += part
    return data


def socks_fixture():
    server = socket.socket()
    server.bind(("127.0.0.1", 0))
    server.listen()

    def handle(client):
        upstream = None
        try:
            client.settimeout(4)
            version, count = exact(client, 2)
            assert version == 5 and 0 in exact(client, count)
            client.sendall(b"\x05\x00")
            version, command, _, kind = exact(client, 4)
            assert version == 5 and command == 1
            if kind == 1:
                host = socket.inet_ntoa(exact(client, 4))
            elif kind == 3:
                host = exact(client, exact(client, 1)[0]).decode()
            else:
                raise AssertionError("Unexpected address family")
            target = struct.unpack("!H", exact(client, 2))[0]
            upstream = socket.create_connection((host, target), 4)
            client.sendall(b"\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x00")
            while True:
                ready, _, _ = select.select([client, upstream], [], [], 4)
                if not ready:
                    return
                for source in ready:
                    data = source.recv(65536)
                    if not data:
                        return
                    (upstream if source is client else client).sendall(data)
        finally:
            client.close()
            if upstream:
                upstream.close()

    def accept():
        while True:
            client, _ = server.accept()
            threading.Thread(target=handle, args=(client,), daemon=True).start()

    threading.Thread(target=accept, daemon=True).start()
    return server


def main():
    binary = sys.argv[1]
    with tempfile.TemporaryDirectory(prefix="zdtd-mode-core-") as work:
        work = Path(work)
        cert = work / "cert.pem"
        key = work / "key.pem"
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", str(key), "-out", str(cert), "-days", "1", "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        import http.server

        class Origin(http.server.BaseHTTPRequestHandler):
            requests = 0
            def do_GET(self):
                type(self).requests += 1
                self.send_response(204)
                self.end_headers()
            def log_message(self, *args):
                pass

        origin = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Origin)
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(cert, key)
        origin.socket = tls.wrap_socket(origin.socket, server_side=True)
        threading.Thread(target=origin.serve_forever, daemon=True).start()
        fixture = socks_fixture()
        test_port, live_port, control_port, tproxy_port, dead_port = [port() for _ in range(5)]
        cfg = json.loads((ROOT / "rust/zdtd/src/programs/mode_core.json").read_text())
        cfg["inbounds"][0]["listen_port"] = tproxy_port
        cfg["inbounds"][1]["listen_port"] = test_port
        # Fixture transport exercises the same final MODE routing without OS rules.
        cfg["inbounds"].append({"type": "mixed", "tag": "fixture-live", "listen": "127.0.0.1", "listen_port": live_port})
        cfg["experimental"]["clash_api"] = {"external_controller": f"127.0.0.1:{control_port}", "secret": "fixture-token"}
        cfg["outbounds"] = [{"type": "socks", "tag": tag, "server": "127.0.0.1", "server_port": target} for tag, target in [("good", fixture.getsockname()[1]), ("dead", dead_port)]]
        cfg["outbounds"] += [{"type": "selector", "tag": group, "outbounds": ["good", "dead"], "default": "good", "interrupt_exist_connections": True} for group in ["MODE", "TEST"]]
        path = work / "core.json"
        path.write_text(json.dumps(cfg))
        subprocess.run([binary, "check", "-c", str(path)], check=True)
        with (work / "core.log").open("w+") as log:
            process = subprocess.Popen([binary, "run", "-c", str(path)], stdout=log, stderr=log)
            try:
                for _ in range(50):
                    if process.poll() is not None:
                        log.seek(0)
                        raise AssertionError(log.read())
                    try:
                        socket.create_connection(("127.0.0.1", control_port), 0.1).close()
                        break
                    except OSError:
                        time.sleep(0.1)
                opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                def api(group, body, token="fixture-token"):
                    return opener.open(urllib.request.Request(f"http://127.0.0.1:{control_port}/proxies/{group}", data=json.dumps(body).encode(), method="PUT", headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"}), timeout=3)
                try:
                    api("TEST", {"name": "dead"}, "wrong-token")
                    raise AssertionError("Unauthenticated selector change accepted")
                except urllib.error.HTTPError as e:
                    assert e.code == 401
                def request(proxy_port, trusted=True):
                    context = ssl.create_default_context(cafile=str(cert)) if trusted else ssl.create_default_context()
                    conn = http.client.HTTPSConnection("127.0.0.1", proxy_port, timeout=4, context=context)
                    conn.set_tunnel("localhost", origin.server_port)
                    try:
                        conn.request("GET", "/generate_204")
                        assert conn.getresponse().status == 204
                    finally:
                        conn.close()
                request(test_port)
                print("PASS: real TLS reply through TEST and selected SOCKS server", flush=True)
                api("TEST", {"name": "dead"}).close()
                count = Origin.requests
                try:
                    request(test_port)
                    raise AssertionError("Dead proxy silently used direct access")
                except (OSError, http.client.HTTPException):
                    pass
                assert Origin.requests == count
                request(live_port)
                print("PASS: failed TEST never falls back to direct; MODE stays on working server", flush=True)
                api("TEST", {"name": "good"}).close()
                try:
                    request(test_port, trusted=False)
                    raise AssertionError("Untrusted TLS certificate accepted")
                except ssl.SSLCertVerificationError:
                    pass
                print("PASS: probe transport validates TLS certificates", flush=True)
            finally:
                process.terminate()
                process.wait(timeout=5)
                origin.shutdown()
                fixture.close()


if __name__ == "__main__":
    main()
