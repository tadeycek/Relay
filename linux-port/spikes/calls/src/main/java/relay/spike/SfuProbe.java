package relay.spike;

import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.audio.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Private pipe signalling endpoint. No microphone, credentials, or public SDP logs. */
public final class SfuProbe {
    /** Register ownership immediately after allocation, before any fallible initialization. */
    static final class Ownership implements AutoCloseable {
        private final Deque<AutoCloseable> actions=new ArrayDeque<>();
        synchronized void defer(AutoCloseable action) { actions.push(action); }
        <T> T own(T value, java.util.function.Consumer<T> dispose) {
            defer(()->dispose.accept(value));
            return value;
        }
        Throwable closeInto(Throwable failure) {
            boolean interrupted=false;
            while(true) {
                AutoCloseable action;
                synchronized(this) { action=actions.poll(); }
                if(action==null) break;
                try { action.close(); }
                catch(Throwable cleanup) {
                    if(cleanup instanceof InterruptedException) interrupted=true;
                    if(failure==null) failure=cleanup;
                    else if(failure!=cleanup) failure.addSuppressed(cleanup);
                }
            }
            if(interrupted) Thread.currentThread().interrupt();
            return failure;
        }
        public void close() throws Exception { rethrow(closeInto(null)); }
    }
    static void rethrow(Throwable failure) throws Exception {
        if(failure instanceof Error) throw (Error)failure;
        if(failure instanceof Exception) throw (Exception)failure;
        if(failure!=null) throw new AssertionError(failure);
    }
    static final class Receive {
        final RTCRtpTransceiver transceiver;
        final RTCRtpReceiver receiver;
        final AudioTrack track;
        final AtomicLong samples=new AtomicLong(), squares=new AtomicLong();
        Receive(RTCRtpTransceiver t, Ownership resources) {
            transceiver=resources.own(t,RTCRtpTransceiver::dispose);
            receiver=resources.own(t.getReceiver(),RTCRtpReceiver::dispose);
            track=resources.own((AudioTrack)receiver.getTrack(),AudioTrack::dispose);
            track.addSink((data,bits,rate,channels,frames)-> {
                if(bits!=16) return;
                long q=0;
                for(int i=0;i+1<data.length;i+=2) { short s=(short)((data[i]&255)|(data[i+1]<<8)); q+=(long)s*s; }
                samples.addAndGet(data.length/2); squares.addAndGet(q);
            });
        }
    }
    static final class Peer implements PeerConnectionObserver, AutoCloseable {
        final int index;
        final boolean silent;
        final Ownership resources=new Ownership(), shutdown=new Ownership();
        final HeadlessAudioDeviceModule adm;
        final PeerConnectionFactory factory;
        final CustomAudioSource source;
        final AudioTrack track;
        final RTCPeerConnection pc;
        final RTCRtpTransceiver publication;
        final RTCRtpSender sender;
        final List<Receive> incoming=new CopyOnWriteArrayList<>();
        final AtomicBoolean running=new AtomicBoolean(true);
        final AtomicLong frames=new AtomicLong();
        final Thread generator;
        Peer(int i, boolean mute) throws Exception {
            index=i; silent=mute;
            try {
            adm=resources.own(new HeadlessAudioDeviceModule(),HeadlessAudioDeviceModule::dispose);
            factory=resources.own(new PeerConnectionFactory(adm),PeerConnectionFactory::dispose);
            source=resources.own(new CustomAudioSource(),CustomAudioSource::dispose);
            track=resources.own(factory.createAudioTrack("generated-"+i,source),AudioTrack::dispose);
            RTCConfiguration cfg=new RTCConfiguration();
            // SFU offers public candidates; outbound ICE checks work with local candidates.
            // Separate STUN gathering exceeded 25 seconds with and without default-route-only enumeration.
            pc=factory.createPeerConnection(cfg,this);
            shutdown.defer(pc::close);
            RTCRtpTransceiverInit init=new RTCRtpTransceiverInit(); init.direction=RTCRtpTransceiverDirection.SEND_ONLY;
            publication=resources.own(pc.addTransceiver(track,init),RTCRtpTransceiver::dispose);
            sender=resources.own(publication.getSender(),RTCRtpSender::dispose);
            generator=new Thread(()-> {
                long start=System.nanoTime();
                try {
                    for(int tick=0;running.get() && tick<15000;tick++) {
                        byte[] pcm=new byte[960];
                        for(int j=0;j<480;j++) {
                            short s=silent?0:(short)(12000*Math.sin(2*Math.PI*(440+220*index)*(tick*480L+j)/48000));
                            pcm[j*2]=(byte)s; pcm[j*2+1]=(byte)(s>>8);
                        }
                        source.pushAudio(pcm,16,48000,1,480); frames.incrementAndGet();
                        long left=start+TimeUnit.MILLISECONDS.toNanos((tick+1)*10L)-System.nanoTime();
                        if(left>0) TimeUnit.NANOSECONDS.sleep(left);
                    }
                } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            },"sfu-generated-"+i);
            shutdown.defer(()->stopGenerator(generator,running));
            generator.start();
            } catch(Throwable failure) {
                rethrow(resources.closeInto(shutdown.closeInto(failure)));
                throw new AssertionError("unreachable");
            }
        }
        public void onIceCandidate(RTCIceCandidate c) { }
        public void onConnectionChange(RTCPeerConnectionState state) {
            System.err.println("SFU_CONNECTION participant="+index+" state="+state);
        }
        public void onIceGatheringChange(RTCIceGatheringState state) {
            System.err.println("SFU_GATHERING participant="+index+" state="+state);
        }
        public void onTrack(RTCRtpTransceiver t) { incoming.add(new Receive(t,resources)); }
        String local(boolean offer) throws Exception {
            CallsProbe.set(pc,CallsProbe.create(pc,offer),true);
            CallsProbe.await(()->pc.getIceGatheringState()==RTCIceGatheringState.COMPLETE,"bounded SFU ICE gathering");
            return pc.getLocalDescription().sdp;
        }
        String metrics() throws Exception {
            List<String> rows=new ArrayList<>();
            for(Receive r:incoming) {
                CompletableFuture<RTCStatsReport> f=new CompletableFuture<>(); pc.getStats(r.receiver,f::complete);
                long packets=0,bytes=0;
                for(RTCStats stat:f.get(10,TimeUnit.SECONDS).getStats().values()) {
                    if(stat.getType()!=RTCStatsType.INBOUND_RTP) continue;
                    Map<String,Object> m=stat.getAttributes();
                    packets+=((Number)m.getOrDefault("packetsReceived",0)).longValue();
                    bytes+=((Number)m.getOrDefault("bytesReceived",0)).longValue();
                }
                rows.add("{\"mid\":\""+r.transceiver.getMid()+"\",\"packets\":"+packets+",\"bytes\":"+bytes+",\"samples\":"+r.samples.get()+",\"square_sum\":"+r.squares.get()+",\"connected\":"+(pc.getConnectionState()==RTCPeerConnectionState.CONNECTED)+"}");
            }
            return "["+String.join(",",rows)+"]";
        }
        public void close() throws Exception {
            rethrow(resources.closeInto(shutdown.closeInto(null)));
        }
    }
    static void stopGenerator(Thread generator, AtomicBoolean running) throws Exception {
        running.set(false);
        generator.interrupt();
        InterruptedException interrupted=Thread.interrupted()?new InterruptedException("generator cleanup interrupted"):null;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(generator.isAlive()) {
            long left=deadline-System.nanoTime();
            if(left<=0) break;
            try { TimeUnit.NANOSECONDS.timedJoin(generator,left); }
            catch(InterruptedException e) { if(interrupted==null) interrupted=e; }
        }
        if(interrupted!=null) Thread.currentThread().interrupt();
        if(generator.isAlive()) {
            IOException failure=new IOException("generator cleanup timeout");
            if(interrupted!=null) failure.addSuppressed(interrupted);
            throw failure;
        }
        if(interrupted!=null) throw interrupted;
    }
    static String encode(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
    public static void main(String[] args) throws Exception {
        List<Peer> peers=new ArrayList<>();
        Ownership peerOwnership=new Ownership();
        Throwable failure=null;
        try(BufferedReader in=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))) {
            String line;
            while((line=in.readLine())!=null) {
                if(line.length()>100000) throw new IOException("command bound");
                String[] p=line.split("\\t",-1); String op=p[0];
                if(op.equals("QUIT")) break;
                int i=Integer.parseInt(p[1]); String result="OK";
                if(op.equals("NEW")) {
                    if(i!=peers.size() || i>2) throw new IOException("peer bound");
                    Peer peer=new Peer(i,p[2].equals("1"));
                    peerOwnership.defer(peer::close);
                    peers.add(peer);
                }
                else {
                    Peer peer=peers.get(i);
                    switch(op) {
                        case "OFFER": result=peer.local(true); break;
                        case "ANSWER": result=peer.local(false); break;
                        case "MID": result=peer.publication.getMid(); break;
                        case "REMOTE": CallsProbe.set(peer.pc,new RTCSessionDescription(p[2].equals("offer")?RTCSdpType.OFFER:RTCSdpType.ANSWER,new String(Base64.getDecoder().decode(p[3]),StandardCharsets.UTF_8)),false); break;
                        case "CONNECTED": CallsProbe.await(()->peer.pc.getConnectionState()==RTCPeerConnectionState.CONNECTED,"SFU connection"); break;
                        case "METRICS": result=peer.metrics(); break;
                        default: throw new IOException("unknown command");
                    }
                }
                System.out.println("SFU_REPLY "+encode(result)); System.out.flush();
            }
        } catch(Throwable operationFailure) {
            failure=operationFailure;
        } finally {
            rethrow(peerOwnership.closeInto(failure));
            System.out.println("SFU_DISPOSED"); System.out.flush();
        }
    }
}
