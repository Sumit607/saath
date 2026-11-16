package com.saath.core;

/**
 * "Sync test beat": a kick on every beat, a sharp click on every eighth note and a bell at the start of each bar.
 * Sharp clicks make any timing gap between speakers easy to hear.
 */
public final class TestBeatSource implements PcmSource {
  public static final int SR = 48000;
  public static final double BPM = 100;
  public static final double LENGTH_S = 180;

  private long frame;
  private final long total;

  public TestBeatSource(double startMs) {
    this.frame = (long) (startMs * SR / 1000.0);
    this.total = (long) (LENGTH_S * SR);
  }

  @Override public int sampleRate() { return SR; }

  @Override public int channels() { return 2; }

  public static double durationMs() { return LENGTH_S * 1000; }

  @Override
  public int read(short[] buf, int maxFrames) {
    if (frame >= total) return -1;
    int n = (int) Math.min(maxFrames, total - frame);
    double beat = 60.0 / BPM;
    for (int i = 0; i < n; i++) {
      double t = (frame + i) / (double) SR;
      double inBeat = t % beat;
      int beatIdx = (int) Math.floor(t / beat);
      double inEighth = t % (beat / 2);
      double s = 0;
      // kick: falling sine 150 -> 50 Hz
      if (inBeat < 0.3) {
        double f = 50 + 100 * Math.exp(-inBeat * 30);
        double ph = 2 * Math.PI * (50 * inBeat + 100 * (1 - Math.exp(-inBeat * 30)) / 30);
        s += 0.55 * Math.sin(ph) * Math.exp(-inBeat * 9) * (f > 0 ? 1 : 0);
      }
      // click: 3 kHz burst, 12 ms
      if (inEighth < 0.012) s += 0.32 * Math.sin(2 * Math.PI * 3000 * inEighth) * Math.exp(-inEighth * 300);
      // bell on beat one of every bar
      if (beatIdx % 4 == 0 && inBeat < 0.4) {
        s += 0.18 * (Math.sin(2 * Math.PI * 880 * inBeat) + 0.4 * Math.sin(2 * Math.PI * 1760 * inBeat)) * Math.exp(-inBeat * 8);
      }
      short v = (short) Math.max(-32767, Math.min(32767, Math.round(s * 32767)));
      buf[2 * i] = v;
      buf[2 * i + 1] = v;
    }
    frame += n;
    return n;
  }

  @Override public void close() {}
}
