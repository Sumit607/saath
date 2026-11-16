package com.saath.core;

import java.util.Arrays;

/**
 * Pulls PCM from a source, stamps each 40 ms chunk with the host time it must play, and hands it to the party.
 * Files are paced to stay leadMs ahead of real time. Live capture is stamped as it arrives, plus leadMs.
 */
public final class Streamer {
  public interface Callback {
    void onEnded();

    void onError(String message);
  }

  private final Party party;
  private final PcmSource src;
  private final double leadMs;
  private final boolean live;
  private final double startPosMs;
  private final Callback cb;
  private volatile boolean running;
  private volatile double startHost;
  private volatile long framesSent;
  private volatile int sampleRate = 48000;
  private volatile double lastPeak;
  private Thread thread;

  public Streamer(Party party, PcmSource src, double leadMs, boolean live, double startPosMs, Callback cb) {
    this.party = party;
    this.src = src;
    this.leadMs = leadMs;
    this.live = live;
    this.startPosMs = startPosMs;
    this.cb = cb;
  }

  public void start() {
    running = true;
    startHost = HostClock.nowMs() + leadMs;
    thread = new Thread(this::run, live ? "stream-live" : "stream-file");
    thread.setPriority(Thread.MAX_PRIORITY);
    thread.start();
  }

  public void stop() {
    running = false;
    if (thread != null) thread.interrupt();
    if (live) src.close(); // unblocks a waiting capture read; file sources close on their own thread
  }

  public boolean isRunning() { return running; }

  /** Position in the song (ms) of the audio coming out of speakers right now. */
  public double positionMs() {
    double sent = framesSent * 1000.0 / sampleRate;
    double e = Math.max(0, Math.min(sent, HostClock.nowMs() - startHost));
    return startPosMs + e;
  }

  /** Peak level (0..1) of the most recent chunk; used to warn when capture hears only silence. */
  public double recentPeak() { return lastPeak; }

  private void run() {
    try {
      int sr = src.sampleRate(), ch = src.channels();
      sampleRate = sr;
      int chunk = Math.max(256, sr / 25);
      short[] buf = new short[chunk * ch];
      int epoch = party.newEpoch();
      int seq = 0;
      startHost = HostClock.nowMs() + leadMs;
      double lastEnd = startHost;
      while (running) {
        int n = src.read(buf, chunk);
        if (n < 0) {
          // let the tail play out, then report the end
          while (running && HostClock.nowMs() < lastEnd) HostClock.sleepMs(20);
          if (running) {
            running = false;
            cb.onEnded();
          }
          return;
        }
        if (n == 0) { HostClock.sleepMs(2); continue; }
        double playAt = startHost + framesSent * 1000.0 / sr;
        if (live) {
          double target = HostClock.nowMs() + leadMs - n * 1000.0 / sr;
          if (Math.abs(playAt - target) > 40) {
            startHost = target - framesSent * 1000.0 / sr;
            playAt = target;
          }
        } else {
          while (running && playAt - HostClock.nowMs() > leadMs + 250) HostClock.sleepMs(8);
          if (!running) return;
        }
        int peak = 0;
        for (int i = 0; i < n * ch; i++) { int a = Math.abs(buf[i]); if (a > peak) peak = a; }
        lastPeak = peak / 32768.0;
        AudioChunk c = new AudioChunk(epoch, seq++, playAt, sr, ch, n, Arrays.copyOf(buf, n * ch));
        party.broadcast(c);
        framesSent += n;
        lastEnd = c.endMs();
      }
    } catch (Exception e) {
      if (running) {
        running = false;
        cb.onError(e.getMessage() == null ? e.toString() : e.getMessage());
      }
    } finally {
      src.close();
    }
  }
}
