package relay.spike;

import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.audio.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Linux native peer of the WP02-AC5 E2EE spike. Config (key=value lines then "end") arrives on stdin from the
 * Python coordinator, which owns the test-only keys; they are held only in memory and forwarded over the
 * loopback bridge to the Android peer. Output contains counts/booleans only.
 */
public final class E2eeProbe {
    static final String[] BUCKETS = {"0", "1", "2-10", "11-29", "30-60", "61+"};
    static int bucket(int n) { return n == 0 ? 0 : n == 1 ? 1 : n <= 10 ? 2 : n <= 29 ? 3 : n <= 60 ? 4 : 5; }

    /** Goertzel-style single-bin energy over 100 ms blocks: the share of decoded energy at the expected tone. */
    static final class Meter {
        final double freq; final long startNanos;
        final AtomicLong samples = new AtomicLong(), nonzero = new AtomicLong(), p2samples = new AtomicLong();
        long squares, p2squares; double tone;
        double re, im; int blockN; long position;
        volatile long phase2At = Long.MAX_VALUE;
        Meter(double freq, long startNanos) { this.freq = freq; this.startNanos = startNanos; }
        synchronized void pcm(byte[] b, int rate) {
            boolean p2 = System.nanoTime() >= phase2At;
            for (int i = 0; i + 1 < b.length; i += 2) {
                short s = (short) ((b[i] & 255) | (b[i + 1] << 8));
                samples.incrementAndGet(); if (s != 0) nonzero.incrementAndGet();
                squares += (long) s * s;
                if (p2) { p2samples.incrementAndGet(); p2squares += (long) s * s; }
                double w = 2 * Math.PI * freq * blockN / rate;
                re += s * Math.cos(w); im -= s * Math.sin(w);
                if (++blockN == rate / 10) { tone += 2 * (re * re + im * im) / blockN; re = im = 0; blockN = 0; }
            }
        }
    }

