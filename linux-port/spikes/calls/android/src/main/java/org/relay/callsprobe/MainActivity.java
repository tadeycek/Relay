package org.relay.callsprobe;

import android.app.Activity;
import android.os.Bundle;
import android.media.AudioManager;
import android.media.AudioDeviceInfo;
import android.util.Log;
import android.widget.TextView;
import org.webrtc.PeerConnectionFactory;

/** Native capability probe only. No microphone permission, capture or network. */
public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView output = new TextView(this);
        output.setPadding(32, 64, 32, 32);
        setContentView(output);
        StringBuilder result = new StringBuilder("Android native WebRTC probe\n");
        try {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this)
                    .setEnableInternalTracer(false).createInitializationOptions());
            PeerConnectionFactory factory = PeerConnectionFactory.builder().createPeerConnectionFactory();
            result.append("native_load=PASS factory_create=PASS\n");
            AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
            AudioDeviceInfo[] inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS);
            AudioDeviceInfo[] outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            result.append("input_devices=").append(inputs.length).append(" output_devices=").append(outputs.length).append("\n");
            for (AudioDeviceInfo d:inputs) result.append("input_type=").append(d.getType()).append("\n");
            for (AudioDeviceInfo d:outputs) result.append("output_type=").append(d.getType()).append("\n");
            try { Class.forName("org.webrtc.FrameCryptor"); result.append("framecryptor_class=AVAILABLE_NOT_INTEROP_TESTED\n"); }
            catch (ClassNotFoundException e) { result.append("framecryptor_class=ABSENT\n"); }
            factory.dispose();
            result.append("factory_dispose=PASS microphone_opened=false p2p_audio=NOT_RUN provider=NOT_RUN");
            Log.i("RelayCallsProbe", result.toString());
        } catch (Throwable failure) {
            result.append("native_probe=FAIL type=").append(failure.getClass().getSimpleName());
            Log.e("RelayCallsProbe", result.toString());
        }
        output.setText(result.toString());
    }
}
