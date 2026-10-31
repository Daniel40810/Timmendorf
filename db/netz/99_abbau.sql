-- ============================================================================
-- Timmendorf · Schema TIMMENDORF Teil 1 · 99: Abbau (als TIMMENDORF)
-- Löscht Netze, Sichten und Tabellen von Teil 1. ant netzdb ruft es vor dem
-- Neuaufbau auf; Fehler für Dinge, die es noch nicht gibt, werden übergangen.
-- Seit Phase 17 nur noch Teil 1: die Sichten TD_V_NET_…, TD_V_HALTESTELLE und TD_V_SEEZEICHEN und die Metadaten
-- von TD_POI und den Netzen; Teil 2 (db/sql) bleibt stehen.
-- ============================================================================
DECLARE
    TYPE t_namen IS TABLE OF VARCHAR2(30);
    netze t_namen := t_namen('TD_NET_FUSS', 'TD_NET_STRASSE', 'TD_NET_BUS', 'TD_NET_SEE');
    PROCEDURE still(s VARCHAR2) IS BEGIN EXECUTE IMMEDIATE s; EXCEPTION WHEN OTHERS THEN NULL; END;
BEGIN
    FOR i IN 1 .. netze.COUNT LOOP
        BEGIN SDO_NET.DROP_NETWORK(netze(i)); EXCEPTION WHEN OTHERS THEN NULL; END;
        still('DROP TABLE ' || netze(i) || '_PART$ PURGE');
        still('DROP TABLE ' || netze(i) || '_PBLOB$ PURGE');
        DELETE FROM USER_SDO_GEOM_METADATA WHERE TABLE_NAME LIKE netze(i) || '\_%' ESCAPE '\';
    END LOOP;
    FOR v IN (SELECT VIEW_NAME FROM USER_VIEWS WHERE VIEW_NAME LIKE 'TD\_V\_NET\_%' ESCAPE '\'
              OR VIEW_NAME IN ('TD_V_HALTESTELLE', 'TD_V_SEEZEICHEN')) LOOP
        still('DROP VIEW ' || v.VIEW_NAME);
    END LOOP;
    still('DROP TABLE TD_POI PURGE');
    still('DROP TABLE TD_NETZ_STAND PURGE');
    still('DROP TABLE TD_QUELLE PURGE');
    still('DROP SEQUENCE TD_POI_SEQ');
    still('DROP SEQUENCE TD_NETZ_STAND_SEQ');
    still('DROP SEQUENCE TD_QUELLE_SEQ');
    DELETE FROM USER_SDO_GEOM_METADATA WHERE TABLE_NAME IN ('TD_POI', 'TD_V_HALTESTELLE', 'TD_V_SEEZEICHEN')
        OR TABLE_NAME LIKE 'TD\_V\_NET\_%' ESCAPE '\';
    COMMIT;
END;
/
