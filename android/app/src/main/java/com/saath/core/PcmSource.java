package com.saath.core;

/** Anything that produces 16-bit interleaved PCM: a decoded song, live capture, or the test beat. */
public interface PcmSource {
  int sampleRate();

  int channels();

  /** Fills buf with up to maxFrames frames. Returns frames read, 0 if none yet, or -1 at the end. */
  int read(short[] buf, int maxFrames) throws Exception;

  void close();
}
