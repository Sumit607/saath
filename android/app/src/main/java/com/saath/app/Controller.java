package com.saath.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import com.saath.core.HttpServer;
import com.saath.core.Json;
import com.saath.core.Party;
import com.saath.core.PcmSource;
import com.saath.core.Streamer;
import com.saath.core.TestBeatSource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Everything the app does, independent of the screen: party, server, music, capture, tune-up, updates. */
final class Controller {
  interface Ui {
    void changed();

    void toast(String text);

    void songsChanged();
  }

  final Context ctx;
  final Updater updater;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService bg = Executors.newSingleThreadExecutor();
  private final SharedPreferences prefs;
  private final Map<String, byte[]> assetCache = new HashMap<>();
  private volatile Ui ui;

  volatile boolean running;
  volatile String mode = "";
  volatile String source = "files";
  private Party party;
  private HttpServer server;
  private int port;
  private final Hotspot hotspot = new Hotspot();
  private HostPlayer host;
  private Streamer streamer;
  private MediaProjection projection;
  private volatile boolean capturing;
  private int projCode;
  private Intent projData;

  private volatile List<Library.Song> songs = new ArrayList<>();
  private volatile Library.Song current;
  private volatile boolean playing;
  private volatile double pausedPos;
  private volatile int playGen;
  private volatile boolean tuning;
  private volatile double silentS;
  private long lastTick;

  private WifiManager.WifiLock wifiLock;
  private PowerManager.WakeLock wakeLock;

  Controller(Context c) {
    ctx = c.getApplicationContext();
    updater = new Updater(ctx);
    prefs = ctx.getSharedPreferences("saath", Context.MODE_PRIVATE);
    main.postDelayed(this::tick, 1000);
  }

  void setUi(Ui u) { ui = u; }

  void clearUi(Ui u) { if (ui == u) ui = null; }

  private void changed() {
    Ui u = ui;
    if (u != null) main.post(u::changed);
  }

  void toast(String t) {
    Ui u = ui;
    if (u != null) main.post(() -> u.toast(t));
  }

  // ---------- party ----------

  synchronized void startParty(String m, String fixedToken) {
    if (running) return;
    try {
      mode = m;
      party = new Party(fixedToken);
      host = new HostPlayer();
      host.setEnabled(source.equals("files"));
      host.start();
      party.setHostSink(host);
      party.setMic(new AndroidMic(ctx));
      party.setListener(this::changed);
      server = new HttpServer(this::asset, party);
      port = server.start();
      running = true;
      if (m.equals("quick")) hotspot.start(ctx, this::changed);
      PartyService.start(ctx, false);
      acquireLocks();
      if (songs.isEmpty()) reloadSongs();
    } catch (Exception e) {
      running = false;
      toast("Couldn't start the party: " + e.getMessage());
    }
    changed();
  }

  synchronized void endParty() {
    if (!running) return;
    running = false;
    stopStream();
    stopProjection();
    playing = false;
    current = null;
    pausedPos = 0;
    Party p = party;
    HttpServer s = server;
    p.endForEveryone();
    new Thread(() -> {
      com.saath.core.HostClock.sleepMs(500);
      s.stop();
    }).start();
    hotspot.stop();
    if (host != null) host.stop();
    releaseLocks();
    PartyService.stop(ctx);
    changed();
    updater.checkAsync(true, this::maybeAutoUpdate);
  }

  void onServiceStarted(boolean capture) {
    if (capture && projData != null) main.post(this::beginCapture);
  }

