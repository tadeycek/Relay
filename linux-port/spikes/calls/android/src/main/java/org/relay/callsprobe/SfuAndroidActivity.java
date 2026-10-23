package org.relay.callsprobe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

/** Live-SFU interop entry point. Receives only a loopback bridge port; no credentials, no microphone. */
public final class SfuAndroidActivity extends Activity {
    private static final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView output = new TextView(this);
        output.setText("Generated-audio SFU interop probe; loopback signalling only; no microphone.");
        setContentView(output);
        int port = getIntent().getIntExtra("bridge_port", -1);
        if (port < 1024 || port > 65535) { Log.e("RelayCallsProbe", "sfu_android=FAIL type=BadPort"); return; }
        if (!running.compareAndSet(false, true)) return; // Activity recreation must not start a second native peer.
        new Thread(() -> {
            try { new SfuAndroidRun().run(getApplicationContext(), port); }
            catch (Throwable failure) { Log.e("RelayCallsProbe", "sfu_android=FAIL type=" + failure.getClass().getSimpleName()); }
            finally { running.set(false); }
        }, "sfu-android-probe").start();
    }
}
