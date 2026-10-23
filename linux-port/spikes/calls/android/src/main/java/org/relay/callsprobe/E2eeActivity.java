package org.relay.callsprobe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

/** E2EE interop entry point (WP02-AC5). Loopback bridge port only; keys arrive over the bridge, never via intent/logs. */
public final class E2eeActivity extends Activity {
    private static final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView output = new TextView(this);
        output.setText("Generated-audio FrameCryptor E2EE probe; loopback signalling only; no microphone.");
        setContentView(output);
        int port = getIntent().getIntExtra("bridge_port", -1);
        if (port < 1024 || port > 65535) { Log.e("RelayCallsProbe", "e2ee_android=FAIL type=BadPort"); return; }
        if (!running.compareAndSet(false, true)) return;
        new Thread(() -> {
            try { new E2eeRun().run(getApplicationContext(), port); }
            catch (Throwable failure) { Log.e("RelayCallsProbe", "e2ee_android=FAIL type=" + failure.getClass().getSimpleName()); }
            finally { running.set(false); }
        }, "e2ee-android-probe").start();
    }
}
