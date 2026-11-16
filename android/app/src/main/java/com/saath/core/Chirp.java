package com.saath.core;

/**
 * The tune-up chirp: a 70 ms sweep from 1.5 to 6.5 kHz with a Hann window.
 * The listener page generates the identical signal in JavaScript.
 * detect() finds where the chirp sits in a microphone recording by FFT cross-correlation.
 */
public final class Chirp {
  public static final double F0 = 1500, F1 = 6500, DURATION_S = 0.07, AMPLITUDE = 0.55;

  private Chirp() {}

  public static float[] make(int sampleRate) {
    int n = (int) Math.floor(sampleRate * DURATION_S);
    float[] d = new float[n];
    double T = n / (double) sampleRate;
    for (int i = 0; i < n; i++) {
      double t = i / (double) sampleRate;
      double ph = 2 * Math.PI * (F0 * t + (F1 - F0) * t * t / (2 * T));
      double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
      d[i] = (float) (Math.sin(ph) * w * AMPLITUDE);
    }
    return d;
  }

  public static final class Hit {
    /** Sample index (fractional) in the searched signal where the chirp starts. */
    public final double index;
    /** Correlation peak in standard deviations of the background; 7 or more is a confident hit. */
    public final double score;

    Hit(double index, double score) {
      this.index = index;
      this.score = score;
    }

    public boolean confident() { return score >= 7; }
  }

  /** Searches sig[from, to) for the template start. Returns the best hit (check confident()). */
  public static Hit detect(float[] sig, int from, int to, float[] tpl) {
    from = Math.max(0, from);
    to = Math.min(sig.length, to);
    int m = tpl.length;
    int len = to - from;
    if (len < m + 8) return new Hit(-1, 0);
    int n = 1;
    while (n < len + m) n <<= 1;
    double[] ar = new double[n], ai = new double[n], br = new double[n], bi = new double[n];
    for (int i = 0; i < len; i++) ar[i] = sig[from + i];
    for (int i = 0; i < m; i++) br[i] = tpl[i];
    fft(ar, ai, false);
    fft(br, bi, false);
    // correlation = IFFT(A * conj(B))
    for (int i = 0; i < n; i++) {
      double r = ar[i] * br[i] + ai[i] * bi[i];
      double im = ai[i] * br[i] - ar[i] * bi[i];
      ar[i] = r;
      ai[i] = im;
    }
    fft(ar, ai, true);
    int valid = len - m + 1;
    // magnitude of the correlation; the chirp's autocorrelation has one sharp main peak
    double[] env = new double[valid];
    for (int i = 0; i < valid; i++) env[i] = Math.abs(ar[i]);
    int best = 0;
    for (int i = 1; i < valid; i++) if (env[i] > env[best]) best = i;
    // background: spread of the correlation away from the main lobe
    double sum = 0, sq = 0;
    int cnt = 0;
    for (int i = 0; i < valid; i++) {
      if (Math.abs(i - best) < m) continue;
      sum += ar[i];
      sq += ar[i] * ar[i];
      cnt++;
    }
    double sd = cnt > 16 ? Math.sqrt(Math.max(1e-24, sq / cnt - (sum / cnt) * (sum / cnt))) : 1e-12;
    double score = env[best] / sd;
    // parabolic interpolation on the signed correlation around the peak
    double idx = best;
    if (best > 0 && best < valid - 1) {
      double y0 = ar[best - 1], y1 = ar[best], y2 = ar[best + 1];
      double den = y0 - 2 * y1 + y2;
      if (Math.abs(den) > 1e-12) idx = best + 0.5 * (y0 - y2) / den;
    }
    return new Hit(from + idx, score);
  }

  /** In-place iterative radix-2 FFT. n must be a power of two. */
  static void fft(double[] re, double[] im, boolean inverse) {
    int n = re.length;
    for (int i = 1, j = 0; i < n; i++) {
      int bit = n >> 1;
      for (; (j & bit) != 0; bit >>= 1) j ^= bit;
      j ^= bit;
      if (i < j) {
        double t = re[i]; re[i] = re[j]; re[j] = t;
        t = im[i]; im[i] = im[j]; im[j] = t;
      }
    }
    for (int len = 2; len <= n; len <<= 1) {
      double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
      double wr = Math.cos(ang), wi = Math.sin(ang);
      for (int i = 0; i < n; i += len) {
        double cr = 1, ci = 0;
        for (int k = 0; k < len / 2; k++) {
          int a = i + k, b = a + len / 2;
          double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
          re[b] = re[a] - xr; im[b] = im[a] - xi;
          re[a] += xr; im[a] += xi;
          double nr = cr * wr - ci * wi;
          ci = cr * wi + ci * wr;
          cr = nr;
        }
      }
    }
    if (inverse) for (int i = 0; i < n; i++) { re[i] /= n; im[i] /= n; }
  }
}
