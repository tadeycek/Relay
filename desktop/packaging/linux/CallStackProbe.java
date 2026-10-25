import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.audio.*;
public class Probe // run via a COPY of the extracted tree with app.mainclass=Probe in lib/app/relay.cfg (see WP10.md) {
  static void step(String n, Runnable r){ try { r.run(); System.out.println("OK   "+n);} catch(Throwable t){ System.out.println("FAIL "+n+": "+t); } }
  public static void main(String[] a) throws Exception {
    for (String c : new String[]{"com.relay.app.desktop.DesktopCallPeer","com.relay.app.desktop.DesktopCallHost","com.relay.app.desktop.DesktopMessageStore","com.relay.app.desktop.DesktopMailboxTransport"})
      step("load "+c, () -> { try { Class.forName(c); } catch(ClassNotFoundException e){ throw new RuntimeException(e);} });
    step("webrtc factory+pc", () -> {
      AudioDeviceModule adm = new AudioDeviceModule();
      PeerConnectionFactory f = new PeerConnectionFactory(adm);
      RTCConfiguration cfg = new RTCConfiguration();
      RTCPeerConnection pc = f.createPeerConnection(cfg, new PeerConnectionObserver(){ public void onIceCandidate(RTCIceCandidate c){} });
      System.out.println("     pc="+(pc!=null));
      if (pc!=null) pc.close();
      f.dispose(); adm.dispose();
    });
    step("java.sql sqlite in-memory", () -> { try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")) { c.createStatement().execute("select 1"); } catch(Exception e){ throw new RuntimeException(e);} });
    step("jdk.crypto.ec P-256 keygen+ECDH", () -> { try { var g=java.security.KeyPairGenerator.getInstance("EC"); g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1")); var k=g.generateKeyPair(); var ka=javax.crypto.KeyAgreement.getInstance("ECDH"); ka.init(k.getPrivate()); ka.doPhase(k.getPublic(),true); ka.generateSecret(); } catch(Exception e){ throw new RuntimeException(e);} });
    step("jdk.security.auth UnixSystem", () -> new com.sun.security.auth.module.UnixSystem().getUid());
    step("java.transaction.xa", () -> { try { Class.forName("javax.transaction.xa.XAResource"); } catch(Exception e){ throw new RuntimeException(e);} });
    Thread.sleep(500);
  }
}
