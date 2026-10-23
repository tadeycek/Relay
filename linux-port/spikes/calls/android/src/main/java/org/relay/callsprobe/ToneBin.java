package org.relay.callsprobe;

/** Single-frequency energy over 100 ms blocks (share of decoded energy at the expected generated tone). */
final class ToneBin {
    final double freq; double re, im, tone; int n;
    ToneBin(double freq) { this.freq = freq; }
    synchronized void add(short s, int rate) {
        double w = 2 * Math.PI * freq * n / rate;
        re += s * Math.cos(w); im -= s * Math.sin(w);
        if (++n == rate / 10) { tone += 2 * (re * re + im * im) / n; re = im = 0; n = 0; }
    }
    synchronized long energy() { return (long) tone; }
}
