-- ============================================================================
-- Timmendorf · Schema TIMMENDORF Teil 1 · 01: Tabellen (als TIMMENDORF)
-- Werkbuch Abschnitte 8 und 12. Schlüssel aus Sequenz und Trigger, nie IDENTITY.
-- Raum: UTM 32 N (SRID 25832), Höhe über NHN, Toleranz 5 mm (F34 A: mit Höhe).
-- ============================================================================

-- ---------------------------------------------------------------- TD_QUELLE
CREATE SEQUENCE TD_QUELLE_SEQ START WITH 1 INCREMENT BY 1 NOCACHE
/
CREATE TABLE TD_QUELLE (
    QUELLE_ID    NUMBER        CONSTRAINT TD_QUELLE_PK PRIMARY KEY,
    NAME         VARCHAR2(200) NOT NULL,
    STAND        VARCHAR2(40),
    LIZENZ       VARCHAR2(400),
    WERKZEUG     VARCHAR2(100),
    ERGEBNIS     VARCHAR2(400),
    ERFASST      DATE          DEFAULT SYSDATE,
    CONSTRAINT TD_QUELLE_UK UNIQUE (NAME)
)
/
CREATE OR REPLACE TRIGGER TD_QUELLE_BI BEFORE INSERT ON TD_QUELLE FOR EACH ROW
BEGIN
    IF :NEW.QUELLE_ID IS NULL THEN :NEW.QUELLE_ID := TD_QUELLE_SEQ.NEXTVAL; END IF;
END;
/
COMMENT ON TABLE TD_QUELLE IS 'Timmendorf: Rohdaten mit Stand und Lizenz (docs/QUELLEN.md)'
/

-- ---------------------------------------------------------------- TD_POI
CREATE SEQUENCE TD_POI_SEQ START WITH 1 INCREMENT BY 1 NOCACHE
/
CREATE TABLE TD_POI (
    POI_ID       NUMBER        CONSTRAINT TD_POI_PK PRIMARY KEY,
    OSM_ID       VARCHAR2(40)  NOT NULL,
    EBENE        VARCHAR2(80),
    ART          VARCHAR2(120),
    NAME         VARCHAR2(200),
    OST          NUMBER(10,2),
    NORD         NUMBER(10,2),
    GEOMETRY     SDO_GEOMETRY,
    CONSTRAINT TD_POI_UK UNIQUE (OSM_ID)
)
/
CREATE OR REPLACE TRIGGER TD_POI_BI BEFORE INSERT ON TD_POI FOR EACH ROW
BEGIN
    IF :NEW.POI_ID IS NULL THEN :NEW.POI_ID := TD_POI_SEQ.NEXTVAL; END IF;
END;
/
COMMENT ON TABLE TD_POI IS 'Timmendorf: Orte aus den Overpass-Punktebenen (daten/netz/poi.tsv), (c) OpenStreetMap-Mitwirkende, ODbL'
/
DELETE FROM USER_SDO_GEOM_METADATA WHERE TABLE_NAME = 'TD_POI'
/
INSERT INTO USER_SDO_GEOM_METADATA (TABLE_NAME, COLUMN_NAME, DIMINFO, SRID) VALUES ('TD_POI', 'GEOMETRY',
    SDO_DIM_ARRAY(SDO_DIM_ELEMENT('X', 560000, 680000, 0.005), SDO_DIM_ELEMENT('Y', 5930000, 6040000, 0.005)), 25832)
/

-- ---------------------------------------------------------------- TD_NETZ_STAND
CREATE SEQUENCE TD_NETZ_STAND_SEQ START WITH 1 INCREMENT BY 1 NOCACHE
/
CREATE TABLE TD_NETZ_STAND (
    STAND_ID     NUMBER        CONSTRAINT TD_NETZ_STAND_PK PRIMARY KEY,
    NETZ         VARCHAR2(30)  NOT NULL,
    INHALT       VARCHAR2(200),
    KNOTEN       NUMBER,
    KANTEN       NUMBER,
    LAENGE_M     NUMBER(12,2),
    PRUEFSUMME   VARCHAR2(64),
    QUELLE       VARCHAR2(400),
    GELADEN      DATE          DEFAULT SYSDATE,
    GUELTIG      VARCHAR2(400),
    CONSTRAINT TD_NETZ_STAND_UK UNIQUE (NETZ)
)
/
CREATE OR REPLACE TRIGGER TD_NETZ_STAND_BI BEFORE INSERT ON TD_NETZ_STAND FOR EACH ROW
BEGIN
    IF :NEW.STAND_ID IS NULL THEN :NEW.STAND_ID := TD_NETZ_STAND_SEQ.NEXTVAL; END IF;
END;
/
COMMENT ON TABLE TD_NETZ_STAND IS 'Timmendorf: je Netz Zahlen, Pruefsumme der Dateien in daten/netz und Ladedatum (Oracle und Datei passen zusammen, wenn die Pruefsumme gleich ist)'
/
