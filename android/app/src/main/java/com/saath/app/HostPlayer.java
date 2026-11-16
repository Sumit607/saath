package com.saath.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;

import com.saath.core.AudioChunk;
import com.saath.core.Chirp;
import com.saath.core.HostClock;
import com.saath.core.Party;

import java.util.concurrent.LinkedBlockingDeque;

/**
 * Plays the party on this phone's own speaker, lined up with the listeners.
 * AudioTrack timestamps tell us when the next written sample will be heard; we pad with silence
 * or skip samples so every chunk starts at its playAt time.
 */
final class HostPlayer implements Party.HostSink {
  private final LinkedBlockingDeque<AudioChunk> queue = new LinkedBlockingDeque<>();
  private volatile boolean enabled = true, running = true, flushRequested;
  private volatile double chirpAt = -1;
  private Thread thread;

  private AudioTrack track;
  private int sr;
  private long written;
  private double baseMs = Double.NaN; // smoothed host time of frame 0
  private final AudioTimestamp ts = new AudioTimestamp();
  private AudioChunk cur;
  private int curOff;
  private short[] silence = new short[0];
  private int noTimestamp;

  void start() {
    thread = new Thread(this::loop, "host-player");
    thread.setPriority(Thread.MAX_PRIORITY);
    thread.start();
  }

  void stop() {
    running = false;
    if (thread != null) thread.interrupt();
  }

  void setEnabled(boolean on) {
    enabled = on;
    if (!on) { queue.clear(); flushRequested = true; }
  }

  @Override public boolean enabled() { return enabled; }

  @Override
  public void onChunk(AudioChunk c) {
    if (!enabled) return;
    queue.offerLast(c);
    while (queue.size() > 120) queue.pollFirst();
  }

  @Override public void flush() { queue.clear(); flushRequested = true; }

  @Override public void playChirpAt(double hostMs) { chirpAt = hostMs; }

  private void loop() {
    try {
      while (running) {
        if (!enabled) { release(); HostClock.sleepMs(50); continue; }
        if (flushRequested) { flushRequested = false; cur = null; }
        if (cur == null) { cur = queue.pollFirst(); curOff = 0; }
        int wantSr = cur != null ? cur.sampleRate : (sr == 0 ? 48000 : sr);
        if (track == null || wantSr != sr) open(wantSr);
        if (track == null) { HostClock.sleepMs(200); continue; }
        int block = sr / 100; // 10 ms
        double tNext = nextFrameMs();
        if (Double.isNaN(tNext)) {
          writeSilence(block);
          if (++noTimestamp > 300 && cur != null) cur = null; // no timing from this device; don't build up a backlog
          continue;
        }
        noTimestamp = 0;
        double ch = chirpAt;
        if (ch > 0 && ch - tNext < 12) {
          int pad = (int) Math.round((ch - tNext) * sr / 1000.0);
          if (pad > 0) writeSilence(pad);
          writeChirp();
          chirpAt = -1;
          continue;
        }
        if (cur == null) { writeSilence(block); continue; }
        double due = cur.playAtMs + curOff * 1000.0 / cur.sampleRate;
        double diff = due - tNext;
        if (diff > 2) {
          writeSilence((int) Math.min(block, Math.round(diff * sr / 1000.0)));
          continue;
        }
        if (diff < -2) {
          curOff += (int) Math.round(-diff * sr / 1000.0);
          if (curOff >= cur.frames) cur = null;
          continue;
        }
        int n = cur.frames - curOff;
        write(cur.pcm, curOff * cur.channels, n * cur.channels);
        cur = null;
      }
    } catch (Exception e) {
      // keep the party going without the host speaker
    } finally {
      release();
    }
  }

  private void open(int rate) {
    release();
    try {
      int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
      track = new AudioTrack.Builder()
          .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
          .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
          .setBufferSizeInBytes(Math.max(min * 2, rate * 4 / 20))
          .setTransferMode(AudioTrack.MODE_STREAM)
          .build();
      sr = rate;
      written = 0;
      baseMs = Double.NaN;
      silence = new short[rate / 50 * 2];
      track.play();
    } catch (Exception e) {
      track = null;
    }
  }

  private void release() {
    if (track != null) {
      try { track.pause(); track.flush(); track.release(); } catch (Exception ignored) {}
      track = null;
    }
    sr = 0;
  }

  /** Host time (ms) at which the next frame we write will leave the speaker; NaN until the device reports timing. */
  private double nextFrameMs() {
    if (track.getTimestamp(ts) && ts.nanoTime > 0) {
      double b = ts.nanoTime / 1e6 - ts.framePosition * 1000.0 / sr;
      baseMs = Double.isNaN(baseMs) || Math.abs(b - baseMs) > 20 ? b : baseMs * 0.9 + b * 0.1;
    }
    if (Double.isNaN(baseMs)) return Double.NaN;
    return baseMs + written * 1000.0 / sr;
  }

  private void writeSilence(int frames) {
    while (frames > 0) {
      int n = Math.min(frames, silence.length / 2);
      write(silence, 0, n * 2);
      frames -= n;
    }
  }

  private void writeChirp() {
    float[] c = Chirp.make(sr);
    short[] s = new short[c.length * 2];
    for (int i = 0; i < c.length; i++) {
      short v = (short) Math.round(c[i] * 1.6 * 32767);
      s[2 * i] = v;
      s[2 * i + 1] = v;
    }
    write(s, 0, s.length);
  }

  private void write(short[] data, int off, int len) {
    int done = 0;
    while (done < len && running) {
      int n = track.write(data, off + done, len - done, AudioTrack.WRITE_BLOCKING);
      if (n <= 0) { HostClock.sleepMs(5); if (n < 0) return; continue; }
      done += n;
    }
    written += done / 2;
  }
}
