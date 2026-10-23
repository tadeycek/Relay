#!/usr/bin/env python3
"""Preflight tests never run adb, clear app data or contact a provider."""
import contextlib
import importlib.util
import io
from pathlib import Path
import types
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('ui_harness',Path(__file__).with_name('run-app-ui.py'))
assert spec is not None and spec.loader is not None
harness=importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)


class AppUiGuards(unittest.TestCase):
    def reject(self,args,marker):
        commands=[]
        def output(cmd,**kw):
            commands.append([str(x) for x in cmd])
            if 'clear' in cmd: raise AssertionError('Attempted app-data clear without valid fixture authorization')
            return 'package:com.relay.app\n'
        with patch('sys.argv',['run-app-ui.py',*args]),patch.dict('os.environ',{'JAVA_HOME':'/fake/jdk'}),\
             patch.object(harness.subprocess,'check_output',side_effect=output),\
             patch.object(harness.subprocess,'run',return_value=types.SimpleNamespace(returncode=0,stdout=marker)),\
             contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as raised:harness.main()
            self.assertEqual(raised.exception.code,2)
        self.assertFalse(any('clear' in cmd for cmd in commands),'Must never clear a non-owned or unapproved app')

    def test_physical_device_rejected_before_adb(self):
        self.reject(['--serial','physical-device'],harness.MARKER)

    def test_existing_app_without_explicit_reset_is_preserved(self):
        self.reject([],harness.MARKER)

    def test_reset_requires_exact_ownership_marker(self):
        self.reject(['--reset-owned-fixture'],b'not-our-fixture')

    def test_missing_java_home_rejected_before_adb(self):
        with patch('sys.argv',['run-app-ui.py']),patch.dict('os.environ',{},clear=True),\
             patch.object(harness.subprocess,'check_output') as command,contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):harness.main()
            command.assert_not_called()


if __name__=='__main__':unittest.main()
