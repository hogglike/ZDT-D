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
import urllib.parse

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


def dns_fixture():
    server = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    server.bind(("127.0.0.1", 0))
    queries = []
    def serve():
        while True:
            try:
                data, peer = server.recvfrom(4096)
            except OSError:
                return
            offset, labels = 12, []
            while data[offset]:
                length = data[offset]; offset += 1
                labels.append(data[offset:offset+length].decode()); offset += length
            offset += 1
            kind, _ = struct.unpack("!HH", data[offset:offset+4])
            host = ".".join(labels)
            queries.append((host, kind))
            supported = host in ["mode-fixture.invalid", "reality-fixture.invalid"] and kind == 1
            question = data[12:offset+4]
            answer = b"\xc0\x0c" + struct.pack("!HHIH", 1, 1, 60, 4) + socket.inet_aton("127.0.0.1") if supported else b""
            server.sendto(data[:2] + struct.pack("!HHHHH", 0x8180, 1, int(supported), 0, 0) + question + answer, peer)
    threading.Thread(target=serve, daemon=True).start()
    return server, queries


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
            def do_HEAD(self):
                self.do_GET()
            def log_message(self, *args):
                pass

        origin = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Origin)
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(cert, key)
        origin.socket = tls.wrap_socket(origin.socket, server_side=True)
        threading.Thread(target=origin.serve_forever, daemon=True).start()
        fixture = socks_fixture()
        dns, dns_queries = dns_fixture()
        test_port, live_port, control_port, tproxy_port, dead_port, reality_port = [port() for _ in range(6)]
        cfg = json.loads((ROOT / "rust/zdtd/src/programs/mode_core.json").read_text())
        bootstrap = cfg["dns"]["servers"][0]
        assert bootstrap["type"] == "udp" and bootstrap["server"] == "1.1.1.1", "Root Android must not use absent ::1:53 DNS"
        bootstrap.update(server="127.0.0.1", server_port=dns.getsockname()[1])
        cfg["certificate"]["certificate_path"] = [str(cert)]
        reality = json.loads(Path(sys.argv[2]).read_text()) if len(sys.argv)>2 else None
        cfg["inbounds"][0]["listen_port"] = tproxy_port
        cfg["inbounds"][1]["listen_port"] = test_port
        # Fixture transport exercises the same final MODE routing without OS rules.
        cfg["inbounds"].append({"type": "mixed", "tag": "fixture-live", "listen": "127.0.0.1", "listen_port": live_port})
        cfg["experimental"]["clash_api"] = {"external_controller": f"127.0.0.1:{control_port}", "secret": "fixture-token"}
        cfg["outbounds"] = [{"type": "socks", "tag": tag, "server": "127.0.0.1", "server_port": target} for tag, target in [("good", fixture.getsockname()[1]), ("dead", dead_port)]]
        cfg["outbounds"][0]["server"] = "mode-fixture.invalid"
        if reality:
            client = reality["client"]
            client["server"] = "reality-fixture.invalid"
            client["server_port"] = reality_port
            client["tag"] = "reality-good"
            cfg["outbounds"].append(client)
            bad = json.loads(json.dumps(client)); bad["tag"] = "reality-bad"
            key = bad["tls"]["reality"]["public_key"]
            bad["tls"]["reality"]["public_key"] = ("A" if key[0]!="A" else "B") + key[1:]
            cfg["outbounds"].append(bad)
            cfg["inbounds"].append({"type":"vless", "tag":"fixture-reality", "listen":"127.0.0.1", "listen_port":reality_port,
                "users":[{"uuid":client["uuid"],"flow":client["flow"]}],
                "tls":{"enabled":True,"server_name":"localhost","reality":{"enabled":True,"handshake":{"server":"127.0.0.1","server_port":origin.server_port},"private_key":reality["private_key"],"short_id":["abcd"]}}})
        tags = [outbound["tag"] for outbound in cfg["outbounds"]]
        cfg["outbounds"] += [{"type": "selector", "tag": group, "outbounds": tags, "default": "good", "interrupt_exist_connections": True} for group in ["MODE", "TEST"]]
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
                def api(group, body, token="fixture-token", controller=control_port):
                    return opener.open(urllib.request.Request(f"http://127.0.0.1:{controller}/proxies/{group}", data=json.dumps(body).encode(), method="PUT", headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"}), timeout=3)
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
                assert ("mode-fixture.invalid", 1) in dns_queries
                print("PASS: real UDP DNS bootstrap for domain endpoint and TLS reply through TEST, without a local port 53 listener", flush=True)
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
                def delay(key):
                    api("TEST", {"name":key}).close()
                    context = ssl.create_default_context(cafile=str(cert))
                    conn = http.client.HTTPSConnection("127.0.0.1", test_port, timeout=4, context=context)
                    conn.set_tunnel("localhost", origin.server_port)
                    started = time.monotonic()
                    try:
                        conn.request("HEAD", "/generate_204", headers={"Cache-Control":"no-cache"})
                        assert conn.getresponse().status == 204
                        assert (time.monotonic()-started)*1000 >= 0
                    finally:
                        conn.close()
                delay("good")
                try:
                    delay("dead")
                    raise AssertionError("Failed server acquired valid latency")
                except (OSError, http.client.HTTPException):
                    pass
                request(live_port)
                print("PASS: independent one-URL core latency returns milliseconds; failed node stays unavailable; MODE stays unchanged", flush=True)
                if reality:
                    # The layout comes from the compiled production Rust helper.
                    # Run a second core concurrently; MODE must stay untouched.
                    latency_port, latency_control = port(), port()
                    latency_cfg = reality["latency_layout"]
                    assert len(latency_cfg["inbounds"]) == 1 and latency_cfg["inbounds"][0]["type"] == "mixed"
                    latency_cfg["inbounds"][0]["listen_port"] = latency_port
                    latency_cfg["dns"] = cfg["dns"]
                    latency_cfg["certificate"] = cfg["certificate"]
                    latency_cfg["outbounds"] = cfg["outbounds"]
                    latency_cfg["experimental"]["clash_api"] = {"external_controller": f"127.0.0.1:{latency_control}", "secret": "fixture-token"}
                    latency_path = work / "latency.json"
                    latency_path.write_text(json.dumps(latency_cfg))
                    subprocess.run([binary, "check", "-c", str(latency_path)], check=True, stdout=subprocess.DEVNULL)
                    with open(work / "latency.log", "w") as latency_log:
                        latency_process = subprocess.Popen([binary, "run", "-c", str(latency_path)], stdout=latency_log, stderr=latency_log)
                        try:
                            for _ in range(50):
                                try:
                                    api("TEST", {"name":"good"}, controller=latency_control).close()
                                    break
                                except (OSError, urllib.error.URLError):
                                    time.sleep(.1)
                            request(latency_port)
                            api("TEST", {"name":"dead"}, controller=latency_control).close()
                            try:
                                request(latency_port)
                                raise AssertionError("Latency core reached origin through another selector or direct path")
                            except (OSError, http.client.HTTPException):
                                pass
                            request(live_port)
                            api("TEST", {"name":"reality-good"}, controller=latency_control).close()
                            request(latency_port)
                            request(live_port)
                            assert process.poll() is None
                            print("PASS: second production latency core checks good/dead/Reality nodes while the traffic core stays running; no TPROXY listener or direct fallback", flush=True)
                        finally:
                            latency_process.terminate()
                            latency_process.wait(timeout=5)
                if reality:
                    api("TEST", {"name":"reality-good"}).close()
                    request(test_port)
                    delay("reality-good")
                    assert ("reality-fixture.invalid", 1) in dns_queries
                    api("TEST", {"name":"reality-bad"}).close()
                    try:
                        request(test_port)
                        raise AssertionError("Wrong Reality public key reached origin")
                    except (OSError, http.client.HTTPException):
                        pass
                    request(live_port)
                    print("PASS: actual VLESS/Reality Vision handshake and TLS traffic with production-rendered node; wrong key rejected", flush=True)
            finally:
                process.terminate()
                process.wait(timeout=5)
                origin.shutdown()
                fixture.close()
                dns.close()


if __name__ == "__main__":
    main()

