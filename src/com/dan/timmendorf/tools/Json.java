package com.dan.timmendorf.tools;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ein kleiner JSON-Leser für die GeoJSON-Ebenen aus Overpass (Phase 3): Objekte werden zu {@link Map}, Felder zu
 * {@link List}, Zahlen zu {@link Double}, dazu {@link String}, {@link Boolean} und null. Ohne Bibliothek (die Werkzeuge
 * brauchen nur das JDK).
 */
public final class Json {
    private final String s;
    private int p;

    private Json(String s) { this.s = s; }

    public static Object lesen(File f) throws IOException { return parse(Files.readString(f.toPath(), StandardCharsets.UTF_8)); }

    public static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object o = j.wert();
        j.ws();
        if (j.p != j.s.length()) throw j.fehler("Text nach dem Ende");
        return o;
    }

    /** Die Features einer FeatureCollection. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> features(File f) throws IOException {
        Object o = lesen(f);
        if (!(o instanceof Map<?, ?> m) || !(m.get("features") instanceof List<?> l)) throw new IOException(f + ": keine FeatureCollection");
        return (List<Map<String, Object>>) l;
    }

    private Object wert() {
        if (p >= s.length()) throw fehler("Ende");
        char c = s.charAt(p);
        switch (c) {
            case '{': return objekt();
            case '[': return feld();
            case '"': return text();
            case 't': erwarte("true"); return Boolean.TRUE;
            case 'f': erwarte("false"); return Boolean.FALSE;
            case 'n': erwarte("null"); return null;
            default: return zahl();
        }
    }

    private Map<String, Object> objekt() {
        Map<String, Object> m = new LinkedHashMap<>();
        p++;
        ws();
        if (s.charAt(p) == '}') { p++; return m; }
        while (true) {
            ws();
            String k = text();
            ws();
            if (s.charAt(p++) != ':') throw fehler("':' erwartet");
            ws();
            m.put(k, wert());
            ws();
            char c = s.charAt(p++);
            if (c == '}') return m;
            if (c != ',') throw fehler("',' oder '}' erwartet");
        }
    }

    private List<Object> feld() {
        List<Object> l = new ArrayList<>();
        p++;
        ws();
        if (s.charAt(p) == ']') { p++; return l; }
        while (true) {
            ws();
            l.add(wert());
            ws();
            char c = s.charAt(p++);
            if (c == ']') return l;
            if (c != ',') throw fehler("',' oder ']' erwartet");
        }
    }

    private String text() {
        if (s.charAt(p) != '"') throw fehler("'\"' erwartet");
        p++;
        StringBuilder b = null;
        int start = p;
        while (true) {
            char c = s.charAt(p);
            if (c == '"') {
                String r = b == null ? s.substring(start, p) : b.append(s, start, p).toString();
                p++;
                return r;
            }
            if (c == '\\') {
                if (b == null) b = new StringBuilder();
                b.append(s, start, p);
                char e = s.charAt(p + 1);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> { b.append((char) Integer.parseInt(s.substring(p + 2, p + 6), 16)); p += 4; }
                    default -> b.append(e);
                }
                p += 2;
                start = p;
                continue;
            }
            p++;
        }
    }

    private Double zahl() {
        int a = p;
        while (p < s.length() && "+-0123456789.eE".indexOf(s.charAt(p)) >= 0) p++;
        if (a == p) throw fehler("Wert erwartet");
        return Double.valueOf(s.substring(a, p));
    }

    private void erwarte(String w) {
        if (!s.startsWith(w, p)) throw fehler(w + " erwartet");
        p += w.length();
    }

    private void ws() { while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++; }

    private IllegalArgumentException fehler(String was) { return new IllegalArgumentException("JSON bei Zeichen " + p + ": " + was); }
}
