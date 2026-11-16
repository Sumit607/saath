package com.saath.app;

import android.content.Context;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/** Android's "local-only hotspot": a Wi-Fi network this phone creates, with a name and password Android picks. */
final class Hotspot {
  volatile String state = "off"; // off, starting, on, failed
  volatile String ssid = "", pass = "", error = "";
  private WifiManager.LocalOnlyHotspotReservation reservation;

  void start(Context c, Runnable onChange) {
    state = "starting";
    error = "";
    try {
      WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
      wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
        @Override
        @SuppressWarnings("deprecation")
        public void onStarted(WifiManager.LocalOnlyHotspotReservation r) {
          reservation = r;
          String s = null, p = null;
          if (Build.VERSION.SDK_INT >= 30) {
            SoftApConfiguration cfg = r.getSoftApConfiguration();
            s = cfg.getSsid();
            p = cfg.getPassphrase();
          } else {
            WifiConfiguration wc = r.getWifiConfiguration();
            if (wc != null) { s = wc.SSID; p = wc.preSharedKey; }
          }
          ssid = unquote(s);
          pass = unquote(p);
          state = "on";
          onChange.run();
        }

        @Override
        public void onStopped() {
          state = "off";
          onChange.run();
        }

        @Override
        public void onFailed(int reason) {
          state = "failed";
          switch (reason) {
            case ERROR_INCOMPATIBLE_MODE: error = "Turn off the normal hotspot (and try turning Wi-Fi off) first."; break;
            case ERROR_TETHERING_DISALLOWED: error = "This phone doesn't allow hotspots right now."; break;
            case ERROR_NO_CHANNEL: error = "No free Wi-Fi channel. Try again in a moment."; break;
            default: error = "Android reported an error (" + reason + ").";
          }
          onChange.run();
        }
      }, new Handler(Looper.getMainLooper()));
    } catch (SecurityException e) {
      state = "failed";
      error = "Allow the Nearby devices permission for Saath, then try again.";
      onChange.run();
    } catch (Exception e) {
      state = "failed";
      error = String.valueOf(e.getMessage());
      onChange.run();
    }
  }

  void stop() {
    try { if (reservation != null) reservation.close(); } catch (Exception ignored) {}
    reservation = null;
    state = "off";
  }

  private static String unquote(String s) {
    if (s == null) return "";
    if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
    return s;
  }
}
