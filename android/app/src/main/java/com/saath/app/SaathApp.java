package com.saath.app;

import android.app.Application;

/** Holds the one Controller for the whole app, so the party survives the screen turning off. */
public final class SaathApp extends Application {
  private static Controller controller;

  @Override
  public void onCreate() {
    super.onCreate();
    controller = new Controller(this);
  }

  public static Controller controller() { return controller; }
}
