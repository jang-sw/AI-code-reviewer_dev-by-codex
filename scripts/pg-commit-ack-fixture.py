"""Owned plaintext PostgreSQL fixture that loses one COMMIT acknowledgement.

Only a verified live backend can be armed. A complete backend CommandComplete
tag, never SQL substrings or TCP packet boundaries, selects the fault. The
COMMIT frame and all later server bytes are discarded; frontend bytes and other
sessions continue normally. This is an application-protocol test, not TLS or
kernel packet-loss emulation. No SQL, authentication payload or cancel secret
is retained as metadata or included in diagnostics/metrics.
"""

import importlib.util
from pathlib import Path
import queue
import re
import struct


_SPEC = importlib.util.spec_from_file_location('commit_ack_tcp_fixture', Path(__file__).with_name('review-network-fixture.py'))
_TCP = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_TCP)
ProxyError = _TCP.ProxyError


def _require(value):
    if not value:
        raise ProxyError('The owned PostgreSQL protocol stream was invalid or unsupported.')


class _Session:
    def __init__(self, pair):
        self.pair = pair
        self.startup_verified = False
        self.cancel = False
        self.pid = None
        self.dropping = False


class _Startup:
    LIMIT = 8192

    def __init__(self, proxy, session):
        self.proxy, self.session = proxy, session
        self.buffer = bytearray()
        self.length = None

    @property
    def buffered(self):
        return len(self.buffer)

    def feed(self, data):
        if self.session.startup_verified or self.session.cancel:
            return data
        offset = 0
        while offset < len(data):
            wanted = (4 if self.length is None else self.length) - len(self.buffer)
            take = min(wanted, len(data) - offset)
            self.buffer.extend(data[offset:offset + take])
            offset += take
            if self.length is None and len(self.buffer) == 4:
                self.length = struct.unpack('!I', self.buffer)[0]
                _require(8 <= self.length <= self.LIMIT)
            if self.length is not None and len(self.buffer) == self.length:
                frame = bytes(self.buffer)
                version = struct.unpack('!I', frame[4:8])[0]
                if version == 80877102:  # CancelRequest is a separate short-lived connection.
                    _require(self.length == 16)
                    pid = struct.unpack('!I', frame[8:12])[0]
                    _require(pid in self.proxy._backends and self.proxy._backends[pid].pair in self.proxy._pairs)
                    self.session.cancel = True
                else:
                    # Refuse SSLRequest, GSSENCRequest, direct TLS and other versions.
                    _require(version == 196608)
                    parts = frame[8:].split(b'\0')
                    _require(len(parts) >= 2 and parts[-2:] == [b'', b''] and len(parts[:-2]) % 2 == 0)
                    fields = {}
                    for index in range(0, len(parts) - 2, 2):
                        name, value = parts[index:index + 2]
                        _require(name and name not in fields)
                        fields[name] = value
                    _require(fields.get(b'user') == self.proxy._expected_user
                             and fields.get(b'database') == b'reviewer_integration'
                             and fields.get(b'application_name') == self.proxy._expected_application)
                    self.session.startup_verified = True
                self.buffer.clear()
                return frame + data[offset:]
        return b''

    def eof(self):
        _require(not self.buffer)


class _Backend:
    # Ordinary frames stream through the existing 64KiB queue. Only a short
    # header or K/C/Z control body is held while its complete shape is verified.
    FRAME_LIMIT = 1_048_576
    TAG_LIMIT = 128
    TYPES = frozenset(b'RKSZTtDCENA123nsIGHWdcVv')

    def __init__(self, proxy, session):
        self.proxy, self.session = proxy, session
        self.header = bytearray()
        self.control_header = b''
        self.body = bytearray()
        self.kind = None
        self.remaining = None

    @property
    def buffered(self):
        return len(self.header) + len(self.control_header) + len(self.body)

    def emit(self, output, data):
        if self.session.dropping:
            self.proxy._increment('droppedBytes', len(data))
        else:
            output.extend(data)

    def feed(self, data):
        _require(self.session.startup_verified and not self.session.cancel)
        output, offset = bytearray(), 0
        while offset < len(data) or self.remaining == 0:
            if self.remaining is None:
                take = min(5 - len(self.header), len(data) - offset)
                self.header.extend(data[offset:offset + take])
                offset += take
                if len(self.header) < 5:
                    break
                self.kind = self.header[0]
                length = struct.unpack('!I', self.header[1:])[0]
                _require(self.kind in self.TYPES and 4 <= length <= self.FRAME_LIMIT)
                if self.kind == ord('K'):
                    _require(length == 12)
                elif self.kind == ord('Z'):
                    _require(length == 5)
                elif self.kind == ord('C'):
                    _require(5 <= length <= 4 + self.TAG_LIMIT)
                self.remaining = length - 4
                if self.kind in b'KCZ':
                    self.control_header = bytes(self.header)
                else:
                    self.emit(output, self.header)
                self.header.clear()
            take = min(self.remaining, len(data) - offset)
            part = data[offset:offset + take]
            offset += take
            self.remaining -= take
            if self.control_header:
                self.body.extend(part)
            else:
                self.emit(output, part)
            if self.remaining == 0:
                if self.control_header:
                    self.proxy._control(self.session, self.kind, self.body)
                    self.emit(output, self.control_header)
                    self.emit(output, self.body)
                    self.body.clear()
                    self.control_header = b''
                self.kind, self.remaining = None, None
        return output

    def eof(self):
        _require(not self.header and self.remaining is None)