  private byte[] asset(String path) {
    synchronized (assetCache) {
      byte[] b = assetCache.get(path);
      if (b != null) return b;
      try (InputStream in = ctx.getAssets().open(path, AssetManager.ACCESS_STREAMING)) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        b = o.toByteArray();
        assetCache.put(path, b);
        return b;
      } catch (Exception e) {
        return null;
      }
    }
  }

  private String ip() {
    if (!running) return null;
    String w = Net.wifiIp(ctx);
    if (mode.equals("online")) return w;
    if (mode.equals("quick") && !"on".equals(hotspot.state)) return null;
    return Net.hotspotIp(w);
  }

  private void acquireLocks() {
    try {
      WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
      wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "saath:party");
      wifiLock.acquire();
    } catch (Exception ignored) {}
    try {
      PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
      wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "saath:party");
      wakeLock.acquire(6 * 60 * 60 * 1000L);
    } catch (Exception ignored) {}
  }

  private void releaseLocks() {
    try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
    try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
  }

  // ---------- music (v1) ----------

  void reloadSongs() {
    bg.execute(() -> {
      songs = Library.load(ctx);
      Ui u = ui;
      if (u != null) main.post(u::songsChanged);
    });
  }

  String songsJson() {
    if (songs.isEmpty()) songs = Library.load(ctx);
    List<Object> l = new ArrayList<>();
    for (Library.Song s : songs) {
      Map<String, Object> o = new java.util.LinkedHashMap<>();
      o.put("id", s.id);
      o.put("title", s.title);
      o.put("artist", s.artist);
      o.put("dur", s.durMs);
      l.add(o);
    }
    return Json.write(l);
  }

  private Library.Song find(String id) {
    for (Library.Song s : songs) if (s.id.equals(id)) return s;
    return null;
  }

  void play(String id) {
    if (!running) return;
    if (!source.equals("files")) setSource("files");
    if (songs.isEmpty()) songs = Library.load(ctx);
    Library.Song s = find(id);
    if (s != null) startSong(s, 0);
  }

  private void startSong(Library.Song s, double pos) {
    stopStream();
    current = s;
    pausedPos = pos;
    playing = true;
    final int gen = ++playGen;
    final Party p = party;
    changed();
    bg.execute(() -> {
      PcmSource src = null;
      try {
        src = s == Library.BEAT ? new TestBeatSource(pos) : new FileSource(ctx, s.uri, (long) pos);
        Streamer st = new Streamer(p, src, 900, false, pos, new Streamer.Callback() {
          @Override public void onEnded() { main.post(() -> { if (gen == playGen) next(); }); }

          @Override public void onError(String message) { toast("Playback stopped: " + message); }
        });
        synchronized (Controller.this) {
          if (gen != playGen || !running) { src.close(); return; }
          streamer = st;
        }
        st.start();
        p.setNowPlaying(s.title, s.artist, true);
      } catch (Exception e) {
        if (src != null) src.close();
        if (gen == playGen) {
          playing = false;
          toast("Couldn't play “" + s.title + "”. The file may be protected or in a format this phone can't read.");
        }
      }
      changed();
    });
  }

  private synchronized void stopStream() {
    if (streamer != null) {
      streamer.stop();
      streamer = null;
    }
  }

  void toggle() {
    if (!running || !source.equals("files")) return;
    if (playing) {
      Streamer st = streamer;
      if (st != null) pausedPos = st.positionMs();
      playGen++;
      stopStream();
      party.newEpoch();
      playing = false;
      if (current != null) party.setNowPlaying(current.title, current.artist, false);
      changed();
    } else if (current != null) {
      startSong(current, pausedPos);
    } else {
      play(Library.BEAT.id);
    }
  }

  void next() { step(1); }

  void prev() {
    Streamer st = streamer;
    if (st != null && st.positionMs() > 3000 && current != null) { startSong(current, 0); return; }
    step(-1);
  }

  private void step(int dir) {
    if (!running || songs.isEmpty()) return;
    int i = current == null ? -1 : songs.indexOf(current);
    int n = songs.size();
    Library.Song s = songs.get(((i + dir) % n + n) % n);
    startSong(s, 0);
  }

  void seek(double ms) {
    if (current == null) return;
    if (playing) startSong(current, ms);
    else { pausedPos = ms; changed(); }
  }

  void setSource(String s) {
    if (!running || s.equals(source)) return;
    playGen++;
    stopStream();
    stopProjection();
    party.newEpoch();
    playing = false;
    current = null;
    source = s;
    host.setEnabled(s.equals("files"));
    party.invalidateHost();
    party.setNowPlaying("", "", false);
    if (s.equals("files")) PartyService.start(ctx, false);
    changed();
  }

  // ---------- any app (v2) ----------

  void onProjectionResult(int code, Intent data) {
    projCode = code;
    projData = data;
    PartyService.start(ctx, true); // the service must be a media-projection service before capture starts
  }

  private void beginCapture() {
    if (!running || projData == null) return;
    try {
      MediaProjectionManager mpm = (MediaProjectionManager) ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
      MediaProjection mp = mpm.getMediaProjection(projCode, projData);
      projData = null;
      if (mp == null) throw new IllegalStateException("Android didn't allow sharing.");
      mp.registerCallback(new MediaProjection.Callback() {
        @Override public void onStop() { main.post(() -> { if (capturing) stopCapture(); }); }
      }, main);
      projection = mp;
      stopStream();
      CaptureSource cap = new CaptureSource(mp);
      Streamer st = new Streamer(party, cap, 800, true, 0, new Streamer.Callback() {
        @Override public void onEnded() {}

        @Override public void onError(String message) { toast(message); main.post(Controller.this::stopCapture); }
      });
      streamer = st;
      st.start();
      capturing = true;
      silentS = 0;
      party.setNowPlaying("Live from the host's phone", "Shared from any app", true);
    } catch (Exception e) {
      projData = null;
      stopProjection();
      toast("Couldn't start sharing: " + e.getMessage());
    }
    changed();
  }

  void stopCapture() {
    boolean was = capturing;
    stopStream();
    stopProjection();
    if (running && was) {
      party.newEpoch();
      party.setNowPlaying("", "", false);
      PartyService.start(ctx, false);
    }
    changed();
  }

  private void stopProjection() {
    capturing = false;
    MediaProjection mp = projection;
    projection = null;
    if (mp != null) try { mp.stop(); } catch (Exception ignored) {}
  }

  // ---------- tune-up ----------

  void tune(boolean apply) {
    if (!running || tuning) return;
    tuning = true;
    final Party p = party;
    new Thread(() -> {
      boolean resume = playing && source.equals("files") && current != null;
      Library.Song song = current;
      double pos = 0;
      if (resume) {
        Streamer st = streamer;
        pos = st != null ? st.positionMs() : pausedPos;
        playGen++;
        stopStream();
        p.newEpoch();
      }
      p.holdAudio = capturing;
      if (capturing) com.saath.core.HostClock.sleepMs(500); // let already-sent audio finish before the chirps
      String result;
      try {
        result = p.runTune(apply);
      } finally {
        p.holdAudio = false;
        tuning = false;
      }
      toast(result);
      if (resume && running && song != null) {
        final double at = pos;
        main.post(() -> startSong(song, at));
      }
      changed();
    }, "tune").start();
  }

  void kick(String cid) { if (running) party.kick(cid); }

  void setPin(boolean on) { if (running) party.setPinRequired(on); }

  void setHostSpeaker(boolean on) {
    if (!running) return;
    host.setEnabled(on);
    party.invalidateHost();
    changed();
  }

  void setOwnHotspot(String ssid, String pass) {
    prefs.edit().putString("ownSsid", ssid).putString("ownPass", pass).apply();
    changed();
  }

  String link() {
    String ip = ip();
    return ip == null ? null : "http://" + ip + ":" + port + "/j/" + party.token;
  }

  /** Debug builds only: start a party and play the test beat, for the automated emulator test. */
  void autotest() {
    if (running) return;
    startParty("online", "AUTOTEST");
    setHostSpeaker(false);
    play(Library.BEAT.id);
  }

  // ---------- updates ----------

  void maybeAutoUpdate() {
    if (updater.available && !running && ui != null && updater.state.equals("idle")
        && ctx.getPackageManager().canRequestPackageInstalls()) {
      updater.downloadAndInstall(this::changed);
    }
    changed();
  }

  void onUpdateResult(int status, String msg) {
    updater.onInstallResult(status, msg);
    changed();
  }

  // ---------- state for the screen ----------

  private void tick() {
    long now = System.currentTimeMillis();
    double dt = lastTick == 0 ? 1 : (now - lastTick) / 1000.0;
    lastTick = now;
    Streamer st = streamer;
    if (capturing && st != null) {
      if (st.recentPeak() < 0.003) silentS += dt; else silentS = 0;
    }
    if (running && party != null) party.prune();
    main.postDelayed(this::tick, 1000);
  }

  String stateJson() {
    boolean r = running;
    Party p = party;
    String ip = r ? ip() : null;
    Map<String, Object> now = new java.util.LinkedHashMap<>();
    if (capturing) {
      now.put("id", "app");
      now.put("title", "Sharing this phone's audio");
      now.put("sub", "From any app");
      now.put("playing", true);
    } else if (current != null) {
      Streamer st = streamer;
      now.put("id", current.id);
      now.put("title", current.title);
      now.put("sub", current.artist);
      now.put("playing", playing);
      now.put("posMs", playing && st != null ? st.positionMs() : pausedPos);
      now.put("durMs", current.durMs);
    }
    Updater u = updater;
    Object upd = u.available ? Json.raw(Json.obj("available", true, "version", u.remoteName, "notes", u.notes, "state", u.state,
        "progress", u.progress, "error", u.error)) : null;
    Streamer st = streamer;
    return Json.obj(
        "version", BuildConfig.VERSION_NAME, "versionCode", BuildConfig.VERSION_CODE, "update", upd,
        "running", r, "mode", mode, "wifiIp", Net.wifiIp(ctx),
        "hotspot", Json.raw(Json.obj("state", hotspot.state, "ssid", hotspot.ssid, "pass", hotspot.pass, "error", hotspot.error)),
        "ip", ip, "ips", r ? Net.allIps() : new ArrayList<String>(), "port", port,
        "link", r && ip != null ? "http://" + ip + ":" + port + "/j/" + p.token : null,
        "party", r && p != null ? Json.raw(p.snapshotJson()) : null,
        "source", source, "now", now,
        "capture", Json.raw(Json.obj("on", capturing, "peak", st != null && capturing ? st.recentPeak() : 0, "silentS", silentS)),
        "perm", Json.raw(Json.obj("music", Perms.music(ctx), "mic", Perms.mic(ctx))),
        "ownHotspot", Json.raw(Json.obj("ssid", prefs.getString("ownSsid", ""), "pass", prefs.getString("ownPass", ""))),
        "tuning", tuning);
  }
}