    static final class Stats {
        final AtomicLong ok = new AtomicLong(), failed = new AtomicLong(), passthrough = new AtomicLong();
        final AtomicLongArray seen = new AtomicLongArray(BUCKETS.length), okBy = new AtomicLongArray(BUCKETS.length), sent = new AtomicLongArray(BUCKETS.length);
        final AtomicLongArray keyIndexSeen = new AtomicLongArray(256);
        String hist(AtomicLongArray a) {
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < BUCKETS.length; i++) sb.append(i > 0 ? "," : "").append('"').append(BUCKETS[i]).append("\":").append(a.get(i));
            return sb.append('}').toString();
        }
    }

    static byte[] bytes(RTCEncodedFrame f) {
        ByteBuffer d = f.getData().duplicate();
        byte[] b = new byte[Math.min(d.remaining(), f.getSize())];
        d.get(b);
        return b;
    }

    static String row(Meter m, long packets, long bytes, long generated, boolean connected, boolean enc, Stats s, Stats tx) {
        return "{\"packets\":" + packets + ",\"bytes\":" + bytes + ",\"samples\":" + m.samples.get() + ",\"nonzero\":" + m.nonzero.get()
            + ",\"square_sum\":" + m.squares + ",\"tone_square\":" + (long) m.tone + ",\"generated_frames\":" + generated
            + ",\"connected\":" + connected + ",\"e2ee_enabled\":" + enc + ",\"frames_ok\":" + s.ok.get() + ",\"frames_failed\":" + s.failed.get()
            + ",\"frames_passthrough\":" + s.passthrough.get() + ",\"phase2_samples\":" + m.p2samples.get() + ",\"phase2_square_sum\":" + m.p2squares
            + ",\"recv_size_buckets\":" + s.hist(s.seen) + ",\"recv_ok_by_bucket\":" + s.hist(s.okBy) + ",\"sent_size_buckets\":" + tx.hist(tx.sent) + "}";
    }

    static String mungeDtx(String sdp) {
        return sdp.replace("useinbandfec=1", "useinbandfec=1;usedtx=1");
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        Map<String, String> cfg = new HashMap<>();
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = input.readLine()) != null && !line.equals("end"); ) {
            int eq = line.indexOf('='); cfg.put(line.substring(0, eq), line.substring(eq + 1));
        }
        boolean enc = cfg.get("linux_enc").equals("1"), rotate = cfg.get("rotate").equals("1"), tamperSend = cfg.get("tamper_send").equals("1"),
            tamperRecv = cfg.get("tamper_recv").equals("1"), replay = cfg.get("replay").equals("1"), silent = cfg.get("silent").equals("1"),
            dtx = cfg.get("dtx").equals("1"), scan = cfg.get("scan").equals("1");
        int duration = Integer.parseInt(cfg.get("duration_ms"));
        byte[] salt = E2eeCrypto.hex(cfg.get("salt"));
        String kdf = cfg.get("kdf"); int keyLen = Integer.parseInt(cfg.get("derived_len"));
        byte[][] keys = new byte[2][];
        if (enc) for (int i = 0; i < 2; i++) keys[i] = E2eeCrypto.derive(E2eeCrypto.hex(cfg.get("linux_key" + i)), salt, kdf, keyLen);

        Stats rx = new Stats(), tx = new Stats();
        long[] t0 = {0};
        Meter meter = new Meter(660, 0);
        List<byte[]> scanFrames = Collections.synchronizedList(new ArrayList<>());
        List<long[]> scanMeta = Collections.synchronizedList(new ArrayList<>());
        AtomicLong counter = new AtomicLong();
        AtomicReference<byte[]> replayFrame = new AtomicReference<>();
        AtomicReference<Throwable> transformFailure = new AtomicReference<>();

        RTCEncodedFrameTransformer sendTransform = frame -> {
            try {
                byte[] p = bytes(frame);
                tx.sent.incrementAndGet(bucket(p.length));
                if (!enc) return;
                int idx = rotate && t0[0] != 0 && System.nanoTime() - t0[0] > 3_000_000_000L ? 1 : 0;
                byte[] out = E2eeCrypto.encrypt(keys[idx], idx, p, frame.getSsrc(), frame.getTimestamp(), counter.getAndIncrement());
                if (replay) { replayFrame.compareAndSet(null, out); out = replayFrame.get(); }
                if (tamperSend && out.length > E2eeCrypto.HDR + 1) out[E2eeCrypto.HDR] ^= 0x01; // flip one ciphertext bit
                frame.setData(out);
            } catch (Throwable t) { transformFailure.compareAndSet(null, t); }
        };
        RTCEncodedFrameTransformer receiveTransform = frame -> {
            try {
                byte[] f = bytes(frame);
                int b = bucket(f.length);
                rx.seen.incrementAndGet(b);
                if (f.length > 0) rx.keyIndexSeen.incrementAndGet(f[f.length - 1] & 0xff);
                if (scan && scanFrames.size() < 60) { scanFrames.add(f); scanMeta.add(new long[] {frame.getSsrc(), frame.getTimestamp()}); }
                if (!enc) return;
                if (f.length == 0) { rx.passthrough.incrementAndGet(); return; }
                if (tamperRecv && f.length > E2eeCrypto.HDR + 1) f[E2eeCrypto.HDR] ^= 0x01;
                byte[] pt = E2eeCrypto.decrypt(keys, f);
                if (pt == null) { rx.failed.incrementAndGet(); frame.drop(); return; }
                rx.ok.incrementAndGet(); rx.okBy.incrementAndGet(b);
                frame.setData(pt);
            } catch (Throwable t) { transformFailure.compareAndSet(null, t); }
        };

        RTCConfiguration config = new RTCConfiguration();
        List<RTCRtpTransceiver> transceivers = new CopyOnWriteArrayList<>();
        List<RTCRtpReceiver> receivers = new CopyOnWriteArrayList<>();
        List<AudioTrack> tracks = new CopyOnWriteArrayList<>();
        HeadlessAudioDeviceModule adm = new HeadlessAudioDeviceModule();
        PeerConnectionFactory factory = new PeerConnectionFactory(adm);
        CustomAudioSource source = new CustomAudioSource();
        AudioTrack track = factory.createAudioTrack("linux-440hz", source);
        RTCPeerConnection pc = factory.createPeerConnection(config, new PeerConnectionObserver() {
            public void onIceCandidate(RTCIceCandidate c) { }
            public void onTrack(RTCRtpTransceiver t) {
                transceivers.add(t); RTCRtpReceiver r = t.getReceiver(); receivers.add(r);
                r.setTransform(receiveTransform);
                if (r.getTrack() instanceof AudioTrack a) {
                    tracks.add(a);
                    a.addSink((data, bits, rate, channels, frames) -> { if (bits == 16) meter.pcm(data, rate); });
                }
            }
        });
        RTCRtpSender sender = pc.addTrack(track, List.of("linux"));
        sender.setTransform(sendTransform);
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            server.setSoTimeout(45000);
            System.out.println("BRIDGE_READY port=" + port); System.out.flush();
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(Math.max(35000, duration + 30000));
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                if (!CrossPlatformProbe.readText(in).equals("relay-android-native-v1")) throw new IOException("wrong endpoint");
                CrossPlatformProbe.writeText(out, "local");
                StringBuilder ac = new StringBuilder();
                for (String k : new String[] {"android_enc", "android_key0", "android_key1", "android_kdf", "android_derived_len", "salt", "rotate", "silent", "dtx", "duration_ms"})
                    ac.append(k.replace("android_", "")).append('=').append(cfg.get(k)).append('\n');
                CrossPlatformProbe.writeText(out, ac.toString());
                RTCSessionDescription offer = CallsProbe.create(pc, true);
                if (dtx) offer = new RTCSessionDescription(RTCSdpType.OFFER, mungeDtx(offer.sdp));
                CallsProbe.set(pc, offer, true);
                CallsProbe.await(() -> pc.getIceGatheringState() == RTCIceGatheringState.COMPLETE, "Linux gathering");
                CrossPlatformProbe.writeText(out, pc.getLocalDescription().sdp);
                String answer = CrossPlatformProbe.readText(in);
                CallsProbe.set(pc, new RTCSessionDescription(RTCSdpType.ANSWER, answer), false);
                CallsProbe.await(() -> pc.getConnectionState() == RTCPeerConnectionState.CONNECTED, "Android/Linux connected");
                long start = System.nanoTime();
                t0[0] = start;
                meter.phase2At = start + 4_000_000_000L;
                int ticks = duration / 10;
                for (int tick = 0; tick < ticks; tick++) {
                    byte[] pcm = new byte[960];
                    for (int i = 0; i < 480; i++) {
                        short s = silent ? 0 : (short) (12000 * Math.sin(2 * Math.PI * 440 * (tick * 480L + i) / 48000));
                        pcm[i * 2] = (byte) s; pcm[i * 2 + 1] = (byte) (s >> 8);
                    }
                    source.pushAudio(pcm, 16, 48000, 1, 480);
                    long left = start + TimeUnit.MILLISECONDS.toNanos((tick + 1) * 10L) - System.nanoTime();
                    if (left > 0) TimeUnit.NANOSECONDS.sleep(left);
                }
                String androidJson = CrossPlatformProbe.readText(in);
                CompletableFuture<RTCStatsReport> stats = new CompletableFuture<>(); pc.getStats(stats::complete);
                RTCStatsReport report = stats.get(10, TimeUnit.SECONDS);
                long packets = 0, bytes = 0;
                for (RTCStats s : report.getStats().values()) {
                    Map<String, Object> m = s.getAttributes();
                    if (s.getType() == RTCStatsType.INBOUND_RTP && ("audio".equals(m.get("kind")) || "audio".equals(m.get("mediaType")))) {
                        packets += ((Number) m.getOrDefault("packetsReceived", 0)).longValue();
                        bytes += ((Number) m.getOrDefault("bytesReceived", 0)).longValue();
                    }
                }
                if (scan) scanReport(scanFrames, scanMeta, cfg, salt);
                if (transformFailure.get() != null) System.out.println("TRANSFORM_FAILURE type=" + transformFailure.get().getClass().getSimpleName());
                StringBuilder ki = new StringBuilder("{");
                boolean first = true;
                for (int i = 0; i < 256; i++) if (rx.keyIndexSeen.get(i) > 0) { ki.append(first ? "" : ",").append('"').append(i).append("\":").append(rx.keyIndexSeen.get(i)); first = false; }
                ki.append('}');
                System.out.println("E2EE_RESULT {\"linux\":" + row(meter, packets, bytes, ticks, pc.getConnectionState() == RTCPeerConnectionState.CONNECTED, enc, rx, tx)
                    + ",\"linux_last_byte_histogram\":" + ki + ",\"android\":" + androidJson + ",\"linux_transform_failure\":" + (transformFailure.get() != null) + "}");
                out.writeBoolean(true); out.flush();
            }
        } finally {
            pc.close();
            for (AudioTrack a : tracks) a.dispose();
            for (RTCRtpReceiver r : receivers) r.dispose();
            sender.dispose();
            for (RTCRtpTransceiver t : transceivers) t.dispose();
            track.dispose(); source.dispose(); factory.dispose(); adm.dispose();
            System.out.println("e2ee_cleanup=COMPLETE");
        }
    }

    /** Layout discovery: tests which standard AES-GCM interpretation of the Android-encrypted frames authenticates. */
    static void scanReport(List<byte[]> frames, List<long[]> meta, Map<String, String> cfg, byte[] salt) throws Exception {
        byte[] material = E2eeCrypto.hex(cfg.get("android_key0"));
        List<String> schemes = new ArrayList<>(List.of("raw", "hkdf-empty", "hkdf-saltinfo"));
        for (int it : new int[] {1, 1000, 10000, 100000, 1000000}) schemes.add("pbkdf2-" + it);
        int n = frames.size();
        int min = Integer.MAX_VALUE, max = 0; long sum = 0;
        for (byte[] f : frames) { min = Math.min(min, f.length); max = Math.max(max, f.length); sum += f.length; }
        System.out.println("SCAN frames=" + n + " size_min=" + min + " size_max=" + max + " size_mean=" + (n == 0 ? 0 : sum / n));
        int ivSsrc = 0, ivTs = 0, ivLen12 = 0; Set<String> ivs = new HashSet<>(); Map<Integer, Integer> last = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            byte[] f = frames.get(i);
            if (f.length < 15) continue;
            if (f[f.length - 2] == 12) ivLen12++;
            for (ByteOrder order : new ByteOrder[] {ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
                ByteBuffer iv = ByteBuffer.wrap(f, f.length - 14, 12).order(order);
                long a = iv.getInt() & 0xffffffffL, b = iv.getInt() & 0xffffffffL;
                if (a == meta.get(i)[0] || b == meta.get(i)[0]) ivSsrc++;
                if (a == (meta.get(i)[1] & 0xffffffffL) || b == (meta.get(i)[1] & 0xffffffffL)) ivTs++;
            }
            ivs.add(Arrays.toString(Arrays.copyOfRange(f, f.length - 14, f.length - 2)));
            last.merge(f[f.length - 1] & 0xff, 1, Integer::sum);
        }
        System.out.println("SCAN second_to_last_byte_is_12_frames=" + ivLen12 + " iv_contains_ssrc=" + ivSsrc + " iv_contains_timestamp=" + ivTs + " iv_distinct=" + ivs.size() + " last_byte_hist=" + last);
        for (int trailer : new int[] {13, 14}) for (int keyLen : new int[] {16, 32}) for (String scheme : schemes) {
            byte[] key = E2eeCrypto.derive(material, salt, scheme, keyLen);
            for (int hdr = 0; hdr <= 4; hdr++) for (int aadMode = 0; aadMode < 2; aadMode++) {
                int ok = 0;
                for (byte[] f : frames) {
                    if (f.length < hdr + 16 + trailer) continue;
                    try {
                        byte[] iv = Arrays.copyOfRange(f, f.length - trailer, f.length - trailer + 12);
                        byte[] aad = aadMode == 0 ? Arrays.copyOf(f, hdr) : new byte[0];
                        E2eeCrypto.gcm(false, key, iv, aad, f, hdr, f.length - hdr - trailer);
                        ok++;
                    } catch (Exception e) { }
                }
                if (ok > 0) System.out.println("SCAN_MATCH trailer_bytes=" + trailer + " scheme=" + scheme + " aes_bits=" + keyLen * 8 + " clear_header=" + hdr + " aad=" + (aadMode == 0 ? "header" : "none") + " authenticated=" + ok + "/" + n);
            }
        }
        System.out.println("SCAN_DONE");
    }
}
