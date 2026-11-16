package com.saath.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny JSON reader and writer, so the core runs on Android and a plain JVM without libraries. */
public final class Json {
  private Json() {}

  /** Wraps a string that is already JSON so it is written as-is. */
  public static final class Raw {
    final String json;
    public Raw(String json) { this.json = json; }
  }

  public static Raw raw(String json) { return new Raw(json); }

  public static String obj(Object... kv) {
    StringBuilder sb = new StringBuilder("{");
    for (int i = 0; i + 1 < kv.length; i += 2) {
      if (i > 0) sb.append(',');
      quote(sb, String.valueOf(kv[i]));
      sb.append(':');
      value(sb, kv[i + 1]);
    }
    return sb.append('}').toString();
  }

  public static String write(Object v) {
    StringBuilder sb = new StringBuilder();
    value(sb, v);
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  static void value(StringBuilder sb, Object v) {
    if (v == null) sb.append("null");
    else if (v instanceof Raw) sb.append(((Raw) v).json);
    else if (v instanceof String) quote(sb, (String) v);
    else if (v instanceof Boolean) sb.append(v.toString());
    else if (v instanceof Double || v instanceof Float) {
      double d = ((Number) v).doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
      else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
      else sb.append(d);
    } else if (v instanceof Number) sb.append(((Number) v).longValue());
    else if (v instanceof Map) {
      sb.append('{');
      boolean first = true;
      for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
        if (!first) sb.append(',');
        first = false;
        quote(sb, e.getKey());
        sb.append(':');
        value(sb, e.getValue());
      }
      sb.append('}');
    } else if (v instanceof List) {
      sb.append('[');
      boolean first = true;
      for (Object o : (List<Object>) v) {
        if (!first) sb.append(',');
        first = false;
        value(sb, o);
      }
      sb.append(']');
    } else quote(sb, v.toString());
  }

  static void quote(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"': sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        default:
          if (c < 0x20 || c == 0x2028 || c == 0x2029) sb.append(String.format("\\u%04x", (int) c));
          else sb.append(c);
      }
    }
    sb.append('"');
  }

  // ---------- reader ----------

  public static Object parse(String s) {
    Parser p = new Parser(s);
    p.ws();
    Object v = p.value();
    p.ws();
    if (p.i != s.length()) throw new IllegalArgumentException("Trailing characters in JSON");
    return v;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> parseObject(String s) {
    Object o = parse(s);
    if (!(o instanceof Map)) throw new IllegalArgumentException("Expected a JSON object");
    return (Map<String, Object>) o;
  }

  public static String str(Map<String, Object> m, String k, String def) {
    Object v = m.get(k);
    return v instanceof String ? (String) v : def;
  }

  public static double num(Map<String, Object> m, String k, double def) {
    Object v = m.get(k);
    return v instanceof Number ? ((Number) v).doubleValue() : def;
  }

  public static boolean bool(Map<String, Object> m, String k, boolean def) {
    Object v = m.get(k);
    return v instanceof Boolean ? (Boolean) v : def;
  }

  private static final class Parser {
    final String s;
    int i;

    Parser(String s) { this.s = s; }

    void ws() {
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    Object value() {
      if (i >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON");
      char c = s.charAt(i);
      if (c == '{') return object();
      if (c == '[') return array();
      if (c == '"') return string();
      if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
      if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
      if (s.startsWith("null", i)) { i += 4; return null; }
      return number();
    }

    Map<String, Object> object() {
      Map<String, Object> m = new LinkedHashMap<>();
      i++;
      ws();
      if (peek() == '}') { i++; return m; }
      while (true) {
        ws();
        String k = string();
        ws();
        expect(':');
        ws();
        m.put(k, value());
        ws();
        char c = next();
        if (c == '}') return m;
        if (c != ',') throw new IllegalArgumentException("Expected , or } in JSON");
      }
    }

    List<Object> array() {
      List<Object> l = new ArrayList<>();
      i++;
      ws();
      if (peek() == ']') { i++; return l; }
      while (true) {
        ws();
        l.add(value());
        ws();
        char c = next();
        if (c == ']') return l;
        if (c != ',') throw new IllegalArgumentException("Expected , or ] in JSON");
      }
    }

    String string() {
      expect('"');
      StringBuilder sb = new StringBuilder();
      while (true) {
        char c = next();
        if (c == '"') return sb.toString();
        if (c == '\\') {
          char e = next();
          switch (e) {
            case 'n': sb.append('\n'); break;
            case 't': sb.append('\t'); break;
            case 'r': sb.append('\r'); break;
            case 'b': sb.append('\b'); break;
            case 'f': sb.append('\f'); break;
            case 'u':
              if (i + 4 > s.length()) throw new IllegalArgumentException("Bad escape in JSON");
              sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
              i += 4;
              break;
            default: sb.append(e);
          }
        } else sb.append(c);
      }
    }

    Double number() {
      int st = i;
      while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
      if (st == i) throw new IllegalArgumentException("Unexpected character in JSON at " + st);
      return Double.parseDouble(s.substring(st, i));
    }

    char peek() { return i < s.length() ? s.charAt(i) : 0; }

    char next() {
      if (i >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON");
      return s.charAt(i++);
    }

    void expect(char c) {
      if (next() != c) throw new IllegalArgumentException("Expected " + c + " in JSON");
    }
  }
}
