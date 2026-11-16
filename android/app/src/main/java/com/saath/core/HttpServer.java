package com.saath.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Minimal HTTP/1.1 + WebSocket server. Serves the listener page and upgrades /ws to a WebSocket. */
public final class HttpServer {
  public interface Assets {
    /** Returns the file bytes for a path like "web/index.html", or null. */
    byte[] get(String path);
  }

  public interface Routes {
    /** Return a JSON body for dynamic GET paths (e.g. /info), or null to fall through to assets. */
    String dynamicJson(String path, Map<String, String> query);

    /** Called after a WebSocket handshake; return the handler for this connection (or null to refuse). */
    WsConn.Handler onWebSocket(WsConn c, Map<String, String> query);
  }

  private final Assets assets;
  private final Routes routes;
  private ServerSocket server;
  private volatile boolean running;
  private int port;

  public HttpServer(Assets assets, Routes routes) {
    this.assets = assets;
    this.routes = routes;
  }

  /** Binds the first free port from 8080 to 8099 on all interfaces. */
  public int start() throws IOException {
    IOException last = null;
    for (int p = 8080; p < 8100; p++) {
      try {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(p), 64);
        server = s;
        port = p;
        break;
      } catch (IOException e) {
        last = e;
      }
    }
    if (server == null) throw last != null ? last : new IOException("No free port");
    running = true;
    Thread t = new Thread(this::acceptLoop, "http-accept");
    t.setDaemon(true);
    t.start();
    return port;
  }

  public int port() { return port; }

  public void stop() {
    running = false;
    try { if (server != null) server.close(); } catch (IOException ignored) {}
  }

  private void acceptLoop() {
    while (running) {
      try {
        Socket s = server.accept();
        s.setTcpNoDelay(true);
        Thread t = new Thread(() -> handle(s), "http-conn");
        t.setDaemon(true);
        t.start();
      } catch (IOException e) {
        if (!running) return;
      }
    }
  }

  private void handle(Socket s) {
    try {
      s.setSoTimeout(15000);
      InputStream in = new BufferedInputStream(s.getInputStream());
      OutputStream out = s.getOutputStream();
      String head = readHead(in);
      if (head == null) { s.close(); return; }
      String[] lines = head.split("\r\n");
      String[] rl = lines[0].split(" ");
      if (rl.length < 2) { s.close(); return; }
      String method = rl[0];
      String target = rl[1];
      Map<String, String> headers = new HashMap<>();
      for (int i = 1; i < lines.length; i++) {
        int c = lines[i].indexOf(':');
        if (c > 0) headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
      }
      String path = target;
      Map<String, String> query = new HashMap<>();
      int q = target.indexOf('?');
      if (q >= 0) {
        path = target.substring(0, q);
        for (String kv : target.substring(q + 1).split("&")) {
          int e = kv.indexOf('=');
          if (e > 0) query.put(dec(kv.substring(0, e)), dec(kv.substring(e + 1)));
        }
      }

      String upgrade = headers.getOrDefault("upgrade", "");
      if ("websocket".equalsIgnoreCase(upgrade) && path.equals("/ws")) {
        String key = headers.get("sec-websocket-key");
        if (key == null) { respond(out, 400, "text/plain", "Bad Request".getBytes(StandardCharsets.UTF_8)); s.close(); return; }
        String accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
        String resp = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        s.setSoTimeout(0);
        WsConn c = new WsConn(s, in, out);
        WsConn.Handler h = routes.onWebSocket(c, query);
        if (h == null) { c.close(); return; }
        c.readLoop(h);
        return;
      }

      if (!method.equals("GET") && !method.equals("HEAD")) {
        respond(out, 405, "text/plain", "Method Not Allowed".getBytes(StandardCharsets.UTF_8));
        s.close();
        return;
      }
      String json = routes.dynamicJson(path, query);
      if (json != null) {
        respond(out, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
      } else {
        String file = null;
        if (path.equals("/") || path.startsWith("/j/")) file = "web/index.html";
        else if (path.equals("/favicon.ico")) { respond(out, 204, "image/x-icon", new byte[0]); s.close(); return; }
        else if (path.startsWith("/static/") && !path.contains("..")) file = "web/" + path.substring(8);
        byte[] body = file == null ? null : assets.get(file);
        if (body == null) respond(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
        else respond(out, 200, mime(file), method.equals("HEAD") ? new byte[0] : body);
      }
      s.close();
    } catch (Exception e) {
      try { s.close(); } catch (IOException ignored) {}
    }
  }

  private static String dec(String s) {
    try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
  }

  private static String mime(String f) {
    if (f.endsWith(".html")) return "text/html; charset=utf-8";
    if (f.endsWith(".js")) return "application/javascript; charset=utf-8";
    if (f.endsWith(".css")) return "text/css; charset=utf-8";
    if (f.endsWith(".png")) return "image/png";
    if (f.endsWith(".svg")) return "image/svg+xml";
    if (f.endsWith(".mp4")) return "video/mp4";
    return "application/octet-stream";
  }

  private static void respond(OutputStream out, int code, String type, byte[] body) throws IOException {
    String reason = code == 200 ? "OK" : code == 204 ? "No Content" : code == 404 ? "Not Found" : code == 405 ? "Method Not Allowed" : "Bad Request";
    String h = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
        + "\r\nCache-Control: no-store\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n";
    out.write(h.getBytes(StandardCharsets.US_ASCII));
    out.write(body);
    out.flush();
  }

  private static String readHead(InputStream in) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    int state = 0;
    while (b.size() < 16384) {
      int c = in.read();
      if (c < 0) return null;
      b.write(c);
      if (c == '\r' && (state == 0 || state == 2)) state++;
      else if (c == '\n' && (state == 1 || state == 3)) {
        state++;
        if (state == 4) return b.toString("ISO-8859-1");
      } else state = 0;
    }
    return null;
  }
}
