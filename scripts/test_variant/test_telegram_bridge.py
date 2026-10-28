"""Real SOCKS5 auth, bytes and failover through the production Rust-rendered layout.
No external test destinations, OS routes, Telegram account or credentials.
"""
import json, socket, struct, subprocess, sys, tempfile, threading, time, urllib.request
from pathlib import Path
from test_connection_modes_core import port, exact, socks_fixture

def main():
    cfg = json.loads(Path(sys.argv[2]).read_text())
    upstream = socks_fixture()
    echo = socket.socket(); echo.bind(("127.0.0.1", 0)); echo.listen()
    delivered = []
    def serve():
        while True:
            client, _ = echo.accept()
            with client:
                data = client.recv(128); delivered.append(data); client.sendall(data)
    threading.Thread(target=serve, daemon=True).start()
    client_port, control, dead = [port() for _ in range(3)]
    cfg["route"]["auto_detect_interface"] = False  # all fixture endpoints are loopback; no OS route inspection
    cfg["inbounds"][0]["listen_port"] = client_port
    cfg["outbounds"][0]["server_port"] = upstream.getsockname()[1]
    cfg["outbounds"][1]["server_port"] = dead
    cfg["experimental"]["clash_api"]["external_controller"] = f"127.0.0.1:{control}"
    with tempfile.TemporaryDirectory() as work:
        path = Path(work) / "core.json"; path.write_text(json.dumps(cfg))
        subprocess.run([sys.argv[1], "check", "-c", str(path)], check=True)
        with (Path(work) / "core.log").open("w+") as log:
            process = subprocess.Popen([sys.argv[1], "run", "-c", str(path)], stdout=log, stderr=log)
            try:
                for _ in range(40):
                    if process.poll() is not None:
                        log.seek(0); raise AssertionError(log.read())
                    try: socket.create_connection(("127.0.0.1", control), .1).close(); break
                    except OSError: time.sleep(.1)
                def connect(password=b"fixture-password"):
                    s = socket.create_connection(("127.0.0.1", client_port), 3); s.settimeout(3)
                    s.sendall(b"\x05\x01\x02"); assert exact(s, 2) == b"\x05\x02"
                    s.sendall(b"\x01\x04zdtd" + bytes([len(password)]) + password)
                    if exact(s, 2) != b"\x01\x00": s.close(); raise PermissionError("credentials rejected")
                    s.sendall(b"\x05\x01\x00\x01\x7f\x00\x00\x01" + struct.pack("!H", echo.getsockname()[1]))
                    reply = exact(s, 4)
                    if reply[1] != 0: s.close(); raise ConnectionError("upstream unavailable")
                    if reply[3] == 1: exact(s, 6)
                    elif reply[3] == 4: exact(s, 18)
                    else: exact(s, exact(s, 1)[0] + 2)
                    return s
                try: connect(b"wrong"); raise AssertionError("bad password accepted")
                except PermissionError: pass
                def exchange():
                    with connect() as s:
                        s.sendall(b"telegram-fixture"); assert exact(s, 16) == b"telegram-fixture"
                exchange()
                def select(key):
                    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                    req = urllib.request.Request(f"http://127.0.0.1:{control}/proxies/TELEGRAM", data=json.dumps({"name": key}).encode(), method="PUT", headers={"Authorization":"Bearer fixture-token", "Content-Type":"application/json"})
                    with opener.open(req, timeout=3) as response: assert response.status == 204
                select("dead"); before = len(delivered)
                try: exchange(); raise AssertionError("failed server used direct fallback")
                except (OSError, ConnectionError): pass
                assert len(delivered) == before
                select("good"); exchange()
                assert len(delivered) == before + 1
                print("PASS: authenticated SOCKS5 forwards bytes; failed server cannot bypass proxy; switching restores connection")
            finally:
                process.terminate(); process.wait(timeout=5)
    echo.close(); upstream.close()
if __name__ == "__main__": main()
