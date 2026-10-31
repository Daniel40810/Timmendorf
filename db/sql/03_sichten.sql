-- =====================================================================================================
-- Timmendorf · Datenbank Teil 2 · 03_sichten.sql
-- Zehn Sichten (F49 A). Mit Geometrie in UTM 32N (SRID 25832), für QGIS:
--   TD_V_ANSICHT        Standorte der Ansichten (feste, Blicke der Regie, gemerkte) mit Blickrichtung
--   TD_V_SITZUNG_ORT    wo das Auge am Ende jeder Sitzung stand, mit Szenenzeit und Lage
--   TD_V_REGATTA_BAHN   Startschiff, Pin, Luvtonne und Lee-Tor jeder Regatta
--   TD_V_ZUENDPLAN      der Ponton jedes Zündplans mit Zahl der Bomben und Dauer
--   TD_V_KLIFFKANTE     die Oberkante des Brodtener Ufers: DGM1 und nach jedem Abbruch einer Sitzung
--   TD_V_ABBRUCH        die abgebrochenen Flächen mit Stunde, Rückgang und Volumen
--   TD_V_FAHRT          die Kamerafahrten als Linie
-- Ohne Geometrie: TD_V_LETZTE_SITZUNG (für „Weiter wie zuletzt“), TD_V_BEFUND (Befunde mit Belegen),
-- TD_V_REGIELAUF (Läufe der Regiebücher mit Buch und Sitzung).
-- Die Sichten von Teil 1 (TD_V_NET_…, TD_V_HALTESTELLE, TD_V_SEEZEICHEN) stehen in db/netz/05_sichten.sql.
-- CREATE OR REPLACE: beliebig oft wiederholbar.
-- =====================================================================================================

CREATE OR REPLACE VIEW TD_V_ANSICHT AS
SELECT a.ANSICHT_ID,
       a.NAME,
       a.ART,
       a.TASTE,
       a.HOEHE,
       a.AUGE_UEBER_GRUND,
       a.GIER_GRAD,
       a.NEIGUNG_GRAD,
       a.MIT_UHR,
       a.SZENE_ZEIT,
       a.LAGE,
       a.HERKUNFT,
       a.GEOMETRY
FROM TD_ANSICHT a
WHERE a.GEOMETRY IS NOT NULL;

CREATE OR REPLACE VIEW TD_V_SITZUNG_ORT AS
SELECT s.SITZUNG_ID,
       s.BEGINN,
       s.ENDE,
       s.SAUBER_BEENDET,
       s.APP_VERSION,
       s.QUALITAET,
       s.SZENE_ZEIT,
       s.LAGE,
       s.PEGEL_M,
       s.KAMERA_HOEHE,
       r.NAME AS REGIEBUCH,
       s.KAMERA AS GEOMETRY
FROM TD_SITZUNG s
LEFT JOIN TD_REGIEBUCH r ON r.REGIEBUCH_ID = s.REGIEBUCH_ID
WHERE s.KAMERA IS NOT NULL;

CREATE OR REPLACE VIEW TD_V_REGATTA_BAHN AS
SELECT t.REGATTA_TONNE_ID,
       r.NAME AS REGATTA,
       r.DATUM,
       t.NR,
       t.ROLLE,
       r.RICHTUNG_GRAD,
       r.LAENGE_M,
       t.GEOMETRY
FROM TD_REGATTA_TONNE t
JOIN TD_REGATTA r ON r.REGATTA_ID = t.REGATTA_ID
WHERE t.GEOMETRY IS NOT NULL;

CREATE OR REPLACE VIEW TD_V_ZUENDPLAN AS
SELECT z.ZUENDPLAN_ID,
       z.NAME,
       z.DATUM,
       z.BEGINN,
       z.DAUER_S,
       z.BOMBEN,
       (SELECT MAX(s.KALIBER_MM) FROM TD_ZUENDPLAN_SCHRITT s WHERE s.ZUENDPLAN_ID = z.ZUENDPLAN_ID) AS GROESSTES_KALIBER_MM,
       z.HERKUNFT,
       z.GEOMETRY
FROM TD_ZUENDPLAN z
WHERE z.GEOMETRY IS NOT NULL;

CREATE OR REPLACE VIEW TD_V_KLIFFKANTE AS
SELECT k.KLIFFKANTE_ID,
       k.STAND,
       k.ART,
       k.SITZUNG_ID,
       k.ABBRUCH_ID,
       k.LAENGE_M,
       ROUND(MDSYS.SDO_GEOM.SDO_LENGTH(k.GEOMETRY, 0.005), 1) AS LAENGE_GERECHNET_M,
       k.HERKUNFT,
       k.GEOMETRY