class PgCommitAckProxy(_TCP.OwnedTcpProxy):
    """Single-use, single-fault proxy; arm_backend is acknowledged by its I/O thread.

    expected_application_name must be the UUID schema used by the owned WAR.
    heal closes only the selected pair and never replays discarded bytes. Its
    single arm cannot be reused, even after the client has already disconnected.
    """

    def __init__(self, target_port, expected_application_name, expected_user='reviewer_test',
                 expected_database='reviewer_integration', listen_port=0, maximum_connections=32):
        if (not isinstance(expected_application_name, str)
                or re.fullmatch(r'restart_test_[0-9a-f]{32}', expected_application_name) is None
                or not isinstance(expected_user, str)
                or re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]{0,62}', expected_user) is None
                or expected_database != 'reviewer_integration'):
            raise ProxyError('Invalid owned PostgreSQL proxy scope.')
        super().__init__(target_port, listen_port, maximum_connections)
        self._expected_application = expected_application_name.encode('ascii')
        self._expected_user = expected_user.encode('ascii')
        self._sessions, self._backends = {}, {}
        self._target = None
        self._armed_once = False
        self._update(faultState='AVAILABLE', commitAcknowledgementsDropped=0,
                     readyForQueryIdleDropped=False, targetClientClosed=False, authenticatedBackends=0)

    def pause(self):
        raise ProxyError('The COMMIT acknowledgement fixture does not support a global pause.')

    def arm_backend(self, pid):
        if type(pid) is not int or not 0 < pid <= 2147483647:
            raise ProxyError('Invalid owned PostgreSQL backend identity.')
        response = {}
        self._transition(('arm', pid, response), 'FORWARD')
        if response.get('accepted') is not True:
            raise ProxyError('The owned PostgreSQL backend could not be armed.')

    def heal(self):
        response = {}
        self._transition(('heal', None, response), 'FORWARD')
        if response.get('accepted') is not True:
            raise ProxyError('The COMMIT acknowledgement fixture has no armed fault to heal.')

    def _drain_commands(self):
        while True:
            try:
                (command, pid, response), acknowledged = self._commands.get_nowait()
            except queue.Empty:
                return
            if command == 'arm':
                session = self._backends.get(pid)
                if (not self._armed_once and session is not None and session.pair in self._pairs
                        and all(not endpoint.read_closed for endpoint in session.pair)):
                    self._target, self._armed_once = session, True
                    self._update(faultState='ARMED')
                    response['accepted'] = True
            elif command == 'heal' and self._armed_once:
                if self._target is not None:
                    self._drop(self._target.pair)
                self._update(faultState='HEALED')
                response['accepted'] = True
            acknowledged.set()

    def _pair_opened(self, pair):
        session = _Session(pair)
        self._sessions[pair] = session
        pair[0].protocol = _Startup(self, session)
        pair[1].protocol = _Backend(self, session)

    def _receive_capacity(self, endpoint, paused):
        # Reserve queue room for a prefix held by the incremental parser. Its
        # eventual release must not increase the parent's 64KiB queue bound.
        return max(0, super()._receive_capacity(endpoint, paused) - endpoint.protocol.buffered)

    def _receive_data(self, endpoint, data):
        forwarded = endpoint.protocol.feed(data)
        _require(len(endpoint.peer.pending) + len(forwarded) <= self.BUFFER_LIMIT)
        endpoint.peer.pending.extend(forwarded)

    def _receive_eof(self, endpoint):
        endpoint.protocol.eof()
        session = self._sessions[endpoint.pair]
        if session is self._target and endpoint is endpoint.pair[0]:
            self._update(targetClientClosed=True)
        super()._receive_eof(endpoint)

    def _pair_closed(self, pair):
        session = self._sessions.pop(pair, None)
        if session is not None and session.pid is not None:
            self._backends.pop(session.pid, None)
            self._update(authenticatedBackends=len(self._backends))
        if session is self._target:
            self._target = None

    def _control(self, session, kind, body):
        if kind == ord('K'):
            pid = struct.unpack('!I', body[:4])[0]
            _require(session.pid is None and 0 < pid <= 2147483647 and pid not in self._backends)
            session.pid = pid
            self._backends[pid] = session
            self._update(authenticatedBackends=len(self._backends))
        elif kind == ord('C'):
            _require(body and body[-1] == 0 and 0 not in body[:-1]
                     and all(32 <= value <= 126 for value in body[:-1]))
            if session is self._target and not session.dropping and body == b'COMMIT\0':
                session.dropping = True
                self._update(faultState='DROPPING', commitAcknowledgementsDropped=1)
        elif kind == ord('Z'):
            _require(body in (b'I', b'T', b'E'))
            if session.dropping and body == b'I':
                self._update(readyForQueryIdleDropped=True)
