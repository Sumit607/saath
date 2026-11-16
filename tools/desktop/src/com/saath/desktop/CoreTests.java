package com.saath.desktop;

import com.saath.core.AudioChunk;
import com.saath.core.Chirp;
import com.saath.core.Json;
import com.saath.core.Party;
import com.saath.core.TestBeatSource;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Plain-Java tests for the core (no JUnit needed). Exit code 1 on any failure. */
public final class CoreTests {
  private static int failed = 0, passed = 0;

  static void check(boolean ok, String what) {
    if (ok) { passed++; System.out.println("  ok   " + what); }
    else { failed++; System.out.println("  FAIL " + what); }
  }

  public static void main(String[] args) throws Exception {
    json();
    chunk();
    chirp();
    tuneAnalysis();
    testBeat();
    System.out.println(passed + " passed, " + failed + " failed");
    System.exit(failed == 0 ? 0 : 1);
  }

  @SuppressWarnings("unchecked")
  static void json() {
    String s = Json.obj("type", "hello", "n", 3.5, "i", 7, "b", true, "z", null, "q", "a\"b\\c\n");
    Map<String, Object> m = Json.parseObject(s);
    check("hello".equals(m.get("type")), "json: string field");
    check(((Double) m.get("n")) == 3.5 && ((Double) m.get("i")) == 7.0, "json: numbers");
    check(Boolean.TRUE.equals(m.get("b")) && m.containsKey("z") && m.get("z") == null, "json: bool and null");
    check("a\"b\\c\n".equals(m.get("q")), "json: escapes round-trip");
    Map<String, Object> n = Json.parseObject("{\"a\":[1,{\"b\":\"\\u00e9\"}],\"c\":-2e3}");
    check(((List<Object>) n.get("a")).size() == 2 && ((Double) n.get("c")) == -2000.0, "json: nested arrays and exponents");
    boolean threw = false;
    try { Json.parseObject("{\"a\":"); } catch (IllegalArgumentException e) { threw = true; }
    check(threw, "json: rejects truncated input");
  }

  static void chunk() {
    short[] pcm = new short[1920 * 2];
    for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (i * 7 - 9000);
    AudioChunk a = new AudioChunk(3, 41, 123456.789, 48000, 2, 1920, pcm);
    byte[] w = a.wire();
    check(w.length == AudioChunk.HEADER + 1920 * 4, "chunk: wire size");
    AudioChunk b = AudioChunk.parse(w);
    check(b.epoch == 3 && b.seq == 41 && b.playAtMs == 123456.789 && b.sampleRate == 48000 && b.channels == 2 && b.frames == 1920, "chunk: header round-trip");
    boolean same = true;
    for (int i = 0; i < pcm.length; i++) same &= pcm[i] == b.pcm[i];
    check(same, "chunk: samples round-trip");
    check(Math.abs(a.durationMs() - 40) < 1e-9, "chunk: 1920 frames at 48 kHz is 40 ms");
  }

  static void chirp() {
    int sr = 48000;
    float[] tpl = Chirp.make(sr);
    check(tpl.length == 3360, "chirp: 70 ms at 48 kHz");
    Random r = new Random(1);
    float[] sig = new float[48000];
    for (int i = 0; i < sig.length; i++) sig[i] = (float) (0.05 * r.nextGaussian() + 0.08 * Math.sin(2 * Math.PI * 3100 * i / sr));
    int at = 20111;
    for (int k = 0; k < tpl.length; k++) sig[at + k] += 0.2f * tpl[k];
    Chirp.Hit h = Chirp.detect(sig, 0, sig.length, tpl);
    check(h.confident(), "chirp: found in noise + tone (score " + String.format("%.1f", h.score) + ")");
    check(Math.abs(h.index - at) <= 1.0, "chirp: position within 1 sample (got " + String.format("%.2f", h.index) + ", want " + at + ")");
    // very quiet chirp under loud noise
    float[] sig2 = new float[48000];
    for (int i = 0; i < sig2.length; i++) sig2[i] = (float) (0.2 * r.nextGaussian());
    for (int k = 0; k < tpl.length; k++) sig2[30000 + k] += 0.15f * tpl[k];
    Chirp.Hit h2 = Chirp.detect(sig2, 0, sig2.length, tpl);
    check(h2.confident() && Math.abs(h2.index - 30000) <= 1.5, "chirp: quiet chirp found under loud noise (score " + String.format("%.1f", h2.score) + ")");
    float[] noise = new float[48000];
    for (int i = 0; i < noise.length; i++) noise[i] = (float) (0.1 * r.nextGaussian());
    check(!Chirp.detect(noise, 0, noise.length, tpl).confident(), "chirp: no false hit in plain noise");
  }

  static void tuneAnalysis() throws Exception {
    int sr = 48000;
    float[] tpl = Chirp.make(sr);
    double start = 1000.0;
    double[] at = {1450, 1970, 2490};
    double[] lat = {12.0, 54.3, 196.7};
    float[] s = new float[(int) (2.4 * sr)];
    Random r = new Random(5);
    for (int i = 0; i < s.length; i++) s[i] = (float) (0.02 * r.nextGaussian());
    for (int d = 0; d < 3; d++) {
      int i0 = (int) Math.round((at[d] + lat[d] - start) * sr / 1000.0);
      for (int k = 0; k < tpl.length; k++) s[i0 + k] += 0.3f * tpl[k];
    }
    Method an = Party.class.getDeclaredMethod("analyse", Party.Recording.class, double[].class);
    an.setAccessible(true);
    double[] e = (double[]) an.invoke(null, new Party.Recording(s, sr, start), at);
    for (int d = 0; d < 3; d++) {
      check(Math.abs(e[d] - lat[d]) < 0.1, "tune: measures " + lat[d] + " ms delay (got " + String.format("%.2f", e[d]) + ")");
    }
  }

  static void testBeat() throws Exception {
    TestBeatSource t = new TestBeatSource(0);
    short[] buf = new short[1920 * 2];
    int n = t.read(buf, 1920);
    int peak = 0;
    for (short v : buf) peak = Math.max(peak, Math.abs(v));
    check(n == 1920 && peak > 10000, "test beat: produces audio");
    TestBeatSource end = new TestBeatSource(TestBeatSource.durationMs() - 10);
    int a = end.read(buf, 1920);
    int b = end.read(buf, 1920);
    check(a == 480 && b == -1, "test beat: ends after its length");
  }
}
