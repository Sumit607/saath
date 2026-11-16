package com.saath.desktop;

import com.saath.core.HostClock;
import com.saath.core.HttpServer;
import com.saath.core.Json;
import com.saath.core.Party;
import com.saath.core.Streamer;
import com.saath.core.TestBeatSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Runs the party core on a desktop JVM so the listener page can be tested in real browsers.
 * Reads commands on stdin: play, pause, tune, check, state, quit. Prints one JSON line per reply.
 * The microphone is simulated from the "chirped" reports test listeners send (see SimMic).
 */
public final class DesktopHost {
  public static void main(String[] args) throws Exception {
    Path assets = Paths.get(args.length > 0 ? args[0] : "android/app/src/main/assets");
    Party party = new Party();
    SimMic mic = new SimMic();
    party.setMic(mic);
    party.extraHandler = (d, m) -> {
      if ("chirped".equals(Json.str(m, "type", ""))) mic.addChirp(Json.num(m, "out", 0));
    };
    HttpServer server = new HttpServer(p -> {
      try { return Files.readAllBytes(assets.resolve(p)); } catch (Exception e) { return null; }
    }, party);
    int port = server.start();
    System.out.println(Json.obj("event", "started", "port", port, "token", party.token, "pin", party.pin()));
    System.out.flush();

    Streamer[] current = new Streamer[1];
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
    String line;
    while ((line = in.readLine()) != null) {
      String cmd = line.trim();
      String out;
      switch (cmd) {
        case "play": {
          if (current[0] != null) current[0].stop();
          Streamer s = new Streamer(party, new TestBeatSource(0), 800, false, 0, new Streamer.Callback() {
            @Override public void onEnded() {}
            @Override public void onError(String message) { System.out.println(Json.obj("event", "error", "msg", message)); }
          });
          current[0] = s;
          s.start();
          party.setNowPlaying("Sync test beat", "Built-in click track", true);
          out = Json.obj("event", "playing");
          break;
        }
        case "pause":
          if (current[0] != null) { current[0].stop(); current[0] = null; }
          party.newEpoch();
          party.setNowPlaying("Sync test beat", "Built-in click track", false);
          out = Json.obj("event", "paused");
          break;
        case "tune":
        case "check":
          out = Json.obj("event", cmd, "result", party.runTune(cmd.equals("tune")), "state", Json.raw(party.snapshotJson()));
          break;
        case "pin-on": party.setPinRequired(true); out = Json.obj("event", "pin", "pin", party.pin()); break;
        case "state": out = Json.obj("event", "state", "state", Json.raw(party.snapshotJson()), "now", HostClock.nowMs()); break;
        case "quit":
          server.stop();
          System.out.println(Json.obj("event", "bye"));
          System.exit(0);
          return;
        default: out = Json.obj("event", "unknown", "cmd", cmd);
      }
      System.out.println(out);
      System.out.flush();
    }
  }
}
