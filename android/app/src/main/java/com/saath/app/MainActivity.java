package com.saath.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

/** The app screen: a WebView showing assets/host/index.html, talking to the Controller through a JS bridge. */
public final class MainActivity extends Activity implements Controller.Ui {
  private static final int REQ_PERMS = 11, REQ_PROJECTION = 12;

  private WebView web;
  private Controller c;
  private final Handler h = new Handler(Looper.getMainLooper());
  private Runnable afterPerms;
  private String permsDeniedText;
  private boolean ticking;

  private final Runnable ticker = new Runnable() {
    @Override public void run() {
      push();
      if (ticking) h.postDelayed(this, 500);
    }
  };

  @Override
  @SuppressWarnings("deprecation")
  @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
  protected void onCreate(Bundle b) {
    super.onCreate(b);
    c = SaathApp.controller();
    web = new WebView(this);
    web.setBackgroundColor(0xFF11141F);
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setMediaPlaybackRequiresUserGesture(false);
    s.setAllowFileAccess(true);
    web.setWebViewClient(new WebViewClient() {
      @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
    });
    web.addJavascriptInterface(new Bridge(), "Native");
    web.setOnApplyWindowInsetsListener((v, insets) -> {
      if (android.os.Build.VERSION.SDK_INT >= 30) {
        android.graphics.Insets in = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
        v.setPadding(in.left, in.top, in.right, in.bottom);
        return WindowInsets.CONSUMED;
      }
      v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
      return insets.consumeSystemWindowInsets();
    });
    setContentView(web);
    web.loadUrl("file:///android_asset/host/index.html");
    c.setUi(this);
    handleIntent(getIntent());
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    handleIntent(intent);
  }

  private void handleIntent(Intent i) {
    if (i == null) return;
    if (BuildConfig.DEBUG && i.getBooleanExtra("autotest", false)) h.postDelayed(c::autotest, 1500);
    if (i.getBooleanExtra("updated", false)) h.postDelayed(() -> toast("Saath was updated to version " + BuildConfig.VERSION_NAME), 1200);
  }

  @Override
  protected void onResume() {
    super.onResume();
    c.setUi(this);
    ticking = true;
    h.post(ticker);
    c.updater.checkAsync(false, () -> h.post(c::maybeAutoUpdate));
  }

  @Override
  protected void onPause() {
    ticking = false;
    h.removeCallbacks(ticker);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    c.clearUi(this);
    super.onDestroy();
  }

  @Override
  @SuppressWarnings("deprecation")
  public void onBackPressed() {
    web.evaluateJavascript("window.saathBack&&window.saathBack()", v -> {
      if (!"true".equals(v)) moveTaskToBack(true); // keep the party running
    });
  }

  // ---------- Controller.Ui ----------

  private void push() {
    if (web == null) return;
    String js = "window.onNative&&window.onNative(" + JSONObject.quote(c.stateJson()) + ")";
    web.evaluateJavascript(js, null);
  }

  @Override public void changed() { push(); }

  @Override
  public void toast(String text) {
    if (web == null) return;
    web.evaluateJavascript("window.onNativeEvent&&window.onNativeEvent(" + JSONObject.quote("{\"type\":\"toast\",\"text\":" + JSONObject.quote(text) + "}") + ")", null);
  }

  @Override
  public void songsChanged() {
    if (web != null) web.evaluateJavascript("window.onNativeEvent&&window.onNativeEvent('{\"type\":\"songs\"}')", null);
  }

  // ---------- permissions and system screens ----------

  private void withPerms(String[] perms, String deniedText, Runnable then) {
    if (Perms.granted(this, perms)) { then.run(); return; }
    afterPerms = then;
    permsDeniedText = deniedText;
    requestPermissions(perms, REQ_PERMS);
  }

