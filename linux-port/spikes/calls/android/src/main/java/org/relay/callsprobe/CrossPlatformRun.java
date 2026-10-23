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

/** Test-only generated PCM, native Opus/RTP/ICE. Never requests RECORD_AUDIO. */
final class CrossPlatformRun {
    static final String TAG="RelayCallsProbe";
    final AtomicLong samples=new AtomicLong(), nonzero=new AtomicLong(), squares=new AtomicLong(), generated=new AtomicLong();
    final AtomicInteger candidates=new AtomicInteger();
    final List<AudioTrack> remoteTracks=new CopyOnWriteArrayList<>();
    final AudioTrackSink sink=(buffer,bits,rate,channels,frames,timestamp)-> {
        if(bits!=16) return;
        ByteBuffer b=buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        long n=0,q=0,count=0;
        while(b.remaining()>=2) { short s=b.getShort(); count++; if(s!=0)n++; q+=(long)s*s; }
        samples.addAndGet(count); nonzero.addAndGet(n); squares.addAndGet(q);
    };
    static void waitFor(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
        while(!condition.call()) { if(System.nanoTime()>end) throw new TimeoutException("native state timeout"); Thread.sleep(20); }
    }
    static abstract class SdpResult implements SdpObserver {
        public void onCreateSuccess(SessionDescription d) { }
        public void onSetSuccess() { }
        public void onCreateFailure(String ignored) { failed(); }
        public void onSetFailure(String ignored) { failed(); }
        abstract void failed();
    }
    static void set(PeerConnection pc,SessionDescription d,boolean local) throws Exception {
        CompletableFuture<Boolean> result=new CompletableFuture<>();
        SdpObserver observer=new SdpResult() {
            public void onSetSuccess() {result.complete(true);}
            void failed() {result.completeExceptionally(new IOException("SDP set rejected (details suppressed)"));}
        };
        if(local)pc.setLocalDescription(observer,d); else pc.setRemoteDescription(observer,d);
        result.get(25,TimeUnit.SECONDS);
    }
    static String readText(DataInputStream in) throws IOException {
        int n=in.readInt(); if(n<1||n>65536)throw new IOException("signal size");
        byte[] b=new byte[n]; in.readFully(b); return new String(b,StandardCharsets.UTF_8);
    }
    static void writeText(DataOutputStream out,String s) throws IOException {
        byte[] b=s.getBytes(StandardCharsets.UTF_8); if(b.length>65536)throw new IOException("signal size");
        out.writeInt(b.length); out.write(b); out.flush();
    }
    void run(Context context,int port,boolean silent) throws Exception {
        try(Socket bridge=new Socket()) {
            bridge.connect(new InetSocketAddress("127.0.0.1",port),10000); bridge.setSoTimeout(35000);
            runConnected(context,silent,bridge);
        }
    }
    void runConnected(Context context,boolean silent,Socket bridge) throws Exception {
        DataInputStream in=new DataInputStream(bridge.getInputStream()); DataOutputStream out=new DataOutputStream(bridge.getOutputStream());
        writeText(out,"relay-android-native-v1");
        String mode=readText(in);
        List<PeerConnection.IceServer> iceServers=Collections.emptyList();
        if(mode.equals("turn")) {
            String url=readText(in),user=readText(in),password=readText(in);
            if(!url.equals("turns:turn.cloudflare.com:443?transport=tcp")||user.length()>4096||password.length()>4096)throw new IOException("invalid ephemeral TURN config");
            iceServers=Collections.singletonList(PeerConnection.IceServer.builder(url).setUsername(user).setPassword(password).createIceServer());
        } else if(!mode.equals("local"))throw new IOException("invalid bridge mode");
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .setInjectableLogger((message,severity,tag)->{},Logging.Severity.LS_ERROR)
            .createInitializationOptions());
        AtomicLong next=new AtomicLong(), frame=new AtomicLong();
        JavaAudioDeviceModule adm=JavaAudioDeviceModule.builder(context)
            .setSampleRate(48000).setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false)
            .setEnableVolumeLogger(false)
            .setAudioBufferCallback((buffer,format,channels,rate,bytes,timestamp)-> {
                // With AudioRecord disabled the SDK calls this without a microphone clock: pace 10 ms here.
                long now=System.nanoTime(); long target=next.get();
                if(target==0 || target<now-TimeUnit.MILLISECONDS.toNanos(100))target=now;
                long left=target-now;
                if(left>0)java.util.concurrent.locks.LockSupport.parkNanos(left);
                next.set(target+TimeUnit.MILLISECONDS.toNanos(10));
                ByteBuffer b=buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN); b.clear();
                long position=frame.getAndAdd(buffer.capacity()/2/channels);
                for(int i=0;i<buffer.capacity()/2/channels;i++) {
                    short s=silent?0:(short)(12000*Math.sin(2*Math.PI*660*(position+i)/rate));
                    for(int c=0;c<channels;c++)b.putShort(s);
                }
                generated.incrementAndGet(); return System.nanoTime();
            }).createAudioDeviceModule();
        adm.setAudioRecordEnabled(false); // Critical: no android.media.AudioRecord is constructed or opened.
        adm.setSpeakerMute(true); // Decoded sink observes native PCM; no listening assertion.
        PeerConnectionFactory factory=PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory();
        MediaConstraints constraints=new MediaConstraints();
        constraints.optional.add(new MediaConstraints.KeyValuePair("googEchoCancellation","false"));
        constraints.optional.add(new MediaConstraints.KeyValuePair("googAutoGainControl","false"));
        constraints.optional.add(new MediaConstraints.KeyValuePair("googNoiseSuppression","false"));
        AudioSource source=factory.createAudioSource(constraints);
        AudioTrack local=factory.createAudioTrack("android-660hz",source);
        PeerConnection.RTCConfiguration config=new PeerConnection.RTCConfiguration(iceServers);
        if(mode.equals("turn"))config.iceTransportsType=PeerConnection.IceTransportsType.RELAY;
        config.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;
        PeerConnection pc=factory.createPeerConnection(config,new PeerConnection.Observer() {
            public void onSignalingChange(PeerConnection.SignalingState s){}
            public void onIceConnectionChange(PeerConnection.IceConnectionState s){}
            public void onIceConnectionReceivingChange(boolean r){}
            public void onIceGatheringChange(PeerConnection.IceGatheringState s){}
            public void onIceCandidate(IceCandidate c){candidates.incrementAndGet();}
            public void onIceCandidatesRemoved(IceCandidate[] c){}
            public void onAddStream(MediaStream s){}
            public void onRemoveStream(MediaStream s){}
            public void onDataChannel(DataChannel c){}
            public void onRenegotiationNeeded(){}
            public void onTrack(RtpTransceiver transceiver) {
                if(transceiver.getReceiver().track() instanceof AudioTrack a) {
                    remoteTracks.add(a); a.addSink(sink);
                }
            }
        });
        if(pc==null)throw new IOException("peer creation failed");
        pc.addTrack(local,Collections.singletonList("android"));
        try(Socket socket=bridge) {
            set(pc,new SessionDescription(SessionDescription.Type.OFFER,readText(in)),false);
            CompletableFuture<SessionDescription> answer=new CompletableFuture<>();
            pc.createAnswer(new SdpResult() {
                public void onCreateSuccess(SessionDescription d){answer.complete(d);}
                void failed(){answer.completeExceptionally(new IOException("SDP answer rejected (details suppressed)"));}
            },new MediaConstraints());
            set(pc,answer.get(25,TimeUnit.SECONDS),true);
            waitFor(()->mode.equals("turn")?candidates.get()>0:pc.iceGatheringState()==PeerConnection.IceGatheringState.COMPLETE);
            writeText(out,pc.getLocalDescription().description);
            waitFor(()->pc.connectionState()==PeerConnection.PeerConnectionState.CONNECTED);
            Thread.sleep(7000);
            CompletableFuture<RTCStatsReport> stats=new CompletableFuture<>(); pc.getStats(stats::complete);
            RTCStatsReport report=stats.get(10,TimeUnit.SECONDS);
            long packets=0,bytes=0;
            for(RTCStats s:report.getStatsMap().values()) {
                Map<String,Object> m=s.getMembers();
                if("inbound-rtp".equals(s.getType()) && ("audio".equals(m.get("kind"))||"audio".equals(m.get("mediaType")))) {
                    packets+=((Number)m.getOrDefault("packetsReceived",0)).longValue();
                    bytes+=((Number)m.getOrDefault("bytesReceived",0)).longValue();
                }
            }
            boolean connected=pc.connectionState()==PeerConnection.PeerConnectionState.CONNECTED;
            out.writeLong(packets); out.writeLong(bytes); out.writeLong(samples.get()); out.writeLong(nonzero.get());
            out.writeLong(squares.get()); out.writeLong(generated.get()); out.writeBoolean(connected); out.flush();
            String[] selected=selected(report);
            for(String value:selected)writeText(out,value);
            boolean passed=connected && packets>0 && bytes>0 && samples.get()>=24000 && nonzero.get()>0 && squares.get()/Math.max(1,samples.get())>250000;
            Log.i(TAG,"android_receive="+(passed?"PASS":"FAIL")+" packets="+packets+" bytes="+bytes+" samples="+samples.get()+" nonzero="+nonzero.get()+" generated_frames="+generated.get()+" ice_candidates="+candidates.get()+" microphone_opened=false synthetic_audio=true");
            in.readBoolean();
        } finally {
            for(AudioTrack a:remoteTracks)a.removeSink(sink);
            pc.close(); pc.dispose(); local.dispose(); source.dispose(); factory.dispose(); adm.release();
            Log.i(TAG,"android_cross_platform_cleanup=COMPLETE");
        }
    }
    static String safe(Object value) {
        String s=String.valueOf(value);
        return Arrays.asList("relay","host","srflx","prflx","tls","tcp","udp").contains(s)?s:"unexposed";
    }
    static String[] selected(RTCStatsReport report) throws IOException {
        Map<String,RTCStats> all=report.getStatsMap();
        for(RTCStats s:all.values())if("transport".equals(s.getType())) {
            RTCStats pair=all.get(s.getMembers().get("selectedCandidatePairId"));
            if(pair==null)continue;
            RTCStats local=all.get(pair.getMembers().get("localCandidateId")),remote=all.get(pair.getMembers().get("remoteCandidateId"));
            if(local==null||remote==null)throw new IOException("selected candidates missing");
            return new String[]{safe(local.getMembers().get("candidateType")),safe(remote.getMembers().get("candidateType")),safe(local.getMembers().get("relayProtocol"))};
        }
        throw new IOException("selected transport missing");
    }
}
