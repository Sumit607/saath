package com.saath.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaRecorder;

import com.saath.core.HostClock;
import com.saath.core.Party;

import java.util.ArrayList;
import java.util.List;

/** Records the microphone during tune-up, with each sample's time on the host clock. */
final class AndroidMic implements Party.Mic {
  private static final int SR = 48000;
  private final Context ctx;
  private AudioRecord rec;
  private Thread thread;
  private volatile boolean on;
  private final List<short[]> blocks = new ArrayList<>();
  private volatile double startHostMs = Double.NaN;

  AndroidMic(Context c) { ctx = c.getApplicationContext(); }

  @Override
  @SuppressLint("MissingPermission")
  public synchronized void start() throws Exception {
    blocks.clear();
    startHostMs = Double.NaN;
    AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
    boolean unprocessed = "true".equals(am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
    int src = unprocessed ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.VOICE_RECOGNITION;
    int min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
    rec = new AudioRecord(src, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 4, SR));
    if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
      rec.release();
      rec = null;
      throw new IllegalStateException("Microphone unavailable");
    }
    rec.startRecording();
    on = true;
    thread = new Thread(this::loop, "tune-mic");
    thread.setPriority(Thread.MAX_PRIORITY);
    thread.start();
  }

  private void loop() {
    AudioTimestamp ts = new AudioTimestamp();
    long frames = 0;
    double firstReadMs = Double.NaN;
    while (on) {
      short[] b = new short[960];
      int n = rec.read(b, 0, b.length);
      if (n <= 0) { if (n < 0) break; continue; }
      if (n < b.length) { short[] c = new short[n]; System.arraycopy(b, 0, c, 0, n); b = c; }
      synchronized (blocks) { blocks.add(b); }
      frames += n;
      if (Double.isNaN(firstReadMs)) firstReadMs = HostClock.nowMs() - n * 1000.0 / SR;
      if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS && ts.nanoTime > 0) {
        startHostMs = ts.nanoTime / 1e6 - ts.framePosition * 1000.0 / SR;
      }
    }
    if (Double.isNaN(startHostMs)) startHostMs = firstReadMs; // fallback: constant input latency cancels out anyway
  }

  @Override
  public synchronized Party.Recording stop() {
    on = false;
    try { if (thread != null) thread.join(1500); } catch (InterruptedException ignored) {}
    try { if (rec != null) { rec.stop(); rec.release(); } } catch (Exception ignored) {}
    rec = null;
    int total = 0;
    synchronized (blocks) { for (short[] b : blocks) total += b.length; }
    if (total == 0 || Double.isNaN(startHostMs)) return null;
    float[] s = new float[total];
    int i = 0;
    synchronized (blocks) {
      for (short[] b : blocks) for (short v : b) s[i++] = v / 32768f;
      blocks.clear();
    }
    return new Party.Recording(s, SR, startHostMs);
  }
}
