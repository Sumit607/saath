package com.saath.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Foreground service that keeps the party alive while the screen is off or another app is open. */
public final class PartyService extends Service {
  static final String EXTRA_CAPTURE = "capture";
  private static final String CHANNEL = "party";
  private static volatile PartyService instance;

  static void start(Context c, boolean capture) {
    Intent i = new Intent(c, PartyService.class).putExtra(EXTRA_CAPTURE, capture);
    c.startForegroundService(i);
  }

  static void stop(Context c) { c.stopService(new Intent(c, PartyService.class)); }

  static boolean running() { return instance != null; }

  @Override
  public void onCreate() {
    super.onCreate();
    instance = this;
    NotificationManager nm = getSystemService(NotificationManager.class);
    if (nm.getNotificationChannel(CHANNEL) == null) {
      NotificationChannel ch = new NotificationChannel(CHANNEL, "Party", NotificationManager.IMPORTANCE_LOW);
      ch.setDescription("Shown while a party is running");
      nm.createNotificationChannel(ch);
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    boolean capture = intent != null && intent.getBooleanExtra(EXTRA_CAPTURE, false);
    Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Notification n = new Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle("Party is live")
        .setContentText(capture ? "Sharing this phone's audio with your friends" : "Friends can join from the QR code in Saath")
        .setContentIntent(pi)
        .setOngoing(true)
        .build();
    int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
    if (capture) {
      type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
      if (Build.VERSION.SDK_INT >= 30 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
        type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
      }
    }
    try {
      startForeground(1, n, type);
    } catch (Exception e) {
      try { startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK); } catch (Exception ignored) {}
    }
    Controller c = SaathApp.controller();
    if (c != null) c.onServiceStarted(capture);
    return START_NOT_STICKY;
  }

  @Override
  public void onDestroy() {
    instance = null;
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) { return null; }
}
