package relay.spike;

import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.audio.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One Linux native peer. The other peer MUST be the standalone Android APK. */
public final class CrossPlatformProbe {
    static final int LIMIT = 65536;
    static final class Counts {
        final AtomicLong samples = new AtomicLong(), nonzero = new AtomicLong(), squares = new AtomicLong();
        void pcm(byte[] bytes) {
            long n = 0, energy = 0;
            for (int i=0; i+1<bytes.length; i+=2) {
                short s=(short)((bytes[i]&255)|(bytes[i+1]<<8));
                if(s!=0) n++;
                energy+=(long)s*s;
            }
            samples.addAndGet(bytes.length/2); nonzero.addAndGet(n); squares.addAndGet(energy);
        }
    }
    static void writeText(DataOutputStream out, String text) throws IOException {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>LIMIT) throw new IOException("signal size");
        out.writeInt(bytes.length); out.write(bytes); out.flush();
    }
    static String readText(DataInputStream in) throws IOException {
        int n=in.readInt(); if(n<1 || n>LIMIT) throw new IOException("signal size");
        byte[] bytes=new byte[n]; in.readFully(bytes); return new String(bytes,StandardCharsets.UTF_8);
    }
    static String row(long packets,long bytes,long samples,long nonzero,long squares,long generated,boolean connected) {
        return "{\"packets\":"+packets+",\"bytes\":"+bytes+",\"samples\":"+samples+",\"nonzero\":"+nonzero+",\"square_sum\":"+squares+",\"generated_frames\":"+generated+",\"connected\":"+connected+"}";
    }
    public static void main(String[] args) throws Exception {
        int port=Integer.parseInt(args[0]);
        boolean silent=args.length>1 && args[1].equals("--silent-linux");
        Counts counts=new Counts();
        List<RTCRtpTransceiver> transceivers=new CopyOnWriteArrayList<>();
        List<RTCRtpReceiver> receivers=new CopyOnWriteArrayList<>();
        List<AudioTrack> tracks=new CopyOnWriteArrayList<>();
        HeadlessAudioDeviceModule adm=new HeadlessAudioDeviceModule();
        PeerConnectionFactory factory=new PeerConnectionFactory(adm);
        CustomAudioSource source=new CustomAudioSource();
        AudioTrack track=factory.createAudioTrack("linux-440hz",source);
        AtomicInteger candidates=new AtomicInteger();
        RTCPeerConnection pc=factory.createPeerConnection(new RTCConfiguration(),new PeerConnectionObserver() {
            public void onIceCandidate(RTCIceCandidate c) { candidates.incrementAndGet(); }
            public void onTrack(RTCRtpTransceiver t) {
                transceivers.add(t); RTCRtpReceiver r=t.getReceiver(); receivers.add(r);
                if(r.getTrack() instanceof AudioTrack a) {
                    tracks.add(a);
                    a.addSink((data,bits,rate,channels,frames)-> { if(bits==16) counts.pcm(data); });
                }
            }
        });
        RTCRtpSender sender=pc.addTrack(track,List.of("linux"));
        try(ServerSocket server=new ServerSocket()) {
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port));
            server.setSoTimeout(45000);
            System.out.println("BRIDGE_READY port="+port); System.out.flush();
            try(Socket socket=server.accept()) {
                socket.setSoTimeout(35000);
                DataInputStream in=new DataInputStream(socket.getInputStream());
                DataOutputStream out=new DataOutputStream(socket.getOutputStream());
                if(!readText(in).equals("relay-android-native-v1")) throw new IOException("wrong endpoint");
                RTCSessionDescription offer=CallsProbe.create(pc,true); CallsProbe.set(pc,offer,true);
                CallsProbe.await(()->pc.getIceGatheringState()==RTCIceGatheringState.COMPLETE,"Linux gathering");
                writeText(out,pc.getLocalDescription().sdp);
                String answer=readText(in);
                CallsProbe.set(pc,new RTCSessionDescription(RTCSdpType.ANSWER,answer),false);
                CallsProbe.await(()->pc.getConnectionState()==RTCPeerConnectionState.CONNECTED,"Android/Linux connected");
                long start=System.nanoTime();
                for(int tick=0;tick<800;tick++) {
                    byte[] pcm=new byte[960];
                    for(int i=0;i<480;i++) {
                        short s=silent?0:(short)(12000*Math.sin(2*Math.PI*440*(tick*480L+i)/48000));
                        pcm[i*2]=(byte)s; pcm[i*2+1]=(byte)(s>>8);
                    }
                    source.pushAudio(pcm,16,48000,1,480);
                    long left=start+TimeUnit.MILLISECONDS.toNanos((tick+1)*10L)-System.nanoTime();
                    if(left>0) TimeUnit.NANOSECONDS.sleep(left);
                }
                // Android sends only numeric receive metrics, never SDP, keys or candidate addresses.
                long ap=in.readLong(), ab=in.readLong(), as=in.readLong(), an=in.readLong(), aq=in.readLong(), ag=in.readLong();
                boolean ac=in.readBoolean();
                CompletableFuture<RTCStatsReport> stats=new CompletableFuture<>(); pc.getStats(stats::complete);
                long packets=0, bytes=0;
                for(RTCStats s:stats.get(10,TimeUnit.SECONDS).getStats().values()) {
                    Map<String,Object> m=s.getAttributes();
                    if(s.getType()==RTCStatsType.INBOUND_RTP && ("audio".equals(m.get("kind")) || "audio".equals(m.get("mediaType")))) {
                        packets+=((Number)m.getOrDefault("packetsReceived",0)).longValue();
                        bytes+=((Number)m.getOrDefault("bytesReceived",0)).longValue();
                    }
                }
                System.out.println("SIGNAL_SUMMARY linux_candidates="+candidates.get()+" offer_bytes="+pc.getLocalDescription().sdp.getBytes(StandardCharsets.UTF_8).length+" answer_bytes="+answer.getBytes(StandardCharsets.UTF_8).length);
                System.out.println("CROSS_PLATFORM_RESULT {\"linux\":"+row(packets,bytes,counts.samples.get(),counts.nonzero.get(),counts.squares.get(),800,pc.getConnectionState()==RTCPeerConnectionState.CONNECTED)+",\"android\":"+row(ap,ab,as,an,aq,ag,ac)+"}");
                out.writeBoolean(true); out.flush(); // Let Android clean up after its metrics have been received.
            }
        } finally {
            pc.close();
            for(AudioTrack a:tracks) a.dispose();
            for(RTCRtpReceiver r:receivers) r.dispose();
            sender.dispose();
            for(RTCRtpTransceiver t:transceivers) t.dispose();
            track.dispose(); source.dispose(); factory.dispose(); adm.dispose();
            System.out.println("cross_platform_cleanup=COMPLETE");
        }
    }
}
