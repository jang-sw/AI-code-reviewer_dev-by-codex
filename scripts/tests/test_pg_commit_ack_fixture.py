"""Owned synthetic PostgreSQL wire peers only; no database, Java or external host."""
import importlib.util
from pathlib import Path
import socket
import struct
import threading
import time
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('pg_commit_ack_fixture', Path(__file__).absolute().parents[1] / 'pg-commit-ack-fixture.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
APPLICATION = 'restart_test_' + 'a' * 32
USER = 'reviewer_test'
SECRET = 0x12345678  # Synthetic cancellation key, never a real credential.


def frame(kind, body=b''):
    return kind + struct.pack('!I', len(body) + 4) + body


COMMIT = frame(b'C', b'COMMIT\0')
IDLE = frame(b'Z', b'I')


def startup(fields=None):
    fields = fields if fields is not None else ((b'user', USER.encode()), (b'database', b'reviewer_integration'),
                                               (b'application_name', APPLICATION.encode()))
    payload = struct.pack('!I', 196608) + b''.join(key + b'\0' + value + b'\0' for key, value in fields) + b'\0'
    return struct.pack('!I', len(payload) + 4) + payload


class FakePostgres:
    """Framed startup replies plus test-controlled backend output, all loopback."""
    def __init__(self):
        self.listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.listener.bind(('127.0.0.1', 0))
        self.listener.listen(32)
        self.listener.settimeout(0.1)
        self.port = self.listener.getsockname()[1]
        self.stop, self.lock = threading.Event(), threading.Lock()
        self.clients, self.received = {}, {}
        self.cancels, self.workers = [], []
        self.next_pid = 10000
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
                pid = self.next_pid
                self.next_pid += 1
                self.clients[pid] = connection
                self.received[pid] = bytearray()
                worker = threading.Thread(target=self.serve, args=(connection, pid), daemon=True)
                self.workers.append(worker)
            worker.start()

    def exact(self, connection, length):
        data = bytearray()
        while len(data) < length and not self.stop.is_set():
            try:
                part = connection.recv(length - len(data))
            except socket.timeout:
                continue
            if not part:
                raise EOFError()
            data.extend(part)
        if len(data) != length:
            raise EOFError()
        return bytes(data)

    def serve(self, connection, pid):
        try:
            length = struct.unpack('!I', self.exact(connection, 4))[0]
            if not 8 <= length <= 8192:
                raise EOFError()
            body = self.exact(connection, length - 4)
            if struct.unpack('!I', body[:4])[0] == 80877102:
                with self.lock:
                    self.cancels.append(body)
                return
            connection.sendall(frame(b'R', struct.pack('!I', 0))
                               + frame(b'K', struct.pack('!II', pid, SECRET)) + IDLE)
            while not self.stop.is_set():
                try:
                    data = connection.recv(65536)
                except socket.timeout:
                    continue
                if not data:
                    return
                with self.lock:
                    self.received[pid].extend(data)
        except (OSError, EOFError):
            pass
        finally:
            with self.lock:
                self.clients.pop(pid, None)
            connection.close()

    def send(self, pid, payload):
        with self.lock:
            connection = self.clients[pid]
        connection.sendall(payload)

    def disconnect(self, pid):
        with self.lock:
            connection = self.clients[pid]
        connection.shutdown(socket.SHUT_RDWR)

    def contents(self, pid):
        with self.lock:
            return bytes(self.received[pid])

    def connected(self, pid):
        with self.lock:
            return pid in self.clients

    def cancel_count(self):
        with self.lock:
            return len(self.cancels)

    def close(self):
        self.stop.set()
        self.listener.close()
        self.thread.join(2)
        with self.lock:
            clients, workers = tuple(self.clients.values()), tuple(self.workers)
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
            raise AssertionError('Owned synthetic PostgreSQL peer did not stop')


class PgCommitAckProxyTest(unittest.TestCase):
    def setUp(self):
        self.server = FakePostgres()
        self.addCleanup(self.server.close)

    def proxy(self, *, expect_failure=False, **options):
        proxy = MODULE.PgCommitAckProxy(self.server.port, APPLICATION, **options).start()
        def cleanup():
            if expect_failure and proxy.metrics()['failed']:
                with self.assertRaises(MODULE.ProxyError):
                    proxy.close()
            else:
                proxy.close()
        self.addCleanup(cleanup)
        return proxy

    def connect(self, proxy):
        client = socket.create_connection(('127.0.0.1', proxy.port), timeout=1)
        client.settimeout(1)
        self.addCleanup(client.close)
        return client

    def receive(self, client, length):
        data = bytearray()
        while len(data) < length:
            part = client.recv(length - len(data))
            self.assertTrue(part)
            data.extend(part)
        return bytes(data)

    def established(self, proxy):
        client = self.connect(proxy)
        client.sendall(startup())
        welcome = self.receive(client, 28)
        self.assertEqual(frame(b'R', struct.pack('!I', 0)), welcome[:9])
        self.assertEqual(b'K\0\0\0\x0c', welcome[9:14])
        self.assertEqual(IDLE, welcome[-6:])
        pid = struct.unpack('!I', welcome[14:18])[0]
        return client, pid

    def wait(self, condition):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            if condition():
                return
            time.sleep(0.01)
        self.fail('Owned protocol fixture did not reach its bounded expected state')

    def silent(self, client):
        client.settimeout(0.1)
        try:
            with self.assertRaises(socket.timeout):
                client.recv(1)
        finally:
            client.settimeout(1)

    def closed(self, client):
        client.settimeout(1)
        try:
            self.assertEqual(b'', client.recv(1))
        except (ConnectionResetError, ConnectionAbortedError):
            pass

    def test_only_exact_target_commit_is_lost_and_other_session_stays_usable(self):
        proxy = self.proxy()
        target, pid = self.established(proxy)
        other, other_pid = self.established(proxy)
        proxy.arm_backend(pid)
        self.assertEqual('ARMED', proxy.metrics()['faultState'])
        ordinary = (frame(b't', struct.pack('!HI', 1, 25)) + frame(b'D', b'COMMIT\0')
                    + frame(b'C', b'ROLLBACK\0') + frame(b'C', b'COMMIT PREPARED\0') + IDLE)
        self.server.send(pid, ordinary)
        self.assertEqual(ordinary, self.receive(target, len(ordinary)))
        self.assertEqual(0, proxy.metrics()['commitAcknowledgementsDropped'])
        self.server.send(other_pid, COMMIT + IDLE)
        self.assertEqual(COMMIT + IDLE, self.receive(other, len(COMMIT + IDLE)))
        before = frame(b'C', b'UPDATE 1\0')
        self.server.send(pid, before + COMMIT + IDLE + frame(b'N', b'synthetic-after-commit'))
        self.assertEqual(before, self.receive(target, len(before)))
        self.wait(lambda: proxy.metrics()['readyForQueryIdleDropped'])
        self.silent(target)
        request = frame(b'Q', b'synthetic frontend still forwarded\0')
        target.sendall(request)
        self.wait(lambda: self.server.contents(pid) == request)
        self.assertEqual('DROPPING', proxy.metrics()['faultState'])
        self.assertEqual(1, proxy.metrics()['commitAcknowledgementsDropped'])
        self.assertFalse(proxy.metrics()['failed'])

    def test_target_commit_header_is_not_leaked_when_header_and_body_arrive_separately(self):
        proxy = self.proxy()
        client, pid = self.established(proxy)
        proxy.arm_backend(pid)
        for part in (COMMIT[:1], COMMIT[1:5], COMMIT[5:8]):
            self.server.send(pid, part)
            self.silent(client)
            self.assertEqual(0, proxy.metrics()['commitAcknowledgementsDropped'])
        self.server.send(pid, COMMIT[8:] + IDLE)
        self.wait(lambda: proxy.metrics()['readyForQueryIdleDropped'])
        self.assertEqual(len(COMMIT + IDLE), proxy.metrics()['droppedBytes'])
        self.silent(client)

    def test_every_fragment_boundary_of_k_c_and_z_preserves_framing_and_ignores_secret_metadata(self):
        wire = frame(b'K', struct.pack('!II', 34567, SECRET)) + COMMIT + IDLE
        for split in range(len(wire) + 1):
            with self.subTest(split=split):
                proxy = MODULE.PgCommitAckProxy(self.server.port, APPLICATION)
                session = MODULE._Session(())
                session.startup_verified = True
                proxy._target = session
                parser = MODULE._Backend(proxy, session)
                actual = parser.feed(wire[:split]) + parser.feed(wire[split:])
                self.assertEqual(wire[:13], actual)
                self.assertEqual(34567, session.pid)
                self.assertEqual({'pair', 'startup_verified', 'cancel', 'pid', 'dropping'}, set(vars(session)))
                self.assertEqual(0, parser.buffered)
                self.assertEqual(1, proxy.metrics()['commitAcknowledgementsDropped'])
                self.assertTrue(proxy.metrics()['readyForQueryIdleDropped'])
                self.assertEqual(len(COMMIT + IDLE), proxy.metrics()['droppedBytes'])

    def test_real_client_eof_is_observed_before_heal_without_closing_other_pairs(self):
        proxy = self.proxy()
        target, pid = self.established(proxy)
        other, other_pid = self.established(proxy)
        proxy.arm_backend(pid)
        self.server.send(pid, COMMIT + IDLE)
        self.wait(lambda: proxy.metrics()['readyForQueryIdleDropped'])
        self.assertFalse(proxy.metrics()['targetClientClosed'])
        target.close()
        self.wait(lambda: proxy.metrics()['targetClientClosed'] and not self.server.connected(pid))
        proxy.heal()
        self.assertEqual('HEALED', proxy.metrics()['faultState'])
        self.assertEqual(1, proxy.metrics()['active'])
        self.server.send(other_pid, COMMIT + IDLE)
        self.assertEqual(COMMIT + IDLE, self.receive(other, len(COMMIT + IDLE)))
        with self.assertRaises(MODULE.ProxyError):
            proxy.arm_backend(other_pid)
        fresh, fresh_pid = self.established(proxy)
        self.server.send(fresh_pid, COMMIT + IDLE)
        self.assertEqual(COMMIT + IDLE, self.receive(fresh, len(COMMIT + IDLE)))
        self.assertFalse(proxy.metrics()['failed'])

    def test_heal_closes_only_target_and_does_not_fabricate_client_eof(self):
        proxy = self.proxy()
        target, pid = self.established(proxy)
        other, other_pid = self.established(proxy)
        proxy.arm_backend(pid)
        self.server.send(pid, COMMIT + IDLE)
        self.wait(lambda: proxy.metrics()['readyForQueryIdleDropped'])
        proxy.heal()
        self.closed(target)
        self.assertFalse(proxy.metrics()['targetClientClosed'])
        self.assertEqual(1, proxy.metrics()['active'])
        self.server.send(other_pid, IDLE)
        self.assertEqual(IDLE, self.receive(other, len(IDLE)))
        proxy.heal()
        self.assertEqual(1, proxy.metrics()['active'])

    def test_unknown_invalid_stale_and_second_arm_are_rejected_without_disrupting_normal_sessions(self):
        proxy = self.proxy()
        client, pid = self.established(proxy)
        for value in (True, 0, -1, 2147483648, '10000', None, pid + 999):
            with self.subTest(value=value), self.assertRaises(MODULE.ProxyError):
                proxy.arm_backend(value)
        with self.assertRaises(MODULE.ProxyError):
            proxy.heal()
        client.close()
        self.wait(lambda: proxy.metrics()['authenticatedBackends'] == 0)
        with self.assertRaises(MODULE.ProxyError):
            proxy.arm_backend(pid)
        kept, kept_pid = self.established(proxy)
        proxy.arm_backend(kept_pid)
        with self.assertRaises(MODULE.ProxyError):
            proxy.arm_backend(kept_pid)
        self.server.send(kept_pid, IDLE)
        self.assertEqual(IDLE, self.receive(kept, len(IDLE)))
        self.assertFalse(proxy.metrics()['failed'])

    def test_cancel_request_for_owned_backend_reaches_server_without_retaining_secret(self):
        proxy = self.proxy()
        _, pid = self.established(proxy)
        cancel = self.connect(proxy)
        packet = struct.pack('!IIII', 16, 80877102, pid, SECRET)
        cancel.sendall(packet[:7])
        self.assertEqual(0, self.server.cancel_count())
        cancel.sendall(packet[7:])
        self.closed(cancel)
        # pgJDBC receives the server EOF and then closes its cancel stream in
        # finally. An EOF read alone leaves this client's write half open.
        self.assertEqual(2, proxy.metrics()['active'])
        cancel.close()
        self.wait(lambda: self.server.cancel_count() == 1 and proxy.metrics()['active'] == 1)
        self.assertEqual(packet[4:], self.server.cancels[0])
        self.assertFalse(proxy.metrics()['failed'])

    def test_wrong_scope_encryption_requests_unknown_cancel_and_oversized_startup_fail_closed(self):
        normal = ((b'user', USER.encode()), (b'database', b'reviewer_integration'),
                  (b'application_name', APPLICATION.encode()))
        invalid = [struct.pack('!II', 8, code) for code in (80877103, 80877104, 196609)]
        invalid += [struct.pack('!I', 8193), struct.pack('!I', 7),
                    struct.pack('!IIII', 16, 80877102, 98765, SECRET), startup(normal + ((b'user', b'duplicate'),))]
        for index in range(3):
            fields = list(normal)
            fields[index] = (fields[index][0], b'unowned')
            invalid.append(startup(fields))
        for index, payload in enumerate(invalid):
            with self.subTest(index=index):
                proxy = self.proxy(expect_failure=True)
                client = self.connect(proxy)
                client.sendall(payload)
                self.wait(lambda: proxy.metrics()['failed'] and proxy.metrics()['mode'] == 'CLOSED')
                self.closed(client)
                self.assertEqual(0, proxy.metrics()['authenticatedBackends'])
                self.assertEqual(0, proxy.metrics()['forwardedBytes'])

    def test_malformed_control_frames_oversized_frames_and_duplicate_backend_pid_fail_closed(self):
        invalid = [b'C' + struct.pack('!I', 4), frame(b'C', b'COMMIT'), frame(b'C', b'COMMIT\0extra\0'),
                   frame(b'C', b'\xff\0'), b'C' + struct.pack('!I', 133),
                   frame(b'K', b'bad'), frame(b'K', struct.pack('!II', 0, SECRET)),
                   frame(b'K', struct.pack('!II', 10000, SECRET)), frame(b'Z', b'?'), frame(b'Z', b'II'),
                   b'D' + struct.pack('!I', MODULE._Backend.FRAME_LIMIT + 1), b'D' + struct.pack('!I', 3)]
        for index, payload in enumerate(invalid):
            with self.subTest(index=index):
                proxy = self.proxy(expect_failure=True)
                client, pid = self.established(proxy)
                self.server.send(pid, payload)
                self.wait(lambda: proxy.metrics()['failed'] and proxy.metrics()['mode'] == 'CLOSED')
                self.closed(client)
                self.assertEqual(0, proxy.metrics()['active'])
                self.assertEqual(0, proxy.metrics()['authenticatedBackends'])

    def test_partial_control_frame_eof_fails_without_forwarding_a_partial_commit(self):
        proxy = self.proxy(expect_failure=True)
        client, pid = self.established(proxy)
        proxy.arm_backend(pid)
        self.server.send(pid, COMMIT[:-1])
        self.silent(client)
        self.server.disconnect(pid)
        self.wait(lambda: proxy.metrics()['failed'])
        self.closed(client)
        self.assertEqual(0, proxy.metrics()['commitAcknowledgementsDropped'])

    def test_long_non_control_frames_stream_under_existing_queue_bound(self):
        proxy = self.proxy()
        client, pid = self.established(proxy)
        payload = frame(b'D', b'x' * 180000 + b'COMMIT\0')
        errors = []
        def send():
            try:
                self.server.send(pid, payload)
            except Exception as error:
                errors.append(type(error).__name__)
        worker = threading.Thread(target=send, daemon=True)
        worker.start()
        try:
            self.assertEqual(payload, self.receive(client, len(payload)))
        finally:
            worker.join(2)
        self.assertFalse(worker.is_alive())
        self.assertEqual([], errors)
        self.assertTrue(all(len(endpoint.pending) <= 65536 for pair in tuple(proxy._pairs) for endpoint in pair))
        self.assertEqual(0, proxy.metrics()['commitAcknowledgementsDropped'])

    def test_capacity_cleanup_and_listener_ownership_remain_bounded(self):
        proxy = self.proxy(maximum_connections=2)
        target, pid = self.established(proxy)
        other, _ = self.established(proxy)
        excess = self.connect(proxy)
        self.wait(lambda: proxy.metrics()['rejected'] == 1)
        self.closed(excess)
        proxy.arm_backend(pid)
        self.server.send(pid, COMMIT + IDLE)
        self.wait(lambda: proxy.metrics()['readyForQueryIdleDropped'])
        started = time.monotonic()
        proxy.close()
        self.assertLess(time.monotonic() - started, MODULE.PgCommitAckProxy.CLOSE_TIMEOUT + 1)
        self.assertFalse(proxy._thread.is_alive())
        self.closed(target)
        self.closed(other)
        self.assertEqual(0, proxy.metrics()['active'])
        self.assertEqual(0, proxy.metrics()['authenticatedBackends'])
        self.assertFalse(proxy.metrics()['targetClientClosed'])
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.settimeout(1)
            self.assertNotEqual(0, probe.connect_ex(('127.0.0.1', proxy.port)))
        with socket.create_connection(('127.0.0.1', self.server.port), timeout=1) as direct:
            direct.sendall(startup())
            self.assertEqual(b'R', self.receive(direct, 1))
        proxy.close()

    def test_invalid_configuration_never_opens_sockets_and_metrics_contain_only_aggregates(self):
        variants = ({'expected_application_name': 'other'}, {'expected_application_name': True},
                    {'expected_user': 'bad user'}, {'expected_database': 'ai_reviewer'},
                    {'target_port': True}, {'target_port': 1023}, {'maximum_connections': 33})
        for options in variants:
            configured = {'target_port': self.server.port, 'expected_application_name': APPLICATION, **options}
            with self.subTest(options=options), patch.object(MODULE._TCP.socket, 'socket') as create:
                with self.assertRaises(MODULE.ProxyError):
                    MODULE.PgCommitAckProxy(**configured)
                create.assert_not_called()
        proxy = self.proxy()
        _, pid = self.established(proxy)
        expected = {'accepted', 'active', 'peakActive', 'rejected', 'droppedBytes', 'forwardedBytes', 'connectionErrors',
                    'mode', 'failed', 'faultState', 'commitAcknowledgementsDropped', 'readyForQueryIdleDropped',
                    'targetClientClosed', 'authenticatedBackends'}
        values = proxy.metrics()
        self.assertEqual(expected, set(values))
        self.assertTrue(all(type(value) in (int, str, bool) for value in values.values()))
        values['faultState'] = 'caller altered'
        self.assertEqual('AVAILABLE', proxy.metrics()['faultState'])
        with self.assertRaises(MODULE.ProxyError):
            proxy.pause()
        proxy.close()
        for operation in (proxy.start, proxy.heal, lambda: proxy.arm_backend(pid)):
            with self.assertRaises(MODULE.ProxyError):
                operation()


if __name__ == '__main__':
    unittest.main()
