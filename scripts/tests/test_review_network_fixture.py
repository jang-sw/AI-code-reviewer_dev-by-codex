"""Owned loopback echo fixtures only; no database, Java, network rules or external host."""

import importlib.util
from pathlib import Path
import socket
import threading
import time
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location('review_network_fixture', Path(__file__).absolute().parents[1] / 'review-network-fixture.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class EchoServer:
    def __init__(self):
        self.listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.listener.bind(('127.0.0.1', 0))
        self.listener.listen(32)
        self.listener.settimeout(0.1)
        self.port = self.listener.getsockname()[1]
        self.stop = threading.Event()
        self.lock = threading.Lock()
        self.clients = set()
        self.workers = []
        self.received = bytearray()
        self.thread = threading.Thread(target=self.accept, daemon=True)
        self.thread.start()

    def accept(self):
        while not self.stop.is_set():
            try:
                connection, _ = self.listener.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            connection.settimeout(0.1)
            with self.lock:
                self.clients.add(connection)
                worker = threading.Thread(target=self.echo, args=(connection,), daemon=True)
                self.workers.append(worker)
            worker.start()

    def echo(self, connection):
        try:
            while not self.stop.is_set():
                try:
                    value = connection.recv(65_536)
                except socket.timeout:
                    continue
                if not value:
                    return
                with self.lock:
                    self.received.extend(value)
                connection.sendall(value)
        except OSError:
            pass
        finally:
            with self.lock:
                self.clients.discard(connection)
            connection.close()

    def contents(self):
        with self.lock:
            return bytes(self.received)

    def connected(self):
        with self.lock:
            return len(self.clients)

    def send(self, payload):
        with self.lock:
            clients = tuple(self.clients)
        for connection in clients:
            connection.sendall(payload)

    def close(self):
        self.stop.set()
        self.listener.close()
        self.thread.join(2)
        with self.lock:
            clients, workers = tuple(self.clients), tuple(self.workers)
        for connection in clients:
            try:
                connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            connection.close()
        deadline = time.monotonic() + 2
        for worker in workers:
            worker.join(max(0, deadline - time.monotonic()))
        if self.thread.is_alive() or any(worker.is_alive() for worker in workers):
            raise AssertionError('Owned echo fixture did not stop')


class OwnedTcpProxyTest(unittest.TestCase):
    def setUp(self):
        self.server = EchoServer()
        self.addCleanup(self.server.close)

    def proxy(self, **options):
        result = MODULE.OwnedTcpProxy(self.server.port, **options).start()
        self.addCleanup(result.close)
        return result

    def connect(self, proxy):
        client = socket.create_connection(('127.0.0.1', proxy.port), timeout=1)
        client.settimeout(1)
        self.addCleanup(client.close)
        return client

    def wait(self, condition):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            if condition():
                return
            time.sleep(0.01)
        self.fail('Owned loopback fixture did not reach its bounded expected state')

    def receive(self, client, length):
        value = bytearray()
        while len(value) < length:
            part = client.recv(length - len(value))
            self.assertTrue(part)
            value.extend(part)
        return bytes(value)

    def assert_closed(self, client):
        client.settimeout(1)
        try:
            self.assertEqual(b'', client.recv(1))
        except (ConnectionResetError, ConnectionAbortedError):
            pass

    def test_normal_forwarding_preserves_bytes_and_tcp_half_close(self):
        proxy = self.proxy()
        client = self.connect(proxy)
        payload = bytes(range(256)) * 512
        client.sendall(payload)
        client.shutdown(socket.SHUT_WR)
        self.assertEqual(payload, self.receive(client, len(payload)))
        self.assert_closed(client)
        self.wait(lambda: proxy.metrics()['active'] == 0)
        self.assertEqual(payload, self.server.contents())
        metrics = proxy.metrics()
        self.assertEqual(len(payload) * 2, metrics['forwardedBytes'])
        self.assertEqual(0, metrics['droppedBytes'])
        self.assertFalse(metrics['failed'])

    def test_pause_discards_both_directions_and_new_connections_without_replay(self):
        proxy = self.proxy()
        original = self.connect(proxy)
        original.sendall(b'visible')
        self.assertEqual(b'visible', self.receive(original, 7))
        proxy.pause()
        self.assertEqual('BLACKHOLE', proxy.metrics()['mode'])
        hidden_client, hidden_server = b'client-hidden-fixture', b'server-hidden-fixture'
        original.sendall(hidden_client)
        self.wait(lambda: self.server.connected() == 1)
        self.server.send(hidden_server)
        self.wait(lambda: proxy.metrics()['droppedBytes'] >= len(hidden_client) + len(hidden_server))
        self.assertEqual(b'visible', self.server.contents())
        original.settimeout(0.15)
        with self.assertRaises(socket.timeout):
            original.recv(1)
        another = self.connect(proxy)
        another.sendall(b'new-hidden-fixture')
        self.wait(lambda: proxy.metrics()['active'] == 2 and proxy.metrics()['droppedBytes'] >=
                  len(hidden_client) + len(hidden_server) + len(b'new-hidden-fixture'))
        another.settimeout(0.15)
        with self.assertRaises(socket.timeout):
            another.recv(1)
        self.assertEqual(b'visible', self.server.contents())
        self.assertEqual(2, proxy.metrics()['active'])
        proxy.heal()
        self.assert_closed(original)
        self.assert_closed(another)
        self.assertEqual('FORWARD', proxy.metrics()['mode'])
        self.assertEqual(0, proxy.metrics()['active'])
        fresh = self.connect(proxy)
        fresh.sendall(b'fresh')
        self.assertEqual(b'fresh', self.receive(fresh, 5))
        self.assertEqual(b'visiblefresh', self.server.contents())

    def test_paused_client_eof_releases_its_upstream_instead_of_leaking_a_session(self):
        proxy = self.proxy()
        client = self.connect(proxy)
        client.sendall(b'initial')
        self.assertEqual(b'initial', self.receive(client, 7))
        proxy.pause()
        client.close()
        self.wait(lambda: proxy.metrics()['active'] == 0 and self.server.connected() == 0)
        self.assertFalse(proxy.metrics()['failed'])

    def test_heal_closes_every_existing_idle_pair_before_accepting_a_new_generation(self):
        proxy = self.proxy()
        clients = [self.connect(proxy) for _ in range(4)]
        self.wait(lambda: proxy.metrics()['active'] == 4 and self.server.connected() == 4)
        proxy.pause()
        proxy.heal()
        for client in clients:
            self.assert_closed(client)
        self.assertEqual(0, proxy.metrics()['active'])
        fresh = self.connect(proxy)
        fresh.sendall(b'healed')
        self.assertEqual(b'healed', self.receive(fresh, 6))

    def test_close_is_bounded_with_paused_idle_sockets_and_leaves_the_target_alive(self):
        proxy = self.proxy()
        clients = [self.connect(proxy) for _ in range(4)]
        self.wait(lambda: proxy.metrics()['active'] == 4)
        proxy.pause()
        started = time.monotonic()
        proxy.close()
        self.assertLess(time.monotonic() - started, MODULE.OwnedTcpProxy.CLOSE_TIMEOUT + 1)
        self.assertFalse(proxy._thread.is_alive())
        self.assertEqual('CLOSED', proxy.metrics()['mode'])
        self.assertEqual(0, proxy.metrics()['active'])
        for client in clients:
            self.assert_closed(client)
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.settimeout(1)
            self.assertNotEqual(0, probe.connect_ex(('127.0.0.1', proxy.port)))
        with socket.create_connection(('127.0.0.1', self.server.port), timeout=1) as direct:
            direct.sendall(b'target-still-alive')
            self.assertEqual(b'target-still-alive', self.receive(direct, 18))
        proxy.close()

    def test_capacity_rejects_only_excess_clients_and_releases_slots_after_disconnect(self):
        proxy = self.proxy(maximum_connections=2)
        first, second = self.connect(proxy), self.connect(proxy)
        self.wait(lambda: proxy.metrics()['active'] == 2)
        excess = self.connect(proxy)
        self.wait(lambda: proxy.metrics()['rejected'] == 1)
        self.assert_closed(excess)
        self.assertEqual(2, proxy.metrics()['active'])
        self.assertEqual(2, proxy.metrics()['peakActive'])
        first.sendall(b'kept')
        self.assertEqual(b'kept', self.receive(first, 4))
        first.close()
        self.wait(lambda: proxy.metrics()['active'] == 1)
        replacement = self.connect(proxy)
        replacement.sendall(b'replacement')
        self.assertEqual(b'replacement', self.receive(replacement, 11))
        self.assertEqual(2, proxy.metrics()['active'])
        second.sendall(b'second')
        self.assertEqual(b'second', self.receive(second, 6))

    def test_invalid_arguments_are_rejected_before_any_socket_is_created(self):
        for values in ({'target_port': value} for value in (True, 0, 1023, 65536, '5432', None)):
            with self.subTest(values=values), patch.object(MODULE.socket, 'socket') as create:
                with self.assertRaises(MODULE.ProxyError):
                    MODULE.OwnedTcpProxy(**values)
                create.assert_not_called()
        for values in ({'listen_port': True}, {'listen_port': 1}, {'listen_port': 65536},
                       {'listen_port': self.server.port}, {'maximum_connections': True},
                       {'maximum_connections': 0}, {'maximum_connections': 33}, {'maximum_connections': '2'}):
            with self.subTest(values=values), patch.object(MODULE.socket, 'socket') as create:
                with self.assertRaises(MODULE.ProxyError):
                    MODULE.OwnedTcpProxy(self.server.port, **values)
                create.assert_not_called()

    def test_occupied_listener_is_preserved_and_failure_has_only_a_fixed_diagnostic(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as occupied:
            occupied.bind(('127.0.0.1', 0))
            occupied.listen(1)
            port = occupied.getsockname()[1]
            proxy = MODULE.OwnedTcpProxy(self.server.port, listen_port=port)
            with self.assertRaisesRegex(MODULE.ProxyError, '^The loopback proxy could not start[.]$') as caught:
                proxy.start()
            self.assertIsNone(caught.exception.__cause__)
            self.assertEqual('CLOSED', proxy.metrics()['mode'])
            with socket.create_connection(('127.0.0.1', port), timeout=1):
                connection, _ = occupied.accept()
                connection.close()

    def test_metrics_are_copies_of_fixed_aggregates_and_lifecycle_is_single_use(self):
        proxy = self.proxy()
        expected = {'accepted', 'active', 'peakActive', 'rejected', 'droppedBytes',
                    'forwardedBytes', 'connectionErrors', 'mode', 'failed'}
        metrics = proxy.metrics()
        self.assertEqual(expected, set(metrics))
        self.assertTrue(all(type(value) in (int, bool, str) for value in metrics.values()))
        metrics['mode'] = 'caller-mutated'
        self.assertEqual('FORWARD', proxy.metrics()['mode'])
        with self.assertRaises(MODULE.ProxyError):
            proxy.start()
        proxy.close()
        for operation in (proxy.pause, proxy.heal, proxy.start):
            with self.assertRaises(MODULE.ProxyError):
                operation()

    def test_close_before_start_is_safe_and_prevents_a_later_start(self):
        proxy = MODULE.OwnedTcpProxy(self.server.port)
        with self.assertRaises(MODULE.ProxyError):
            _ = proxy.port
        proxy.close()
        proxy.close()
        self.assertEqual('CLOSED', proxy.metrics()['mode'])
        with self.assertRaises(MODULE.ProxyError):
            proxy.start()


if __name__ == '__main__':
    unittest.main()