  @Override
  public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
    if (req != REQ_PERMS) return;
    boolean ok = results.length > 0;
    for (int r : results) ok &= r == android.content.pm.PackageManager.PERMISSION_GRANTED;
    Runnable then = afterPerms;
    afterPerms = null;
    if (ok && then != null) then.run();
    else if (!ok && permsDeniedText != null) toast(permsDeniedText);
    push();
  }

  @Override
  protected void onActivityResult(int req, int code, Intent data) {
    super.onActivityResult(req, code, data);
    if (req == REQ_PROJECTION) {
      if (code == RESULT_OK && data != null) c.onProjectionResult(code, data);
      else toast("Sharing wasn't started. Tap Start sharing and choose Start now.");
    }
  }

  private void startParty(String mode) {
    Runnable go = () -> {
      if (Perms.notifications().length > 0 && !Perms.granted(this, Perms.notifications())) requestPermissions(Perms.notifications(), 99);
      c.startParty(mode, null);
    };
    if (mode.equals("quick")) withPerms(Perms.hotspot(), "Saath needs the Nearby devices permission to make a hotspot.", go);
    else go.run();
  }

  private void openHotspotSettings() {
    Intent i = new Intent();
    i.setClassName("com.android.settings", "com.android.settings.TetherSettings");
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    try { startActivity(i); return; } catch (Exception ignored) {}
    try { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); } catch (Exception ignored) {}
  }

  private void installUpdate() {
    if (!getPackageManager().canRequestPackageInstalls()) {
      toast("Allow Saath to install its own updates, then come back and tap Update now.");
      try {
        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
      } catch (Exception ignored) {}
      return;
    }
    c.updater.downloadAndInstall(c::maybeAutoUpdate);
  }

  // ---------- bridge for the page ----------

  public final class Bridge {
    @JavascriptInterface public String state() { return c.stateJson(); }

    @JavascriptInterface public String songs() { return c.songsJson(); }

    @JavascriptInterface public void startParty(String mode) { h.post(() -> MainActivity.this.startParty(mode)); }

    @JavascriptInterface public void endParty() { h.post(c::endParty); }

    @JavascriptInterface public void play(String id) { h.post(() -> c.play(id)); }

    @JavascriptInterface public void toggle() { h.post(c::toggle); }

    @JavascriptInterface public void next() { h.post(c::next); }

    @JavascriptInterface public void prev() { h.post(c::prev); }

    @JavascriptInterface public void seek(double ms) { h.post(() -> c.seek(ms)); }

    @JavascriptInterface public void setSource(String s) { h.post(() -> c.setSource(s)); }

    @JavascriptInterface public void startCapture() {
      h.post(() -> withPerms(Perms.mic(), "Saath needs microphone access to share audio from other apps.", () -> {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
      }));
    }

    @JavascriptInterface public void stopCapture() { h.post(c::stopCapture); }

    @JavascriptInterface public void tune() {
      h.post(() -> withPerms(Perms.mic(), "Tune-up listens with the microphone. Allow microphone access to use it.", () -> c.tune(true)));
    }

    @JavascriptInterface public void check() {
      h.post(() -> withPerms(Perms.mic(), "Sync check listens with the microphone. Allow microphone access to use it.", () -> c.tune(false)));
    }

    @JavascriptInterface public void kick(String cid) { h.post(() -> c.kick(cid)); }

    @JavascriptInterface public void setPin(boolean on) { h.post(() -> c.setPin(on)); }

    @JavascriptInterface public void setHostSpeaker(boolean on) { h.post(() -> c.setHostSpeaker(on)); }

    @JavascriptInterface public void requestMusicPermission() {
      h.post(() -> withPerms(Perms.music(), "Without this permission Saath can't list your songs.", c::reloadSongs));
    }

    @JavascriptInterface public void copy(String text) {
      h.post(() -> {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Saath party link", text));
      });
    }

    @JavascriptInterface public void shareLink() {
      h.post(() -> {
        String link = c.link();
        if (link == null) return;
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Join my Saath party: " + link);
        startActivity(Intent.createChooser(i, "Share party link"));
      });
    }

    @JavascriptInterface public void openHotspotSettings() { h.post(MainActivity.this::openHotspotSettings); }

    @JavascriptInterface public void setOwnHotspot(String ssid, String pass) { h.post(() -> c.setOwnHotspot(ssid, pass)); }

    @JavascriptInterface public void installUpdate() { h.post(MainActivity.this::installUpdate); }
  }
}
