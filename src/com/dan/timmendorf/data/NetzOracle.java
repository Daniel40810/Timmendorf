package com.dan.timmendorf.data;

import com.dan.timmendorf.scene.Netz;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Die Netze aus dem Oracle Network Data Model (Schema TIMMENDORF Teil 1, Phase 3b, Werkbuch Abschnitt 8, F20 A): liest
 * {@code TD_NET_…_NODE$} und {@code _LINK$} in dasselbe {@link Netz} wie das Datei-Abbild in {@code daten/netz}. Die App
 * nimmt Oracle, wenn sie verbunden ist und {@code TD_NETZ_STAND} dieselbe Prüfsumme trägt wie die Dateien; sonst die Datei.
 */
public final class NetzOracle {
    private NetzOracle() { }

    /** Prüfsumme des Netzes in {@code TD_NETZ_STAND} oder null (Tabelle oder Zeile fehlt). */
    public static String pruefsumme(Connection c, Netz.Art a) {
        try (PreparedStatement s = c.prepareStatement("select PRUEFSUMME from TD_NETZ_STAND where NETZ = ?")) {
            s.setString(1, a.oracle);
            try (ResultSet r = s.executeQuery()) { return r.next() ? r.getString(1) : null; }
        } catch (SQLException e) {
            return null;
        }
    }

    /** Liest ein Netz aus Oracle. */
    public static Netz laden(Connection c, Netz.Art a) throws SQLException {
        List<Netz.Knoten> kn = new ArrayList<>();
        String t = a.oracle;
        try (PreparedStatement s = c.prepareStatement("select n.NODE_ID, n.GEOMETRY.SDO_POINT.X, n.GEOMETRY.SDO_POINT.Y, n.HOEHE, n.ART, n.NODE_NAME, n.OSM_ID from "
                + t + "_NODE$ n order by n.NODE_ID")) {
            s.setFetchSize(5000);
            try (ResultSet r = s.executeQuery()) {
                while (r.next())
                    kn.add(new Netz.Knoten(r.getInt(1), r.getDouble(2), r.getDouble(3), r.getDouble(4), nz(r.getString(5)), nz(r.getString(6)), nz(r.getString(7))));
            }
        }
        List<Netz.Kante> ka = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("select l.LINK_ID, l.START_NODE_ID, l.END_NODE_ID, l.COST, l.ZEIT_S, l.ART, l.LINK_NAME, l.BIDIRECTED, l.BELAG, l.LICHT, "
                + "l.BREITE, l.ZUGANG, l.EBENE, l.BRUECKE, l.TEMPO, l.OSM_ID, l.GEOMETRY.SDO_ORDINATES from " + t + "_LINK$ l order by l.LINK_ID")) {
            s.setFetchSize(2000);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    Array arr = r.getArray(17);
                    Object[] o = (Object[]) arr.getArray();
                    double[] p = new double[o.length];
                    for (int i = 0; i < o.length; i++) p[i] = ((BigDecimal) o[i]).doubleValue();
                    arr.free();
                    ka.add(new Netz.Kante(r.getInt(1), r.getInt(2), r.getInt(3), r.getDouble(4), r.getDouble(5), nz(r.getString(6)), nz(r.getString(7)),
                            "N".equals(r.getString(8)), nz(r.getString(9)), nz(r.getString(10)), r.getDouble(11), nz(r.getString(12)), r.getInt(13),
                            "J".equals(r.getString(14)), r.getDouble(15), nz(r.getString(16)), p));
                }
            }
        }
        return new Netz(a, kn, ka);
    }

    static String nz(String s) { return s == null ? "" : s; }
}
