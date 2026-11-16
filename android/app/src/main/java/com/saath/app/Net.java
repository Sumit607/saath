package com.saath.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Finds the addresses friends can reach this phone on. */
final class Net {
  private Net() {}

  /** IPv4 address of the Wi-Fi network this phone is connected to, or null. */
  @SuppressWarnings("deprecation")
  static String wifiIp(Context c) {
    try {
      ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
      for (Network n : cm.getAllNetworks()) {
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
        LinkProperties lp = cm.getLinkProperties(n);
        if (lp == null) continue;
        for (LinkAddress la : lp.getLinkAddresses()) {
          InetAddress a = la.getAddress();
          if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
        }
      }
    } catch (Exception ignored) {}
    return null;
  }

  /** All private IPv4 addresses on local interfaces (not mobile data or VPN), as "iface ip". */
  static List<String[]> localAddresses() {
    List<String[]> out = new ArrayList<>();
    try {
      for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!ni.isUp() || ni.isLoopback()) continue;
        String name = ni.getName().toLowerCase(Locale.ROOT);
        if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") || name.startsWith("v4-")
            || name.startsWith("dummy") || name.startsWith("ppp") || name.startsWith("ims")) continue;
        for (InetAddress a : Collections.list(ni.getInetAddresses())) {
          if (a instanceof Inet4Address && a.isSiteLocalAddress()) out.add(new String[] {name, a.getHostAddress()});
        }
      }
    } catch (Exception ignored) {}
    return out;
  }

  /** The address of this phone's own hotspot: a local address that is not the Wi-Fi client address. */
  static String hotspotIp(String wifiIp) {
    String best = null;
    int bestScore = -1;
    for (String[] a : localAddresses()) {
      if (a[1].equals(wifiIp)) continue;
      int score = 1;
      if (a[0].contains("ap") || a[0].contains("swlan") || a[0].contains("softap")) score = 4;
      else if (a[0].startsWith("wlan")) score = 3;
      else if (a[0].startsWith("rndis") || a[0].startsWith("usb")) score = 0;
      if (a[1].endsWith(".1")) score++;
      if (score > bestScore) { bestScore = score; best = a[1]; }
    }
    return best;
  }

  static List<String> allIps() {
    List<String> l = new ArrayList<>();
    for (String[] a : localAddresses()) l.add(a[1]);
    return l;
  }
}
