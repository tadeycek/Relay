package relay.spike;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Standard-library AES-GCM implementation of the layout that Android's org.webrtc.FrameCryptor produces
 * (verified by experiment, see E2EE-INTEROP-EVIDENCE.md):
 *
 *   audio frame on the wire = frame[0..HDR) | AES-GCM(ciphertext || 16-byte tag) | IV(12) | ivLength(1)=12 | keyIndex(1)
 *   key = PBKDF2-HMAC-SHA256(sharedMaterial, ratchetSalt, 100000 iterations) truncated/derived to 16 bytes (AES-128)
 *
 * HDR bytes stay in clear and are the GCM additional authenticated data.
 * Keys are never logged by this class.
 */
final class E2eeCrypto {
    static final int HDR = 1;          // Opus TOC byte stays unencrypted (verified on the wire)
    static final int IV = 12, TAG = 16, TRAILER = IV + 2; // IV | ivLength(=12) | keyIndex
    static final SecureRandom RANDOM = new SecureRandom();

    private E2eeCrypto() { }

    static byte[] hmac(byte[] key, byte[]... parts) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.length == 0 ? new byte[1] : key /* RFC 2104: empty key == zero-padded key */, "HmacSHA256"));
        for (byte[] p : parts) mac.update(p);
        return mac.doFinal();
    }

    static byte[] pbkdf2(byte[] password, byte[] salt, int iterations, int length) throws GeneralSecurityException {
        byte[] out = new byte[length];
        int done = 0;
        for (int block = 1; done < length; block++) {
            byte[] u = hmac(password, salt, ByteBuffer.allocate(4).putInt(block).array());
            byte[] t = u.clone();
            for (int i = 1; i < iterations; i++) {
                u = hmac(password, u);
                for (int j = 0; j < t.length; j++) t[j] ^= u[j];
            }
            int n = Math.min(t.length, length - done);
            System.arraycopy(t, 0, out, done, n);
            done += n;
        }
        return out;
    }

    static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int length) throws GeneralSecurityException {
        byte[] prk = hmac(salt, ikm);
        byte[] out = new byte[length], prev = new byte[0];
        int done = 0;
        for (int counter = 1; done < length; counter++) {
            prev = hmac(prk, prev, info, new byte[] {(byte) counter});
            int n = Math.min(prev.length, length - done);
            System.arraycopy(prev, 0, out, done, n);
            done += n;
        }
        return out;
    }

    /** Key derivation that Android's key provider applies to the shared material (PBKDF2 or HKDF, see evidence). */
    static byte[] derive(byte[] material, byte[] salt, String scheme, int length) throws GeneralSecurityException {
        if (scheme.equals("raw")) return Arrays.copyOf(material, length);
        if (scheme.startsWith("pbkdf2-")) return pbkdf2(material, salt, Integer.parseInt(scheme.substring(7)), length);
        if (scheme.equals("hkdf-empty")) return hkdf(material, salt, new byte[0], length);
        if (scheme.equals("hkdf-saltinfo")) return hkdf(material, salt, salt, length);
        throw new IllegalArgumentException("scheme");
    }

    static byte[] gcm(boolean encrypt, byte[] key, byte[] iv, byte[] aad, byte[] in, int off, int len) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        if (aad != null && aad.length > 0) c.updateAAD(aad);
        return c.doFinal(in, off, len);
    }

    /** Encrypt one audio payload. Empty payloads are returned untouched (what FrameCryptor does; see evidence). */
    static byte[] encrypt(byte[] key, int keyIndex, byte[] payload, long ssrc, long timestamp, long counter) throws GeneralSecurityException {
        if (payload.length == 0) return payload;
        int hdr = Math.min(HDR, payload.length);
        byte[] iv = ByteBuffer.allocate(IV).putInt((int) ssrc).putInt((int) timestamp).putInt((int) counter).array();
        byte[] aad = Arrays.copyOf(payload, hdr);
        byte[] ct = gcm(true, key, iv, aad, payload, hdr, payload.length - hdr);
        byte[] out = new byte[hdr + ct.length + TRAILER];
        System.arraycopy(payload, 0, out, 0, hdr);
        System.arraycopy(ct, 0, out, hdr, ct.length);
        System.arraycopy(iv, 0, out, hdr + ct.length, IV);
        out[out.length - 2] = (byte) IV;
        out[out.length - 1] = (byte) keyIndex;
        return out;
    }

    /** Returns plaintext payload or null when the frame is malformed / authentication fails / key index unknown. */
    static byte[] decrypt(byte[][] keysByIndex, byte[] frame) {
        try {
            if (frame.length < HDR + TAG + TRAILER) return null;
            int idx = frame[frame.length - 1] & 0xff;
            if (idx >= keysByIndex.length || keysByIndex[idx] == null) return null;
            if ((frame[frame.length - 2] & 0xff) != IV) return null;
            byte[] iv = Arrays.copyOfRange(frame, frame.length - TRAILER, frame.length - 2);
            byte[] aad = Arrays.copyOf(frame, HDR);
            byte[] pt = gcm(false, keysByIndex[idx], iv, aad, frame, HDR, frame.length - HDR - TRAILER);
            byte[] out = new byte[HDR + pt.length];
            System.arraycopy(frame, 0, out, 0, HDR);
            System.arraycopy(pt, 0, out, HDR, pt.length);
            return out;
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
