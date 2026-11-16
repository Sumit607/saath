package com.saath.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import com.saath.core.Json;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * Checks GitHub releases for a newer build, downloads it and installs it over this one.
 * After the first update (which Android asks you to confirm), later updates can install without a prompt.
 */
final class Updater {
  static final String LATEST = "https://github.com/Sumit607/saath/releases/latest/download/";

  volatile boolean available;
  volatile int remoteCode;
  volatile String remoteName = "", notes = "", apkUrl = "";
  volatile String state = "idle"; // idle, downloading, ready, installing, failed
  volatile double progress;
  volatile String error = "";
  private volatile long lastCheck;
  private final Context ctx;

  Updater(Context c) { ctx = c.getApplicationContext(); }

  /** Safe to call often; checks at most every 30 minutes unless forced. */
  void checkAsync(boolean force, Runnable after) {
    if (!force && System.currentTimeMillis() - lastCheck < 30 * 60_000L) return;
    lastCheck = System.currentTimeMillis();
    new Thread(() -> {
      try {
        String body = new String(get(LATEST + "version.json"), "UTF-8");
        Map<String, Object> m = Json.parseObject(body);
        remoteCode = (int) Json.num(m, "versionCode", 0);
        remoteName = Json.str(m, "versionName", "");
        notes = Json.str(m, "notes", "");
        apkUrl = Json.str(m, "apk", LATEST + "saath.apk");
        available = remoteCode > BuildConfig.VERSION_CODE;
      } catch (Exception e) {
        // offline or no release yet: try again later
      }
      if (after != null) after.run();
    }, "update-check").start();
  }

  /** Downloads the new APK, then installs it. */
  void downloadAndInstall(Runnable onChange) {
    if (!available || state.equals("downloading") || state.equals("installing")) return;
    state = "downloading";
    progress = 0;
    error = "";
    onChange.run();
    new Thread(() -> {
      try {
        File f = new File(ctx.getCacheDir(), "update.apk");
        download(apkUrl, f, onChange);
        state = "installing";
        onChange.run();
        install(f);
      } catch (Exception e) {
        state = "failed";
        error = "Couldn't download the update. Check the internet connection and try again.";
        onChange.run();
      }
    }, "update-download").start();
  }

  private void install(File apk) throws Exception {
    PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
    PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
    if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
    params.setAppPackageName(ctx.getPackageName());
    int id = pi.createSession(params);
    try (PackageInstaller.Session s = pi.openSession(id)) {
      try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("saath.apk", 0, apk.length())) {
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        s.fsync(out);
      }
      Intent i = new Intent(ctx, UpdateReceiver.class);
      int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
      PendingIntent p = PendingIntent.getBroadcast(ctx, 7, i, flags);
      s.commit(p.getIntentSender());
    }
  }

  void onInstallResult(int status, String message) {
    if (status == PackageInstaller.STATUS_SUCCESS) state = "idle";
    else if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
      state = "failed";
      error = status == PackageInstaller.STATUS_FAILURE_ABORTED ? "The update was cancelled." : "The update didn't install: " + (message == null ? "unknown error" : message);
    }
  }

  private static byte[] get(String url) throws Exception {
    HttpURLConnection c = open(url);
    try (InputStream in = c.getInputStream()) {
      java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
      return b.toByteArray();
    } finally { c.disconnect(); }
  }

  private void download(String url, File f, Runnable onChange) throws Exception {
    HttpURLConnection c = open(url);
    long len = c.getContentLengthLong();
    try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(f)) {
      byte[] buf = new byte[65536];
      long got = 0, lastTick = 0;
      int n;
      while ((n = in.read(buf)) > 0) {
        out.write(buf, 0, n);
        got += n;
        if (len > 0) progress = got / (double) len;
        if (System.currentTimeMillis() - lastTick > 300) { lastTick = System.currentTimeMillis(); onChange.run(); }
      }
    } finally { c.disconnect(); }
  }

  /** Follows GitHub's redirects to its download servers. */
  private static HttpURLConnection open(String url) throws Exception {
    for (int hops = 0; hops < 6; hops++) {
      HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
      c.setConnectTimeout(10000);
      c.setReadTimeout(20000);
      c.setInstanceFollowRedirects(false);
      c.setRequestProperty("User-Agent", "Saath-Android");
      int code = c.getResponseCode();
      if (code >= 300 && code < 400) {
        String loc = c.getHeaderField("Location");
        c.disconnect();
        if (loc == null) throw new Exception("Redirect without location");
        url = new URL(new URL(url), loc).toString();
        continue;
      }
      if (code != 200) { c.disconnect(); throw new Exception("HTTP " + code); }
      return c;
    }
    throw new Exception("Too many redirects");
  }
}
