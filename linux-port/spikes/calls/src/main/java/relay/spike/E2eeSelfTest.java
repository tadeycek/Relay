package relay.spike;

import java.util.Arrays;

/** Offline self-test of the AES-GCM frame layout code (no WebRTC). Exit code 1 on any failure. */
public final class E2eeSelfTest {
    static int passed, failed;
    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (ok) passed++; else failed++;
    }
    public static void main(String[] args) throws Exception {
        // RFC 6070 PBKDF2-HMAC-SHA1 is not SHA-256; use RFC 7914 / well-known SHA-256 vector: P="passwd", S="salt", c=1, dkLen=64 prefix 55ac046e56e3089fec1691c22544b605
        byte[] pb = E2eeCrypto.pbkdf2("passwd".getBytes(), "salt".getBytes(), 1, 16);
        check(Arrays.equals(pb, E2eeCrypto.hex("55ac046e56e3089fec1691c22544b605")), "pbkdf2-sha256 known vector");
        // RFC 5869 test case 1
        byte[] ikm = new byte[22]; Arrays.fill(ikm, (byte) 0x0b);
        byte[] hk = E2eeCrypto.hkdf(ikm, E2eeCrypto.hex("000102030405060708090a0b0c"), E2eeCrypto.hex("f0f1f2f3f4f5f6f7f8f9"), 16);
        check(Arrays.equals(hk, E2eeCrypto.hex("3cb25f25faacd57a90434f64d0362f2a")), "hkdf-sha256 RFC5869 vector");
        for (int keyLen : new int[] {16, 32}) {
            byte[] key = new byte[keyLen]; E2eeCrypto.RANDOM.nextBytes(key);
            byte[][] keys = new byte[2][]; keys[1] = key;
            byte[] opus = new byte[60]; E2eeCrypto.RANDOM.nextBytes(opus);
            byte[] wire = E2eeCrypto.encrypt(key, 1, opus, 7, 960, 0);
            check(wire.length == opus.length + 16 + 14, "AES-" + keyLen * 8 + " overhead is tag16+iv12+ivlen1+idx1");
            check(wire[0] == opus[0], "AES-" + keyLen * 8 + " first byte stays in clear");
            check(!Arrays.equals(Arrays.copyOfRange(wire, 1, 60), Arrays.copyOfRange(opus, 1, 60)), "AES-" + keyLen * 8 + " payload is encrypted");
            check(Arrays.equals(E2eeCrypto.decrypt(keys, wire), opus), "AES-" + keyLen * 8 + " round trip");
            byte[] bad = wire.clone(); bad[10] ^= 1;
            check(E2eeCrypto.decrypt(keys, bad) == null, "AES-" + keyLen * 8 + " ciphertext bit flip rejected");
            bad = wire.clone(); bad[0] ^= 1;
            check(E2eeCrypto.decrypt(keys, bad) == null, "AES-" + keyLen * 8 + " header (AAD) bit flip rejected");
            bad = wire.clone(); bad[wire.length - 5] ^= 1; // inside the IV
            check(E2eeCrypto.decrypt(keys, bad) == null, "AES-" + keyLen * 8 + " IV bit flip rejected");
            byte[] other = key.clone(); other[0] ^= 1;
            check(E2eeCrypto.decrypt(new byte[][] {null, other}, wire) == null, "AES-" + keyLen * 8 + " wrong key rejected");
            check(E2eeCrypto.decrypt(new byte[][] {key}, wire) == null, "AES-" + keyLen * 8 + " unknown key index rejected");
            check(E2eeCrypto.decrypt(keys, opus) == null, "AES-" + keyLen * 8 + " plaintext frame rejected");
            check(E2eeCrypto.encrypt(key, 1, new byte[0], 7, 960, 0).length == 0, "empty payload passes through");
            check(E2eeCrypto.decrypt(keys, new byte[] {1, 2, 3}) == null, "tiny frame below header+tag+trailer rejected");
        }
        byte[] k = new byte[16]; byte[] w = E2eeCrypto.encrypt(k, 1, new byte[40], 1, 1, 1);
        check(w[w.length - 2] == 12 && w[w.length - 1] == 1, "trailer is ... IV(12) | ivLength=12 | keyIndex");
        System.out.println("e2ee_selftest passed=" + passed + " failed=" + failed);
        if (failed != 0) System.exit(1);
    }
}
