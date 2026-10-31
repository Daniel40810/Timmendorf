package com.dan.timmendorf.data;

import com.dan.store.Db;
import com.dan.store.Protokoll;
import com.dan.store.Zahl;
import com.dan.timmendorf.scene.Regie;

import java.io.StringReader;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Speichern im Schema TIMMENDORF, Teil 2 (Phase 17, F37 A). Eine Verbindung, alle Zugriffe synchronisiert (der Faden der
 * Daten, die Chronik und die Oberfläche teilen sie). Geometrien in UTM 32N (SRID 25832): Punkte als
 * {@code SDO_POINT_TYPE}, Linien und Flächen als WKT über {@code SDO_GEOMETRY(clob, 25832)}; gelesen mit
 * {@code SDO_UTIL.TO_WKTGEOMETRY}. Szenenzeit als ms seit 1970 und als TIMESTAMP WITH TIME ZONE in Europe/Berlin.
 * Mitgelieferte Zeilen werden nie überschrieben.
 */
public final class DbSpeicher implements Speicher {
    private final Db.Konfig konfig;
    private Connection c;
    private final String wo;

    public DbSpeicher(Db.Konfig k) throws Fehler {
        this.konfig = k;
        try {
            c = Db.oeffnen(k);
            wo = "Datenbank " + Db.einWert(c, "select user || ' auf ' || sys_context('USERENV','CON_NAME') from dual");
        } catch (SQLException e) {
            throw new Fehler("Keine Verbindung zu " + k.kurz() + ": " + Db.erste(e), e);
        }
    }

    /** Für Prüfungen und den Einrichter: die offene Verbindung. */
    public synchronized Connection verbindung() throws Fehler {
        try {
            if (c == null || c.isClosed() || !c.isValid(3)) c = Db.oeffnen(konfig);
            return c;
        } catch (SQLException e) {
            throw new Fehler("Verbindung verloren: " + Db.erste(e), e);
        }
    }

    @Override public String wo() { return wo; }
    @Override public boolean datenbank() { return true; }

    private interface Arbeit<T> { T tun(Connection c) throws SQLException; }

    private synchronized <T> T mit(String was, Arbeit<T> a) throws Fehler {
        Connection cc = verbindung();
        try {
            return a.tun(cc);
        } catch (SQLException e) {
            throw new Fehler(was + ": " + Db.erste(e), e);
        }
    }

    /** In einer Transaktion: alles oder nichts. */
    private synchronized <T> T zusammen(String was, Arbeit<T> a) throws Fehler {
        Connection cc = verbindung();
        try {
            cc.setAutoCommit(false);
            T t = a.tun(cc);
            cc.commit();
            return t;
        } catch (SQLException e) {
            try { cc.rollback(); } catch (SQLException e2) { /* weiter */ }
            throw new Fehler(was + ": " + Db.erste(e), e);
        } finally {
            try { cc.setAutoCommit(true); } catch (SQLException e) { /* weiter */ }
        }
    }

    // ------------------------------------------------------------------ Hilfen

    static int zahl(Connection c, String sql, Object... a) throws SQLException {
        String v = Db.einWert(c, sql, a);
        return v == null ? 0 : Integer.parseInt(v);
    }

    private static long neueId(Connection c, String seq) throws SQLException { return Long.parseLong(Db.einWert(c, "SELECT TO_CHAR(" + seq + ".CURRVAL) FROM DUAL")); }

    static void setD(PreparedStatement st, int i, Double v) throws SQLException {
        if (v == null || Double.isNaN(v) || Double.isInfinite(v)) st.setNull(i, Types.NUMERIC); else st.setDouble(i, v);
    }

    static void setS(PreparedStatement st, int i, String v) throws SQLException {
        if (v == null || v.isEmpty()) st.setNull(i, Types.VARCHAR);
        else if (v.length() > 3900) st.setCharacterStream(i, new StringReader(v), v.length());
        else st.setString(i, v);
    }

    private static void setClob(PreparedStatement st, int i, String v) throws SQLException {
        if (v == null) st.setNull(i, Types.CLOB); else st.setCharacterStream(i, new StringReader(v), v.length());
    }

    private static void setL(PreparedStatement st, int i, Long v) throws SQLException {
        if (v == null || v < 0) st.setNull(i, Types.NUMERIC); else st.setLong(i, v);
    }

    /** Platzhalter für eine Szenenzeit als TIMESTAMP WITH TIME ZONE (zwei Platzhalter: ms, ms). */
    static final String TSTZ = "CASE WHEN ? IS NULL THEN NULL ELSE FROM_TZ(TIMESTAMP '1970-01-01 00:00:00' + NUMTODSINTERVAL(? / 1000, 'SECOND'), 'UTC') AT TIME ZONE 'Europe/Berlin' END";

    private static int setTz(PreparedStatement st, int i, Long ms) throws SQLException {
        setL(st, i, ms);
        setL(st, i + 1, ms);
        return i + 2;
    }

    private static Long ms(ResultSet r, String col) throws SQLException { long v = r.getLong(col); return r.wasNull() ? null : v; }
    private static Double dbl(ResultSet r, String col) throws SQLException { double v = r.getDouble(col); return r.wasNull() ? null : v; }
    private static double d(ResultSet r, String col) throws SQLException { double v = r.getDouble(col); return r.wasNull() ? Double.NaN : v; }
    private static Integer integer(ResultSet r, String col) throws SQLException { int v = r.getInt(col); return r.wasNull() ? null : v; }

    private static String clob(ResultSet r, String col) throws SQLException {
        Clob cl = r.getClob(col);
        return cl == null ? null : cl.getSubString(1, (int) cl.length());
    }

    private static Daten.Geom geom(ResultSet r, String col) throws SQLException {
        String w = clob(r, col);
        return w == null ? null : Daten.Geom.ausWkt(w);
    }

    /** SQL für eine Geometrie: Punkt als SDO_POINT_TYPE mit zwei Platzhaltern, sonst WKT mit einem; null: NULL. */
    static String geomSql(Daten.Geom g) {
        if (g == null) return "NULL";
        if (g.art().equals("PUNKT")) return "MDSYS.SDO_GEOMETRY(2001, 25832, MDSYS.SDO_POINT_TYPE(?, ?, NULL), NULL, NULL)";
        return "MDSYS.SDO_GEOMETRY(?, 25832)";
    }

    private static int setGeom(PreparedStatement st, int i, Daten.Geom g) throws SQLException {
        if (g == null) return i;
        if (g.art().equals("PUNKT")) {
            double[] u = Daten.utm(g.p()[0][0], g.p()[0][1]);
            st.setDouble(i, Math.round(u[0] * 100) / 100.0);
            st.setDouble(i + 1, Math.round(u[1] * 100) / 100.0);
            return i + 2;
        }
        setClob(st, i, g.wkt());
        return i + 1;
    }

    static Double r1(double v) { return Double.isNaN(v) ? null : Math.round(v * 10) / 10.0; }
    static Double r2(double v) { return Double.isNaN(v) ? null : Math.round(v * 100) / 100.0; }
    static Double r3(double v) { return Double.isNaN(v) ? null : Math.round(v * 1000) / 1000.0; }

