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
import time

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


def serve(address, port, rcode, protocol, transparent=False):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM if protocol == "udp" else socket.SOCK_STREAM)
    if transparent:
        sock.setsockopt(socket.IPPROTO_IP, getattr(socket, "IP_TRANSPARENT", 19), 1)
    sock.bind((address, port))
    if protocol == "tcp":
        sock.listen(8)
    else:
        # A wildcard listener must reply from the query's destination address.
        # Otherwise a connected UDP client correctly rejects its response.
        pktinfo = getattr(socket, "IP_PKTINFO", 8)  # Linux UAPI IP_PKTINFO
        sock.setsockopt(socket.IPPROTO_IP, pktinfo, 1)
        if transparent:
            sock.setsockopt(socket.IPPROTO_IP, 20, 1)  # IP_RECVORIGDSTADDR

    def response(data):
        assert len(data) >= 12
        return data[:2] + struct.pack("!H", 0x8180 | rcode) + data[4:]

    def loop():
        while True:
            if protocol == "udp":
                data, control, _, peer = sock.recvmsg(4096, 1024)
                destination = next(info[8:12] for level, kind, info in control
                                   if level == socket.IPPROTO_IP and kind == pktinfo)
                if transparent:
                    original = next(info for level, kind, info in control
                                    if level == socket.IPPROTO_IP and kind == 20)
                    address = socket.inet_ntoa(original[4:8])
                    port = struct.unpack("!H", original[2:4])[0]
                    # TPROXY preserves the original destination: a UDP proxy
                    # must reply from that IP AND port, not its listener port.
                    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as reply:
                        reply.setsockopt(socket.IPPROTO_IP, 19, 1)
                        reply.bind((address, port))
                        reply.sendto(response(data), peer)
                    continue
                outgoing = struct.pack("I", 0) + destination + bytes(4)
                sock.sendmsg([response(data)], [(socket.IPPROTO_IP, pktinfo, outgoing)], 0, peer)
            else:
                conn, _ = sock.accept()
                with conn:
                    conn.settimeout(3)
                    length = struct.unpack("!H", recv_exact(conn, 2))[0]
                    result = response(recv_exact(conn, length))
                    conn.sendall(struct.pack("!H", len(result)) + result)

    threading.Thread(target=loop, daemon=True).start()
    return sock


