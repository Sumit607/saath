package com.saath.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

/** Receives the result of an update install; shows Android's confirm screen when it is needed. */
public final class UpdateReceiver extends BroadcastReceiver {
  @Override
  @SuppressWarnings("deprecation")
  public void onReceive(Context context, Intent intent) {
    int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
    String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
      Intent confirm = Build.VERSION.SDK_INT >= 33
          ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
          : intent.getParcelableExtra(Intent.EXTRA_INTENT);
      if (confirm != null) {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { context.startActivity(confirm); } catch (Exception ignored) {}
      }
    }
    Controller c = SaathApp.controller();
    if (c != null) c.onUpdateResult(status, msg);
  }
}
