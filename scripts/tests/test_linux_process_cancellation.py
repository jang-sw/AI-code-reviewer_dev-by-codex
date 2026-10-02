"""Real Linux signals against temporary Python fixtures; no database or server tools."""
import importlib.util
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / 'test-postgres-linux.py'
SPEC = importlib.util.spec_from_file_location('linux_cancellation_runner', SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
REAL_POPEN = subprocess.Popen


@unittest.skipUnless(sys.platform.startswith('linux'), 'Real Linux process signals required')
class LinuxProcessCancellationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='reviewer-signal-fixture-')
        self.addCleanup(self.temporary.cleanup)
        self.workspace = Path(self.temporary.name)
        self.processes = []
        self.addCleanup(self.stop_owned_processes)
        self.environment = {'PATH': os.defpath, 'PYTHONUNBUFFERED': '1'}

    def stop_owned_processes(self):
        # Every recorded child was started by this fixture in its own session.
        # Never inspect or signal unrelated PIDs, and never signal a reaped PID.
        for process in reversed(self.processes):
            if process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            process.wait(timeout=5)
            for stream in (process.stdout, process.stderr):
                if stream is not None:
                    stream.close()

    def fixture(self, name, body):
        path = self.workspace / name
        path.write_text('import os, signal, subprocess, sys, time\nfrom pathlib import Path\n'
                        + textwrap.dedent(body), encoding='utf-8')
        return path

    def wait_ready(self, process, marker):
        deadline = time.monotonic() + 5
        while not marker.exists() and process.poll() is None and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertTrue(marker.exists(), 'Temporary Python child did not become ready')

    def cancel_command(self, script):
        owner = self

        class ReadyProcess(REAL_POPEN):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, **kwargs)
                owner.processes.append(self)
                self.ready_checked = False

            def communicate(self, input=None, timeout=None):
                if not self.ready_checked:
                    owner.wait_ready(self, owner.workspace / 'ready')
                    self.ready_checked = True
                # Keep real waiting/signals/processes; shorten only cancellation grace.
                if timeout == 60:
                    timeout = 0.75
                elif timeout == 5:
                    timeout = 0.25
                return super().communicate(input=input, timeout=timeout)

        with patch.object(MODULE.subprocess, 'Popen', ReadyProcess):
            with self.assertRaises(MODULE.SafetyError) as failure:
                MODULE.run_command([sys.executable, script], environment=self.environment,
                                   cwd=self.workspace, timeout=0.1, cooperative_cancel=True)
        self.assertEqual(str(failure.exception), 'A local verification command failed or timed out.')
        self.assertIsNotNone(self.processes[-1].poll())
        return self.processes[-1]

    def test_cooperative_timeout_finishes_cleanup_but_preserves_failure(self):
        script = self.fixture('cooperative.py', '''
            try:
                Path('ready').write_text('ready')
                signal.pause()
            except KeyboardInterrupt:
                pass
            finally:
                Path('cleaned').write_text('finished')
            sys.exit(0)
        ''')
        process = self.cancel_command(script)
        self.assertEqual((self.workspace / 'cleaned').read_text(), 'finished')
        # Cleanup success must not turn the parent's original timeout into success.
        self.assertEqual(process.returncode, 0)

    def test_cooperative_interrupt_leaves_grandchild_alive_for_child_cleanup(self):
        self.fixture('grandchild.py', '''
            def interrupted(_signal, _frame):
                Path('grandchild-interrupted').write_text('unexpected broadcast')
                sys.exit(95)
            signal.signal(signal.SIGINT, interrupted)
            Path('grandchild-ready').write_text('ready')
            while True:
                signal.pause()
        ''')
        script = self.fixture('orchestrator.py', '''
            child = None
            try:
                child = subprocess.Popen([sys.executable, 'grandchild.py'],
                    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                deadline = time.monotonic() + 3
                while not Path('grandchild-ready').exists():
                    if child.poll() is not None or time.monotonic() >= deadline:
                        raise RuntimeError('Fixture grandchild did not become ready')
                    time.sleep(0.01)
                Path('ready').write_text('ready')
                signal.pause()
            except KeyboardInterrupt:
                pass
            finally:
                if child is not None:
                    try:
                        # Give a wrongly broadcast SIGINT time to become observable.
                        time.sleep(0.1)
                        alive = child.poll() is None and not Path('grandchild-interrupted').exists()
                        Path('alive-during-cleanup').write_text(str(alive))
                    finally:
                        if child.poll() is None:
                            child.terminate()
                        try:
                            child.wait(timeout=1)
                        except subprocess.TimeoutExpired:
                            child.kill()
                            child.wait(timeout=1)
                        Path('grandchild-reaped').write_text(str(child.returncode))
        ''')
        process = self.cancel_command(script)
        self.assertEqual(process.returncode, 0)
        self.assertEqual((self.workspace / 'alive-during-cleanup').read_text(), 'True')
        self.assertEqual((self.workspace / 'grandchild-reaped').read_text(), str(-signal.SIGTERM))
        self.assertFalse((self.workspace / 'grandchild-interrupted').exists())

    def test_stubborn_child_escalates_to_its_owned_group_and_is_reaped(self):
        script = self.fixture('stubborn.py', '''
            signal.signal(signal.SIGINT, signal.SIG_IGN)
            signal.signal(signal.SIGTERM, signal.SIG_IGN)
            Path('ready').write_text('ready')
            while True:
                signal.pause()
        ''')
        started = time.monotonic()
        with patch.object(MODULE.os, 'killpg', wraps=os.killpg) as group_signal:
            process = self.cancel_command(script)
        self.assertEqual(process.returncode, -signal.SIGKILL)
        self.assertEqual([call.args for call in group_signal.call_args_list],
                         [(process.pid, signal.SIGTERM), (process.pid, signal.SIGKILL)])
        self.assertLess(time.monotonic() - started, 5)

    def test_main_sigterm_runs_execute_finally_and_restores_handler(self):
        script = self.fixture('parent.py', '''
            import importlib.util
            spec = importlib.util.spec_from_file_location('signal_fixture_runner', sys.argv[1])
            runner = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(runner)
            class SyntheticRun(runner.TestRun):
                def __init__(self, _args):
                    pass
                def prepare(self):
                    pass
                def start(self):
                    pass
                def databases(self):
                    pass
                def verify(self):
                    Path('ready').write_text('ready')
                    signal.pause()
                def cleanup(self):
                    Path('owned-cleanup').write_text('finished')
            runner.TestRun = SyntheticRun
            # The synthetic execute path never invokes a tool, even in root-run CI.
            runner.require_linux_user = lambda: None
            previous = signal.getsignal(signal.SIGTERM)
            result = runner.main(['--pg-bin', '/unused-fixture', '--java', '/unused-fixture'])
            Path('handler-restored').write_text(str(signal.getsignal(signal.SIGTERM) == previous))
            sys.exit(result)
        ''')
        process = REAL_POPEN([sys.executable, script, SCRIPT], cwd=self.workspace,
                             env=self.environment, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, start_new_session=True, text=True)
        self.processes.append(process)
        self.wait_ready(process, self.workspace / 'ready')
        process.send_signal(signal.SIGTERM)
        stdout, stderr = process.communicate(timeout=5)
        self.assertEqual(process.returncode, 130)
        self.assertEqual((self.workspace / 'owned-cleanup').read_text(), 'finished')
        self.assertEqual((self.workspace / 'handler-restored').read_text(), 'True')
        self.assertNotIn('PASS:', stdout)
        self.assertIn('INTERRUPTED:', stderr)


if __name__ == '__main__':
    unittest.main()
