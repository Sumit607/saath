package com.saath.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/** Which runtime permissions each feature needs on this Android version. */
final class Perms {
  private Perms() {}

  static String[] music() {
    return Build.VERSION.SDK_INT >= 33 ? new String[] {Manifest.permission.READ_MEDIA_AUDIO}
        : new String[] {Manifest.permission.READ_EXTERNAL_STORAGE};
  }

  static String[] hotspot() {
    return Build.VERSION.SDK_INT >= 33 ? new String[] {Manifest.permission.NEARBY_WIFI_DEVICES}
        : new String[] {Manifest.permission.ACCESS_FINE_LOCATION};
  }

  static String[] mic() { return new String[] {Manifest.permission.RECORD_AUDIO}; }

  static String[] notifications() {
    return Build.VERSION.SDK_INT >= 33 ? new String[] {Manifest.permission.POST_NOTIFICATIONS} : new String[0];
  }

  static boolean granted(Context c, String[] perms) {
    for (String p : perms) if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
    return true;
  }

  static boolean music(Context c) { return granted(c, music()); }

  static boolean mic(Context c) { return granted(c, mic()); }
}
