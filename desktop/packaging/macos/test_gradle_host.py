"""Gradle configuration regression for unsupported desktop hosts."""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


class HostConfigurationTests(unittest.TestCase):
    def test_simulated_windows_configures_help_but_rejects_native_tasks(self):
        java_home = os.environ.get('JAVA_HOME')
        if not java_home or not (Path(java_home) / 'bin/java').is_file():
            self.skipTest('JDK 21 JAVA_HOME required for Gradle host configuration test')
        with tempfile.TemporaryDirectory() as tmp:
            scratch = Path(tmp)
            real_os = os.uname().sysname
            init = scratch / 'simulate-windows.gradle'
            init.write_text("gradle.beforeProject { p ->\n"
                            "  p.layout.buildDirectory.set(new File('" + str(scratch / 'build') +
                            "', p.path == ':' ? 'root' : p.path.substring(1).replace(':', '/')))\n"
                            "  if (p.path == ':desktop') System.setProperty('os.name', 'Windows 11')\n"
                            "}\ngradle.afterProject { p ->\n"
                            "  if (p.path == ':desktop') System.setProperty('os.name', '" + real_os + "')\n}\n")
            base = [str(ROOT / 'gradlew'), '--no-daemon', '-Dorg.gradle.java.home=' + java_home,
                    '-I', str(init), '--project-cache-dir', str(scratch / 'cache'),
                    '-Pkotlin.incremental=false', '--dry-run', '--console=plain']
            env = {**os.environ, 'TMPDIR': str(scratch), 'PATH': str(Path(java_home) / 'bin') + os.pathsep + os.environ['PATH']}
            def run(task):
                return subprocess.run(base + [task], cwd=ROOT, env=env, capture_output=True, text=True, timeout=180)
            help_result = run(':desktop:help')
            self.assertEqual(help_result.returncode, 0, help_result.stdout + help_result.stderr)
            for task in (':desktop:run', ':desktop:createDistributable'):
                result = run(task)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn('Relay desktop native distribution requires Linux x86_64 or macOS Apple Silicon', result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
