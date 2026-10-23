package org.relay.callsprobe;

import android.content.Context;
import android.util.Log;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Android peer of the WP02-AC5 E2EE spike: native org.webrtc.FrameCryptor (AES_GCM) on the sender and on each
 * receiver, generated PCM only, no microphone. Test keys arrive over the loopback bridge and are never logged.
 */
final class E2eeRun {
    static final String TAG = "RelayCallsProbe";
    final AtomicLong samples = new AtomicLong(), nonzero = new AtomicLong(), p2samples = new AtomicLong(), generated = new AtomicLong();
    long squares, p2squares; double tone; double re, im; int blockN;
    volatile long phase2At = Long.MAX_VALUE;
    final Map<String, Integer> states = new ConcurrentHashMap<>();
    final Map<String, Integer> senderStates = new ConcurrentHashMap<>();
    final List<AudioTrack> remoteTracks = new CopyOnWriteArrayList<>();
    final List<FrameCryptor> cryptors = new CopyOnWriteArrayList<>();

    final AudioTrackSink sink = (buffer, bits, rate, channels, frames, timestamp) -> {
        if (bits != 16) return;
        ByteBuffer b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        synchronized (this) {
            boolean p2 = System.nanoTime() >= phase2At;
            while (b.remaining() >= 2) {
                short s = b.getShort();
                samples.incrementAndGet(); if (s != 0) nonzero.incrementAndGet();
                squares += (long) s * s;
                if (p2) { p2samples.incrementAndGet(); p2squares += (long) s * s; }
                double w = 2 * Math.PI * 440 * blockN / rate;
                re += s * Math.cos(w); im -= s * Math.sin(w);
                if (++blockN == rate / 10) { tone += 2 * (re * re + im * im) / blockN; re = im = 0; blockN = 0; }
            }
        }
    };