def query(address, protocol, expected, dest_port=53, mark=0):
    request = packet()
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM if protocol == "udp" else socket.SOCK_STREAM) as sock:
        sock.settimeout(3)
        if mark:
            sock.setsockopt(socket.SOL_SOCKET, getattr(socket, "SO_MARK", 36), mark)
        sock.connect((address, dest_port))
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

    # A mode's scoped mark must also win over older NAT/DNAT proxy rules.
    # Real packets prove that other marks and unmarked DNS keep their old paths.
    scope_mark = "0x03000000/0xff000000"
    for protocol in ("udp", "tcp"):
        sockets.append(serve("10.253.240.1", 5353, 1, protocol))
        old_proxy = ["-t", "nat", "-A", "OUTPUT", "-d", "10.253.240.1", "-p", protocol, "--dport", "5353", "-j", "DNAT", "--to-destination", "10.253.240.1:19600"]
        run("iptables", *old_proxy)
        query("10.253.240.1", protocol, 3, 5353)
    for operation in ("-I",):
        args = run(driver, "--mode-nat", operation, scope_mark).stdout.strip().split("\t")
        run("iptables", *args)
    for protocol in ("udp", "tcp"):
        query("10.253.240.1", protocol, 1, 5353, 0x03000000)
        query("10.253.240.1", protocol, 3, 5353, 0x05000000)
        query("10.253.240.1", protocol, 3)
    args = run(driver, "--mode-nat", "-D", scope_mark).stdout.strip().split("\t")
    run("iptables", *args)
    for protocol in ("udp", "tcp"):
        query("10.253.240.1", protocol, 3, 5353, 0x03000000)
    print("PASS: mode mark overrides old DNAT only for its own traffic; DNS/foreign scopes and cleanup survive", flush=True)

    # Exercise actual production TPROXY rule builders, including the DIVERT
    # hook. The mode must carry marked TCP/UDP while normal DNS TCP sockets
    # remain outside DIVERT and both profile listeners keep replying.
    run("ip", "link", "add", "fixture0", "type", "dummy")
    run("ip", "addr", "add", "198.18.0.1/24", "dev", "fixture0")
    run("ip", "link", "set", "fixture0", "up")
    run("ip", "route", "add", "203.0.113.0/24", "dev", "fixture0")
    run("sysctl", "-w", "net.ipv4.conf.all.rp_filter=0")
    run("sysctl", "-w", "net.ipv4.conf.lo.rp_filter=0")
    run("ip", "rule", "add", "pref", "100", "fwmark", "0x01000000/0x01000000", "lookup", "787")
    run("ip", "route", "add", "local", "0.0.0.0/0", "dev", "lo", "table", "787")
    run("iptables", "-t", "mangle", "-N", "FIXTURE_DIVERT")
    run("iptables", "-t", "mangle", "-A", "FIXTURE_DIVERT", "-j", "ACCEPT")
    divert = run(driver, "--divert", "FIXTURE_DIVERT").stdout.strip().split("\t")
    run("iptables", "-t", "mangle", "-A", "PREROUTING", *divert)
    for protocol in ("udp", "tcp"):
        sockets.append(serve("127.0.0.1", 19972, 0, protocol, transparent=True))
        for rule_line in run(driver, "--mark", protocol).stdout.strip().splitlines():
            run("iptables", "-t", "mangle", "-A", "OUTPUT", *rule_line.split("\t"))
        args = run(driver, "--tproxy", protocol).stdout.strip().split("\t")
        run("iptables", "-t", "mangle", "-A", "PREROUTING", *args)
        try:
            query("203.0.113.10", protocol, 0, 1443)
        except Exception:
            print(run("iptables-save", "-c").stdout, flush=True)
            print(run("ip", "rule", "show").stdout, flush=True)
            raise
    def diverted_packets():
        saved = run("iptables-save", "-c", "-t", "mangle").stdout
        counters = next(line.split()[0] for line in saved.splitlines() if "-A FIXTURE_DIVERT " in line)
        return int(counters.strip("[]").split(":")[0])
    time.sleep(0.1)  # Let the completed fixture's final FIN/ACK settle.
    baseline = diverted_packets()
    assert baseline > 0, "Established transparent TCP did not enter DIVERT"
    for _ in range(3):
        for protocol in ("udp", "tcp"):
            query("10.253.240.1", protocol, 3)
            query("10.253.240.5", protocol, 0)
            query("127.0.0.2", protocol, 2)
    assert diverted_packets() == baseline, "Normal DNS sockets were captured by mode DIVERT"
    run("iptables", "-t", "mangle", "-D", "PREROUTING", *divert)
    run("iptables", "-t", "mangle", "-F", "FIXTURE_DIVERT")
    run("iptables", "-t", "mangle", "-X", "FIXTURE_DIVERT")
    run("iptables", "-t", "mangle", "-F", "OUTPUT")
    run("iptables", "-t", "mangle", "-F", "PREROUTING")
    run("ip", "rule", "del", "pref", "100", "fwmark", "0x01000000/0x01000000", "lookup", "787")
    for protocol in ("udp", "tcp"):
        query("10.253.240.1", protocol, 3)
        query("10.253.240.5", protocol, 0)
    print("PASS: production TCP/UDP TPROXY carries selected traffic; normal DNS stays outside DIVERT and survives mode cleanup", flush=True)

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
        source = (ROOT / "rust/zdtd/src/iptables/iptables_tproxy.rs").read_text()
        functions = source[source.index("fn add_mark_rule("):source.index("fn add_rule_idempotent(")]
        driver_src.write_text(
            '#[path = ' + json.dumps(str(HELPER)) + '] mod redirect;\n'
            '#[path = ' + json.dumps(str(ROOT / "rust/zdtd/src/programs/mode_policy.rs")) + '] mod modes;\n'
            'type Result<T> = std::result::Result<T,String>;\n'
            'fn mark_mask_hex(mark:u32)->String {format!("0x{mark:08x}/0xff000000")}\n'
            'fn add_rule_idempotent(_: &str, args:Vec<String>)->Result<()> {println!("{}",args.join("\\t"));Ok(())}\n'
            + functions + '\n'
            'fn main() { let a: Vec<String> = std::env::args().collect();\n'
            'if a[1]=="--divert" {println!("{}",modes::divert_match_args("0x01000000/0x01000000",&a[2]).join("\\t"));return;}\n'
            'if a[1]=="--mark" {add_mark_rule("OUTPUT","0",&a[2],Some("--dport 1443"),"all",&[],0x03000000).unwrap();return;}\n'
            'if a[1]=="--tproxy" {add_tproxy_rule("PREROUTING",&a[2],Some("--dport 1443"),0x03000000,19972).unwrap();return;}\n'
            'if a[1]=="--mode-nat" {println!("{}", modes::nat_bypass_args(&a[2], "OUTPUT", &a[3]).join("\\t"));return;}\n'
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
