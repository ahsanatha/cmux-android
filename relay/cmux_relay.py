#!/usr/bin/env python3
"""Tailnet-only byte relay from TCP to a local cmux Unix socket."""

from __future__ import annotations

import argparse
import ipaddress
import logging
import socket
import socketserver
import threading


def is_tailnet_address(value: str) -> bool:
    try:
        address = ipaddress.ip_address(value)
    except ValueError:
        return False
    if address.version == 4:
        return address in ipaddress.ip_network("100.64.0.0/10")
    return address.is_private and not address.is_loopback


def resolve_tailnet_bind(value: str) -> str:
    try:
        if is_tailnet_address(value):
            return value
        addresses = {item[4][0] for item in socket.getaddrinfo(value, None)}
    except socket.gaierror as error:
        raise ValueError(f"Cannot resolve bind address: {value}") from error
    if len(addresses) != 1 or not all(is_tailnet_address(item) for item in addresses):
        raise ValueError("--bind must resolve to exactly one Tailscale address")
    return next(iter(addresses))


class RelayHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        upstream = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            upstream.connect(self.server.cmux_socket)  # type: ignore[attr-defined]
            relay(self.request, upstream)
        except OSError:
            logging.info("cmux connection failed from %s", self.client_address[0])
        finally:
            upstream.close()


def relay(client: socket.socket, upstream: socket.socket) -> None:
    def copy(source: socket.socket, target: socket.socket) -> None:
        try:
            while True:
                data = source.recv(64 * 1024)
                if not data:
                    break
                target.sendall(data)
        except OSError:
            pass
        finally:
            try:
                target.shutdown(socket.SHUT_WR)
            except OSError:
                pass

    threads = [threading.Thread(target=copy, args=(source, target), daemon=True)
               for source, target in ((client, upstream), (upstream, client))]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()


class RelayServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True
    request_queue_size = 32

    def __init__(self, address: tuple[str, int], cmux_socket: str):
        self.cmux_socket = cmux_socket
        super().__init__(address, RelayHandler)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", required=True, help="one Tailscale IPv4/IPv6 address")
    parser.add_argument("--port", type=int, default=58466)
    parser.add_argument("--socket", default="/tmp/cmux.sock")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if not 1 <= args.port <= 65535:
        raise SystemExit("--port must be between 1 and 65535")
    bind = resolve_tailnet_bind(args.bind)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    with RelayServer((bind, args.port), args.socket) as server:
        logging.info("cmux relay listening on %s:%d", bind, args.port)
        server.serve_forever()


if __name__ == "__main__":
    main()
