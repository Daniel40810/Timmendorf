-- ============================================================================
-- Timmendorf · Schema TIMMENDORF Teil 1 · 04: nach dem Laden (als TIMMENDORF)
-- Räumliche Indizes, Partitionen und Partition-BLOBs für die LOD-Analyse
-- (F35 A, oracle.spatial.network.lod), Prüfung der Netze. Protokoll der
-- Partitionierung in DATA_PUMP_DIR (td_netz.log), Recht aus 00_sys.sql.
-- ============================================================================
DECLARE
    TYPE t_namen IS TABLE OF VARCHAR2(30);
    netze t_namen := t_namen('TD_NET_FUSS', 'TD_NET_STRASSE', 'TD_NET_BUS', 'TD_NET_SEE');
    n NUMBER;
BEGIN
    FOR i IN 1 .. netze.COUNT LOOP
        EXECUTE IMMEDIATE 'CREATE INDEX ' || netze(i) || '_NODE_SX ON ' || netze(i) || '_NODE$ (GEOMETRY) INDEXTYPE IS MDSYS.SPATIAL_INDEX_V2';
        EXECUTE IMMEDIATE 'CREATE INDEX ' || netze(i) || '_LINK_SX ON ' || netze(i) || '_LINK$ (GEOMETRY) INDEXTYPE IS MDSYS.SPATIAL_INDEX_V2';
        EXECUTE IMMEDIATE 'CREATE INDEX ' || netze(i) || '_LINK_S ON ' || netze(i) || '_LINK$ (START_NODE_ID)';
        EXECUTE IMMEDIATE 'CREATE INDEX ' || netze(i) || '_LINK_E ON ' || netze(i) || '_LINK$ (END_NODE_ID)';
        -- Partitionen: bis 5000 Knoten je Partition (das Seenetz ist eine)
        SDO_NET.SPATIAL_PARTITION(netze(i), netze(i) || '_PART$', 5000, 'DATA_PUMP_DIR', 'td_netz.log', 'a', 1);
        SDO_NET.GENERATE_PARTITION_BLOBS(netze(i), 1, netze(i) || '_PBLOB$', FALSE, TRUE, 'DATA_PUMP_DIR', 'td_netz.log', 'a');
        -- Namen der Tabellen in den Metadaten (falls die Prozeduren sie nicht selbst eintragen)
        UPDATE USER_SDO_NETWORK_METADATA
           SET PARTITION_TABLE_NAME = NVL(PARTITION_TABLE_NAME, netze(i) || '_PART$'),
               PARTITION_BLOB_TABLE_NAME = NVL(PARTITION_BLOB_TABLE_NAME, netze(i) || '_PBLOB$')
         WHERE NETWORK = netze(i);
        COMMIT;
    END LOOP;
END;
/
CREATE INDEX TD_POI_SX ON TD_POI (GEOMETRY) INDEXTYPE IS MDSYS.SPATIAL_INDEX_V2
/