    private static boolean mitgeliefert(Connection c, String tabelle, String name) throws SQLException {
        return zahl(c, "SELECT COUNT(*) FROM " + tabelle + " WHERE NAME = ? AND HERKUNFT = 'MITGELIEFERT'", name) > 0;
    }

    private static String kurz(String s, int n) { return s == null || s.length() <= n ? s : s.substring(0, n - 1) + "…"; }

    // ------------------------------------------------------------------ Stand

    @Override public Daten.Stand stand() throws Fehler {
        return mit("Stand", c -> new Daten.Stand(zahl(c, "SELECT COUNT(*) FROM TD_BEFUND"), zahl(c, "SELECT COUNT(*) FROM TD_REGIEBUCH"),
                zahl(c, "SELECT COUNT(*) FROM TD_REGIELAUF"), zahl(c, "SELECT COUNT(*) FROM TD_ANSICHT"), zahl(c, "SELECT COUNT(*) FROM TD_ANSICHT WHERE ART = 'GEMERKT'"),
                zahl(c, "SELECT COUNT(*) FROM TD_FAHRT"), zahl(c, "SELECT COUNT(*) FROM TD_PAKET_PRESET"), zahl(c, "SELECT COUNT(*) FROM TD_PAKET_PRESET WHERE HERKUNFT = 'EIGEN'"),
                zahl(c, "SELECT COUNT(*) FROM TD_BOOT_KLASSE"), zahl(c, "SELECT COUNT(*) FROM TD_BOOT"), zahl(c, "SELECT COUNT(*) FROM TD_REGATTA_ERGEBNIS"),
                zahl(c, "SELECT COUNT(*) FROM TD_ZUENDPLAN"), zahl(c, "SELECT COUNT(*) FROM TD_STURMFLUT"), zahl(c, "SELECT COUNT(*) FROM TD_ABBRUCH"),
                zahl(c, "SELECT COUNT(*) FROM TD_KLIFFKANTE"), zahl(c, "SELECT COUNT(*) FROM TD_LEUCHTFEUER"), zahl(c, "SELECT COUNT(*) FROM TD_LINIE"),
                zahl(c, "SELECT COUNT(*) FROM TD_SITZUNG"), letzte(c, -1)));
    }

    // ------------------------------------------------------------------ Befunde

