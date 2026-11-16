package com.saath.desktop;

import com.saath.core.Chirp;
import com.saath.core.HostClock;
import com.saath.core.Party;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A simulated microphone. Test listeners report the host time at which their chirp really leaves their
 * (simulated) speaker; this builds a noisy recording with each chirp at that moment, as a real mic would hear it.
 */
final class SimMic implements Party.Mic {
  private static final int SR = 48000;
  private final List<Double> chirps = new ArrayList<>();
  private double start;
  private boolean on;

  synchronized void addChirp(double hostMs) { if (on) chirps.add(hostMs); }

  @Override
  public synchronized void start() {
    chirps.clear();
    start = HostClock.nowMs();
    on = true;
  }

  @Override
  public synchronized Party.Recording stop() {
    on = false;
    double end = HostClock.nowMs();
    int n = (int) ((end - start) * SR / 1000.0);
    float[] s = new float[Math.max(n, 1)];
    Random r = new Random(42);
    for (int i = 0; i < s.length; i++) {
      double t = i / (double) SR;
      // room noise plus some "music" in the same band as the chirp
      s[i] = (float) (0.02 * r.nextGaussian() + 0.05 * Math.sin(2 * Math.PI * 2200 * t) + 0.04 * Math.sin(2 * Math.PI * 440 * t));
    }
    float[] tpl = Chirp.make(SR);
    // 1.5 ms of mic input latency, the same for every chirp (cancels out in tune-up)
    for (double c : chirps) {
      double pos = (c + 1.5 - start) * SR / 1000.0;
      int i0 = (int) Math.floor(pos);
      double frac = pos - i0;
      for (int k = 0; k < tpl.length; k++) {
        int i = i0 + k;
        if (i + 1 >= s.length || i < 0) continue;
        s[i] += (float) (0.35 * tpl[k] * (1 - frac));
        s[i + 1] += (float) (0.35 * tpl[k] * frac);
      }
    }
    return new Party.Recording(s, SR, start);
  }
}
