package com.saath.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After an update installs, reopen the app so the user lands back where they were. */
public final class UpdatedReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
    Intent i = new Intent(context, MainActivity.class);
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    i.putExtra("updated", true);
    try { context.startActivity(i); } catch (Exception ignored) {}
  }
}
