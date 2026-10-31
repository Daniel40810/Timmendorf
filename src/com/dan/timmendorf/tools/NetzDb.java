package com.dan.timmendorf.tools;

import com.dan.store.Db;
import com.dan.timmendorf.data.NetzOracle;
import com.dan.timmendorf.data.Verbindung;
import com.dan.timmendorf.scene.Netz;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Schema TIMMENDORF Teil 1 (Phase 3b, Werkbuch Abschnitt 8, F34–F36 alle A): {@code ant netzdb} baut die Netze im Oracle
 * Network Data Model aus {@code daten/netz} auf, {@code ant netzvergleich} prüft sie. Die DDL steht in
 * {@code db/netz/*.sql} (F36 A); dieses Werkzeug führt dieselben Dateien aus:
 * <ol>
 * <li>mit {@code -Dsys.passwort=…}: {@code 00_sys.sql} als SYS AS SYSDBA (Rechte, Verzeichnis für das Protokoll der
 * Partitionierung, Platz); das Passwort wird nirgends gespeichert;</li>
 * <li>als TIMMENDORF (Zugang aus {@code db/db.properties}): {@code 99_abbau.sql}, {@code 01_tabellen.sql},
 * {@code 02_netze.sql};</li>
 * <li>Laden per JDBC-Batch: {@code TD_QUELLE} aus {@code docs/QUELLEN.md}, {@code TD_POI} aus {@code poi.tsv}, Knoten und
 * Kanten der vier Netze mit Geometrie samt Höhe (3001/3002, SRID 25832);</li>
 * <li>{@code 04_nach_dem_laden.sql} (Indizes, Partitionen, Partition-BLOBs), {@code 05_sichten.sql};
 * {@code SDO_NET.VALIDATE_NETWORK}; {@code TD_NETZ_STAND} mit der Prüfsumme der Dateien;</li>
 * <li>der Vergleich: die Prüfwege aus {@code pruefwege.tsv} mit {@code oracle.spatial.network.lod} (F35 A) gegen den
 * kürzesten Weg im Datei-Abbild, gleiche Kosten auf 1 cm; dazu {@code docs/status/Netz_Oracle_Phase3.png} aus den
 * Tabellen in Oracle.</li>
 * </ol>
 */
public final class NetzDb {
    public static final File SKRIPTE = new File("db/netz");
    public static final File BILD = new File("docs/status/Netz_Oracle_Phase3.png");

    private NetzDb() { }

    public static void main(String[] a) throws Exception {
        boolean nurVergleich = a.length > 0 && a[0].equals("vergleich");
        Consumer<String> log = System.out::println;
        File daten = new File("daten"), nd = new File(daten, "netz");
        for (Netz.Art x : Netz.Art.values())
            if (!Netz.da(nd, x)) throw new IOException("daten/netz/" + x.datei + "_… fehlt: erst ant aufbereitung -Dschritt=netz+inhalt");
        Db.Konfig k = Verbindung.konfig();
        if (!k.hatPasswort()) throw new IOException("db/db.properties ohne Passwort: einmal die App starten und im Startbild anmelden, oder "
                + Verbindung.ENV + " setzen");
        Db.antwortS = 1800;   // Partitionierung und Indizes dauern
        long t0 = System.nanoTime();
        int fehler = 0;
        if (!nurVergleich) {
            String sys = System.getProperty("sys.passwort", "");
            if (!sys.isEmpty() && !sys.startsWith("$")) {
                log.accept("--- 00_sys.sql als SYS AS SYSDBA");
                Properties p = new Properties();
                p.setProperty("user", "sys");
                p.setProperty("password", sys);
                p.setProperty("internal_logon", "sysdba");
                try (Connection c = DriverManager.getConnection(k.url, p)) { skript(c, new File(SKRIPTE, "00_sys.sql"), log, false); }
            } else log.accept("--- 00_sys.sql übergangen (ohne -Dsys.passwort=…; einmal nötig, danach nicht mehr)");
            try (Connection c = Db.oeffnen(k)) {
                log.accept("Verbunden: " + Db.pruefen(k));
                log.accept("--- 99_abbau.sql");
                skript(c, new File(SKRIPTE, "99_abbau.sql"), log, true);
                log.accept("--- 01_tabellen.sql");
                skript(c, new File(SKRIPTE, "01_tabellen.sql"), log, false);
                log.accept("--- 02_netze.sql");
                skript(c, new File(SKRIPTE, "02_netze.sql"), log, false);
                c.setAutoCommit(false);
                log.accept("--- Laden");
                quellen(c, log);
                poi(c, new File(nd, "poi.tsv"), log);
                for (Netz.Art x : Netz.Art.values()) laden(c, Netz.laden(nd, x), log);
                c.commit();
                c.setAutoCommit(true);
                log.accept("--- 04_nach_dem_laden.sql (Indizes, Partitionen, Partition-BLOBs)");
                skript(c, new File(SKRIPTE, "04_nach_dem_laden.sql"), log, false);
                log.accept("--- 05_sichten.sql");
                skript(c, new File(SKRIPTE, "05_sichten.sql"), log, false);
                log.accept("--- Prüfung der Netze");
                for (Netz.Art x : Netz.Art.values()) {
                    String v = validate(c, x.oracle);
                    boolean ok = "TRUE".equalsIgnoreCase(v);
                    if (!ok) fehler++;
                    log.accept(Raster.f("  %s %s: SDO_NET.VALIDATE_NETWORK = %s", ok ? "ok    " : "FEHLER", x.oracle, v));
                    stand(c, nd, x, v);
                }
            }
        }
        fehler += vergleich(k, nd, daten, log);
        log.accept(Raster.f("=== %s in %.0f s", fehler == 0 ? "ERGEBNIS: alles in Ordnung" : "ERGEBNIS: " + fehler + " Fehler", (System.nanoTime() - t0) / 1e9));
        if (fehler > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ Skripte

    /**
     * Führt ein Skript aus db/netz aus: Anweisungen enden mit einer Zeile „/“, ganze Kommentarzeilen „--“ zählen nicht.
     * {@code still}: Fehler nur melden (Abbau).
     */
    static void skript(Connection c, File f, Consumer<String> log, boolean still) throws IOException, SQLException {
        List<String> zeilen = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        int nr = 0;
        for (String z : zeilen) {
            if (z.strip().equals("/")) {
                // Kommentar- und Leerzeilen vor der Anweisung weg (sonst erkennt die Prüfung unten PL/SQL nicht und nimmt
                // dem END das Semikolon; Befund Phase 4)
                String s = sb.toString().replaceAll("(?m)\\A(\\s*(--[^\\n]*)?\\n)+", "").strip();
                sb.setLength(0);
                if (s.isEmpty()) continue;
                nr++;
                boolean plsql = s.toUpperCase(Locale.ROOT).matches("(?s)^(DECLARE|BEGIN|CREATE OR REPLACE (TRIGGER|PROCEDURE|FUNCTION|PACKAGE)).*");
                if (!plsql && s.endsWith(";")) s = s.substring(0, s.length() - 1);
                String kopf = s.lines().findFirst().orElse("").strip();
                try (Statement st = c.createStatement()) {
                    st.execute(s);
                    log.accept("  ok     " + kurz(kopf));
                } catch (SQLException e) {
                    if (still) { log.accept("  (übergangen) " + kurz(kopf) + ": " + Db.erste(e)); continue; }
                    log.accept("  FEHLER " + kurz(kopf) + ": " + e.getMessage().strip());
                    throw new SQLException(f.getName() + ", Anweisung " + nr + ": " + e.getMessage(), e);
                }
                continue;
            }
            if ((z.strip().startsWith("--") || z.isBlank()) && sb.length() == 0) continue;
            sb.append(z).append('\n');
        }
    }

    static String kurz(String s) { return s.length() > 90 ? s.substring(0, 90) + " …" : s; }

    // ------------------------------------------------------------------ Laden

    static void quellen(Connection c, Consumer<String> log) throws IOException, SQLException {
        File q = new File("docs/QUELLEN.md");
        if (!q.isFile()) return;
        int n = 0;
        try (PreparedStatement s = c.prepareStatement("insert into TD_QUELLE (NAME, STAND, LIZENZ, WERKZEUG, ERGEBNIS) values (?, ?, ?, ?, ?)")) {
            for (String z : Files.readAllLines(q.toPath(), StandardCharsets.UTF_8)) {
                if (!z.startsWith("| ") || z.startsWith("| Rohdatei") || z.startsWith("|---")) continue;
                String[] f = z.split("\\|");
                if (f.length < 6) continue;
                s.setString(1, cut(md(f[1]), 200));
                s.setString(2, cut(md(f[2]), 40));
                s.setString(3, cut(md(f[3]), 400));
                s.setString(4, cut(md(f[4]), 100));
                s.setString(5, cut(md(f[5]), 400));
                s.addBatch();
                n++;
            }
            s.executeBatch();
        }
        log.accept("  TD_QUELLE: " + n + " Rohdaten aus docs/QUELLEN.md");
    }

    static String md(String s) { return s.replace("`", "").strip(); }
    static String cut(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }

    static void poi(Connection c, File f, Consumer<String> log) throws IOException, SQLException {
        int n = 0;
        try (PreparedStatement s = c.prepareStatement("insert into TD_POI (OSM_ID, EBENE, ART, NAME, OST, NORD, GEOMETRY) values (?, ?, ?, ?, ?, ?, "
                + "SDO_GEOMETRY(2001, 25832, SDO_POINT_TYPE(?, ?, NULL), NULL, NULL))")) {
            for (String z : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                if (z.startsWith("osm\t") || z.isBlank()) continue;
                String[] a = z.split("\t", -1);
                double e = Double.parseDouble(a[2]), no = Double.parseDouble(a[3]);
                s.setString(1, a[0]);
                s.setString(2, cut(a[1], 80));
                s.setString(3, cut(a[4], 120));
                s.setString(4, cut(a[5], 200));
                s.setDouble(5, e);
                s.setDouble(6, no);
                s.setDouble(7, e);
                s.setDouble(8, no);
                s.addBatch();
                if (++n % 1000 == 0) s.executeBatch();
            }
            s.executeBatch();
        }
        log.accept(Raster.f("  TD_POI: %,d Orte", n));
    }

    /** Spalten einer Tabelle (Oracle legt je nach Version etwas andere Spalten an). */
    static Set<String> spalten(Connection c, String tabelle) throws SQLException {
        Set<String> s = new HashSet<>();
        try (PreparedStatement p = c.prepareStatement("select COLUMN_NAME from USER_TAB_COLUMNS where TABLE_NAME = ?")) {
            p.setString(1, tabelle);
            try (ResultSet r = p.executeQuery()) { while (r.next()) s.add(r.getString(1)); }
        }
        return s;
    }

    static void laden(Connection c, Netz n, Consumer<String> log) throws SQLException {
        String t = n.art.oracle;
        Set<String> ns = spalten(c, t + "_NODE$"), ls = spalten(c, t + "_LINK$");
        // Knoten
        StringBuilder col = new StringBuilder("NODE_ID, NODE_NAME, GEOMETRY, HOEHE, ART, OSM_ID"), val = new StringBuilder("?, ?, SDO_GEOMETRY(3001, 25832, SDO_POINT_TYPE(?, ?, ?), NULL, NULL), ?, ?, ?");
        if (ns.contains("NODE_TYPE")) { col.append(", NODE_TYPE"); val.append(", ?"); }
        if (ns.contains("ACTIVE")) { col.append(", ACTIVE"); val.append(", 'Y'"); }
        try (PreparedStatement s = c.prepareStatement("insert into " + t + "_NODE$ (" + col + ") values (" + val + ")")) {
            int i = 0;
            for (Netz.Knoten k : n.knoten) {
                s.setInt(1, k.id());
                s.setString(2, cut(k.name(), 200));
                s.setDouble(3, k.e());
                s.setDouble(4, k.n());
                s.setDouble(5, k.h());
                s.setDouble(6, k.h());
                s.setString(7, k.art());
                s.setString(8, k.osm());
                if (ns.contains("NODE_TYPE")) s.setString(9, cut(k.art(), 24));
                s.addBatch();
                if (++i % 2000 == 0) s.executeBatch();
            }
            s.executeBatch();
        }
        // Kanten
        col = new StringBuilder("LINK_ID, LINK_NAME, START_NODE_ID, END_NODE_ID, GEOMETRY, COST, BIDIRECTED, ZEIT_S, ART, BELAG, LICHT, BREITE, ZUGANG, EBENE, BRUECKE, TEMPO, OSM_ID");
        val = new StringBuilder("?, ?, ?, ?, SDO_GEOMETRY(3002, 25832, NULL, SDO_ELEM_INFO_ARRAY(1, 2, 1), ?), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?");
        if (ls.contains("LINK_TYPE")) { col.append(", LINK_TYPE"); val.append(", ?"); }
        if (ls.contains("ACTIVE")) { col.append(", ACTIVE"); val.append(", 'Y'"); }
        if (ls.contains("LINK_LEVEL")) { col.append(", LINK_LEVEL"); val.append(", 1"); }
        oracle.jdbc.OracleConnection oc = c.unwrap(oracle.jdbc.OracleConnection.class);
        try (PreparedStatement s = c.prepareStatement("insert into " + t + "_LINK$ (" + col + ") values (" + val + ")")) {
            int i = 0;
            for (Netz.Kante k : n.kanten) {
                double[] p = k.punkte();
                Double[] o = new Double[p.length];
                for (int j = 0; j < p.length; j++) o[j] = p[j];
                Array arr = oc.createOracleArray("MDSYS.SDO_ORDINATE_ARRAY", o);
                s.setInt(1, k.id());
                s.setString(2, cut(k.name(), 200));
                s.setInt(3, k.von());
                s.setInt(4, k.nach());
                s.setArray(5, arr);
                s.setDouble(6, k.laenge());
                s.setString(7, k.einbahn() ? "N" : "Y");
                s.setDouble(8, k.zeit());
                s.setString(9, k.art());
                s.setString(10, cut(k.belag(), 60));
                s.setString(11, cut(k.licht(), 20));
                s.setDouble(12, k.breite());
                s.setString(13, cut(k.zugang(), 60));
                s.setInt(14, k.ebene());
                s.setString(15, k.bruecke() ? "J" : "N");
                if (k.tempo() > 0) s.setDouble(16, k.tempo()); else s.setNull(16, Types.NUMERIC);
                s.setString(17, k.osm());
                if (ls.contains("LINK_TYPE")) s.setString(18, cut(k.art(), 24));
                s.addBatch();
                if (++i % 1000 == 0) s.executeBatch();
            }
            s.executeBatch();
        }
        log.accept("  " + n);
    }

    static String validate(Connection c, String netz) {
        try (CallableStatement s = c.prepareCall("{? = call SDO_NET.VALIDATE_NETWORK(?)}")) {
            s.registerOutParameter(1, Types.VARCHAR);
            s.setString(2, netz);
            s.execute();
            return s.getString(1);
        } catch (SQLException e) {
            return Db.erste(e);
        }
    }

    static void stand(Connection c, File nd, Netz.Art x, String gueltig) throws IOException, SQLException {
        Netz n = Netz.laden(nd, x);
        try (PreparedStatement s = c.prepareStatement("insert into TD_NETZ_STAND (NETZ, INHALT, KNOTEN, KANTEN, LAENGE_M, PRUEFSUMME, QUELLE, GUELTIG) values (?, ?, ?, ?, ?, ?, ?, ?)")) {
            s.setString(1, x.oracle);
            s.setString(2, x.inhalt);
            s.setInt(3, n.knoten.size());
            s.setInt(4, n.kanten.size());
            s.setDouble(5, Math.round(n.laenge() * 100) / 100.0);
            s.setString(6, Netz.pruefsumme(nd, x));
            s.setString(7, "daten/netz/" + x.datei + "_knoten.tsv, _kanten.tsv (NetzImport, Overpass 30.10.2026)");
            s.setString(8, cut(gueltig == null ? "" : gueltig, 400));
            s.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ Vergleich

    /** Eine Wegsuche in Oracle: Kosten (m) und Knotenfolge, oder Kosten −1 ohne Weg. */
    interface Suche extends AutoCloseable {
        String name();
        double weg(String netz, long von, long nach, long[][] knoten) throws Exception;
        @Override default void close() { }
    }

    /**
     * LOD (F35 A) mit eigenem Klassenlader: {@code sdonm.jar} von 2021 bindet in seinen Abfragen Platzhalter mit Namen
     * und mit Nummern zugleich, und der JDBC-Treiber 23 in {@code lib/} weist das ab (ORA-17090, Befund Phase 4). Liegt
     * ein Treiber 21 in {@code lib/lod/} (aus {@code ORACLE_HOME/jdbc/lib/ojdbc11.jar}), rechnet LOD damit.
     */
    static final class LodIsoliert implements Suche {
        final java.net.URLClassLoader cl;
        final Connection c;
        final java.util.Map<String, Object> analyst = new java.util.HashMap<>();

        LodIsoliert(Db.Konfig k, File treiber) throws Exception {
            File lib = new File("lib");
            java.net.URL[] u = {treiber.toURI().toURL(), new File(lib, "sdonm.jar").toURI().toURL(), new File(lib, "sdoutl.jar").toURI().toURL(),
                    new File(lib, "sdoapi.jar").toURI().toURL()};
            cl = new java.net.URLClassLoader(u, ClassLoader.getPlatformClassLoader());
            java.sql.Driver d = (java.sql.Driver) Class.forName("oracle.jdbc.OracleDriver", true, cl).getDeclaredConstructor().newInstance();
            Properties p = new Properties();
            p.setProperty("user", k.user);
            p.setProperty("password", passwort(k));
            c = d.connect(k.url, p);
        }

        public String name() { return "LOD mit " + c.getClass().getPackageName() + " aus lib/lod"; }

        public double weg(String netz, long von, long nach, long[][] knoten) throws Exception {
            Class<?> mgr = cl.loadClass("oracle.spatial.network.lod.LODNetworkManager"), nio = cl.loadClass("oracle.spatial.network.lod.NetworkIO"),
                    na = cl.loadClass("oracle.spatial.network.lod.NetworkAnalyst"), pon = cl.loadClass("oracle.spatial.network.lod.PointOnNet"),
                    con = cl.loadClass("oracle.spatial.network.lod.LODNetworkConstraint"), lsp = cl.loadClass("oracle.spatial.network.lod.LogicalSubPath"),
                    lp = cl.loadClass("oracle.spatial.network.lod.LogicalPath"), md = cl.loadClass("oracle.spatial.network.NetworkMetadata");
            Object an = analyst.get(netz);
            if (an == null) {
                Object io = mgr.getMethod("getCachedNetworkIO", Connection.class, String.class, String.class, md).invoke(null, c, netz, netz, null);
                an = mgr.getMethod("getNetworkAnalyst", nio).invoke(null, io);
                analyst.put(netz, an);
            }
            Object p = na.getMethod("shortestPathDijkstra", pon, pon, con).invoke(an, pon.getConstructor(long.class).newInstance(von),
                    pon.getConstructor(long.class).newInstance(nach), null);
            if (p == null) { knoten[0] = new long[0]; return -1; }
            knoten[0] = (long[]) lp.getMethod("getNodeIds").invoke(lsp.getMethod("getReferencePath").invoke(p));
            return (Double) lsp.getMethod("getCost").invoke(p);
        }

        @Override public void close() {
            try { c.close(); cl.close(); } catch (Exception e) { /* zu */ }
        }
    }

    /** LOD im Klassenpfad der App (geht nur mit einem Treiber, der gemischte Platzhalter nimmt). */
    static final class Lod implements Suche {
        final Connection c;
        final java.util.Map<String, oracle.spatial.network.lod.NetworkAnalyst> analyst = new java.util.HashMap<>();
        Lod(Connection c) { this.c = c; }
        public String name() { return "LOD (oracle.spatial.network.lod)"; }
        public double weg(String netz, long von, long nach, long[][] knoten) throws Exception {
            oracle.spatial.network.lod.NetworkAnalyst an = analyst.get(netz);
            if (an == null) {
                an = oracle.spatial.network.lod.LODNetworkManager.getNetworkAnalyst(oracle.spatial.network.lod.LODNetworkManager.getCachedNetworkIO(c, netz, netz, null));
                analyst.put(netz, an);
            }
            oracle.spatial.network.lod.LogicalSubPath p = an.shortestPathDijkstra(new oracle.spatial.network.lod.PointOnNet(von), new oracle.spatial.network.lod.PointOnNet(nach), null);
            if (p == null) { knoten[0] = new long[0]; return -1; }
            knoten[0] = p.getReferencePath().getNodeIds();
            return p.getCost();
        }
    }

    /** Im Datenbankkern: SDO_NET_MEM (Netz im Speicher der Sitzung, kürzester Weg nach Dijkstra); braucht keinen Treiber 21. */
    static final class NetMem implements Suche {
        final Connection c;
        NetMem(Connection c) { this.c = c; }
        public String name() { return "SDO_NET_MEM im Datenbankkern"; }
        public double weg(String netz, long von, long nach, long[][] knoten) throws Exception {
            String plsql = "DECLARE pid NUMBER; ids SDO_NUMBER_ARRAY; s VARCHAR2(32767); BEGIN "
                    + "SDO_NET_MEM.NETWORK_MANAGER.READ_NETWORK(?, 'FALSE'); "
                    + "pid := SDO_NET_MEM.NETWORK_MANAGER.SHORTEST_PATH(?, ?, ?); "
                    + "IF pid IS NULL OR pid < 0 THEN ? := -1; ? := ''; ELSE "
                    + "? := SDO_NET_MEM.PATH.GET_COST(?, pid); ids := SDO_NET_MEM.PATH.GET_NODE_IDS(?, pid); "
                    + "FOR i IN 1 .. ids.COUNT LOOP s := s || ids(i) || ' '; END LOOP; ? := s; END IF; "
                    + "SDO_NET_MEM.NETWORK_MANAGER.DROP_NETWORK(?); END;";
            try (CallableStatement s = c.prepareCall(plsql)) {
                s.setString(1, netz);
                s.setString(2, netz);
                s.setLong(3, von);
                s.setLong(4, nach);
                s.registerOutParameter(5, Types.NUMERIC);
                s.registerOutParameter(6, Types.VARCHAR);
                s.registerOutParameter(7, Types.NUMERIC);
                s.setString(8, netz);
                s.setString(9, netz);
                s.registerOutParameter(10, Types.VARCHAR);
                s.setString(11, netz);
                s.execute();
                String ids = s.getString(10) != null ? s.getString(10) : s.getString(6);
                double cost = s.getBigDecimal(7) != null ? s.getDouble(7) : s.getDouble(5);
                String[] t = ids == null || ids.isBlank() ? new String[0] : ids.strip().split(" ");
                knoten[0] = new long[t.length];
                for (int i = 0; i < t.length; i++) knoten[0][i] = Long.parseLong(t[i]);
                return cost;
            }
        }
    }

    /** Das Passwort aus db/db.properties oder der Umgebung (für den Treiber in lib/lod). */
    static String passwort(Db.Konfig k) throws IOException {
        Properties p = new Properties();
        try (var in = new java.io.InputStreamReader(new java.io.FileInputStream(k.datei), StandardCharsets.UTF_8)) { p.load(in); }
        String pw = p.getProperty("password", "");
        if (pw.isEmpty()) pw = System.getenv(Verbindung.ENV) != null ? System.getenv(Verbindung.ENV) : "";
        return pw;
    }

    /** Die Prüfwege in Oracle gegen das Datei-Abbild; gibt die Zahl der Fehler zurück. */
    static int vergleich(Db.Konfig k, File nd, File daten, Consumer<String> log) throws Exception {
        log.accept("--- Vergleich: Prüfwege in Oracle (oracle.spatial.network.lod) gegen daten/netz");
        int fehler = 0;
        List<String[]> wege = new ArrayList<>();
        List<String> bericht = new ArrayList<>();
        Netz[] ausOracle = new Netz[4];
        List<Suche> suchen = new ArrayList<>();
        try (Connection c = Db.oeffnen(k)) {
            File[] l21 = new File("lib/lod").listFiles((d, n) -> n.startsWith("ojdbc") && n.endsWith(".jar"));
            if (l21 != null && l21.length > 0) {
                try { suchen.add(new LodIsoliert(k, l21[0])); } catch (Exception e) { log.accept("  lib/lod/" + l21[0].getName() + ": " + e); }
            }
            suchen.add(new Lod(c));
            suchen.add(new NetMem(c));
            for (Netz.Art x : Netz.Art.values()) {
                ausOracle[x.ordinal()] = NetzOracle.laden(c, x);
                Netz datei = Netz.laden(nd, x);
                boolean gleich = ausOracle[x.ordinal()].knoten.size() == datei.knoten.size() && ausOracle[x.ordinal()].kanten.size() == datei.kanten.size()
                        && Math.abs(ausOracle[x.ordinal()].laenge() - datei.laenge()) < 0.5;
                String ps = NetzOracle.pruefsumme(c, x), pd = Netz.pruefsumme(nd, x);
                if (!gleich || !pd.equals(ps)) fehler++;
                String z = Raster.f("%s %s aus Oracle: %,d Knoten, %,d Kanten, %.2f km; Datei %.2f km; Prüfsumme %s", gleich && pd.equals(ps) ? "ok    " : "FEHLER", x.oracle,
                        ausOracle[x.ordinal()].knoten.size(), ausOracle[x.ordinal()].kanten.size(), ausOracle[x.ordinal()].laenge() / 1000, datei.laenge() / 1000,
                        pd.equals(ps) ? "gleich" : "anders (" + ps + ")");
                log.accept("  " + z);
                bericht.add(z.strip());
            }
            for (String l : Files.readAllLines(new File(nd, "pruefwege.tsv").toPath(), StandardCharsets.UTF_8)) {
                String[] s = l.split("\t", -1);
                if (s[0].equals("netz")) continue;
                Netz.Art x = Netz.Art.valueOf(s[0].substring(7));
                Netz datei = Netz.laden(nd, x);
                int von = Integer.parseInt(s[2]), nach = Integer.parseInt(s[3]);
                double[] ld = new double[1];
                long[] weg = datei.kuerzester(von, nach, ld);
                double lo = -1;
                long[][] kn = {new long[0]};
                String mit = null;
                // die erste Suche, die geht, rechnet alle weiteren Wege; eine, die scheitert, fällt heraus
                while (!suchen.isEmpty()) {
                    Suche su = suchen.get(0);
                    try {
                        lo = su.weg(x.oracle, von, nach, kn);
                        mit = su.name();
                        break;
                    } catch (Exception e) {
                        Throwable t = e instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
                        String z = "Hinweis: " + su.name() + " geht nicht (" + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()).lines().findFirst().orElse("")
                                + ")" + (su instanceof Lod ? "; LOD aus sdonm.jar 2021 mit dem Treiber 23 in lib/ (ORA-17090). Abhilfe: ojdbc11.jar aus ORACLE_HOME\\jdbc\\lib nach lib\\lod\\" : "");
                        log.accept("  " + z);
                        bericht.add(z);
                        su.close();
                        suchen.remove(0);
                    }
                }
                if (mit == null) {
                    fehler++;
                    log.accept("  FEHLER " + s[1] + ": keine Wegsuche in Oracle geht");
                    continue;
                }
                long[] knotenOracle = kn[0];
                boolean ok = weg == null ? lo < 0 : Math.abs(lo - ld[0]) < 0.01;
                if (!ok) fehler++;
                String z = Raster.f("%s %s: Oracle %.2f m, Datei %.2f m (route %s m), %d Knoten · %s", ok ? "ok    " : "FEHLER", s[1], lo, weg == null ? -1 : ld[0], s[4],
                        knotenOracle.length, mit);
                log.accept("  " + z);
                bericht.add(z.strip());
                StringBuilder ids = new StringBuilder();
                for (long id : knotenOracle) { if (ids.length() > 0) ids.append(' '); ids.append(id); }
                wege.add(new String[]{s[1], x.oracle, "", ids.toString()});
            }
        } finally {
            for (Suche su : suchen) su.close();
        }
        NetzImport.bild(ausOracle, wege, bericht, daten, BILD, "Timmendorf Phase 3b · Netze aus Oracle (Schema TIMMENDORF, Network Data Model) · Prüfwege in Oracle gerechnet");
        log.accept("  Prüfbild " + BILD);
        return fehler;
    }
}
