package relay.spike;

import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.audio.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;
import java.io.*;

/** Two real native peers, synthetic audio only: never opens a microphone. */
public final class CallsProbe {
    static final int TIMEOUT_SECONDS = 25;
    static final List<Integer> iceSizes = new CopyOnWriteArrayList<>();
    static final List<RTCDataChannel> channels = new CopyOnWriteArrayList<>();
    static final List<RTCRtpSender> senders = new CopyOnWriteArrayList<>();
    static final List<RTCRtpReceiver> receivers = new CopyOnWriteArrayList<>();
    static final List<RTCRtpTransceiver> transceivers = new CopyOnWriteArrayList<>();
    static final List<AudioTrack> remoteTracks = new CopyOnWriteArrayList<>();
    static final CountDownLatch delivered = new CountDownLatch(2);
    static final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
    static final AtomicInteger messages = new AtomicInteger();
    static final class Side implements PeerConnectionObserver {
        final String name;
        final List<RTCIceCandidate> candidates = new CopyOnWriteArrayList<>();
        final AtomicLong pcmBytes = new AtomicLong(), nonzero = new AtomicLong();
        final List<Integer> encoded = new CopyOnWriteArrayList<>();
        volatile RTCPeerConnection pc;
        Side(String name) { this.name = name; }
        public void onIceCandidate(RTCIceCandidate c) {
            if (candidates.size() >= 64) { callbackFailure.set(new IllegalStateException("ICE count exceeded")); return; }
            candidates.add(c);
            iceSizes.add(c.sdp.getBytes(StandardCharsets.UTF_8).length);
        }
        public void onConnectionChange(RTCPeerConnectionState s) { System.out.println("connection " + name + "=" + s); }
        public void onIceGatheringChange(RTCIceGatheringState s) { System.out.println("gathering " + name + "=" + s); }
        public void onIceCandidateError(RTCPeerConnectionIceErrorEvent e) {
            System.out.printf("ice_error side=%s code=%d allocate_timeout=%s auth=%s%n", name, e.getErrorCode(),
                e.getErrorText().toLowerCase(Locale.ROOT).contains("timed out"),
                e.getErrorText().toLowerCase(Locale.ROOT).contains("unauthorized"));
        }
        public void onTrack(RTCRtpTransceiver transceiver) {
            transceivers.add(transceiver);
            RTCRtpReceiver receiver = transceiver.getReceiver(); receivers.add(receiver);
            if (receiver.getTrack() instanceof AudioTrack track) {
                remoteTracks.add(track);
                track.addSink((data, bits, rate, count, frames) -> {
                    pcmBytes.addAndGet(data.length);
                    for (byte b : data) if (b != 0) nonzero.incrementAndGet();
                });
            }
        }
        public void onDataChannel(RTCDataChannel channel) { observe(channel); }
    }
    static void observe(RTCDataChannel channel) {
        channels.add(channel);
        channel.registerObserver(new RTCDataChannelObserver() {
            public void onBufferedAmountChange(long amount) { }
            public void onStateChange() { }
            public void onMessage(RTCDataChannelBuffer buffer) {
                byte[] b = new byte[buffer.data.remaining()]; buffer.data.get(b);
                String text = new String(b, StandardCharsets.UTF_8);
                if (!text.equals("relay-spike-a") && !text.equals("relay-spike-b")) {
                    callbackFailure.set(new AssertionError("Unexpected data payload")); return;
                }
                messages.incrementAndGet(); delivered.countDown();
            }
        });
    }
    static RTCSessionDescription create(RTCPeerConnection pc, boolean offer) throws Exception {
        CompletableFuture<RTCSessionDescription> result = new CompletableFuture<>();
        CreateSessionDescriptionObserver observer = new CreateSessionDescriptionObserver() {
            public void onSuccess(RTCSessionDescription sdp) { result.complete(sdp); }
            public void onFailure(String ignored) { result.completeExceptionally(new IllegalStateException("SDP creation failed (detail suppressed)")); }
        };
        if (offer) pc.createOffer(new RTCOfferOptions(), observer); else pc.createAnswer(new RTCAnswerOptions(), observer);
        return result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    static void set(RTCPeerConnection pc, RTCSessionDescription sdp, boolean local) throws Exception {
        CompletableFuture<Void> result = new CompletableFuture<>();
        SetSessionDescriptionObserver observer = new SetSessionDescriptionObserver() {
            public void onSuccess() { result.complete(null); }
            public void onFailure(String ignored) { result.completeExceptionally(new IllegalStateException("SDP setting failed (detail suppressed)")); }
        };
        if (local) pc.setLocalDescription(sdp, observer); else pc.setRemoteDescription(sdp, observer);
        result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    static void await(java.util.function.BooleanSupplier condition, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (!condition.getAsBoolean()) {
            if (callbackFailure.get() != null) throw new IllegalStateException("Native callback failed", callbackFailure.get());
            if (System.nanoTime() > deadline) { System.out.println("timeout_phase=" + label); throw new TimeoutException(label); }
            Thread.sleep(20);
        }
    }
    static byte[] deflate(byte[] input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream zip = new DeflaterOutputStream(out)) { zip.write(input); }
        return out.toByteArray();
    }
    static byte[] inflateBounded(byte[] input, int limit) throws IOException {
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(input))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[1024]; int n;
            while ((n = in.read(b)) != -1) { if (out.size() + n > limit) throw new IOException("Decoded SDP exceeds bound"); out.write(b, 0, n); }
            return out.toByteArray();
        }
    }
    static void recordBody(String type, String body) throws IOException {
        String destination=System.getenv("RELAY_SPIKE_BODY_DIR");
        if(destination==null) return;
        java.nio.file.Path dir=java.nio.file.Path.of(destination);
        if(!java.nio.file.Files.isDirectory(dir)) throw new IOException("Body output directory must already exist");
        java.nio.file.Files.writeString(java.nio.file.Files.createTempFile(dir,type+"-", ".callbody"),body);
    }
    static void measure(String type, String value) throws Exception {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8), compressed = deflate(raw);
        if (!Arrays.equals(raw, inflateBounded(compressed, 65536))) throw new AssertionError("SDP roundtrip failed");
        // This is the proposed TYPE:CALL body, NOT a gift-wrap/Tink measurement.
        String json = metadata(type) + ",\"sdp\":\"" + Base64.getEncoder().encodeToString(compressed) + "\"}";
        String callBody="TYPE:CALL|J:" + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        recordBody(type,callBody);
        int body = callBody.getBytes(StandardCharsets.UTF_8).length;
        System.out.printf("signal %s raw_utf8=%d zlib=%d base64_zlib=%d proposed_call_body=%d%n", type, raw.length, compressed.length, Base64.getEncoder().encodeToString(compressed).length(), body);
    }
    static String metadata(String type) {
        // Fixed non-secret measurement identifiers. Not the production call codec.
        return "{\"v\":1,\"op\":\""+type+"\",\"call\":\"00000000000000000000000000000000\",\"ts\":0,\"expires\":45000,\"device\":\"spike-a\",\"caller\":\""+"0".repeat(64)+"\",\"callee\":\""+"1".repeat(64)+"\",\"nonce\":\"00000000000000000000000000000000\",\"media\":\"audio\"";
    }
    static void measureIce(List<RTCIceCandidate> candidates) throws Exception {
        // Real gathered candidates; at most 20 per proposed frame, per plan.
        for (int from=0; from<candidates.size(); from+=20) {
            List<String> entries = new ArrayList<>();
            for (RTCIceCandidate c : candidates.subList(from, Math.min(from+20,candidates.size()))) {
                // libwebrtc candidate fields contain no JSON control characters here.
                entries.add("{\"sdpMid\":\""+c.sdpMid+"\",\"sdpMLineIndex\":"+c.sdpMLineIndex+",\"candidate\":\""+c.sdp.replace("\\", "\\\\").replace("\"", "\\\"")+"\"}");
            }
            byte[] json = (metadata("ice")+",\"cands\":["+String.join(",",entries)+"]}").getBytes(StandardCharsets.UTF_8);
            recordBody("ice","TYPE:CALL|J:"+Base64.getEncoder().encodeToString(json));
            System.out.printf("signal ice candidates=%d json_utf8=%d zlib_json=%d proposed_call_body=%d%n", entries.size(), json.length, deflate(json).length, ("TYPE:CALL|J:"+Base64.getEncoder().encodeToString(json)).getBytes(StandardCharsets.UTF_8).length);
        }
    }
    static void boundedCodecChecks() throws Exception {
        byte[] huge = new byte[65537];
        try { inflateBounded(deflate(huge), 65536); throw new AssertionError("Expansion accepted"); } catch (IOException expected) { }
        try { inflateBounded(new byte[]{1,2,3}, 65536); throw new AssertionError("Corrupt stream accepted"); } catch (IOException expected) { }
        System.out.println("bounded_codec_checks=PASS expansion_limit=65536 malformed_rejected=true");
    }
    static RTCConfiguration config(boolean turn) {
        RTCConfiguration config = new RTCConfiguration();
        if (turn) {
            if (!"1".equals(System.getenv("RELAY_SPIKE_LIVE"))) throw new IllegalArgumentException("Live TURN requires RELAY_SPIKE_LIVE=1");
            RTCIceServer server = new RTCIceServer();
            String url = required("RELAY_TURN_URL");
            if (!url.matches("turns:[A-Za-z0-9.-]+:443\\?transport=tcp")) throw new IllegalArgumentException("TURN URL must be turns:host:443?transport=tcp");
            server.urls.add(url); server.username = required("RELAY_TURN_USERNAME"); server.password = required("RELAY_TURN_PASSWORD");
            config.iceServers.add(server); config.iceTransportPolicy = RTCIceTransportPolicy.RELAY;
            // Probe uses the default route only: unrelated Docker/virtual adapters can
            // hold gathering open after successful TURN allocations on the real route.
            config.portAllocatorConfig = new PortAllocatorConfig().setDisableAdapterEnumeration(true);
        }
        return config;
    }
    static void selectedRelayPair(Side side) throws Exception {
        CompletableFuture<RTCStatsReport> pending = new CompletableFuture<>();
        side.pc.getStats(pending::complete);
        Map<String, RTCStats> all = pending.get(10, TimeUnit.SECONDS).getStats();
        boolean found = false;
        for (RTCStats transport : all.values()) {
            if (transport.getType() != RTCStatsType.TRANSPORT) continue;
            Object selected = transport.getAttributes().get("selectedCandidatePairId");
            if (!(selected instanceof String id)) continue;
            RTCStats pair = all.get(id);
            if (pair == null || pair.getType() != RTCStatsType.CANDIDATE_PAIR) throw new AssertionError("Selected ICE pair missing");
            Map<String,Object> attrs = pair.getAttributes();
            RTCStats local = all.get(attrs.get("localCandidateId")), remote = all.get(attrs.get("remoteCandidateId"));
            if (local == null || remote == null
                    || !"relay".equals(local.getAttributes().get("candidateType"))
                    || !"relay".equals(remote.getAttributes().get("candidateType"))
                    || !"tls".equals(local.getAttributes().get("relayProtocol"))
                    || !"succeeded".equals(attrs.get("state"))) throw new AssertionError("Selected ICE pair not TURN/TLS");
            long sent = ((Number)attrs.getOrDefault("bytesSent", 0)).longValue();
            long received = ((Number)attrs.getOrDefault("bytesReceived", 0)).longValue();
            if (sent <= 0 || received <= 0) throw new AssertionError("Selected TURN pair carried no traffic");
            System.out.printf("selected_pair side=%s local=relay remote=relay relay_protocol=tls sent=%d received=%d%n", side.name, sent, received);
            found = true;
        }
        if (!found) throw new AssertionError("Selected ICE transport not reported");
    }
    static String required(String name) { String s=System.getenv(name); if(s==null || s.isBlank()) throw new IllegalArgumentException("Missing " + name); return s; }
    static void stats(String label, List<Integer> values) {
        IntSummaryStatistics s = values.stream().mapToInt(Integer::intValue).summaryStatistics();
        System.out.printf("%s count=%d min_bytes=%d max_bytes=%d total_bytes=%d%n", label,s.getCount(), s.getCount()==0?0:s.getMin(),s.getCount()==0?0:s.getMax(),s.getSum());
    }
    public static void main(String[] args) throws Exception {
        boolean turn = Arrays.asList(args).contains("--turn");
        RTCConfiguration cfg = config(turn); // Fail before loading native code or contacting any provider.
        Thread.setDefaultUncaughtExceptionHandler((t,e) -> { callbackFailure.set(e); System.err.println("uncaught_failure thread="+t.getName()+" type="+e.getClass().getSimpleName()); });
        boundedCodecChecks();
        AudioDeviceModule physical = new AudioDeviceModule();
        System.out.println("native_load=PASS os="+System.getProperty("os.name")+" arch="+System.getProperty("os.arch")+" java="+System.getProperty("java.version"));
        System.out.println("recording_devices="+physical.getRecordingDevices());
        System.out.println("playout_devices="+physical.getPlayoutDevices());
        physical.dispose();
        HeadlessAudioDeviceModule admA = new HeadlessAudioDeviceModule(), admB = new HeadlessAudioDeviceModule();
        PeerConnectionFactory fa = new PeerConnectionFactory(admA), fb = new PeerConnectionFactory(admB);
        CustomAudioSource sa = new CustomAudioSource(), sb = new CustomAudioSource();
        AudioTrack ta = fa.createAudioTrack("tone-a",sa), tb = fb.createAudioTrack("tone-b",sb);
        Side a = new Side("a"), b = new Side("b");
        try {
            a.pc=fa.createPeerConnection(cfg,a); b.pc=fb.createPeerConnection(cfg,b);
            RTCRtpSender senderA = a.pc.addTrack(ta,List.of("a")), senderB = b.pc.addTrack(tb,List.of("b"));
            senders.add(senderA); senders.add(senderB);
            senderA.setTransform(frame -> a.encoded.add(frame.getSize()));
            senderB.setTransform(frame -> b.encoded.add(frame.getSize()));
            RTCDataChannel ca=a.pc.createDataChannel("from-a",new RTCDataChannelInit()), cb=b.pc.createDataChannel("from-b",new RTCDataChannelInit());
            observe(ca); observe(cb);
            RTCSessionDescription offer=create(a.pc,true); measure("offer",offer.sdp);
            set(a.pc,offer,true); set(b.pc,offer,false);
            RTCSessionDescription answer=create(b.pc,false); measure("answer",answer.sdp);
            set(b.pc,answer,true); set(a.pc,answer,false);
            await(() -> a.pc.getIceGatheringState()==RTCIceGatheringState.COMPLETE && b.pc.getIceGatheringState()==RTCIceGatheringState.COMPLETE,"ICE gathering");
            if (turn && java.util.stream.Stream.concat(a.candidates.stream(),b.candidates.stream()).anyMatch(c -> !c.sdp.contains(" typ relay "))) throw new AssertionError("Non-relay candidate in forced mode");
            for (RTCIceCandidate c : a.candidates) b.pc.addIceCandidate(c);
            for (RTCIceCandidate c : b.candidates) a.pc.addIceCandidate(c);
            await(() -> ca.getState()==RTCDataChannelState.OPEN && cb.getState()==RTCDataChannelState.OPEN,"Data channels open");
            ca.send(new RTCDataChannelBuffer(ByteBuffer.wrap("relay-spike-a".getBytes(StandardCharsets.UTF_8)),false));
            cb.send(new RTCDataChannelBuffer(ByteBuffer.wrap("relay-spike-b".getBytes(StandardCharsets.UTF_8)),false));
            if (!delivered.await(TIMEOUT_SECONDS,TimeUnit.SECONDS)) throw new TimeoutException("Data roundtrip");
            long start = System.nanoTime();
            for(int tick=0;tick<300;tick++) {
                for(int side=0;side<2;side++) {
                    byte[] pcm=new byte[960]; double hz=side==0?440:660;
                    for(int i=0;i<480;i++) { short sample=(short)(12000*Math.sin(2*Math.PI*hz*(tick*480L+i)/48000)); pcm[2*i]=(byte)sample; pcm[2*i+1]=(byte)(sample>>8); }
                    (side==0?sa:sb).pushAudio(pcm,16,48000,1,480);
                }
                long remaining=start+TimeUnit.MILLISECONDS.toNanos((tick+1)*10L)-System.nanoTime();
                if (remaining>0) TimeUnit.NANOSECONDS.sleep(remaining);
            }
            await(() -> a.nonzero.get()>0 && b.nonzero.get()>0,"Decoded nonzero audio both ways");
            if(callbackFailure.get()!=null) throw new IllegalStateException("Callback failure",callbackFailure.get());
            if (turn) { selectedRelayPair(a); selectedRelayPair(b); }
            measureIce(a.candidates); measureIce(b.candidates);
            stats("ice_sdp",iceSizes); stats("encoded_audio_a",a.encoded); stats("encoded_audio_b",b.encoded);
            if (a.encoded.isEmpty() || b.encoded.isEmpty()) throw new AssertionError("No encoded frames");
            System.out.printf("decoded_audio a_pcm_bytes=%d a_nonzero_bytes=%d b_pcm_bytes=%d b_nonzero_bytes=%d%n",a.pcmBytes.get(),a.nonzero.get(),b.pcmBytes.get(),b.nonzero.get());
            System.out.println("data_messages="+messages.get()+" result=PASS mode="+(turn?"forced-turn-tls443":"local-two-native-peers")+" synthetic_audio=true audible_call=false");
        } finally {
            if(a.pc!=null) a.pc.close(); if(b.pc!=null) b.pc.close();
            for(RTCDataChannel c:channels) { c.unregisterObserver(); c.dispose(); }
            for(AudioTrack t:remoteTracks) t.dispose();
            for(RTCRtpReceiver r:receivers) r.dispose();
            for(RTCRtpSender s:senders) s.dispose();
            for(RTCRtpTransceiver t:transceivers) t.dispose();
            ta.dispose(); tb.dispose(); sa.dispose(); sb.dispose(); fa.dispose(); fb.dispose(); admA.dispose(); admB.dispose();
            System.out.println("cleanup=COMPLETE");
        }
    }
}