FROM TD_KLIFFKANTE k
WHERE k.GEOMETRY IS NOT NULL;

CREATE OR REPLACE VIEW TD_V_ABBRUCH AS
SELECT a.ABBRUCH_ID,
       a.SITZUNG_ID,
       f.NAME AS STURMFLUT,
       a.NR,
       a.STUNDE,
       a.SZENE_ZEIT,
       a.LAENGE_M,
       a.RUECKGANG_M,
       a.VOLUMEN_M3,
       ROUND(MDSYS.SDO_GEOM.SDO_AREA(a.GEOMETRY, 0.005), 1) AS FLAECHE_M2,
       a.GEOMETRY
FROM TD_ABBRUCH a
LEFT JOIN TD_STURMFLUT f ON f.STURMFLUT_ID = a.STURMFLUT_ID
WHERE a.GEOMETRY IS NOT NULL;

CREATE OR REPLACE VIEW TD_V_FAHRT AS
SELECT f.FAHRT_ID,
       f.NAME,
       f.ART,
       f.DAUER_S,
       f.LAENGE_M,
       f.BESCHREIBUNG,
       f.HERKUNFT,
       f.GEOMETRY
FROM TD_FAHRT f
WHERE f.GEOMETRY IS NOT NULL;

-- Die letzte Sitzung mit Zustand (für „Weiter wie zuletzt“ und zum Nachsehen in SQL Developer).
CREATE OR REPLACE VIEW TD_V_LETZTE_SITZUNG AS
SELECT * FROM (
  SELECT s.SITZUNG_ID,
         s.BEGINN,
         s.ENDE,
         s.SAUBER_BEENDET,
         s.APP_VERSION,
         s.QUALITAET,
         s.SZENE_ZEIT,
         s.ZEITRAFFER,
         s.LAGE,
         s.PEGEL_M,
         s.WIND_MS,
         s.WIND_AUS_GRAD,
         f.NAME AS STURMFLUT,
         r.NAME AS REGIEBUCH,
         s.REGIE_SEKUNDE,
         s.ZUSTAND_ZEIT,
         DBMS_LOB.GETLENGTH(s.ZUSTAND) AS ZUSTAND_ZEICHEN,
         DBMS_LOB.GETLENGTH(s.VORSCHAU) AS VORSCHAU_BYTE
  FROM TD_SITZUNG s
  LEFT JOIN TD_STURMFLUT f ON f.STURMFLUT_ID = s.STURMFLUT_ID
  LEFT JOIN TD_REGIEBUCH r ON r.REGIEBUCH_ID = s.REGIEBUCH_ID
  WHERE s.ZUSTAND IS NOT NULL
  ORDER BY s.ZUSTAND_ZEIT DESC NULLS LAST, s.SITZUNG_ID DESC
) WHERE ROWNUM = 1;

-- Die Befunde mit ihren Belegen in einer Zeile.
CREATE OR REPLACE VIEW TD_V_BEFUND AS
SELECT b.BEFUND_ID,
       b.NR,
       b.PHASE,
       b.THEMA,
       b.ART,
       b.STATUS,
       b.BEHOBEN_IN,
       b.COMMIT_ID,
       b.TEXT,
       (SELECT LISTAGG(q.QUELLE, '; ') WITHIN GROUP (ORDER BY q.QUELLE) FROM TD_BEFUND_QUELLE q WHERE q.BEFUND_ID = b.BEFUND_ID) AS BELEGE
FROM TD_BEFUND b;

-- Die Läufe der Regiebücher mit Buch und Sitzung.
CREATE OR REPLACE VIEW TD_V_REGIELAUF AS
SELECT l.REGIELAUF_ID,
       l.NAME,
       r.HERKUNFT AS BUCH_HERKUNFT,
       l.SITZUNG_ID,
       s.APP_VERSION,
       l.BEGINN,
       l.ENDE,
       ROUND((CAST(l.ENDE AS DATE) - CAST(l.BEGINN AS DATE)) * 86400) AS DAUER_S,
       l.VOLLSTAENDIG,
       l.IM_KINO,
       l.GEWARTET_S,
       l.AUSGEWICHEN,
       l.FOTOS,
       l.BILDER_S,
       l.GPU_MS,
       l.QUALITAET,
       l.BLATT
FROM TD_REGIELAUF l
LEFT JOIN TD_REGIEBUCH r ON r.REGIEBUCH_ID = l.REGIEBUCH_ID
LEFT JOIN TD_SITZUNG s ON s.SITZUNG_ID = l.SITZUNG_ID;

-- Ende 03_sichten.sql
