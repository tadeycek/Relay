package org.relay.callsprobe;

import android.content.Context;
import android.util.Log;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One Android native peer driven by a Python coordinator over 127.0.0.1 (adb reverse). SDP only. */
final class SfuAndroidRun {
    static final String TAG = "RelayCallsProbe";
    static final int LIMIT = 65536;
    static final class Receive {
        final RtpTransceiver transceiver;
        final AudioTrack track;
        final AtomicLong samples = new AtomicLong(), squares = new AtomicLong();
        final AudioTrackSink sink = (buffer, bits, rate, channels, frames, timestamp) -> {
            if (bits != 16) return;
            ByteBuffer b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            long q = 0, count = 0;
            while (b.remaining() >= 2) { short s = b.getShort(); count++; q += (long) s * s; }
            samples.addAndGet(count); squares.addAndGet(q);
        };
        Receive(RtpTransceiver t, AudioTrack a) { transceiver = t; track = a; a.addSink(sink); }
    }
    final List<Receive> incoming = new CopyOnWriteArrayList<>();
    final AtomicLong generated = new AtomicLong();
    PeerConnectionFactory factory;
    JavaAudioDeviceModule adm;
    AudioSource source;
    AudioTrack local;
    PeerConnection pc;
    RtpTransceiver publication;
    volatile boolean silent;

