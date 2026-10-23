"""Offline ownership failure injection; compiles the real probe, never loads JNI."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent
JAVA_HOME = Path(os.environ.get("JAVA_HOME", "/home/tadej/tools/jdk-21.0.12.1+1"))
HARNESS = r'''
package relay.spike;
import java.lang.reflect.*;
import java.util.*;

public class NativeCleanupHarness {
    public static void main(String[] args) throws Exception {
        // Reflection lets the baseline compile and fail at a behavioral assertion.
        Class<?> type;
        try { type = Class.forName("relay.spike.SfuProbe$Ownership"); }
        catch (ClassNotFoundException missing) {
            throw new AssertionError("Missing partial-construction ownership/unwind helper", missing);
        }
        if (args.length > 0 && args[0].equals("interrupted")) {
            Method stop;
            try { stop = SfuProbe.class.getDeclaredMethod("stopGenerator", Thread.class, java.util.concurrent.atomic.AtomicBoolean.class); }
            catch (NoSuchMethodException missing) { throw new AssertionError("Missing interruption-safe generator shutdown", missing); }
            var running = new java.util.concurrent.atomic.AtomicBoolean(true);
            Thread worker = new Thread(() -> { while (running.get()) Thread.onSpinWait(); });
            worker.start();
            Thread.currentThread().interrupt();
            try { stop.invoke(null, worker, running); throw new AssertionError("interruption lost"); }
            catch (InvocationTargetException e) {
                if (!(e.getCause() instanceof InterruptedException)) throw e;
            }
            if (worker.isAlive() || running.get() || !Thread.interrupted())
                throw new AssertionError("generator not stopped/joined or interrupt not restored");
            return;
        }
        Object owner = type.getDeclaredConstructor().newInstance();
        if (args.length > 0 && args[0].equals("peers")) {
            Method deferPeer = type.getDeclaredMethod("defer", AutoCloseable.class);
            Method closePeers = type.getDeclaredMethod("closeInto", Throwable.class);
            List<Integer> attemptedPeers = new ArrayList<>();
            InterruptedException interrupted = new InterruptedException("join failed");
            RuntimeException peerFailure = new RuntimeException("other peer");
            deferPeer.invoke(owner, (AutoCloseable) () -> { attemptedPeers.add(1); throw peerFailure; });
            deferPeer.invoke(owner, (AutoCloseable) () -> { attemptedPeers.add(2); throw interrupted; });
            Throwable failure = (Throwable) closePeers.invoke(owner, new Object[]{null});
            if (!attemptedPeers.equals(List.of(2, 1)) || failure != interrupted
                || !Arrays.equals(failure.getSuppressed(), new Throwable[]{peerFailure}) || !Thread.interrupted())
                throw new AssertionError("interrupted/failed peer skipped remaining cleanup");
            SfuProbe.rethrow(failure); // JVM must naturally exit failed, never System.exit.
            throw new AssertionError("cleanup failure swallowed");
        }
        if (args.length > 0 && args[0].equals("construction")) {
            Method own = type.getDeclaredMethod("own", Object.class, java.util.function.Consumer.class);
            Method unwind = type.getDeclaredMethod("closeInto", Throwable.class);
            List<Integer> acquired = new ArrayList<>();
            RuntimeException original = new RuntimeException("init");
            RuntimeException dispose = new RuntimeException("dispose");
            for (int i = 0; i < 3; i++) {
                final int resource = i;
                Object returned = own.invoke(owner, Integer.valueOf(i), (java.util.function.Consumer<Integer>) value -> {
                    acquired.add(value); if (resource == 1) throw dispose;
                });
                if (!returned.equals(i)) throw new AssertionError("ownership changed resource");
            }
            if (unwind.invoke(owner, original) != original || !acquired.equals(List.of(2, 1, 0))
                || !Arrays.equals(original.getSuppressed(), new Throwable[]{dispose}))
                throw new AssertionError("partial construction lost resources or original failure");
            return;
        }
        Method defer = type.getDeclaredMethod("defer", AutoCloseable.class);
        Method close = type.getDeclaredMethod("closeInto", Throwable.class);
        List<Integer> attempted = new ArrayList<>();
        RuntimeException first = new RuntimeException("first");
        Error second = new AssertionError("second");
        defer.invoke(owner, (AutoCloseable) () -> { attempted.add(1); throw second; });
        defer.invoke(owner, (AutoCloseable) () -> { attempted.add(2); throw first; });
        Throwable result = (Throwable) close.invoke(owner, new Object[]{null});
        if (!attempted.equals(List.of(2, 1))) throw new AssertionError("not all cleanup attempted: " + attempted);
        if (result != first || !Arrays.equals(result.getSuppressed(), new Throwable[]{second}))
            throw new AssertionError("cleanup failures not aggregated");
        if (close.invoke(owner, new Object[]{null}) != null || attempted.size() != 2)
            throw new AssertionError("cleanup not idempotent");
    }
}
'''


class NativeCleanupTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        scratch = ROOT / ".private"
        scratch.mkdir(exist_ok=True)
        cls.temp = tempfile.TemporaryDirectory(prefix="native-cleanup-testclasses-", dir=scratch)
        cls.out = Path(cls.temp.name)
        harness = cls.out / "NativeCleanupHarness.java"
        harness.write_text(HARNESS)
        cls.classpath = str(cls.out) + os.pathsep + str(ROOT / "build/install/relay-calls-spike/lib/*")
        subprocess.run([str(JAVA_HOME / "bin/javac"), "-cp", cls.classpath, "-d", str(cls.out),
                        str(ROOT / "src/main/java/relay/spike/SfuProbe.java"), str(harness)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_harness(self, case=""):
        result = subprocess.run([str(JAVA_HOME / "bin/java"), "-cp", self.classpath,
                                 "relay.spike.NativeCleanupHarness", case], text=True, capture_output=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_all_resources_attempted_and_failures_preserved(self):
        self.run_harness()

    def test_partial_construction_preserves_original(self):
        self.run_harness("construction")

    def test_interrupted_generator_is_joined(self):
        self.run_harness("interrupted")

    def test_failed_interrupted_peer_attempts_others_and_exits_failed(self):
        result = subprocess.run([str(JAVA_HOME / "bin/java"), "-cp", self.classpath,
                                 "relay.spike.NativeCleanupHarness", "peers"], text=True, capture_output=True, timeout=15)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("java.lang.InterruptedException: join failed", result.stderr)
        self.assertIn("Suppressed: java.lang.RuntimeException: other peer", result.stderr)
        self.assertNotIn("AssertionError", result.stderr)

    def test_native_allocations_and_peer_cleanup_use_ownership(self):
        source = (ROOT / "src/main/java/relay/spike/SfuProbe.java").read_text()
        self.assertIn("adm=resources.own(new HeadlessAudioDeviceModule()", source)
        self.assertIn("factory=resources.own(new PeerConnectionFactory(adm)", source)
        self.assertIn("source=resources.own(new CustomAudioSource()", source)
        self.assertIn("rethrow(resources.closeInto(shutdown.closeInto(failure)))", source)
        self.assertIn("peerOwnership.defer(peer::close)", source)
        self.assertIn("rethrow(peerOwnership.closeInto(failure))", source)
        self.assertNotIn("System.exit", source)


if __name__ == "__main__":
    unittest.main()
