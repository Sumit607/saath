package com.saath.core;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** One WebSocket connection (server side). Frames are sent from a dedicated writer thread. */
public final class WsConn {
  public interface Handler {
    void onText(WsConn c, String text);
    void onClose(WsConn c);
  }

  /** A pong is stamped with the host clock at the moment it is written, so queue time never skews clock sync. */
  private static final class Pong {
    final double t0, hr;
    Pong(double t0, double hr) { this.t0 = t0; this.hr = hr; }
  }

  private static final Object CLOSE = new Object();
  private static final long MAX_QUEUED_BYTES = 3_000_000; // ~15 s of PCM; beyond this the client can't keep up

  private final Socket socket;
  private final InputStream in;
  private final OutputStream out;
  private final LinkedBlockingDeque<Object> queue = new LinkedBlockingDeque<>();
  private final AtomicLong queuedBytes = new AtomicLong();
  private volatile boolean open = true;
  public final String remote;
  public volatile Object tag;

  WsConn(Socket socket, InputStream in, OutputStream out) {
    this.socket = socket;
    this.in = in;
    this.out = new BufferedOutputStream(out, 64 * 1024);
    this.remote = String.valueOf(socket.getInetAddress()).replace("/", "");
    Thread w = new Thread(this::writeLoop, "ws-writer");
    w.setDaemon(true);
    w.start();
  }

  public boolean isOpen() { return open; }

  public void sendText(String s) { enqueue(frame(0x1, s.getBytes(StandardCharsets.UTF_8)), false); }

  public void sendBinary(byte[] b) { enqueue(frame(0x2, b), false); }

  /** Clock-sync reply goes to the front of the queue. */
  public void sendPong(double t0, double receivedAtHostMs) {
    if (open) queue.offerFirst(new Pong(t0, receivedAtHostMs));
  }

  public long queuedBytes() { return queuedBytes.get(); }

  private void enqueue(byte[] f, boolean front) {
    if (!open) return;
    if (queuedBytes.addAndGet(f.length) > MAX_QUEUED_BYTES) {
      close();
      return;
    }
    if (front) queue.offerFirst(f); else queue.offerLast(f);
  }

  public void close() {
    if (!open) return;
    open = false;
    queue.offerFirst(CLOSE);
    try { socket.close(); } catch (IOException ignored) {}
  }

  static byte[] frame(int opcode, byte[] payload) {
    int n = payload.length;
    int hl = n < 126 ? 2 : (n < 65536 ? 4 : 10);
    byte[] f = new byte[hl + n];
    f[0] = (byte) (0x80 | opcode);
    if (n < 126) f[1] = (byte) n;
    else if (n < 65536) {
      f[1] = 126;
      f[2] = (byte) (n >>> 8);
      f[3] = (byte) n;
    } else {
      f[1] = 127;
      long ln = n;
      for (int i = 0; i < 8; i++) f[2 + i] = (byte) (ln >>> (56 - 8 * i));
    }
    System.arraycopy(payload, 0, f, hl, n);
    return f;
  }

  private void writeLoop() {
    try {
      while (open) {
        Object o = queue.poll(30, TimeUnit.SECONDS);
        if (o == null) {
          writeRaw(frame(0x9, new byte[0])); // keep-alive ping
          out.flush();
          continue;
        }
        if (o == CLOSE) break;
        if (o instanceof Pong) {
          Pong p = (Pong) o;
          String js = Json.obj("type", "pong", "t0", p.t0, "hr", p.hr, "hs", HostClock.nowMs());
          writeRaw(frame(0x1, js.getBytes(StandardCharsets.UTF_8)));
        } else {
          byte[] f = (byte[]) o;
          queuedBytes.addAndGet(-f.length);
          writeRaw(f);
        }
        if (queue.isEmpty()) out.flush();
      }
    } catch (Exception e) {
      // socket closed or interrupted
    } finally {
      open = false;
      try { socket.close(); } catch (IOException ignored) {}
    }
  }

  private void writeRaw(byte[] f) throws IOException { out.write(f); }

  /** Blocks reading frames until the connection closes. */
  void readLoop(Handler h) {
    ByteArrayOutputStream partial = null;
    int partialOp = 0;
    try {
      while (open) {
        int b0 = in.read();
        if (b0 < 0) break;
        int b1 = readByte();
        boolean fin = (b0 & 0x80) != 0;
        int op = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) len = (readByte() << 8) | readByte();
        else if (len == 127) {
          len = 0;
          for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
        }
        if (len > 1_000_000) throw new IOException("Frame too large");
        byte[] mask = new byte[4];
        if (masked) readFully(mask);
        byte[] payload = new byte[(int) len];
        readFully(payload);
        if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];

        if (op == 0x8) break; // close
        if (op == 0x9) { enqueue(frame(0xA, payload), true); continue; } // ping -> pong
        if (op == 0xA) continue; // pong
        if (op == 0x0) { // continuation
          if (partial == null) continue;
          partial.write(payload);
          if (fin) {
            if (partialOp == 0x1) h.onText(this, new String(partial.toByteArray(), StandardCharsets.UTF_8));
            partial = null;
          }
          continue;
        }
        if (!fin) {
          partial = new ByteArrayOutputStream();
          partial.write(payload);
          partialOp = op;
          continue;
        }
        if (op == 0x1) h.onText(this, new String(payload, StandardCharsets.UTF_8));
      }
    } catch (Exception e) {
      // connection dropped
    } finally {
      close();
      h.onClose(this);
    }
  }

  private int readByte() throws IOException {
    int b = in.read();
    if (b < 0) throw new IOException("EOF");
    return b;
  }

  private void readFully(byte[] b) throws IOException {
    int off = 0;
    while (off < b.length) {
      int n = in.read(b, off, b.length - off);
      if (n < 0) throw new IOException("EOF");
      off += n;
    }
  }
}
