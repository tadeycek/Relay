"""Offline execution of main's actual cleanup AST; no device/provider setup."""
import ast
from contextlib import redirect_stdout
import io
from pathlib import Path
import subprocess
import unittest
from unittest.mock import Mock, call


SOURCE = Path(__file__).with_name('cross_platform.py')


def cleanup_code(optimize=0):
    """Compile the real finally block, not a duplicate cleanup implementation."""
    tree = ast.parse(SOURCE.read_text(), filename=str(SOURCE))
    main = next(node for node in tree.body
                if isinstance(node, ast.FunctionDef) and node.name == 'main')
    cleanup = next(node.finalbody for node in main.body
                   if isinstance(node, ast.Try) and node.finalbody)
    return compile(ast.Module(body=cleanup, type_ignores=[]), str(SOURCE),
                   'exec', optimize=optimize)


class CleanupTests(unittest.TestCase):
    def setUp(self):
        self.process = Mock()
        self.process.poll.return_value = None
        self.device = Mock(return_value='')
        self.revoke = Mock(return_value=204)
        self.output = io.StringIO()
        self.namespace: dict[str, object] = dict(process=self.process, device=self.device,
                              mapped=True, port=12345, PACKAGE='test.package',
                              expires=1234567890, turn_username='test-username',
                              turn_helpers={'revoke': self.revoke},
                              subprocess=subprocess)

    def run_cleanup(self, optimize=0):
        with redirect_stdout(self.output):
            exec(cleanup_code(optimize), self.namespace)

    def assert_revoked(self):
        self.revoke.assert_called_once_with('test-username')
        self.assertIn('turn_credentials_revoked_http=204', self.output.getvalue())

    def assert_device_cleanup(self):
        self.assertEqual(self.device.call_args_list, [
            call('shell', 'am', 'force-stop', 'test.package', check=False),
            call('reverse', '--remove', 'tcp:12345'),
            call('reverse', '--list'),
        ])

    def test_force_stop_timeout_still_removes_mapping_and_revokes(self):
        failure = subprocess.TimeoutExpired('adb', 30)
        self.device.side_effect = [failure, '', '']
        with self.assertRaises(subprocess.TimeoutExpired) as raised:
            self.run_cleanup()
        self.assertIs(raised.exception, failure)
        self.assert_device_cleanup()
        self.assert_revoked()

    def test_reverse_remove_failure_still_reads_back_and_revokes(self):
        failure = subprocess.CalledProcessError(1, 'adb reverse --remove')
        self.device.side_effect = ['', failure, '']
        with self.assertRaises(subprocess.CalledProcessError) as raised:
            self.run_cleanup()
        self.assertIs(raised.exception, failure)
        self.assert_device_cleanup()
        self.assert_revoked()

    def test_reverse_readback_failure_still_revokes(self):
        for failure in (subprocess.TimeoutExpired('adb reverse --list', 30),
                        subprocess.CalledProcessError(1, 'adb reverse --list')):
            with self.subTest(failure=type(failure).__name__):
                self.setUp()
                self.device.side_effect = ['', '', failure]
                with self.assertRaises(type(failure)) as raised:
                    self.run_cleanup()
                self.assertIs(raised.exception, failure)
                self.assert_device_cleanup()
                self.assert_revoked()

    def test_remaining_reverse_mapping_still_reports_failure_and_revokes(self):
        self.device.side_effect = ['', '', 'tcp:12345 tcp:12345']
        with self.assertRaisesRegex(AssertionError, 'Owned reverse mapping cleanup failed'):
            self.run_cleanup()
        self.assert_device_cleanup()
        self.assert_revoked()

    def test_native_wait_failure_still_cleans_device_and_revokes(self):
        for failure in (RuntimeError('native wait failed'),
                        subprocess.TimeoutExpired('native', 5)):
            with self.subTest(failure=type(failure).__name__):
                self.setUp()
                self.process.wait.side_effect = [
                    subprocess.TimeoutExpired('native', 10), failure]
                with self.assertRaises(type(failure)) as raised:
                    self.run_cleanup()
                self.assertIs(raised.exception, failure)
                self.process.terminate.assert_called_once_with()
                self.process.kill.assert_called_once_with()
                self.assertEqual(self.process.wait.call_args_list,
                                 [call(timeout=10), call(timeout=5)])
                self.assert_device_cleanup()
                self.assert_revoked()

    def test_revocation_is_called_even_when_optimized(self):
        for optimize in (0, 1):
            with self.subTest(optimize=optimize):
                self.setUp()
                self.run_cleanup(optimize)
                self.assert_revoked()

    def test_non204_revocation_never_prints_confirmation(self):
        for optimize in (0, 1):
            for status in (200, 403, None):
                with self.subTest(optimize=optimize, status=status):
                    self.setUp()
                    self.revoke.return_value = status
                    with self.assertRaises(Exception):
                        self.run_cleanup(optimize)
                    self.revoke.assert_called_once_with('test-username')
                    self.assertNotIn('turn_credentials_revoked_http=204',
                                     self.output.getvalue())

    def test_revocation_exception_never_prints_confirmation(self):
        for optimize in (0, 1):
            with self.subTest(optimize=optimize):
                self.setUp()
                failure = RuntimeError('revocation failed')
                self.revoke.side_effect = failure
                with self.assertRaises(RuntimeError) as raised:
                    self.run_cleanup(optimize)
                self.assertIs(raised.exception, failure)
                self.revoke.assert_called_once_with('test-username')
                self.assertNotIn('turn_credentials_revoked_http=204',
                                 self.output.getvalue())

    def test_failed_revocation_preserves_prior_cleanup_failure_in_chain(self):
        for optimize in (0, 1):
            with self.subTest(optimize=optimize):
                self.setUp()
                cleanup_failure = subprocess.TimeoutExpired('adb', 30)
                revoke_failure = RuntimeError('revocation failed')
                self.device.side_effect = [cleanup_failure, '', '']
                self.revoke.side_effect = revoke_failure
                with self.assertRaises(RuntimeError) as raised:
                    self.run_cleanup(optimize)
                self.assertIs(raised.exception, revoke_failure)
                self.assertIs(raised.exception.__context__, cleanup_failure)
                self.revoke.assert_called_once_with('test-username')
                self.assertNotIn('turn_credentials_revoked_http=204',
                                 self.output.getvalue())

    def test_unacquired_resources_are_not_cleaned_up(self):
        self.namespace.update(process=None, mapped=False, expires=None,
                              turn_helpers=None, turn_username=None)
        self.run_cleanup()
        self.process.terminate.assert_not_called()
        self.device.assert_called_once_with('shell', 'am', 'force-stop',
                                            'test.package', check=False)
        self.revoke.assert_not_called()
        self.assertEqual(self.output.getvalue(), '')


if __name__ == '__main__':
    unittest.main()
