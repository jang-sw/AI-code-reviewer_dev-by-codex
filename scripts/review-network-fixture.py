"""Owned loopback TCP stream fault fixture; never changes a database or OS network rules.

BLACKHOLE drains and discards application bytes in both directions. TCP EOF and
errors still propagate, so this is not a simulation of dropped kernel packets.
No payload is logged, retained for replay, or returned by metrics().
"""

import errno
import queue
import selectors
import socket
import threading
import time


class ProxyError(RuntimeError):
    """Fixed diagnostics only; socket addresses and operating-system errors are withheld."""


class _Endpoint:
    def __init__(self, connection, pair, *, connecting=False):
        self.connection = connection
        self.pair = pair
        self.peer = None
        self.pending = bytearray()
        self.connecting = connecting
        self.connect_deadline = time.monotonic() + 2.0
        self.read_closed = False
        self.write_closed = False
        self.registered = False


class OwnedTcpProxy:
    """A single-use proxy with at most maximum_connections owned socket pairs.

    accepted counts all accepted clients, including capacity rejections. active
    counts admitted pairs, including upstream connections still being established.
    pause/heal return only after the selector thread acknowledges the transition.
    """

    BUFFER_LIMIT = 65_536
    COMMAND_TIMEOUT = 3.0
    CLOSE_TIMEOUT = 5.0
    _CONNECTING = {errno.EINPROGRESS, errno.EWOULDBLOCK, errno.EALREADY, errno.EINTR,
                   getattr(errno, 'WSAEWOULDBLOCK', errno.EWOULDBLOCK)}

    def __init__(self, target_port, listen_port=0, maximum_connections=32):
        if (type(target_port) is not int or not 1024 <= target_port <= 65535
                or type(listen_port) is not int or (listen_port != 0 and not 1024 <= listen_port <= 65535)
                or (listen_port != 0 and listen_port == target_port)
                or type(maximum_connections) is not int or not 1 <= maximum_connections <= 32):
            raise ProxyError('Invalid loopback proxy configuration.')
        self._target_port = target_port
        self._listen_port = listen_port
        self._maximum_connections = maximum_connections
        self._port = None
        self._listener = None
        self._wake_reader = None
        self._wake_writer = None
        self._selector = None
        self._thread = None
        self._pairs = set()
        self._commands = queue.Queue()
        self._stopping = threading.Event()
        self._operations = threading.Lock()
        self._statistics = threading.Lock()
        self._stats = {'accepted': 0, 'active': 0, 'peakActive': 0, 'rejected': 0,
                       'droppedBytes': 0, 'forwardedBytes': 0, 'connectionErrors': 0,
                       'mode': 'NEW', 'failed': False}

    @property
    def port(self):
        if self._port is None:
            raise ProxyError('The loopback proxy has not started.')
        return self._port

    def metrics(self):
        with self._statistics:
            return dict(self._stats)

    def _update(self, **values):
        with self._statistics:
            self._stats.update(values)

    def _increment(self, field, count=1):
        with self._statistics:
            self._stats[field] += count

    def start(self):
        with self._operations:
            if self.metrics()['mode'] != 'NEW':
                raise ProxyError('The loopback proxy cannot be started again.')
            try:
                self._listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                if hasattr(socket, 'SO_EXCLUSIVEADDRUSE'):
                    self._listener.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
                self._listener.bind(('127.0.0.1', self._listen_port))
                self._listener.listen(self._maximum_connections)
                self._listener.setblocking(False)
                self._port = self._listener.getsockname()[1]
                self._wake_reader, self._wake_writer = socket.socketpair()
                self._wake_reader.setblocking(False)
                self._wake_writer.setblocking(False)
                self._selector = selectors.DefaultSelector()
                self._selector.register(self._listener, selectors.EVENT_READ, 'listener')
                self._selector.register(self._wake_reader, selectors.EVENT_READ, 'wake')
                self._update(mode='FORWARD')
                self._thread = threading.Thread(target=self._run, name='review-owned-tcp-proxy', daemon=True)
                self._thread.start()
            except (OSError, RuntimeError, ValueError):
                self._update(failed=True)
                self._dispose()
                raise ProxyError('The loopback proxy could not start.') from None
        return self

    def _wake(self):
        writer = self._wake_writer
        try:
            if writer is not None:
                writer.send(b'x')
        except (OSError, ValueError):
            # select also wakes periodically, including if this tiny pipe is full.
            pass

    def _transition(self, command, expected):
        with self._operations:
            state = self.metrics()
            if self._thread is None or not self._thread.is_alive() or self._stopping.is_set() or state['failed']:
                raise ProxyError('The loopback proxy is not available for a transition.')
            acknowledged = threading.Event()
            self._commands.put((command, acknowledged))
            self._wake()
            if not acknowledged.wait(self.COMMAND_TIMEOUT) or self.metrics()['mode'] != expected or self.metrics()['failed']:
                self._stopping.set()
                self._wake()
                raise ProxyError('The loopback proxy transition did not complete.')

    def pause(self):
        self._transition('pause', 'BLACKHOLE')

    def heal(self):
        self._transition('heal', 'FORWARD')

    def close(self):
        with self._operations:
            self._stopping.set()
            self._wake()
            if self._thread is not None:
                self._thread.join(self.CLOSE_TIMEOUT)
                if self._thread.is_alive():
                    raise ProxyError('The owned loopback proxy did not stop within its cleanup limit.')
            else:
                self._dispose()
            if self.metrics()['failed']:
                raise ProxyError('The loopback proxy failed while handling its owned sockets.')

    def __enter__(self):
        return self.start()

    def __exit__(self, *_):
        self.close()

    def _drain_commands(self):
        while True:
            try:
                command, acknowledged = self._commands.get_nowait()
            except queue.Empty:
                return
            if command == 'pause':
                self._update(mode='BLACKHOLE')
                for pair in tuple(self._pairs):
                    for endpoint in pair:
                        self._increment('droppedBytes', len(endpoint.pending))
                        endpoint.pending.clear()
                    self._refresh(pair)
            elif command == 'heal':
                for pair in tuple(self._pairs):
                    self._drop(pair)
                # Reject connections queued before this boundary, then admit new clients.
                if not self._accept(discard=True):
                    raise ProxyError('The loopback proxy could not drain its pending clients.')
                self._update(mode='FORWARD')
            acknowledged.set()

    def _accept(self, *, discard=False):
        # Bounded batches leave control commands and existing relays responsive.
        for _ in range(64):
            try:
                client, _ = self._listener.accept()
            except BlockingIOError:
                return True
            self._increment('accepted')
            if discard or len(self._pairs) >= self._maximum_connections:
                client.close()
                self._increment('rejected')
                continue
            upstream, pair = None, None
            try:
                client.setblocking(False)
                upstream = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                upstream.setblocking(False)
                status = upstream.connect_ex(('127.0.0.1', self._target_port))
                if status != 0 and status not in self._CONNECTING:
                    raise OSError()
                pair = (_Endpoint(client, None), _Endpoint(upstream, None, connecting=status != 0))
                pair[0].pair = pair[1].pair = pair
                pair[0].peer, pair[1].peer = pair[1], pair[0]
                self._pairs.add(pair)
                self._pair_opened(pair)
                with self._statistics:
                    self._stats['active'] = len(self._pairs)
                    self._stats['peakActive'] = max(self._stats['peakActive'], len(self._pairs))
                self._refresh(pair)
            except OSError:
                if pair in self._pairs:
                    self._drop(pair)
                else:
                    client.close()
                    if upstream is not None:
                        upstream.close()
                self._increment('connectionErrors')
        return False

    # Narrow stream hooks let protocol fixtures reuse owned sockets and bounded I/O.
    # The default implementation keeps this byte-transparent proxy unchanged.
    def _pair_opened(self, pair):
        pass

    def _receive_capacity(self, endpoint, paused):
        return self.BUFFER_LIMIT if paused else self.BUFFER_LIMIT - len(endpoint.peer.pending)

    def _receive_data(self, endpoint, data):
        endpoint.peer.pending.extend(data)

    def _receive_eof(self, endpoint):
        endpoint.read_closed = True

    def _pair_closed(self, pair):
        pass

    def _interest(self, endpoint, events):
        if events:
            if endpoint.registered:
                self._selector.modify(endpoint.connection, events, endpoint)
            else:
                self._selector.register(endpoint.connection, events, endpoint)
                endpoint.registered = True
        elif endpoint.registered:
            self._selector.unregister(endpoint.connection)
            endpoint.registered = False

    def _refresh(self, pair):
        if pair not in self._pairs:
            return
        paused = self.metrics()['mode'] == 'BLACKHOLE'
        for endpoint in pair:
            if not endpoint.connecting and endpoint.peer.read_closed and not endpoint.pending and not endpoint.write_closed:
                try:
                    endpoint.connection.shutdown(socket.SHUT_WR)
                    endpoint.write_closed = True
                except OSError:
                    self._drop(pair)
                    return
        if all(endpoint.read_closed and not endpoint.pending for endpoint in pair):
            self._drop(pair)
            return
        for endpoint in pair:
            if endpoint.connecting:
                events = selectors.EVENT_WRITE
            else:
                events = 0
                if not endpoint.read_closed and self._receive_capacity(endpoint, paused) > 0:
                    events |= selectors.EVENT_READ
                if not paused and endpoint.pending and not endpoint.write_closed:
                    events |= selectors.EVENT_WRITE
            self._interest(endpoint, events)

    def _service(self, endpoint, events):
        pair = endpoint.pair
        if pair not in self._pairs:
            return
        try:
            if endpoint.connecting:
                if endpoint.connection.getsockopt(socket.SOL_SOCKET, socket.SO_ERROR) != 0:
                    raise OSError()
                endpoint.connecting = False
            else:
                paused = self.metrics()['mode'] == 'BLACKHOLE'
                if events & selectors.EVENT_READ:
                    capacity = self._receive_capacity(endpoint, paused)
                    if capacity:
                        try:
                            data = endpoint.connection.recv(capacity)
                        except BlockingIOError:
                            data = None
                        if data == b'':
                            self._receive_eof(endpoint)
                        elif data:
                            if paused:
                                self._increment('droppedBytes', len(data))
                            else:
                                self._receive_data(endpoint, data)
                if events & selectors.EVENT_WRITE and endpoint.pending and not paused:
                    try:
                        written = endpoint.connection.send(endpoint.pending)
                    except BlockingIOError:
                        written = 0
                    if written:
                        del endpoint.pending[:written]
                        self._increment('forwardedBytes', written)
            self._refresh(pair)
        except OSError:
            self._increment('connectionErrors')
            self._drop(pair)

    def _drop(self, pair):
        if pair not in self._pairs:
            return
        self._pairs.remove(pair)
        self._pair_closed(pair)
        for endpoint in pair:
            if endpoint.registered:
                try:
                    self._selector.unregister(endpoint.connection)
                except (KeyError, ValueError, OSError):
                    pass
                endpoint.registered = False
            endpoint.pending.clear()
            try:
                endpoint.connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                endpoint.connection.close()
            except OSError:
                self._update(failed=True)
        self._update(active=len(self._pairs))

    def _run(self):
        try:
            while not self._stopping.is_set():
                self._drain_commands()
                for key, events in self._selector.select(0.1):
                    if self._stopping.is_set():
                        break
                    if key.data == 'wake':
                        try:
                            self._wake_reader.recv(4096)
                        except BlockingIOError:
                            pass
                        self._drain_commands()
                    elif key.data == 'listener':
                        self._accept()
                    else:
                        self._service(key.data, events)
                now = time.monotonic()
                for pair in tuple(self._pairs):
                    if any(endpoint.connecting and now >= endpoint.connect_deadline for endpoint in pair):
                        self._increment('connectionErrors')
                        self._drop(pair)
        except Exception:
            self._update(failed=True)
        finally:
            self._dispose()
            while True:
                try:
                    _, acknowledged = self._commands.get_nowait()
                    acknowledged.set()
                except queue.Empty:
                    break

    def _dispose(self):
        for pair in tuple(self._pairs):
            self._drop(pair)
        if self._selector is not None:
            try:
                self._selector.close()
            except OSError:
                self._update(failed=True)
            self._selector = None
        for name in ('_listener', '_wake_reader', '_wake_writer'):
            connection = getattr(self, name)
            if connection is not None:
                try:
                    connection.close()
                except OSError:
                    self._update(failed=True)
                setattr(self, name, None)
        self._update(mode='CLOSED', active=0)
