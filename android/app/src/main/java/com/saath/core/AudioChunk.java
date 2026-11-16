package com.saath.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A slice of PCM audio stamped with the host-clock time it must come out of every speaker.
 * Wire format (little endian, 32-byte header): "SA" ver flags | epoch u32 | seq u32 | playAt f64 (host ms)
 * | sampleRate u32 | channels u8 + 3 pad | frames u32 | int16 interleaved samples.
 */
public final class AudioChunk {
  public static final int HEADER = 32;

  public final int epoch, seq, sampleRate, channels, frames;
  public final double playAtMs;
  public final short[] pcm;
  private byte[] wire;

  public AudioChunk(int epoch, int seq, double playAtMs, int sampleRate, int channels, int frames, short[] pcm) {
    this.epoch = epoch;
    this.seq = seq;
    this.playAtMs = playAtMs;
    this.sampleRate = sampleRate;
    this.channels = channels;
    this.frames = frames;
    this.pcm = pcm;
  }

  public double durationMs() { return frames * 1000.0 / sampleRate; }

  public double endMs() { return playAtMs + durationMs(); }

  public synchronized byte[] wire() {
    if (wire == null) {
      ByteBuffer b = ByteBuffer.allocate(HEADER + frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN);
      b.put((byte) 'S').put((byte) 'A').put((byte) 1).put((byte) 0);
      b.putInt(epoch).putInt(seq).putDouble(playAtMs).putInt(sampleRate);
      b.put((byte) channels).put((byte) 0).put((byte) 0).put((byte) 0);
      b.putInt(frames);
      for (int i = 0; i < frames * channels; i++) b.putShort(pcm[i]);
      wire = b.array();
    }
    return wire;
  }

  public static AudioChunk parse(byte[] w) {
    ByteBuffer b = ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN);
    if (b.get() != 'S' || b.get() != 'A') throw new IllegalArgumentException("Not an audio chunk");
    b.get();
    b.get();
    int epoch = b.getInt(), seq = b.getInt();
    double at = b.getDouble();
    int sr = b.getInt();
    int ch = b.get();
    b.get(); b.get(); b.get();
    int frames = b.getInt();
    short[] pcm = new short[frames * ch];
    for (int i = 0; i < pcm.length; i++) pcm[i] = b.getShort();
    return new AudioChunk(epoch, seq, at, sr, ch, frames, pcm);
  }
}
