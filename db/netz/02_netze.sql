-- ============================================================================
-- Timmendorf · Schema TIMMENDORF Teil 1 · 02: die vier Netze (als TIMMENDORF)
-- Werkbuch Abschnitt 8: Network Data Model, gerichtet, Gegenrichtung über
-- BIDIRECTED (Einbahn = 'N'), COST = Länge in m. NODE_ID und LINK_ID sind die
-- Nummern aus daten/netz. Geometrie mit Höhe (F34 A): Knoten 3001, Kanten 3002.
-- ============================================================================
DECLARE
    TYPE t_namen IS TABLE OF VARCHAR2(30);
    netze t_namen := t_namen('TD_NET_FUSS', 'TD_NET_STRASSE', 'TD_NET_BUS', 'TD_NET_SEE');
    PROCEDURE ddl(s VARCHAR2) IS BEGIN EXECUTE IMMEDIATE s; END;
    PROCEDURE spalte(t VARCHAR2, s VARCHAR2) IS
    BEGIN
        EXECUTE IMMEDIATE 'ALTER TABLE ' || t || ' ADD (' || s || ')';
    EXCEPTION WHEN OTHERS THEN
        IF SQLCODE NOT IN (-1430) THEN RAISE; END IF;   -- Spalte gibt es schon
    END;
BEGIN
    FOR i IN 1 .. netze.COUNT LOOP
        SDO_NET.CREATE_SDO_NETWORK(netze(i), 1, TRUE, FALSE);
        -- Knoten
        ddl('ALTER TABLE ' || netze(i) || '_NODE$ MODIFY (NODE_NAME VARCHAR2(200))');
        spalte(netze(i) || '_NODE$', 'HOEHE NUMBER(8,2)');
        spalte(netze(i) || '_NODE$', 'ART VARCHAR2(30)');
        spalte(netze(i) || '_NODE$', 'OSM_ID VARCHAR2(40)');
        -- Kanten
        ddl('ALTER TABLE ' || netze(i) || '_LINK$ MODIFY (LINK_NAME VARCHAR2(200))');
        spalte(netze(i) || '_LINK$', 'BIDIRECTED VARCHAR2(1)');
        spalte(netze(i) || '_LINK$', 'ZEIT_S NUMBER(12,2)');
        spalte(netze(i) || '_LINK$', 'ART VARCHAR2(30)');
        spalte(netze(i) || '_LINK$', 'BELAG VARCHAR2(60)');
        spalte(netze(i) || '_LINK$', 'LICHT VARCHAR2(20)');
        spalte(netze(i) || '_LINK$', 'BREITE NUMBER(6,2)');
        spalte(netze(i) || '_LINK$', 'ZUGANG VARCHAR2(60)');
        spalte(netze(i) || '_LINK$', 'EBENE NUMBER(3)');
        spalte(netze(i) || '_LINK$', 'BRUECKE VARCHAR2(1)');
        spalte(netze(i) || '_LINK$', 'TEMPO NUMBER(6,1)');
        spalte(netze(i) || '_LINK$', 'OSM_ID VARCHAR2(40)');
        ddl('COMMENT ON TABLE ' || netze(i) || '_LINK$ IS ''Timmendorf: Kanten aus daten/netz (Overpass 30.10.2026, (c) OpenStreetMap-Mitwirkende, ODbL); COST = Laenge in m''');
        -- Raum (Index erst nach dem Laden, 04_nach_dem_laden.sql)
        DELETE FROM USER_SDO_GEOM_METADATA WHERE TABLE_NAME IN (netze(i) || '_NODE$', netze(i) || '_LINK$', netze(i) || '_PATH$');
        -- auch die Pfadtabelle braucht Raum-Metadaten (VALIDATE_NETWORK: "path geom metadata missing", Befund Phase 4)
        INSERT INTO USER_SDO_GEOM_METADATA (TABLE_NAME, COLUMN_NAME, DIMINFO, SRID) VALUES (netze(i) || '_PATH$', 'GEOMETRY',
            SDO_DIM_ARRAY(SDO_DIM_ELEMENT('X', 560000, 680000, 0.005), SDO_DIM_ELEMENT('Y', 5930000, 6040000, 0.005),
                          SDO_DIM_ELEMENT('Z', -100, 300, 0.005)), 25832);
        INSERT INTO USER_SDO_GEOM_METADATA (TABLE_NAME, COLUMN_NAME, DIMINFO, SRID) VALUES (netze(i) || '_NODE$', 'GEOMETRY',
            SDO_DIM_ARRAY(SDO_DIM_ELEMENT('X', 560000, 680000, 0.005), SDO_DIM_ELEMENT('Y', 5930000, 6040000, 0.005),
                          SDO_DIM_ELEMENT('Z', -100, 300, 0.005)), 25832);
        INSERT INTO USER_SDO_GEOM_METADATA (TABLE_NAME, COLUMN_NAME, DIMINFO, SRID) VALUES (netze(i) || '_LINK$', 'GEOMETRY',
            SDO_DIM_ARRAY(SDO_DIM_ELEMENT('X', 560000, 680000, 0.005), SDO_DIM_ELEMENT('Y', 5930000, 6040000, 0.005),
                          SDO_DIM_ELEMENT('Z', -100, 300, 0.005)), 25832);
    END LOOP;
    COMMIT;
END;
/
