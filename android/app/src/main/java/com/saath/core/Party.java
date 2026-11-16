package com.saath.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The party hub: who has joined, their speaker corrections, audio fan-out and the acoustic tune-up.
 * Platform-free: Android plugs in the host speaker (HostSink) and the microphone (Mic).
 */
public final class Party implements HttpServer.Routes {

  public interface Listener { void onChanged(); }

  /** The V60's own speaker. */
  public interface HostSink {
    boolean enabled();

    void onChunk(AudioChunk c);

    void flush();

    /** Plays the tune-up chirp so it leaves the speaker at hostMs. */
    void playChirpAt(double hostMs);
  }

  public interface Mic {
    void start() throws Exception;

    Recording stop();
  }

  public static final class Recording {
    public final float[] samples;
    public final int sampleRate;
    /** Host time (ms) at which samples[0] reached the microphone. */
    public final double startHostMs;

    public Recording(float[] samples, int sampleRate, double startHostMs) {
      this.samples = samples;
      this.sampleRate = sampleRate;
      this.startHostMs = startHostMs;
    }
  }

  public static final class Device {
    public final String cid;
    public volatile String name = "Guest", ua = "", kind = "phone";
    volatile WsConn conn;
    public volatile boolean connected, ready;
    public volatile double correctionMs;
    public volatile Double measuredMs;
    public volatile boolean tuned, tuneFailed;
    public volatile double rttMs = -1, outLatencyMs = -1;
    public volatile int late, buffered;
    public volatile double lastSeen;

    Device(String cid) { this.cid = cid; }
  }

  public static final class TuneState {
    public final String mode;
    public volatile int idx, total;
    public volatile String name = "";
    public volatile String phase = "listening";

    TuneState(String mode) { this.mode = mode; }
  }

  public static final String HOST_ID = "host";
  private static final int MAX_LISTENERS = 12;
  private static final double CHIRP_SPACING_MS = 520;

  public final String token;
  private volatile String pin;
  private volatile boolean pinRequired;
  private final Map<String, Device> devices = Collections.synchronizedMap(new LinkedHashMap<>());
  private final ArrayDeque<AudioChunk> ring = new ArrayDeque<>();
  private volatile int epoch = 0;
  private volatile HostSink hostSink;
  private volatile Mic mic;
  private volatile Listener listener;
  private volatile String title = "", subtitle = "";
  private volatile boolean playing;
  private volatile TuneState tuning;
  private volatile String lastTune = "";
  private volatile double hostMeasuredMs = Double.NaN;
  private volatile boolean hostTuned;
  private final Object tuneLock = new Object();

  public Party() { this(null); }

