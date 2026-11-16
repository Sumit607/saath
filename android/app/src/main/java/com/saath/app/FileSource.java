package com.saath.app;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import com.saath.core.PcmSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Decodes a song file (MP3, M4A, FLAC, OGG, WAV...) to 16-bit stereo PCM, starting at a given position. */
final class FileSource implements PcmSource {
  private final MediaExtractor ex = new MediaExtractor();
  private final MediaCodec codec;
  private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
  private int sr, inCh;
  private boolean floatPcm;
  private boolean inputDone, outputDone;
  private short[] pending = new short[0];
  private int pendingFrames, pendingPos;
  private final long skipUntilUs;

  FileSource(Context c, Uri uri, long startMs) throws IOException {
    ex.setDataSource(c, uri, null);
    int track = -1;
    MediaFormat fmt = null;
    for (int i = 0; i < ex.getTrackCount(); i++) {
      MediaFormat f = ex.getTrackFormat(i);
      String mime = f.getString(MediaFormat.KEY_MIME);
      if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
    }
    if (track < 0) throw new IOException("This file has no audio track.");
    ex.selectTrack(track);
    sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
    inCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
    codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
    codec.configure(fmt, null, null, 0);
    codec.start();
    skipUntilUs = Math.max(0, startMs) * 1000L;
    if (skipUntilUs > 0) ex.seekTo(skipUntilUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
    // decode until the first audio comes out, so the real output sample rate is known before streaming
    int guard = 0;
    while (pendingFrames == 0 && !outputDone && guard++ < 2000) step();
  }

  @Override public int sampleRate() { return sr; }

  @Override public int channels() { return 2; }

  @Override
  public int read(short[] buf, int maxFrames) {
    int guard = 0;
    while (pendingPos >= pendingFrames && !outputDone && guard++ < 500) step();
    if (pendingPos >= pendingFrames) return outputDone ? -1 : 0;
    int n = Math.min(maxFrames, pendingFrames - pendingPos);
    System.arraycopy(pending, pendingPos * 2, buf, 0, n * 2);
    pendingPos += n;
    return n;
  }

  private void step() {
    if (!inputDone) {
      int idx = codec.dequeueInputBuffer(5000);
      if (idx >= 0) {
        ByteBuffer b = codec.getInputBuffer(idx);
        int size = b == null ? -1 : ex.readSampleData(b, 0);
        if (size < 0) {
          codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
          inputDone = true;
        } else {
          codec.queueInputBuffer(idx, 0, size, ex.getSampleTime(), 0);
          ex.advance();
        }
      }
    }
    int o = codec.dequeueOutputBuffer(info, 5000);
    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
      MediaFormat f = codec.getOutputFormat();
      if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
      if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) inCh = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
      floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
      return;
    }
    if (o < 0) return;
    ByteBuffer ob = codec.getOutputBuffer(o);
    if (ob != null && info.size > 0) {
      ob.position(info.offset);
      ob.limit(info.offset + info.size);
      ob.order(ByteOrder.nativeOrder());
      int bytesPer = floatPcm ? 4 : 2;
      int frames = info.size / (bytesPer * Math.max(1, inCh));
      int drop = 0;
      if (info.presentationTimeUs < skipUntilUs) {
        drop = (int) Math.min(frames, (skipUntilUs - info.presentationTimeUs) * sr / 1_000_000L);
      }
      int keep = frames - drop;
      if (keep > 0) {
        if (pending.length < keep * 2) pending = new short[keep * 2];
        for (int f = 0; f < frames; f++) {
          float l, r;
          if (floatPcm) {
            l = ob.getFloat();
            r = inCh > 1 ? ob.getFloat() : l;
            for (int k = 2; k < inCh; k++) ob.getFloat();
          } else {
            l = ob.getShort() / 32768f;
            r = inCh > 1 ? ob.getShort() / 32768f : l;
            for (int k = 2; k < inCh; k++) ob.getShort();
          }
          if (f < drop) continue;
          int j = (f - drop) * 2;
          pending[j] = clip(l);
          pending[j + 1] = clip(r);
        }
        pendingFrames = keep;
        pendingPos = 0;
      }
    }
    codec.releaseOutputBuffer(o, false);
    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
  }

  private static short clip(float v) {
    int s = Math.round(v * 32767f);
    return (short) Math.max(-32768, Math.min(32767, s));
  }

  @Override
  public void close() {
    try { codec.stop(); } catch (Exception ignored) {}
    try { codec.release(); } catch (Exception ignored) {}
    try { ex.release(); } catch (Exception ignored) {}
  }
}