    @Override public List<Daten.Befund> befunde() throws Fehler {
        return mit("Befunde", c -> {
            Map<Long, List<String>> q = new LinkedHashMap<>();
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT BEFUND_ID, QUELLE FROM TD_BEFUND_QUELLE ORDER BY BEFUND_QUELLE_ID")) {
                while (r.next()) q.computeIfAbsent(r.getLong(1), k -> new ArrayList<>()).add(r.getString(2));
            }
            List<Daten.Befund> l = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT BEFUND_ID, NR, PHASE, THEMA, TEXT, ART, STATUS, BEHOBEN_IN, COMMIT_ID FROM TD_BEFUND ORDER BY NR")) {
                while (r.next())
                    l.add(new Daten.Befund(r.getInt(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getString(8), r.getString(9),
                            q.getOrDefault(r.getLong(1), List.of())));
            }
            return l;
        });
    }

    // ------------------------------------------------------------------ Regiebücher und Läufe

    @Override public List<Daten.Regiebuch> regiebuecher() throws Fehler {
        return mit("Regiebücher", c -> {
            List<Daten.Regiebuch> l = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT NAME, TEXT, TITEL, DATEI, HERKUNFT FROM TD_REGIEBUCH "
                    + "ORDER BY CASE HERKUNFT WHEN 'MITGELIEFERT' THEN 0 ELSE 1 END, REGIEBUCH_ID")) {
                while (r.next()) l.add(new Daten.Regiebuch(r.getString(1), clob(r, "TEXT"), r.getString(3), r.getString(4), r.getString(5)));
            }
            return l;
        });
    }

    @Override public void speichern(Daten.Regiebuch rb) throws Fehler {
        Regie.Book b;
        try { b = Regie.parse(rb.text()); } catch (RuntimeException e) { throw new Fehler("Regiebuch nicht lesbar: " + e.getMessage(), e); }
        if (!b.name().equals(rb.name())) throw new Fehler("Der Name im Text („" + b.name() + "“) passt nicht zu „" + rb.name() + "“");
        zusammen("Regiebuch speichern", c -> {
            if (mitgeliefert(c, "TD_REGIEBUCH", b.name())) throw new SQLException("„" + b.name() + "“ ist mitgeliefert; bitte unter einem eigenen Namen speichern");
            try (PreparedStatement st = c.prepareStatement("DELETE FROM TD_REGIEBUCH WHERE NAME = ? AND HERKUNFT = 'EIGEN'")) { st.setString(1, b.name()); st.executeUpdate(); }
            try (PreparedStatement st = c.prepareStatement("INSERT INTO TD_REGIEBUCH (NAME, TITEL, TEXT, PRUEFSUMME, STICHWORTE, FOTOS, DAUER_S, HERKUNFT) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, 'EIGEN')")) {
                st.setString(1, b.name());
                setS(st, 2, kurz(b.about(), 200));
                setClob(st, 3, rb.text());
                setS(st, 4, Startdaten.sha256(rb.text()));
                st.setInt(5, b.zeilen().size());
                st.setInt(6, b.fotos());
                st.setDouble(7, b.length());
                st.executeUpdate();
            }
            long id = neueId(c, "TD_REGIEBUCH_SEQ");
            try (PreparedStatement st = c.prepareStatement("INSERT INTO TD_STICHWORT (REGIEBUCH_ID, NR, SEKUNDE, BEFEHL, ARGUMENTE, TEXT) VALUES (?, ?, ?, ?, ?, ?)")) {
                int nr = 0;
                for (Regie.Zeile z : b.zeilen()) {
                    st.setLong(1, id);
                    st.setInt(2, ++nr);
                    st.setDouble(3, z.sekunde());
                    st.setString(4, z.befehl());
                    setS(st, 5, kurz(z.argumente(), 400));
                    setS(st, 6, kurz(z.text(), 400));
                    st.addBatch();
                }
                st.executeBatch();
            }
            return null;
        });
    }

    @Override public void loeschenRegiebuch(String name) throws Fehler {
        zusammen("Regiebuch löschen", c -> {
            if (mitgeliefert(c, "TD_REGIEBUCH", name)) throw new SQLException("„" + name + "“ ist mitgeliefert und wird nicht gelöscht");
            try (PreparedStatement st = c.prepareStatement("DELETE FROM TD_REGIEBUCH WHERE NAME = ? AND HERKUNFT = 'EIGEN'")) { st.setString(1, name); st.executeUpdate(); }
            return null;
        });
    }

    @Override public void regielauf(Daten.Regielauf l, Long sitzung) throws Fehler {
        zusammen("Lauf eines Regiebuchs", c -> {
            try (PreparedStatement st = c.prepareStatement("INSERT INTO TD_REGIELAUF (REGIEBUCH_ID, NAME, SITZUNG_ID, BEGINN, ENDE, VOLLSTAENDIG, IM_KINO, SCHRITTE, "
                    + "GEWARTET_S, AUSGEWICHEN, FOTOS, BILDER_S, GPU_MS, QUALITAET, BLATT) VALUES ((SELECT MAX(REGIEBUCH_ID) FROM TD_REGIEBUCH WHERE NAME = ?), "
                    + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                st.setString(1, l.name());
                st.setString(2, l.name());
                setL(st, 3, sitzung);
                st.setTimestamp(4, new Timestamp(l.beginnMs()));
                if (l.endeMs() == null) st.setNull(5, Types.TIMESTAMP); else st.setTimestamp(5, new Timestamp(l.endeMs()));
                st.setInt(6, l.vollstaendig() ? 1 : 0);
                st.setInt(7, l.kino() ? 1 : 0);
                if (l.schritte() == null) st.setNull(8, Types.NUMERIC); else st.setInt(8, l.schritte());
                setD(st, 9, r1(l.gewartetS()));
                st.setInt(10, l.ausgewichen());
                st.setInt(11, l.fotos());
                setD(st, 12, r2(l.bilderS()));
                setD(st, 13, r3(l.gpuMs()));
                setS(st, 14, l.qualitaet());
                setS(st, 15, kurz(l.blatt(), 400));
                st.executeUpdate();
            }
            return null;
        });
    }

    @Override public List<Daten.Regielauf> regielaeufe(int n) throws Fehler {
        return mit("Läufe", c -> {
            List<Daten.Regielauf> l = new ArrayList<>();
            try (PreparedStatement st = c.prepareStatement("SELECT * FROM TD_REGIELAUF ORDER BY REGIELAUF_ID DESC FETCH FIRST ? ROWS ONLY")) {
                st.setInt(1, n);
                try (ResultSet r = st.executeQuery()) {
                    while (r.next()) {
                        Timestamp e = r.getTimestamp("ENDE");
                        l.add(new Daten.Regielauf(r.getString("NAME"), r.getTimestamp("BEGINN").getTime(), e == null ? null : e.getTime(), r.getInt("VOLLSTAENDIG") == 1,
                                r.getInt("IM_KINO") == 1, integer(r, "SCHRITTE"), d(r, "GEWARTET_S"), r.getInt("AUSGEWICHEN"), r.getInt("FOTOS"), d(r, "BILDER_S"),
                                d(r, "GPU_MS"), r.getString("QUALITAET"), r.getString("BLATT")));
                    }
                }
            }
            return l;
        });
    }

    // ------------------------------------------------------------------ Ansichten und Fahrten

    private static final String ANSICHT_SQL = "SELECT ANSICHT_ID, NAME, TASTE, ART, OST, NORD, HOEHE, AUGE_UEBER_GRUND, GIER_GRAD, NEIGUNG_GRAD, FOV_GRAD, MIT_UHR, "
            + "SZENE_MS, LAGE, HERKUNFT";

    private static Daten.Ansicht ansicht(ResultSet r, String zustand, byte[] png) throws SQLException {
        double ost = d(r, "OST"), nord = d(r, "NORD");
        double[] xz = Double.isNaN(ost) ? new double[]{Double.NaN, Double.NaN} : Daten.xz(ost, nord);
        double[] k = {xz[0], d(r, "HOEHE"), xz[1], d(r, "GIER_GRAD"), d(r, "NEIGUNG_GRAD")};
        return new Daten.Ansicht(r.getLong("ANSICHT_ID"), r.getString("NAME"), integer(r, "TASTE"), r.getString("ART"), k, dbl(r, "AUGE_UEBER_GRUND"), dbl(r, "FOV_GRAD"),
                r.getInt("MIT_UHR") == 1, ms(r, "SZENE_MS"), r.getString("LAGE"), zustand, png, r.getString("HERKUNFT"));
    }

    @Override public List<Daten.Ansicht> ansichten() throws Fehler {
        return mit("Ansichten", c -> {
            List<Daten.Ansicht> l = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery(ANSICHT_SQL + " FROM TD_ANSICHT ORDER BY CASE ART WHEN 'FEST' THEN 0 WHEN 'BLICK' THEN 1 "
                    + "ELSE 2 END, TASTE, CASE ART WHEN 'GEMERKT' THEN -ANSICHT_ID ELSE ANSICHT_ID END")) {
                while (r.next()) l.add(ansicht(r, null, null));
            }
            return l;
        });
    }

    @Override public Daten.Ansicht ansicht(long id) throws Fehler {
        return mit("Ansicht", c -> {
            try (PreparedStatement st = c.prepareStatement(ANSICHT_SQL + ", ZUSTAND, VORSCHAU FROM TD_ANSICHT WHERE ANSICHT_ID = ?")) {
                st.setLong(1, id);
                try (ResultSet r = st.executeQuery()) {
                    if (!r.next()) throw new SQLException("Ansicht " + id + " gibt es nicht");
                    return ansicht(r, clob(r, "ZUSTAND"), r.getBytes("VORSCHAU"));
                }
            }
        });
    }

    @Override public long speichern(Daten.Ansicht a, Long sitzung) throws Fehler {
        Daten.Geom lage = Daten.Geom.punkt(a.kamera()[0], a.kamera()[2]);
        double[] u = Daten.utm(a.kamera()[0], a.kamera()[2]);
        return zusammen("Ansicht merken", c -> {
            String name = a.name();
            for (int i = 2; zahl(c, "SELECT COUNT(*) FROM TD_ANSICHT WHERE NAME = ?", name) > 0; i++) name = a.name() + " (" + i + ")";
            try (PreparedStatement st = c.prepareStatement("INSERT INTO TD_ANSICHT (NAME, ART, OST, NORD, HOEHE, GIER_GRAD, NEIGUNG_GRAD, FOV_GRAD, MIT_UHR, SZENE_MS, "
                    + "SZENE_ZEIT, LAGE, ZUSTAND, VORSCHAU, SITZUNG_ID, HERKUNFT, GEOMETRY) VALUES (?, 'GEMERKT', ?, ?, ?, ?, ?, ?, ?, ?, " + TSTZ + ", ?, ?, ?, ?, 'EIGEN', "
                    + geomSql(lage) + ")")) {
                st.setString(1, name);
                setD(st, 2, r2(u[0]));
                setD(st, 3, r2(u[1]));
                setD(st, 4, r2(a.kamera()[1]));
                setD(st, 5, r2(a.kamera()[3]));
                setD(st, 6, r2(a.kamera()[4]));
                setD(st, 7, a.fov());
                st.setInt(8, a.mitUhr() ? 1 : 0);
                setL(st, 9, a.szeneMs());
                int i = setTz(st, 10, a.szeneMs());
                setS(st, i++, a.lage());
                setClob(st, i++, a.zustand());
                if (a.vorschau() == null) st.setNull(i++, Types.BLOB); else st.setBytes(i++, a.vorschau());
                setL(st, i++, sitzung);
                setGeom(st, i, lage);
                st.executeUpdate();
            }
            return neueId(c, "TD_ANSICHT_SEQ");
        });
    }

    @Override public void loeschenAnsicht(long id) throws Fehler {
        zusammen("Ansicht löschen", c -> {
            try (PreparedStatement st = c.prepareStatement("DELETE FROM TD_ANSICHT WHERE ANSICHT_ID = ? AND HERKUNFT = 'EIGEN'")) {
                st.setLong(1, id);
                if (st.executeUpdate() == 0) throw new SQLException("Ansicht " + id + " ist mitgeliefert oder gibt es nicht");
            }
            return null;
        });
    }

    @Override public List<Daten.Fahrt> fahrten() throws Fehler {
        return mit("Fahrten", c -> {
            Map<Long, List<Daten.Stuetzpunkt>> p = new LinkedHashMap<>();
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT FAHRT_ID, NR, T_S, OST, NORD, HOEHE, GIER_GRAD, NEIGUNG_GRAD FROM TD_STUETZPUNKT ORDER BY FAHRT_ID, NR")) {
                while (r.next()) {
                    double[] xz = Daten.xz(r.getDouble(4), r.getDouble(5));
                    p.computeIfAbsent(r.getLong(1), k -> new ArrayList<>()).add(new Daten.Stuetzpunkt(r.getInt(2), r.getDouble(3), xz[0], d(r, "HOEHE"), xz[1],
                            d(r, "GIER_GRAD"), d(r, "NEIGUNG_GRAD")));
                }
            }
            List<Daten.Fahrt> l = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT FAHRT_ID, NAME, ART, DAUER_S, LAENGE_M, BESCHREIBUNG, HERKUNFT, "
                    + "MDSYS.SDO_UTIL.TO_WKTGEOMETRY(GEOMETRY) AS WKT FROM TD_FAHRT ORDER BY CASE HERKUNFT WHEN 'MITGELIEFERT' THEN 0 ELSE 1 END, FAHRT_ID")) {
                while (r.next())
                    l.add(new Daten.Fahrt(r.getString(2), r.getString(3), dbl(r, "DAUER_S"), dbl(r, "LAENGE_M"), r.getString(6), r.getString(7), geom(r, "WKT"),
                            p.getOrDefault(r.getLong(1), List.of())));
            }
            return l;
        });
    }

    // ------------------------------------------------------------------ Presets

    @Override public List<Daten.PaketPreset> presets(String paket, String klasse) throws Fehler {
        return mit("Presets", c -> {
            Map<Long, Map<String, String>> w = new LinkedHashMap<>();
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT PAKET_PRESET_ID, FELD, WERT FROM TD_PAKET_WERT ORDER BY PAKET_PRESET_ID, PAKET_WERT_ID")) {
                while (r.next()) w.computeIfAbsent(r.getLong(1), k -> new LinkedHashMap<>()).put(r.getString(2), r.getString(3) == null ? "" : r.getString(3));
            }
            List<Daten.PaketPreset> l = new ArrayList<>();
            try (PreparedStatement st = c.prepareStatement("SELECT PAKET_PRESET_ID, PAKET, KLASSE, NAME, BESCHREIBUNG, HERKUNFT FROM TD_PAKET_PRESET WHERE (? IS NULL OR PAKET = ?) "
                    + "AND (? IS NULL OR KLASSE = ?) ORDER BY PAKET, KLASSE, CASE HERKUNFT WHEN 'MITGELIEFERT' THEN 0 ELSE 1 END, NAME")) {
                st.setString(1, paket);
                st.setString(2, paket);
                st.setString(3, klasse);
                st.setString(4, klasse);
                try (ResultSet r = st.executeQuery()) {
                    while (r.next()) l.add(new Daten.PaketPreset(r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), w.getOrDefault(r.getLong(1), Map.of())));
                }
            }
            return l;
        });
    }

    @Override public void speichern(Daten.PaketPreset p) throws Fehler {
        Map<String, String> typ = Startdaten.typen(p);
        zusammen("Preset speichern", c -> {
            if (zahl(c, "SELECT COUNT(*) FROM TD_PAKET_PRESET WHERE PAKET = ? AND KLASSE = ? AND NAME = ? AND HERKUNFT = 'MITGELIEFERT'", p.paket(), p.klasse(), p.name()) > 0)
                throw new SQLException("„" + p.name() + "“ ist mitgeliefert; bitte unter einem eigenen Namen speichern");
            try (PreparedStatement st = c.prepareStatement("DELETE FROM TD_PAKET_PRESET WHERE PAKET = ? AND KLASSE = ? AND NAME = ? AND HERKUNFT = 'EIGEN'")) {
                st.setString(1, p.paket()); st.setString(2, p.klasse()); st.setString(3, p.name()); st.executeUpdate();
            }
            try (PreparedStatement st = c.prepareStatement("INSERT INTO TD_PAKET_PRESET (PAKET, KLASSE, NAME, BESCHREIBUNG, HERKUNFT) VALUES (?, ?, ?, ?, 'EIGEN')")) {
                st.setString(1, p.paket()); st.setString(2, p.klasse()); st.setString(3, p.name()); setS(st, 4, p.beschreibung()); st.executeUpdate();
            }
            long id = neueId(c, "TD_PAKET_PRESET_SEQ");
            try (PreparedStatement st = c.prepareStatement("INSERT INTO TD_PAKET_WERT (PAKET_PRESET_ID, FELD, TYP, WERT) VALUES (?, ?, ?, ?)")) {
                for (Map.Entry<String, String> e : p.felder().entrySet()) {
                    st.setLong(1, id);
                    st.setString(2, e.getKey());
                    st.setString(3, typ.getOrDefault(e.getKey(), "STRING"));
                    setS(st, 4, e.getValue());
                    st.addBatch();
                }
                st.executeBatch();
            }
            return null;
        });
    }

    @Override public void loeschenPreset(String paket, String klasse, String name) throws Fehler {
        zusammen("Preset löschen", c -> {
            try (PreparedStatement st = c.prepareStatement("DELETE FROM TD_PAKET_PRESET WHERE PAKET = ? AND KLASSE = ? AND NAME = ? AND HERKUNFT = 'EIGEN'")) {
                st.setString(1, paket); st.setString(2, klasse); st.setString(3, name);
                if (st.executeUpdate() == 0) throw new SQLException("„" + name + "“ ist mitgeliefert oder gibt es nicht");
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ Fachdaten

    @Override public List<Daten.BootKlasse> bootKlassen() throws Fehler {
        return mit("Bootsklassen", c -> {
            Map<Long, List<Daten.Polarpunkt>> p = new LinkedHashMap<>();
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT BOOT_KLASSE_ID, ART, TWS_KN, TWA_GRAD, FAHRT_KN, KRAENGUNG_GRAD FROM TD_POLARE "
                    + "ORDER BY BOOT_KLASSE_ID, ART, TWS_KN, TWA_GRAD")) {
                while (r.next())
                    p.computeIfAbsent(r.getLong(1), k -> new ArrayList<>()).add(new Daten.Polarpunkt(r.getString(2), r.getDouble(3), r.getDouble(4), d(r, "FAHRT_KN"),
                            d(r, "KRAENGUNG_GRAD")));
            }
            List<Daten.BootKlasse> l = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT * FROM TD_BOOT_KLASSE ORDER BY BOOT_KLASSE_ID")) {
                while (r.next())
                    l.add(new Daten.BootKlasse(r.getString("NAME"), r.getString("ART"), r.getString("RIGG"), d(r, "LUEA_M"), d(r, "LWL_M"), d(r, "BREITE_M"), d(r, "MASSE_KG"),
                            d(r, "SEGELFLAECHE_M2"), d(r, "VORWIND_M2"), d(r, "MAST_M"), r.getInt("CREW"), r.getString("QUELLE"),
                            p.getOrDefault(r.getLong("BOOT_KLASSE_ID"), List.of())));
            }
            return l;
        });
    }

    @Override public List<Daten.Boot> boote() throws Fehler {
        return mit("Boote", c -> {
            List<Daten.Boot> l = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT b.NAME, k.NAME AS KLASSE, b.SEGELNUMMER, b.ROLLE, b.FARBE FROM TD_BOOT b "
                    + "LEFT JOIN TD_BOOT_KLASSE k ON k.BOOT_KLASSE_ID = b.BOOT_KLASSE_ID ORDER BY b.BOOT_ID")) {
                while (r.next()) l.add(new Daten.Boot(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5)));
            }
            return l;
        });
    }

    @Override public List<Daten.Regatta> regatten() throws Fehler {
        return mit("Regatten", c -> {
            Map<Long, List<Daten.Tonne>> t = new LinkedHashMap<>();
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT REGATTA_ID, NR, ROLLE, OST, NORD FROM TD_REGATTA_TONNE ORDER BY REGATTA_ID, NR")) {
                while (r.next()) {
                    double[] xz = Daten.xz(r.getDouble(4), r.getDouble(5));
                    t.computeIfAbsent(r.getLong(1), k -> new ArrayList<>()).add(new Daten.Tonne(r.getInt(2), r.getString(3), xz[0], xz[1]));
                }
            }
            List<Daten.Regatta> l = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT g.*, k.NAME AS KLASSE FROM TD_REGATTA g LEFT JOIN TD_BOOT_KLASSE k "
                    + "ON k.BOOT_KLASSE_ID = g.BOOT_KLASSE_ID ORDER BY g.REGATTA_ID")) {
                while (r.next()) {
                    java.sql.Date dt = r.getDate("DATUM");
                    l.add(new Daten.Regatta(r.getString("NAME"), dt == null ? null : dt.toLocalDate(), r.getString("KLASSE"), d(r, "RICHTUNG_GRAD"), d(r, "LAENGE_M"),
                            d(r, "LINIE_M"), r.getInt("RUNDEN"), t.getOrDefault(r.getLong("REGATTA_ID"), List.of())));
                }
            }
            return l;
        });
    }

    @Override public void ergebnisse(List<Daten.Ergebnis> e, Long sitzung) throws Fehler {
        if (e.isEmpty()) return;
        zusammen("Ergebnisse", c -> {
            try (PreparedStatement st = c.prepareStatement("INSERT INTO TD_REGATTA_ERGEBNIS (REGATTA_ID, SITZUNG_ID, LAUF, BOOT_ID, SEGELNUMMER, PLATZ, ZEIT_S, WENDEN, HALSEN, "
                    + "FRUEHSTART, WIND_MS, WIND_AUS_GRAD, SZENE_MS, SZENE_ZEIT) VALUES ((SELECT MAX(REGATTA_ID) FROM TD_REGATTA WHERE NAME = ?), ?, ?, "
                    + "(SELECT MAX(BOOT_ID) FROM TD_BOOT WHERE SEGELNUMMER = ? AND ROLLE = 'REGATTA'), ?, ?, ?, ?, ?, ?, ?, ?, ?, " + TSTZ + ")")) {
                for (Daten.Ergebnis x : e) {
                    st.setString(1, x.regatta());
                    setL(st, 2, sitzung);
                    st.setInt(3, x.lauf());
                    setS(st, 4, x.segelnummer());
                    setS(st, 5, x.segelnummer());
                    st.setInt(6, x.platz());
                    setD(st, 7, r1(x.zeitS()));
                    st.setInt(8, x.wenden());
                    st.setInt(9, x.halsen());
                    st.setInt(10, x.fruehstart() ? 1 : 0);
                    setD(st, 11, r2(x.wind()));
                    setD(st, 12, r1(x.windAus()));
                    st.setLong(13, x.szeneMs());
                    setTz(st, 14, x.szeneMs());
                    st.addBatch();
                }
                st.executeBatch();
            }
            return null;
        });
    }

    @Override public List<Daten.Ergebnis> ergebnisse(int n) throws Fehler {
        return mit("Ergebnisse", c -> {
            List<Daten.Ergebnis> l = new ArrayList<>();
            try (PreparedStatement st = c.prepareStatement("SELECT e.*, g.NAME AS REGATTA FROM TD_REGATTA_ERGEBNIS e JOIN TD_REGATTA g ON g.REGATTA_ID = e.REGATTA_ID "
                    + "ORDER BY e.REGATTA_ERGEBNIS_ID DESC FETCH FIRST ? ROWS ONLY")) {
                st.setInt(1, n);
                try (ResultSet r = st.executeQuery()) {
                    while (r.next())
                        l.add(new Daten.Ergebnis(r.getString("REGATTA"), r.getInt("LAUF"), r.getString("SEGELNUMMER"), r.getInt("PLATZ"), d(r, "ZEIT_S"), r.getInt("WENDEN"),
                                r.getInt("HALSEN"), r.getInt("FRUEHSTART") == 1, d(r, "WIND_MS"), d(r, "WIND_AUS_GRAD"), r.getLong("SZENE_MS")));
                }
            }
            return l;
        });
    }

    @Override public List<Daten.Effekt> effekte() throws Fehler {
        return mit("Effekte", c -> {
            List<Daten.Effekt> l = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT * FROM TD_EFFEKT ORDER BY EFFEKT_ID")) {
                while (r.next())
                    l.add(new Daten.Effekt(r.getString("NAME"), r.getString("BILD"), r.getString("SALZ"), r.getString("FARBE"), dbl(r, "WELLENLAENGE_NM"), r.getInt("KALIBER_MM"),
                            d(r, "HALBMESSER_M"), d(r, "BRENNDAUER_S")));
            }
            return l;
        });
    }

    @Override public List<Daten.Zuendplan> zuendplaene() throws Fehler {
        return mit("Zündpläne", c -> {
            Map<Long, List<Daten.Schuss>> s = new LinkedHashMap<>();
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT x.ZUENDPLAN_ID, x.NR, x.T_S, e.NAME, x.KALIBER_MM, x.NEIGUNG_GRAD, x.AZIMUT_GRAD "
                    + "FROM TD_ZUENDPLAN_SCHRITT x LEFT JOIN TD_EFFEKT e ON e.EFFEKT_ID = x.EFFEKT_ID ORDER BY x.ZUENDPLAN_ID, x.NR")) {
                while (r.next())
                    s.computeIfAbsent(r.getLong(1), k -> new ArrayList<>()).add(new Daten.Schuss(r.getInt(2), r.getDouble(3), r.getString(4), r.getInt(5), r.getDouble(6), r.getDouble(7)));
            }
            List<Daten.Zuendplan> l = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT z.*, z.GEOMETRY.SDO_POINT.X AS PX, z.GEOMETRY.SDO_POINT.Y AS PY FROM TD_ZUENDPLAN z "
                    + "ORDER BY z.ZUENDPLAN_ID")) {
                while (r.next()) {
                    Double px = dbl(r, "PX"), py = dbl(r, "PY");
                    double[] xz = px == null ? Startdaten.ponton() : Daten.xz(px, py);
                    java.sql.Date dt = r.getDate("DATUM");
                    l.add(new Daten.Zuendplan(r.getString("NAME"), dt == null ? null : dt.toLocalDate(), r.getString("BEGINN"), d(r, "DAUER_S"), clob(r, "TEXT"), xz[0], xz[1],
                            r.getString("HERKUNFT"), s.getOrDefault(r.getLong("ZUENDPLAN_ID"), List.of())));
                }
            }
            return l;
        });
    }

    @Override public List<Daten.Sturmflut> sturmfluten() throws Fehler {
        return mit("Sturmfluten", c -> {
            Map<Long, List<Daten.Pegel>> p = new LinkedHashMap<>();
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT STURMFLUT_ID, STUNDE, PEGEL_M, WIND_MS, WIND_AUS_GRAD, HS_M, "
                    + "ROUND((CAST(SYS_EXTRACT_UTC(ZEIT) AS DATE) - DATE '1970-01-01') * 86400000) AS MS FROM TD_STURMFLUT_PEGEL ORDER BY STURMFLUT_ID, STUNDE")) {
                while (r.next())
                    p.computeIfAbsent(r.getLong(1), k -> new ArrayList<>()).add(new Daten.Pegel(r.getDouble(2), r.getLong("MS"), d(r, "PEGEL_M"), d(r, "WIND_MS"),
                            d(r, "WIND_AUS_GRAD"), dbl(r, "HS_M")));
            }
            List<Daten.Sturmflut> l = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT STURMFLUT_ID, NAME, SCHEITEL_M, KLASSE, QUELLE, BEMERKUNG, "
                    + "ROUND((CAST(SYS_EXTRACT_UTC(BEGINN) AS DATE) - DATE '1970-01-01') * 86400000) AS B, "
                    + "ROUND((CAST(SYS_EXTRACT_UTC(SCHEITEL_ZEIT) AS DATE) - DATE '1970-01-01') * 86400000) AS S FROM TD_STURMFLUT ORDER BY BEGINN")) {
                while (r.next())
                    l.add(new Daten.Sturmflut(r.getString("NAME"), r.getLong("B"), r.getLong("S"), d(r, "SCHEITEL_M"), r.getString("KLASSE"), r.getString("QUELLE"),
                            r.getString("BEMERKUNG"), p.getOrDefault(r.getLong(1), List.of())));
            }
            return l;
        });
    }

    @Override public void abbruch(Daten.Abbruch a, Long sitzung) throws Fehler {
        double[] u = Daten.utm(a.x(), a.z());
        zusammen("Abbruch schreiben", c -> {
            long sf = zahl(c, "SELECT NVL(MAX(STURMFLUT_ID), 0) FROM TD_STURMFLUT WHERE NAME = ?", Startdaten.STURMFLUT);
            try (PreparedStatement st = c.prepareStatement("INSERT INTO TD_ABBRUCH (SITZUNG_ID, STURMFLUT_ID, NR, STUNDE, SZENE_MS, SZENE_ZEIT, OST, NORD, LAENGE_M, "
                    + "RUECKGANG_M, VOLUMEN_M3, GEOMETRY) VALUES (?, ?, ?, ?, ?, " + TSTZ + ", ?, ?, ?, ?, ?, " + geomSql(a.flaeche()) + ")")) {
                setL(st, 1, sitzung);
                setL(st, 2, sf > 0 ? sf : null);
                st.setInt(3, a.nr());
                setD(st, 4, r2(a.stunde()));
                st.setLong(5, a.szeneMs());
                int i = setTz(st, 6, a.szeneMs());
                setD(st, i++, r2(u[0]));
                setD(st, i++, r2(u[1]));
                setD(st, i++, r1(a.laenge()));
                setD(st, i++, r2(a.rueck()));
                setD(st, i++, r1(a.volumen()));
                setGeom(st, i, a.flaeche());
                st.executeUpdate();
            }
            long id = neueId(c, "TD_ABBRUCH_SEQ");
            Daten.Kante k = a.kante();
            if (k != null && k.linie() != null)
                try (PreparedStatement st = c.prepareStatement("INSERT INTO TD_KLIFFKANTE (STAND, ART, SITZUNG_ID, ABBRUCH_ID, LAENGE_M, HERKUNFT, GEOMETRY) "
                        + "VALUES (?, 'LAUF', ?, ?, ?, 'EIGEN', " + geomSql(k.linie()) + ")")) {
                    st.setString(1, kurz(k.stand(), 40));
                    setL(st, 2, sitzung);
                    st.setLong(3, id);
                    setD(st, 4, r1(k.laenge()));
                    setGeom(st, 5, k.linie());
                    st.executeUpdate();
                }
            return null;
        });
    }

    @Override public List<Daten.Abbruch> abbrueche(int n) throws Fehler {
        return mit("Abbrüche", c -> {
            List<Daten.Abbruch> l = new ArrayList<>();
            try (PreparedStatement st = c.prepareStatement("SELECT a.*, MDSYS.SDO_UTIL.TO_WKTGEOMETRY(a.GEOMETRY) AS WKT FROM TD_ABBRUCH a ORDER BY a.ABBRUCH_ID DESC FETCH FIRST ? ROWS ONLY")) {
                st.setInt(1, n);
                try (ResultSet r = st.executeQuery()) {
                    while (r.next()) {
                        double[] xz = Daten.xz(r.getDouble("OST"), r.getDouble("NORD"));
                        l.add(new Daten.Abbruch(r.getInt("NR"), d(r, "STUNDE"), r.getLong("SZENE_MS"), xz[0], xz[1], d(r, "LAENGE_M"), d(r, "RUECKGANG_M"), d(r, "VOLUMEN_M3"),
                                geom(r, "WKT"), null));
                    }
                }
            }
            return l;
        });
    }

    @Override public List<Daten.Kante> kanten() throws Fehler {
        return mit("Kanten", c -> {
            List<Daten.Kante> l = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT k.STAND, k.ART, a.NR, k.LAENGE_M, MDSYS.SDO_UTIL.TO_WKTGEOMETRY(k.GEOMETRY) AS WKT "
                    + "FROM TD_KLIFFKANTE k LEFT JOIN TD_ABBRUCH a ON a.ABBRUCH_ID = k.ABBRUCH_ID ORDER BY k.KLIFFKANTE_ID")) {
                while (r.next()) l.add(new Daten.Kante(r.getString(1), r.getString(2), integer(r, "NR"), d(r, "LAENGE_M"), geom(r, "WKT")));
            }
            return l;
        });
    }

    @Override public List<Daten.Leuchtfeuer> leuchtfeuer() throws Fehler {
        return mit("Leuchtfeuer", c -> {
            Map<Long, List<Daten.Sektor>> s = new LinkedHashMap<>();
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT LEUCHTFEUER_ID, NR, VON_GRAD, BIS_GRAD, FARBE, TRAGWEITE_SM FROM TD_SEKTOR ORDER BY LEUCHTFEUER_ID, NR")) {
                while (r.next())
                    s.computeIfAbsent(r.getLong(1), k -> new ArrayList<>()).add(new Daten.Sektor(r.getInt(2), r.getDouble(3), r.getDouble(4), r.getString(5), dbl(r, "TRAGWEITE_SM")));
            }
            List<Daten.Leuchtfeuer> l = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT * FROM TD_LEUCHTFEUER ORDER BY LEUCHTFEUER_ID")) {
                while (r.next()) {
                    double[] xz = Daten.xz(r.getDouble("OST"), r.getDouble("NORD"));
                    l.add(new Daten.Leuchtfeuer(r.getString("NAME"), r.getString("KENNUNG"), d(r, "FEUERHOEHE_M"), dbl(r, "TRAGWEITE_SM"), r.getInt("HEUTE_AN") == 1, xz[0], xz[1],
                            r.getString("QUELLE"), s.getOrDefault(r.getLong("LEUCHTFEUER_ID"), List.of())));
                }
            }
            return l;
        });
    }

    @Override public List<Daten.Linie> linien() throws Fehler {
        return mit("Linien", c -> {
            Map<Long, List<Daten.Abfahrt>> a = new LinkedHashMap<>();
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT LINIE_ID, HALTESTELLE, KNOTEN, TAGE, ZEIT, RICHTUNG FROM TD_ABFAHRT ORDER BY LINIE_ID, ZEIT")) {
                while (r.next()) a.computeIfAbsent(r.getLong(1), k -> new ArrayList<>()).add(new Daten.Abfahrt(r.getString(2), ms(r, "KNOTEN"), r.getString(4), r.getString(5), r.getString(6)));
            }
            List<Daten.Linie> l = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT * FROM TD_LINIE ORDER BY LINIE_ID")) {
                while (r.next())
                    l.add(new Daten.Linie(r.getString("NR"), r.getString("NAME"), r.getString("BETREIBER"), r.getInt("TAKT_MIN"), a.getOrDefault(r.getLong("LINIE_ID"), List.of())));
            }
            return l;
        });
    }

    // ------------------------------------------------------------------ Sitzungen

    @Override public long sitzungBeginnen(String version, String gpu, String qualitaet) throws Fehler {
        return zusammen("Sitzung beginnen", c -> {
            try (PreparedStatement st = c.prepareStatement("INSERT INTO TD_SITZUNG (RECHNER, BENUTZER, APP_VERSION, GPU, QUALITAET) VALUES (?, ?, ?, ?, ?)")) {
                setS(st, 1, kurz(com.dan.store.DateiAblage.rechner(), 60));
                setS(st, 2, kurz(System.getProperty("user.name", ""), 60));
                st.setString(3, kurz(version, 20));
                setS(st, 4, kurz(gpu, 120));
                setS(st, 5, qualitaet);
                st.executeUpdate();
            }
            long id = neueId(c, "TD_SITZUNG_SEQ");
            try (Statement st = c.createStatement()) { st.executeUpdate("DELETE FROM TD_LOG WHERE ZEIT < SYSTIMESTAMP - INTERVAL '90' DAY"); }
            return id;
        });
    }

    @Override public void sitzungSichern(long id, Sicherung s) throws Fehler {
        Map<String, String> sp = s.spalten();
        double rs = Zahl.lies(sp.get(REGIE_SEKUNDE)), zr = Zahl.lies(sp.get(ZEITRAFFER)), ms = Zahl.lies(sp.get(SZENE_MS)), pg = Zahl.lies(sp.get(PEGEL)),
                wi = Zahl.lies(sp.get(WIND)), wa = Zahl.lies(sp.get(WIND_AUS)), kx = Zahl.lies(sp.get(KAMERA_X)), ky = Zahl.lies(sp.get(KAMERA_Y)), kz = Zahl.lies(sp.get(KAMERA_Z));
        Long szene = Double.isNaN(ms) ? null : (long) ms;
        Daten.Geom kam = Double.isNaN(kx) || Double.isNaN(kz) ? null : Daten.Geom.punkt(kx, kz);
        zusammen("Sitzung sichern", c -> {
            try (PreparedStatement st = c.prepareStatement("UPDATE TD_SITZUNG SET ZUSTAND = ?, ZUSTAND_ZEIT = SYSTIMESTAMP, REGIEBUCH_ID = (SELECT MAX(REGIEBUCH_ID) "
                    + "FROM TD_REGIEBUCH WHERE NAME = ?), REGIE_SEKUNDE = ?, SZENE_MS = ?, SZENE_ZEIT = " + TSTZ + ", ZEITRAFFER = ?, LAGE = ?, PEGEL_M = ?, WIND_MS = ?, "
                    + "WIND_AUS_GRAD = ?, STURMFLUT_ID = CASE WHEN ? = 'true' THEN (SELECT MAX(STURMFLUT_ID) FROM TD_STURMFLUT WHERE NAME = ?) END, KAMERA_HOEHE = ?, "
                    + "KAMERA = " + (kam == null ? "NULL" : geomSql(kam)) + " WHERE SITZUNG_ID = ?")) {
                setClob(st, 1, s.zustand());
                setS(st, 2, sp.get(REGIEBUCH));
                setD(st, 3, rs);
                setL(st, 4, szene);
                int i = setTz(st, 5, szene);
                setD(st, i++, zr);
                setS(st, i++, kurz(sp.get(LAGE), 40));
                setD(st, i++, r3(pg));
                setD(st, i++, r2(wi));
                setD(st, i++, r1(wa));
                st.setString(i++, "true".equals(sp.get(STURMFLUT)) ? "true" : "false");
                st.setString(i++, Startdaten.STURMFLUT);
                setD(st, i++, r2(ky));
                i = setGeom(st, i, kam);
                st.setLong(i, id);
                st.executeUpdate();
            }
            return null;
        });
    }

    @Override public void vorschau(long sitzung, byte[] png) throws Fehler {
        zusammen("Vorschau", c -> {
            try (PreparedStatement st = c.prepareStatement("UPDATE TD_SITZUNG SET VORSCHAU = ? WHERE SITZUNG_ID = ?")) { st.setBytes(1, png); st.setLong(2, sitzung); st.executeUpdate(); }
            return null;
        });
    }

    @Override public void sitzungBeenden(long id) throws Fehler {
        zusammen("Sitzung beenden", c -> {
            try (PreparedStatement st = c.prepareStatement("UPDATE TD_SITZUNG SET ENDE = SYSTIMESTAMP, SAUBER_BEENDET = 1 WHERE SITZUNG_ID = ?")) { st.setLong(1, id); st.executeUpdate(); }
            return null;
        });
    }

    private static final String SITZUNG_SQL = "SELECT s.SITZUNG_ID, s.BEGINN, s.ENDE, s.SAUBER_BEENDET, s.ZUSTAND_ZEIT, r.NAME AS REGIEBUCH, s.REGIE_SEKUNDE, s.SZENE_MS, s.LAGE, "
            + "s.PEGEL_M";

    private static Daten.Sitzung sitzung(ResultSet r, String zustand, byte[] png) throws SQLException {
        Timestamp b = r.getTimestamp("BEGINN"), e = r.getTimestamp("ENDE"), zz = r.getTimestamp("ZUSTAND_ZEIT");
        return new Daten.Sitzung(r.getLong("SITZUNG_ID"), b.getTime(), e == null ? null : e.getTime(), r.getInt("SAUBER_BEENDET") == 1, zustand, zz == null ? null : zz.getTime(),
                r.getString("REGIEBUCH"), dbl(r, "REGIE_SEKUNDE"), ms(r, "SZENE_MS"), r.getString("LAGE"), dbl(r, "PEGEL_M"), png);
    }

    private static Daten.Sitzung letzte(Connection c, long ausser) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(SITZUNG_SQL + ", s.ZUSTAND, s.VORSCHAU FROM TD_SITZUNG s LEFT JOIN TD_REGIEBUCH r ON r.REGIEBUCH_ID = s.REGIEBUCH_ID "
                + "WHERE s.ZUSTAND IS NOT NULL AND s.SITZUNG_ID <> ? ORDER BY s.ZUSTAND_ZEIT DESC NULLS LAST, s.SITZUNG_ID DESC FETCH FIRST 1 ROWS ONLY")) {
            st.setLong(1, ausser);
            try (ResultSet r = st.executeQuery()) {
                return r.next() ? sitzung(r, clob(r, "ZUSTAND"), r.getBytes("VORSCHAU")) : null;
            }
        }
    }

    @Override public Daten.Sitzung letzteSitzung(long ausser) throws Fehler { return mit("Letzte Sitzung", c -> letzte(c, ausser)); }

    @Override public List<Daten.Sitzung> sitzungen(int n) throws Fehler {
        return mit("Sitzungen", c -> {
            List<Daten.Sitzung> l = new ArrayList<>();
            try (PreparedStatement st = c.prepareStatement(SITZUNG_SQL + " FROM TD_SITZUNG s LEFT JOIN TD_REGIEBUCH r ON r.REGIEBUCH_ID = s.REGIEBUCH_ID "
                    + "ORDER BY s.SITZUNG_ID DESC FETCH FIRST ? ROWS ONLY")) {
                st.setInt(1, n);
                try (ResultSet r = st.executeQuery()) { while (r.next()) l.add(sitzung(r, null, null)); }
            }
            return l;
        });
    }

    // ------------------------------------------------------------------ Protokoll

    /** Die Stufen von com.dan.store.Protokoll sind Arten von TD_LOG. */
    static String art(String stufe) {
        return switch (stufe == null ? "" : stufe) {
            case Protokoll.FEHLER -> "FEHLER";
            case Protokoll.WARNUNG -> "WARNUNG";
            default -> "INFO";
        };
    }

    @Override public void protokoll(List<Protokoll.Zeile> zeilen, Long sitzung) throws Fehler {
        if (zeilen.isEmpty()) return;
        zusammen("Protokoll", c -> {
            try (PreparedStatement st = c.prepareStatement("INSERT INTO TD_LOG (SITZUNG_ID, ZEIT, ART, QUELLE, TEXT, SZENE_TEXT, ORT, DETAIL) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (Protokoll.Zeile z : zeilen) {
                    setL(st, 1, sitzung);
                    st.setTimestamp(2, new Timestamp(z.zeitMs()));
                    String art = art(z.stufe());
                    if ("Sitzung".equals(z.quelle()) && z.text().startsWith("Beginn")) art = "START";
                    st.setString(3, art);
                    setS(st, 4, kurz(z.quelle(), 60));
                    setS(st, 5, kurz(z.text(), 1000));
                    setS(st, 6, kurz(z.szenenzeit(), 40));
                    setS(st, 7, kurz(z.ort(), 40));
                    setClob(st, 8, z.detail());
                    st.addBatch();
                }
                st.executeBatch();
            }
            return null;
        });
    }

    @Override public void ereignis(String art, String quelle, String text, Double wert, Double wert2, Long sitzung) throws Fehler {
        zusammen("Ereignis", c -> {
            try (PreparedStatement st = c.prepareStatement("INSERT INTO TD_LOG (SITZUNG_ID, ART, QUELLE, TEXT, WERT, WERT2) VALUES (?, ?, ?, ?, ?, ?)")) {
                setL(st, 1, sitzung);
                st.setString(2, art);
                setS(st, 3, kurz(quelle, 60));
                setS(st, 4, kurz(text, 1000));
                setD(st, 5, wert);
                setD(st, 6, wert2);
                st.executeUpdate();
            }
            return null;
        });
    }

    @Override public List<String> letzteZeilen(int n) throws Fehler {
        return mit("Protokoll lesen", c -> {
            List<String> l = new ArrayList<>();
            try (PreparedStatement st = c.prepareStatement("SELECT ZEIT, ART, QUELLE, TEXT FROM TD_LOG ORDER BY LOG_ID DESC FETCH FIRST ? ROWS ONLY")) {
                st.setInt(1, n);
                try (ResultSet r = st.executeQuery()) {
                    while (r.next()) l.add(0, Protokoll.zeile(new Protokoll.Zeile(r.getTimestamp(1).getTime(), r.getString(2), r.getString(3), r.getString(4), null, null, null)));
                }
            }
            return l;
        });
    }

    // ------------------------------------------------------------------ Übernahme (F37 A)

    /** Ob eine Datei mit dieser Prüfsumme schon übernommen ist. */
    public boolean uebernommen(String pruefsumme) throws Fehler {
        return mit("Übernahme", c -> zahl(c, "SELECT COUNT(*) FROM TD_UEBERNAHME WHERE PRUEFSUMME = ?", pruefsumme) > 0);
    }

    /** Die Übernahme einer Datei merken. */
    public void uebernahme(String datei, String art, String pruefsumme, int zeilen) throws Fehler {
        zusammen("Übernahme merken", c -> {
            try (PreparedStatement st = c.prepareStatement("INSERT INTO TD_UEBERNAHME (DATEI, ART, PRUEFSUMME, ZEILEN) VALUES (?, ?, ?, ?)")) {
                st.setString(1, kurz(datei, 400));
                setS(st, 2, art);
                st.setString(3, pruefsumme);
                st.setInt(4, zeilen);
                st.executeUpdate();
            }
            return null;
        });
    }

    @Override public synchronized void schliessen() {
        try { if (c != null) c.close(); } catch (SQLException e) { /* zu */ }
        c = null;
    }
}