    static Map<String, String> parse(String text) {
        Map<String, String> m = new HashMap<>();
        for (String line : text.split("\n")) { int eq = line.indexOf('='); if (eq > 0) m.put(line.substring(0, eq), line.substring(eq + 1)); }
        return m;
    }
    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
    static String mapJson(Map<String, Integer> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : new TreeMap<>(m).entrySet()) { sb.append(first ? "" : ",").append('"').append(e.getKey()).append("\":").append(e.getValue()); first = false; }
        return sb.append('}').toString();
    }
    static String munge(String sdp) { return sdp.replace("useinbandfec=1", "useinbandfec=1;usedtx=1"); }

    FrameCryptor attach(boolean sender, PeerConnectionFactory factory, Object rtp, String id, FrameCryptorKeyProvider kp, Map<String, Integer> sink) {
        FrameCryptor c = sender
            ? FrameCryptorFactory.createFrameCryptorForRtpSender(factory, (RtpSender) rtp, id, FrameCryptorAlgorithm.AES_GCM, kp)
            : FrameCryptorFactory.createFrameCryptorForRtpReceiver(factory, (RtpReceiver) rtp, id, FrameCryptorAlgorithm.AES_GCM, kp);
        c.setObserver((participant, state) -> sink.merge(state.name(), 1, Integer::sum));
        c.setKeyIndex(0);
        c.setEnabled(true);
        cryptors.add(c);
        return c;
    }

    void run(Context context, int port) throws Exception {
        try (Socket bridge = new Socket()) {
            bridge.connect(new InetSocketAddress("127.0.0.1", port), 10000);
            DataInputStream in = new DataInputStream(bridge.getInputStream()); DataOutputStream out = new DataOutputStream(bridge.getOutputStream());
            CrossPlatformRun.writeText(out, "relay-android-native-v1");
            if (!CrossPlatformRun.readText(in).equals("local")) throw new IOException("invalid bridge mode");
            Map<String, String> cfg = parse(CrossPlatformRun.readText(in));
            bridge.setSoTimeout(Math.max(35000, Integer.parseInt(cfg.get("duration_ms")) + 30000));
            runConnected(context, cfg, in, out);
        }
    }

    void runConnected(Context context, Map<String, String> cfg, DataInputStream in, DataOutputStream out) throws Exception {
        boolean enc = cfg.get("enc").equals("1"), rotate = cfg.get("rotate").equals("1"), silent = cfg.get("silent").equals("1"), dtx = cfg.get("dtx").equals("1");
        int duration = Integer.parseInt(cfg.get("duration_ms"));
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false).setInjectableLogger((m, s, t) -> { }, Logging.Severity.LS_ERROR).createInitializationOptions());
        AtomicLong next = new AtomicLong(), frame = new AtomicLong();
        JavaAudioDeviceModule adm = JavaAudioDeviceModule.builder(context)
            .setSampleRate(48000).setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false).setEnableVolumeLogger(false)
            .setAudioBufferCallback((buffer, format, channels, rate, bytes, timestamp) -> {
                long now = System.nanoTime(); long target = next.get();
                if (target == 0 || target < now - TimeUnit.MILLISECONDS.toNanos(100)) target = now;
                long left = target - now;
                if (left > 0) java.util.concurrent.locks.LockSupport.parkNanos(left);
                next.set(target + TimeUnit.MILLISECONDS.toNanos(10));
                ByteBuffer b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN); b.clear();
                long position = frame.getAndAdd(buffer.capacity() / 2 / channels);
                for (int i = 0; i < buffer.capacity() / 2 / channels; i++) {
                    short s = silent ? 0 : (short) (12000 * Math.sin(2 * Math.PI * 660 * (position + i) / rate));
                    for (int c = 0; c < channels; c++) b.putShort(s);
                }
                generated.incrementAndGet(); return System.nanoTime();
            }).createAudioDeviceModule();
        adm.setAudioRecordEnabled(false);
        adm.setSpeakerMute(true);
        PeerConnectionFactory factory = PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory();
        FrameCryptorKeyProvider kp = null;
        if (enc) {
            FrameCryptorKeyDerivationAlgorithm kdf = FrameCryptorKeyDerivationAlgorithm.valueOf(cfg.get("kdf"));
            kp = FrameCryptorFactory.createFrameCryptorKeyProvider(true, hex(cfg.get("salt")), 0, new byte[0], 0, 16, false, kdf);
            if (!kp.setSharedKey(0, hex(cfg.get("key0"))) || !kp.setSharedKey(1, hex(cfg.get("key1")))) throw new IOException("key provider rejected key");
        }
        final FrameCryptorKeyProvider provider = kp;
        MediaConstraints constraints = new MediaConstraints();
        for (String k : new String[] {"googEchoCancellation", "googAutoGainControl", "googNoiseSuppression"})
            constraints.optional.add(new MediaConstraints.KeyValuePair(k, "false"));
        AudioSource source = factory.createAudioSource(constraints);
        AudioTrack local = factory.createAudioTrack("android-660hz", source);
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(Collections.emptyList());
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        AtomicInteger candidates = new AtomicInteger();
        PeerConnection pc = factory.createPeerConnection(config, new PeerConnection.Observer() {
            public void onSignalingChange(PeerConnection.SignalingState s) { }
            public void onIceConnectionChange(PeerConnection.IceConnectionState s) { }
            public void onIceConnectionReceivingChange(boolean r) { }
            public void onIceGatheringChange(PeerConnection.IceGatheringState s) { }
            public void onIceCandidate(IceCandidate c) { candidates.incrementAndGet(); }
            public void onIceCandidatesRemoved(IceCandidate[] c) { }
            public void onAddStream(MediaStream s) { }
            public void onRemoveStream(MediaStream s) { }
            public void onDataChannel(DataChannel c) { }
            public void onRenegotiationNeeded() { }
            public void onTrack(RtpTransceiver t) {
                if (provider != null) attach(false, factory, t.getReceiver(), "linux", provider, states);
                if (t.getReceiver().track() instanceof AudioTrack a) { remoteTracks.add(a); a.addSink(sink); }
            }
        });
        if (pc == null) throw new IOException("peer creation failed");
        RtpSender sender = pc.addTrack(local, Collections.singletonList("android"));
        FrameCryptor sendCryptor = provider != null ? attach(true, factory, sender, "android", provider, senderStates) : null;
        try {
            CrossPlatformRun.set(pc, new SessionDescription(SessionDescription.Type.OFFER, CrossPlatformRun.readText(in)), false);
            CompletableFuture<SessionDescription> answer = new CompletableFuture<>();
            pc.createAnswer(new CrossPlatformRun.SdpResult() {
                public void onCreateSuccess(SessionDescription d) { answer.complete(d); }
                void failed() { answer.completeExceptionally(new IOException("SDP answer rejected (details suppressed)")); }
            }, new MediaConstraints());
            SessionDescription ans = answer.get(25, TimeUnit.SECONDS);
            if (dtx) ans = new SessionDescription(SessionDescription.Type.ANSWER, munge(ans.description));
            CrossPlatformRun.set(pc, ans, true);
            CrossPlatformRun.waitFor(() -> pc.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE);
            CrossPlatformRun.writeText(out, pc.getLocalDescription().description);
            CrossPlatformRun.waitFor(() -> pc.connectionState() == PeerConnection.PeerConnectionState.CONNECTED);
            long start = System.nanoTime();
            phase2At = start + 4_000_000_000L;
            if (rotate && sendCryptor != null) { Thread.sleep(3000); sendCryptor.setKeyIndex(1); Log.i(TAG, "e2ee_android_rotated_sender_key_index=" + sendCryptor.getKeyIndex()); }
            long wait = Math.max(0, duration - 1000 - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            Thread.sleep(wait);
            CompletableFuture<RTCStatsReport> stats = new CompletableFuture<>(); pc.getStats(stats::complete);
            RTCStatsReport report = stats.get(10, TimeUnit.SECONDS);
            long packets = 0, bytes = 0;
            for (RTCStats s : report.getStatsMap().values()) {
                Map<String, Object> m = s.getMembers();
                if ("inbound-rtp".equals(s.getType()) && ("audio".equals(m.get("kind")) || "audio".equals(m.get("mediaType")))) {
                    packets += ((Number) m.getOrDefault("packetsReceived", 0)).longValue();
                    bytes += ((Number) m.getOrDefault("bytesReceived", 0)).longValue();
                }
            }
            boolean connected = pc.connectionState() == PeerConnection.PeerConnectionState.CONNECTED;
            String json;
            synchronized (this) {
                json = "{\"packets\":" + packets + ",\"bytes\":" + bytes + ",\"samples\":" + samples.get() + ",\"nonzero\":" + nonzero.get()
                    + ",\"square_sum\":" + squares + ",\"tone_square\":" + (long) tone + ",\"generated_frames\":" + generated.get()
                    + ",\"connected\":" + connected + ",\"e2ee_enabled\":" + enc + ",\"frames_ok\":0,\"frames_failed\":0"
                    + ",\"phase2_samples\":" + p2samples.get() + ",\"phase2_square_sum\":" + p2squares
                    + ",\"states\":" + mapJson(states) + ",\"sender_states\":" + mapJson(senderStates)
                    + ",\"sender_key_index\":" + (sendCryptor != null ? sendCryptor.getKeyIndex() : -1) + "}";
            }
            CrossPlatformRun.writeText(out, json);
            Log.i(TAG, "e2ee_android_receive samples=" + samples.get() + " packets=" + packets + " states=" + mapJson(states));
            in.readBoolean();
        } finally {
            for (AudioTrack a : remoteTracks) a.removeSink(sink);
            for (FrameCryptor c : cryptors) { c.setObserver(null); c.dispose(); }
            if (provider != null) provider.dispose();
            pc.close(); pc.dispose(); local.dispose(); source.dispose(); factory.dispose(); adm.release();
            Log.i(TAG, "android_cross_platform_cleanup=COMPLETE");
        }
    }
}