  /** token: the secret in the QR link; null picks a random one. */
  public Party(String fixedToken) {
    Random r = new Random();
    String a = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 6; i++) sb.append(a.charAt(r.nextInt(a.length())));
    token = fixedToken != null ? fixedToken : sb.toString();
    pin = String.format(java.util.Locale.ROOT, "%04d", 1000 + r.nextInt(9000));
  }

  /** While true, live audio is dropped (used to keep the room quiet during tune-up). */
  public volatile boolean holdAudio;

  /** The host speaker setting changed: its last measurement no longer applies. */
  public void invalidateHost() {
    hostMeasuredMs = Double.NaN;
    hostTuned = false;
    changed();
  }

  public void setHostSink(HostSink s) { hostSink = s; }

  public void setMic(Mic m) { mic = m; }

  public void setListener(Listener l) { listener = l; }

  public String pin() { return pin; }

  public boolean pinRequired() { return pinRequired; }

  public void setPinRequired(boolean on) { pinRequired = on; changed(); }

  private void changed() {
    Listener l = listener;
    if (l != null) l.onChanged();
  }

  // ---------- HTTP / WebSocket ----------

  @Override
  public String dynamicJson(String path, Map<String, String> q) {
    if (path.equals("/info")) {
      String t = q.getOrDefault("token", "");
      return Json.obj("ok", t.equals(token), "pin", pinRequired, "title", title, "playing", playing, "devices", connectedCount());
    }
    return null;
  }

  @Override
  public WsConn.Handler onWebSocket(WsConn c, Map<String, String> q) {
    if (!token.equals(q.getOrDefault("token", ""))) {
      c.sendText(Json.obj("type", "reject", "reason", "link"));
      HostClock.sleepMs(200);
      return null;
    }
    return new WsConn.Handler() {
      @Override public void onText(WsConn conn, String text) { handle(conn, text); }

      @Override public void onClose(WsConn conn) {
        Object t = conn.tag;
        if (t instanceof Device) {
          Device d = (Device) t;
          if (d.conn == conn) {
            d.connected = false;
            d.ready = false;
            d.lastSeen = HostClock.nowMs();
            changed();
          }
        }
      }
    };
  }

  private void handle(WsConn c, String text) {
    Map<String, Object> m;
    try { m = Json.parseObject(text); } catch (Exception e) { return; }
    String type = Json.str(m, "type", "");
    if (type.equals("ping")) {
      c.sendPong(Json.num(m, "t0", 0), HostClock.nowMs());
      return;
    }
    Device d = c.tag instanceof Device ? (Device) c.tag : null;
    switch (type) {
      case "hello": {
        String cid = Json.str(m, "cid", "");
        if (cid.isEmpty() || cid.length() > 64) cid = "c" + System.nanoTime();
        if (pinRequired && !pin.equals(Json.str(m, "pin", ""))) {
          c.sendText(Json.obj("type", "reject", "reason", "pin"));
          return;
        }
        Device dev;
        synchronized (devices) {
          dev = devices.get(cid);
          if (dev == null) {
            if (connectedCount() >= MAX_LISTENERS) {
              c.sendText(Json.obj("type", "reject", "reason", "full"));
              return;
            }
            dev = new Device(cid);
            devices.put(cid, dev);
          }
        }
        WsConn old = dev.conn;
        dev.conn = c;
        c.tag = dev;
        if (old != null && old != c) old.close();
        String name = Json.str(m, "name", "Guest").trim();
        dev.name = name.isEmpty() ? "Guest" : (name.length() > 24 ? name.substring(0, 24) : name);
        dev.ua = Json.str(m, "ua", "");
        dev.kind = kindOf(dev.ua);
        dev.connected = true;
        dev.ready = false;
        dev.lastSeen = HostClock.nowMs();
        c.sendText(Json.obj("type", "welcome", "cid", cid, "epoch", epoch, "corr", dev.correctionMs, "tuned", dev.tuned,
            "measured", dev.measuredMs, "title", title, "sub", subtitle, "playing", playing, "devices", connectedCount()));
        changed();
        broadcastState();
        break;
      }
      case "ready": { // clock is synced: start sending audio, beginning with what is still to play
        if (d == null) return;
        List<AudioChunk> pending = new ArrayList<>();
        double now = HostClock.nowMs();
        synchronized (ring) {
          for (AudioChunk a : ring) if (a.epoch == epoch && a.playAtMs > now + 60) pending.add(a);
        }
        for (AudioChunk a : pending) c.sendBinary(a.wire());
        d.ready = true;
        changed();
        break;
      }
      case "stat": {
        if (d == null) return;
        d.rttMs = Json.num(m, "rtt", -1);
        d.late = (int) Json.num(m, "late", 0);
        d.buffered = (int) Json.num(m, "buf", 0);
        d.outLatencyMs = Json.num(m, "outLat", -1);
        d.lastSeen = HostClock.nowMs();
        break;
      }
      case "bye": {
        if (d != null) {
          devices.remove(d.cid);
          c.close();
          changed();
          broadcastState();
        }
        break;
      }
      default: {
        java.util.function.BiConsumer<Device, Map<String, Object>> x = extraHandler;
        if (x != null && d != null) x.accept(d, m);
        break;
      }
    }
  }

  /** Test hook: receives messages the party doesn't handle itself. */
  public volatile java.util.function.BiConsumer<Device, Map<String, Object>> extraHandler;

  static String kindOf(String ua) {
    String u = ua.toLowerCase();
    if (u.contains("iphone") || u.contains("ipad")) return "iphone";
    if (u.contains("android") || u.contains("mobile")) return "phone";
    return "laptop";
  }

  public int connectedCount() {
    int n = 0;
    synchronized (devices) { for (Device d : devices.values()) if (d.connected) n++; }
    return n;
  }

  public List<Device> deviceList() {
    synchronized (devices) { return new ArrayList<>(devices.values()); }
  }

  public void kick(String cid) {
    Device d = devices.remove(cid);
    if (d != null && d.conn != null) {
      d.conn.sendText(Json.obj("type", "reject", "reason", "removed"));
      d.conn.close();
    }
    changed();
    broadcastState();
  }

  /** Forget devices that left more than 10 minutes ago. */
  public void prune() {
    double now = HostClock.nowMs();
    synchronized (devices) {
      devices.values().removeIf(d -> !d.connected && now - d.lastSeen > 600_000);
    }
  }

  // ---------- audio fan-out ----------

  public int newEpoch() {
    int e;
    synchronized (ring) {
      e = ++epoch;
      ring.clear();
    }
    String msg = Json.obj("type", "flush", "epoch", e);
    for (Device d : deviceList()) if (d.connected && d.conn != null) d.conn.sendText(msg);
    HostSink hs = hostSink;
    if (hs != null) hs.flush();
    return e;
  }

  public void broadcast(AudioChunk c) {
    if (c.epoch != epoch || holdAudio) return;
    double now = HostClock.nowMs();
    synchronized (ring) {
      ring.addLast(c);
      while (!ring.isEmpty() && (ring.peekFirst().endMs() < now - 100 || ring.size() > 80)) ring.pollFirst();
    }
    byte[] w = c.wire();
    for (Device d : deviceList()) {
      WsConn conn = d.conn;
      if (d.connected && d.ready && conn != null) conn.sendBinary(w);
    }
    HostSink hs = hostSink;
    if (hs != null && hs.enabled()) hs.onChunk(c);
  }

  public void setNowPlaying(String title, String subtitle, boolean playing) {
    this.title = title == null ? "" : title;
    this.subtitle = subtitle == null ? "" : subtitle;
    this.playing = playing;
    broadcastState();
    changed();
  }

  public void broadcastState() {
    String msg = Json.obj("type", "state", "title", title, "sub", subtitle, "playing", playing, "devices", connectedCount() + 1);
    for (Device d : deviceList()) if (d.connected && d.conn != null) d.conn.sendText(msg);
  }

  public void endForEveryone() {
    String msg = Json.obj("type", "ended");
    for (Device d : deviceList()) {
      if (d.conn != null) {
        d.conn.sendText(msg);
        WsConn c = d.conn;
        new Thread(() -> { HostClock.sleepMs(300); c.close(); }).start();
      }
    }
  }

  // ---------- tune-up ----------

  public TuneState tuning() { return tuning; }

  public String lastTune() { return lastTune; }

  public double hostMeasuredMs() { return hostMeasuredMs; }

  public boolean hostTuned() { return hostTuned; }

  /** Marks every device as needing a new tune-up (e.g. after the host speaker setting changes). */
  public void invalidateTune() {
    for (Device d : deviceList()) d.measuredMs = null;
    hostMeasuredMs = Double.NaN;
    changed();
  }

  /**
   * Runs on a background thread. With apply=true: measure, correct every device, then measure again to verify.
   * With apply=false: measure only (Sync check). Returns a one-line summary.
   */
  public String runTune(boolean apply) {
    synchronized (tuneLock) {
      Mic m = mic;
      if (m == null) return lastTune = "The microphone isn't available.";
      List<Object> targets = new ArrayList<>();
      HostSink hs = hostSink;
      if (hs != null && hs.enabled()) targets.add(HOST_ID);
      for (Device d : deviceList()) if (d.connected && d.ready) targets.add(d);
      if (targets.isEmpty() || (targets.size() == 1 && targets.get(0) == HOST_ID)) {
        return lastTune = "No listeners to tune yet.";
      }
      TuneState ts = new TuneState(apply ? "tune" : "check");
      ts.total = targets.size() * (apply ? 2 : 1);
      tuning = ts;
      changed();
      try {
        double[] errs = measureAll(m, targets, ts, 0);
        if (errs == null) return lastTune = "Couldn't use the microphone. Allow microphone access and try again.";
        double ref = reference(targets, errs);
        if (Double.isNaN(ref)) {
          markResult(targets, errs, Double.NaN, false);
          return lastTune = "Couldn't hear any device. Bring them within 1 m of this phone and turn the volume up.";
        }
        if (apply) {
          for (int i = 0; i < targets.size(); i++) {
            Object t = targets.get(i);
            if (t instanceof Device && !Double.isNaN(errs[i])) {
              Device d = (Device) t;
              d.correctionMs += errs[i] - ref;
              d.tuned = true;
              d.tuneFailed = false;
              sendTune(d);
            }
          }
          HostClock.sleepMs(400); // let listeners reschedule
          ts.phase = "verifying";
          errs = measureAll(m, targets, ts, targets.size());
          if (errs == null) return lastTune = "Tuned, but the check after it failed.";
          ref = reference(targets, errs);
        }
        markResult(targets, errs, ref, apply);
        int heard = 0, total = 0;
        double worst = 0;
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int i = 0; i < targets.size(); i++) {
          total++;
          if (!Double.isNaN(errs[i])) {
            heard++;
            double r = errs[i] - (Double.isNaN(ref) ? 0 : ref);
            worst = Math.max(worst, Math.abs(r));
            lo = Math.min(lo, r);
            hi = Math.max(hi, r);
          }
        }
        String spread = heard > 1 ? String.format(java.util.Locale.ROOT, "%.1f", hi - lo) : "0";
        lastTune = (heard < total ? (total - heard) + " device(s) couldn't be heard. " : "")
            + "Gap between speakers: " + spread + " ms.";
        return lastTune;
      } finally {
        tuning = null;
        changed();
      }
    }
  }

  private void sendTune(Device d) {
    if (d.conn != null) d.conn.sendText(Json.obj("type", "tune", "corr", d.correctionMs, "tuned", d.tuned, "measured", d.measuredMs));
  }

  private void markResult(List<Object> targets, double[] errs, double ref, boolean apply) {
    for (int i = 0; i < targets.size(); i++) {
      Object t = targets.get(i);
      double r = Double.isNaN(errs[i]) || Double.isNaN(ref) ? Double.NaN : errs[i] - ref;
      if (t == HOST_ID) {
        hostMeasuredMs = r;
        if (apply && !Double.isNaN(r)) hostTuned = true;
      } else {
        Device d = (Device) t;
        d.measuredMs = Double.isNaN(r) ? null : r;
        d.tuneFailed = Double.isNaN(r);
        sendTune(d);
      }
    }
  }

  /** The host speaker is the reference when it plays; otherwise the median listener. */
  private static double reference(List<Object> targets, double[] errs) {
    for (int i = 0; i < targets.size(); i++) if (targets.get(i) == HOST_ID && !Double.isNaN(errs[i])) return errs[i];
    List<Double> v = new ArrayList<>();
    for (double e : errs) if (!Double.isNaN(e)) v.add(e);
    if (v.isEmpty()) return Double.NaN;
    Collections.sort(v);
    return v.get(v.size() / 2);
  }

  /** Each target chirps in its own slot; returns arrival minus scheduled time (ms), NaN where not heard. */
  private double[] measureAll(Mic m, List<Object> targets, TuneState ts, int idxBase) {
    try { m.start(); } catch (Exception e) { return null; }
    double[] at = new double[targets.size()];
    try {
      HostClock.sleepMs(300);
      double t0 = HostClock.nowMs() + 450;
      for (int i = 0; i < targets.size(); i++) {
        at[i] = t0 + i * CHIRP_SPACING_MS;
        Object t = targets.get(i);
        if (t == HOST_ID) {
          HostSink hs = hostSink;
          if (hs != null) hs.playChirpAt(at[i]);
        } else {
          Device d = (Device) t;
          if (d.conn != null) d.conn.sendText(Json.obj("type", "chirp", "at", at[i]));
        }
      }
      for (int i = 0; i < targets.size(); i++) {
        while (HostClock.nowMs() < at[i]) HostClock.sleepMs(10);
        ts.idx = idxBase + i;
        Object t = targets.get(i);
        ts.name = t == HOST_ID ? "This phone" : ((Device) t).name;
        changed();
      }
      while (HostClock.nowMs() < at[targets.size() - 1] + 650) HostClock.sleepMs(10);
    } finally {
      Recording rec = m.stop();
      if (rec == null) return null;
      lastRecording = rec;
      lastChirpTimes = at;
      return analyse(rec, at);
    }
  }

  volatile Recording lastRecording;
  volatile double[] lastChirpTimes;

  /** Finds each chirp in the recording. Package-visible for tests. */
  static double[] analyse(Recording rec, double[] at) {
    float[] tpl = Chirp.make(rec.sampleRate);
    double[] out = new double[at.length];
    for (int i = 0; i < at.length; i++) {
      int from = (int) ((at[i] - 60 - rec.startHostMs) * rec.sampleRate / 1000.0);
      int to = (int) ((at[i] + 460 + Chirp.DURATION_S * 1000 - rec.startHostMs) * rec.sampleRate / 1000.0);
      Chirp.Hit h = Chirp.detect(rec.samples, from, to, tpl);
      out[i] = h.confident() ? rec.startHostMs + h.index * 1000.0 / rec.sampleRate - at[i] : Double.NaN;
    }
    return out;
  }

  // ---------- state for the host app ----------

  public String snapshotJson() {
    List<Object> list = new ArrayList<>();
    for (Device d : deviceList()) {
      Map<String, Object> o = new LinkedHashMap<>();
      o.put("cid", d.cid);
      o.put("name", d.name);
      o.put("kind", d.kind);
      o.put("connected", d.connected);
      o.put("ready", d.ready);
      o.put("tuned", d.tuned);
      o.put("failed", d.tuneFailed);
      o.put("measured", d.measuredMs);
      o.put("corr", d.correctionMs);
      o.put("rtt", d.rttMs);
      o.put("late", d.late);
      list.add(o);
    }
    TuneState t = tuning;
    Object tj = t == null ? null : Json.raw(Json.obj("mode", t.mode, "idx", t.idx, "total", t.total, "name", t.name, "phase", t.phase));
    HostSink hs = hostSink;
    return Json.obj("token", token, "pin", pin, "pinRequired", pinRequired, "devices", list,
        "hostSpeaker", hs != null && hs.enabled(), "hostMeasured", Double.isNaN(hostMeasuredMs) ? null : hostMeasuredMs,
        "hostTuned", hostTuned, "tuning", tj, "lastTune", lastTune, "title", title, "sub", subtitle, "playing", playing);
  }
}
