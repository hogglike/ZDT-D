"""Live UDP/TCP regression in a disposable Linux network namespace.

Compiles the actual Rust rule builder, then uses those arguments with iptables.
No host or Android routing is changed. A wildcard DNS server represents the
socket binding required by Android dnsmasq; all traffic stays inside the netns.
"""
from pathlib import Path
import errno
import json
import os
import socket
import struct
import subprocess
import sys
import tempfile
import threading

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / "rust/zdtd/src/programs/dns_port_redirect.rs"


def run(*args, check=True):
    result = subprocess.run(args, text=True, capture_output=True)
    if check and result.returncode:
        print(result.stdout + result.stderr, file=sys.stderr, flush=True)
        result.check_returncode()
    return result


def packet():
    return struct.pack("!6H", 0x3157, 0x100, 1, 0, 0, 0) + b"\x07example\x03com\x00\x00\x01\x00\x01"


def recv_exact(sock, count):
    data = b""
    while len(data) < count:
        chunk = sock.recv(count - len(data))
        if not chunk:
            raise AssertionError("truncated TCP DNS response")
        data += chunk
    return data


def serve(address, port, rcode, protocol):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM if protocol == "udp" else socket.SOCK_STREAM)
    sock.bind((address, port))
    if protocol == "tcp":
        sock.listen(8)

    def response(data):
        assert len(data) >= 12
        return data[:2] + struct.pack("!H", 0x8180 | rcode) + data[4:]

    def loop():
        while True:
            if protocol == "udp":
                data, peer = sock.recvfrom(4096)
                sock.sendto(response(data), peer)
            else:
                conn, _ = sock.accept()
                with conn:
                    conn.settimeout(3)
                    length = struct.unpack("!H", recv_exact(conn, 2))[0]
                    result = response(recv_exact(conn, length))
                    conn.sendall(struct.pack("!H", len(result)) + result)

    threading.Thread(target=loop, daemon=True).start()
    return sock


def query(address, protocol, expected):
    request = packet()
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM if protocol == "udp" else socket.SOCK_STREAM) as sock:
        sock.settimeout(3)
        sock.connect((address, 53))
        if protocol == "udp":
            sock.send(request)
            result = sock.recv(4096)
        else:
            sock.sendall(struct.pack("!H", len(request)) + request)
            length = struct.unpack("!H", recv_exact(sock, 2))[0]
            result = recv_exact(sock, length)
    assert result[:2] == request[:2], result.hex()
    flags = struct.unpack("!H", result[2:4])[0]
    assert flags & 0x8000 and flags & 15 == expected, (protocol, address, flags, expected)


def namespace_tests(driver):
    assert os.geteuid() == 0
    run("ip", "link", "set", "lo", "up")
    for address in ("10.253.240.1", "10.253.240.5"):
        run("ip", "addr", "add", address + "/32", "dev", "lo")

    # Reproduce the old collision, including TCP which cannot share wildcard
    # and specific-address listeners just by enabling SO_REUSEADDR.
    for protocol in ("udp", "tcp"):
        kind = socket.SOCK_DGRAM if protocol == "udp" else socket.SOCK_STREAM
        with socket.socket(socket.AF_INET, kind) as old, socket.socket(socket.AF_INET, kind) as system:
            old.bind(("10.253.240.1", 53))
            if protocol == "tcp":
                old.listen(1)
            try:
                system.bind(("0.0.0.0", 53))
            except OSError as error:
                assert error.errno == errno.EADDRINUSE, error
            else:
                raise AssertionError("old listener did not reproduce wildcard collision")
        print(f"PASS: old {protocol} port 53 listener reproduces wildcard collision", flush=True)

    sockets = []
    # Distinct responses identify which listener actually received each query.
    for protocol in ("udp", "tcp"):
        sockets.append(serve("10.253.240.1", 19600, 3, protocol))
        sockets.append(serve("10.253.240.5", 19601, 0, protocol))
        sockets.append(serve("0.0.0.0", 53, 2, protocol))
    print("PASS: two high-port profiles coexist with system UDP and TCP port 53", flush=True)

    def rule(operation, address, port, protocol, check=True):
        args = run(driver, operation, address, str(port), protocol).stdout.strip().split("\t")
        return run("iptables", *args, check=check)

    # A foreign rule must survive both installation and cleanup.
    foreign = ["-t", "nat", "-A", "OUTPUT", "-d", "192.0.2.1", "-p", "udp", "--dport", "53", "-j", "RETURN"]
    run("iptables", *foreign)
    for address, port in (("10.253.240.1", 19600), ("10.253.240.5", 19601)):
        for protocol in ("udp", "tcp"):
            assert rule("-C", address, port, protocol, check=False).returncode != 0
            rule("-I", address, port, protocol)
            rule("-C", address, port, protocol)
    for protocol in ("udp", "tcp"):
        query("10.253.240.1", protocol, 3)
        query("10.253.240.5", protocol, 0)
        query("127.0.0.2", protocol, 2)
        print(f"PASS: {protocol} profiles return their own DNS replies; system DNS is untouched", flush=True)

    for address, port in (("10.253.240.1", 19600), ("10.253.240.5", 19601)):
        for protocol in ("udp", "tcp"):
            rule("-D", address, port, protocol)
            assert rule("-C", address, port, protocol, check=False).returncode != 0
            query(address, protocol, 2)
    foreign[2] = "-C"
    run("iptables", *foreign)
    print("PASS: cleanup removes only owned rules and restores system DNS delivery", flush=True)
    for sock in sockets:
        sock.close()


def main():
    if len(sys.argv) == 3 and sys.argv[1] == "--namespace":
        namespace_tests(sys.argv[2])
        return
    with tempfile.TemporaryDirectory(prefix="zdtd-dns-redirect-") as directory:
        directory = Path(directory)
        driver_src = directory / "driver.rs"
        driver_src.write_text(
            '#[path = ' + json.dumps(str(HELPER)) + '] mod redirect;\n'
            'fn main() { let a: Vec<String> = std::env::args().collect();\n'
            'println!("{}", redirect::rule_args(&a[1], &a[2], a[3].parse().unwrap(), &a[4]).join("\\t")); }\n'
        )
        tests = directory / "unit-tests"
        driver = directory / "driver"
        run("rustc", "--test", str(HELPER), "-o", str(tests))
        result = run(str(tests))
        print(result.stdout, flush=True)
        run("rustc", str(driver_src), "-o", str(driver))
        command = ["unshare", "--net", sys.executable, str(Path(__file__).resolve()), "--namespace", str(driver)]
        if os.geteuid() != 0:
            command.insert(0, "sudo")
        subprocess.run(command, check=True)


if __name__ == "__main__":
    main()