    static abstract class SdpResult implements SdpObserver {
        public void onCreateSuccess(SessionDescription d) { }
        public void onSetSuccess() { }
        public void onCreateFailure(String ignored) { failed(); }
        public void onSetFailure(String ignored) { failed(); }
        abstract void failed();
    }
    static void waitFor(Callable<Boolean> condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!condition.call()) { if (System.nanoTime() > end) throw new TimeoutException("native state timeout"); Thread.sleep(20); }
    }
    static void set(PeerConnection pc, SessionDescription d, boolean local) throws Exception {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        SdpObserver observer = new SdpResult() {
            public void onSetSuccess() { result.complete(true); }
            void failed() { result.completeExceptionally(new IOException("SDP set rejected (details suppressed)")); }
        };
        if (local) pc.setLocalDescription(observer, d); else pc.setRemoteDescription(observer, d);
        result.get(25, TimeUnit.SECONDS);
    }
    static SessionDescription create(PeerConnection pc, boolean offer) throws Exception {
        CompletableFuture<SessionDescription> r = new CompletableFuture<>();
        SdpObserver observer = new SdpResult() {
            public void onCreateSuccess(SessionDescription d) { r.complete(d); }
            void failed() { r.completeExceptionally(new IOException("SDP create rejected (details suppressed)")); }
        };
        if (offer) pc.createOffer(observer, new MediaConstraints()); else pc.createAnswer(observer, new MediaConstraints());
        return r.get(25, TimeUnit.SECONDS);
    }
    static String readText(DataInputStream in) throws IOException {
        int n = in.readInt(); if (n < 1 || n > LIMIT) throw new IOException("signal size");
        byte[] b = new byte[n]; in.readFully(b); return new String(b, StandardCharsets.UTF_8);
    }
    static void writeText(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8); if (b.length < 1 || b.length > LIMIT) throw new IOException("signal size");
        out.writeInt(b.length); out.write(b); out.flush();
    }

    void run(Context context, int port) throws Exception {
        try (Socket bridge = new Socket()) {
            bridge.connect(new InetSocketAddress("127.0.0.1", port), 10000); bridge.setSoTimeout(60000);
            DataInputStream in = new DataInputStream(bridge.getInputStream());
            DataOutputStream out = new DataOutputStream(bridge.getOutputStream());
            writeText(out, "relay-sfu-android-v1");
            try {
                while (true) {
                    String[] p = readText(in).split("\t", -1);
                    String result = handle(context, p);
                    if (result == null) { writeText(out, "OK"); break; }
                    writeText(out, result);
                }
            } finally {
                dispose();
            }
        }
    }

    /** Returns null for QUIT. Replies never carry anything but SDP, MID or numeric metrics. */
    String handle(Context context, String[] p) throws Exception {
        switch (p[0]) {
            case "QUIT": return null;
            case "NEW": create(context, p[1].equals("1")); return "OK";
            case "OFFER": {
                set(pc, create(pc, true), true);
                waitFor(() -> pc.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE);
                return pc.getLocalDescription().description;
            }
            case "MID": return publication.getMid();
            case "REMOTE":
                set(pc, new SessionDescription(p[1].equals("offer") ? SessionDescription.Type.OFFER : SessionDescription.Type.ANSWER, p[2]), false);
                return "OK";
            case "ANSWER": {
                set(pc, create(pc, false), true);
                waitFor(() -> pc.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE);
                return pc.getLocalDescription().description;
            }
            case "CONNECTED": waitFor(() -> pc.connectionState() == PeerConnection.PeerConnectionState.CONNECTED); return "OK";
            case "METRICS": return metrics();
            default: throw new IOException("unknown command");
        }
    }

    void create(Context context, boolean mute) throws Exception {
        if (pc != null) throw new IOException("peer already created");
        silent = mute;
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .setInjectableLogger((message, severity, tag) -> { }, Logging.Severity.LS_ERROR)
            .createInitializationOptions());
        AtomicLong next = new AtomicLong(), frame = new AtomicLong();
        adm = JavaAudioDeviceModule.builder(context)
            .setSampleRate(48000).setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false)
            .setEnableVolumeLogger(false)
            .setAudioBufferCallback((buffer, format, channels, rate, bytes, timestamp) -> {
                // With AudioRecord disabled there is no microphone clock: pace 10 ms here.
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
        adm.setAudioRecordEnabled(false); // Critical: no android.media.AudioRecord is constructed or opened.
        adm.setSpeakerMute(true); // Decoded sink observes native PCM; no listening assertion.
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory();
        MediaConstraints constraints = new MediaConstraints();
        constraints.optional.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "false"));
        constraints.optional.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "false"));
        constraints.optional.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "false"));
        source = factory.createAudioSource(constraints);
        local = factory.createAudioTrack("android-660hz", source);
        // Local ICE only (no STUN/TURN): the SFU offers public candidates and outbound checks suffice.
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(Collections.emptyList());
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        pc = factory.createPeerConnection(config, new PeerConnection.Observer() {
            public void onSignalingChange(PeerConnection.SignalingState s) { }
            public void onIceConnectionChange(PeerConnection.IceConnectionState s) { }
            public void onIceConnectionReceivingChange(boolean r) { }
            public void onIceGatheringChange(PeerConnection.IceGatheringState s) { }
            public void onIceCandidate(IceCandidate c) { }
            public void onIceCandidatesRemoved(IceCandidate[] c) { }
            public void onAddStream(MediaStream s) { }
            public void onRemoveStream(MediaStream s) { }
            public void onDataChannel(DataChannel c) { }
            public void onRenegotiationNeeded() { }
            public void onConnectionChange(PeerConnection.PeerConnectionState s) {
                Log.i(TAG, "sfu_android_connection=" + s);
            }
            public void onTrack(RtpTransceiver t) {
                if (t.getReceiver().track() instanceof AudioTrack a) incoming.add(new Receive(t, a));
            }
        });
        if (pc == null) throw new IOException("peer creation failed");
        publication = pc.addTransceiver(local, new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY));
    }

    String metrics() throws Exception {
        List<String> rows = new ArrayList<>();
        for (Receive r : incoming) {
            CompletableFuture<RTCStatsReport> f = new CompletableFuture<>();
            pc.getStats(r.transceiver.getReceiver(), f::complete);
            long packets = 0, bytes = 0;
            for (RTCStats s : f.get(10, TimeUnit.SECONDS).getStatsMap().values()) {
                if (!"inbound-rtp".equals(s.getType())) continue;
                Map<String, Object> m = s.getMembers();
                packets += ((Number) m.getOrDefault("packetsReceived", 0)).longValue();
                bytes += ((Number) m.getOrDefault("bytesReceived", 0)).longValue();
            }
            rows.add("{\"mid\":\"" + r.transceiver.getMid() + "\",\"packets\":" + packets + ",\"bytes\":" + bytes
                + ",\"samples\":" + r.samples.get() + ",\"square_sum\":" + r.squares.get()
                + ",\"generated_frames\":" + generated.get()
                + ",\"connected\":" + (pc.connectionState() == PeerConnection.PeerConnectionState.CONNECTED) + "}");
        }
        return "[" + String.join(",", rows) + "]";
    }

    void dispose() {
        try {
            for (Receive r : incoming) r.track.removeSink(r.sink);
            if (pc != null) { pc.close(); pc.dispose(); }
            if (local != null) local.dispose();
            if (source != null) source.dispose();
            if (factory != null) factory.dispose();
            if (adm != null) adm.release();
        } finally {
            Log.i(TAG, "sfu_android_cleanup=COMPLETE");
        }
    }
}
