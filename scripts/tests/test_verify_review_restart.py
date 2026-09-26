import importlib.util
from pathlib import Path
import os
import tempfile
import unittest
from unittest.mock import patch
from urllib.request import Request

SPEC = importlib.util.spec_from_file_location('verify_review_restart', Path(__file__).resolve().parents[1] / 'verify-review-restart.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class RestartDrillBoundaryTest(unittest.TestCase):
    @unittest.skipIf(os.name == 'nt', 'POSIX executable symlink dispatch regression')
    def test_executable_symlink_basename_is_preserved_for_linux_wrappers(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            wrapper = directory / 'pg_wrapper'
            wrapper.write_text('#!/bin/sh\nprintf "%s" "${0##*/}"\n', encoding='utf-8')
            wrapper.chmod(0o700)
            psql = directory / 'psql'
            psql.symlink_to(wrapper)
            war = directory / 'fixture.war'
            war.touch()
            argv = ['verify-review-restart.py', '--war', str(war), '--java', str(wrapper),
                    '--psql', str(psql), '--port', '18089']
            with patch.object(MODULE.sys, 'argv', argv), patch.object(MODULE.socket, 'socket'):
                args = MODULE.arguments()
            result = MODULE.subprocess.run([str(args.psql)], capture_output=True, text=True, check=True)
            self.assertEqual(result.stdout, 'psql')

    def test_database_rejects_other_hosts_databases_parameters_and_ports_before_any_command(self):
        invalid = (
            'jdbc:postgresql://db.example.test:5432/reviewer_integration',
            'jdbc:postgresql://127.0.0.1:5432/production',
            'jdbc:postgresql://127.0.0.1:5432/reviewer_integration?currentSchema=public',
            'jdbc:postgresql://127.0.0.1:0/reviewer_integration',
            'jdbc:postgresql://127.0.0.1:65536/reviewer_integration',
        )
        with patch.object(MODULE.subprocess, 'run') as command:
            for url in invalid:
                with self.subTest(url=url), self.assertRaises(MODULE.VerificationError):
                    MODULE.Database(Path('psql'), url, Path('owned-work'))
            command.assert_not_called()

    def test_child_environment_does_not_inherit_api_keys_configuration_or_java_injection(self):
        with patch.dict(os.environ, {
            'OPENAI_API_KEY': 'synthetic-private-value',
            'GIT_CONNECTIONS_0_TOKEN': 'synthetic-private-value',
            'SPRING_APPLICATION_JSON': 'synthetic-override',
            'JAVA_TOOL_OPTIONS': 'synthetic-override',
            'JDK_JAVA_OPTIONS': 'synthetic-override',
            '_JAVA_OPTIONS': 'synthetic-override',
            'HTTPS_PROXY': 'http://proxy.example.test',
        }, clear=True):
            self.assertEqual(MODULE.base_environment(), {})

    def test_database_account_name_cannot_add_sql_or_command_options(self):
        with patch.dict(os.environ, {'TEST_DATABASE_USERNAME': "owner';drop schema public;--"}):
            with self.assertRaises(MODULE.VerificationError):
                MODULE.Database(Path('psql'), 'jdbc:postgresql://127.0.0.1:5432/reviewer_integration', Path('owned-work'))

    def test_snapshot_rejects_identifiers_outside_owned_schema_before_sql(self):
        with patch.dict(os.environ, {'TEST_DATABASE_USERNAME': 'reviewer_test'}):
            database = MODULE.Database(Path('psql'), 'jdbc:postgresql://127.0.0.1:5432/reviewer_integration', Path('owned-work'))
        with patch.object(database, 'sql') as sql:
            for schema, project in (('public', 1), ('restart_test_bad', 1), ('restart_test_' + 'a' * 32, -1)):
                with self.subTest(schema=schema), self.assertRaises(MODULE.VerificationError):
                    database.snapshot(schema, project)
            sql.assert_not_called()

    def test_redirects_cannot_leave_the_owned_origin_or_context(self):
        base = 'http://127.0.0.1:18089/restart_test_' + 'a' * 32
        redirect = MODULE.LocalRedirects(base)
        request = Request(base + '/login')
        for target in ('https://example.test/login', 'http://127.0.0.1:18089/login', base + '-other/login'):
            with self.subTest(target=target), self.assertRaises(MODULE.VerificationError):
                redirect.redirect_request(request, None, 302, '', {}, target)
        accepted = redirect.redirect_request(request, None, 302, '', {}, base + '/projects')
        self.assertEqual(accepted.full_url, base + '/projects')

    def test_fixture_never_echoes_child_command_output_on_failure(self):
        with patch.dict(os.environ, {'TEST_DATABASE_USERNAME': 'reviewer_test'}):
            database = MODULE.Database(Path('psql'), 'jdbc:postgresql://127.0.0.1:5432/reviewer_integration', Path('owned-work'))
        response = MODULE.subprocess.CompletedProcess([], 1, stdout='synthetic-sensitive-output', stderr='synthetic-sensitive-error')
        with patch.object(MODULE.subprocess, 'run', return_value=response):
            with self.assertRaises(MODULE.VerificationError) as failure:
                database.sql('SELECT 1;')
        self.assertNotIn('synthetic-sensitive', str(failure.exception))


if __name__ == '__main__':
    unittest.main()
