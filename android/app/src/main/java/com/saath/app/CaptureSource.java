package com.saath.app;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;

import com.saath.core.PcmSource;

/** Copies what other apps play (Spotify, YouTube Music...) using Android's playback capture. */
final class CaptureSource implements PcmSource {
  static final int SR = 48000;
  private final AudioRecord rec;
  private volatile boolean closed;

  @SuppressLint("MissingPermission")
  CaptureSource(MediaProjection mp) {
    AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(mp)
        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
        .addMatchingUsage(AudioAttributes.USAGE_GAME)
        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
        .build();
    AudioFormat f = new AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(SR)
        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
        .build();
    int min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
    rec = new AudioRecord.Builder()
        .setAudioFormat(f)
        .setBufferSizeInBytes(Math.max(min * 4, SR * 4 / 4))
        .setAudioPlaybackCaptureConfig(cfg)
        .build();
    if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Couldn't start sharing audio on this phone.");
    rec.startRecording();
  }

  @Override public int sampleRate() { return SR; }

  @Override public int channels() { return 2; }

  @Override
  public int read(short[] buf, int maxFrames) throws Exception {
    if (closed) return -1;
    int n = rec.read(buf, 0, maxFrames * 2, AudioRecord.READ_BLOCKING);
    if (n < 0) {
      if (closed) return -1;
      throw new IllegalStateException("Audio sharing stopped (error " + n + ").");
    }
    return n / 2;
  }

  @Override
  public void close() {
    closed = true;
    try { rec.stop(); } catch (Exception ignored) {}
    try { rec.release(); } catch (Exception ignored) {}
  }
}
