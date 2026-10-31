-- ============================================================================
-- Timmendorf · Schema TIMMENDORF Teil 1 · 00: Rechte (als SYS AS SYSDBA)
-- Werkbuch Abschnitt 8, F35 A: die LOD-Analyse braucht Partitionen, und
-- SDO_NET.SPATIAL_PARTITION schreibt ihr Protokoll in ein Verzeichnisobjekt.
-- Wir nehmen das vorhandene DATA_PUMP_DIR der PDB.
--
-- In SQL Developer: als SYS (Rolle SYSDBA) mit dem Dienst PDBORCL verbinden
-- und das Skript laufen lassen (F5). Oder: ant netzdb -Dsys.passwort=…
-- (das Passwort wird nirgends gespeichert).
--
-- Jede Anweisung endet mit einer Zeile "/" (SQL Developer, SQL*Plus und
-- ant netzdb lesen dasselbe Format).
-- ============================================================================

-- Rechte direkt, nicht nur über die Rolle DB_CONNECT: SDO_NET legt Tabellen
-- aus PL/SQL heraus an, und dort gelten Rollen nicht.
GRANT CREATE TABLE, CREATE VIEW, CREATE SEQUENCE, CREATE TRIGGER, CREATE PROCEDURE TO TIMMENDORF
/

-- Protokoll der Partitionierung
GRANT READ, WRITE ON DIRECTORY DATA_PUMP_DIR TO TIMMENDORF
/

-- Platz im Standard-Tablespace des Schemas
DECLARE
    ts VARCHAR2(128);
BEGIN
    SELECT default_tablespace INTO ts FROM dba_users WHERE username = 'TIMMENDORF';
    EXECUTE IMMEDIATE 'ALTER USER TIMMENDORF QUOTA UNLIMITED ON ' || ts;
END;
/
