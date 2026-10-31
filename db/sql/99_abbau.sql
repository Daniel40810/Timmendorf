-- =====================================================================================================
-- Timmendorf · Datenbank Teil 2 · 99_abbau.sql
-- ACHTUNG: Entfernt alle Objekte von Teil 2 samt Inhalt (Sitzungen, Protokoll, eigene Regiebücher, Ansichten,
-- Presets, Ergebnisse der Regatten, Abbrüche). Die App führt dieses Skript NIE aus (F48 A); es ist nur für einen
-- Neuanfang von Hand gedacht (SQL Developer, F5). Teil 1 (TD_QUELLE, TD_POI, TD_NETZ_STAND, die Netze und ihre
-- Sichten) bleibt stehen. Danach richtet „Einrichten“ oder ant dbeinrichten alles neu ein.
-- =====================================================================================================

DECLARE
  TYPE t_namen IS TABLE OF VARCHAR2(30);
  tabellen t_namen := t_namen('TD_ABFAHRT', 'TD_LINIE', 'TD_SEKTOR', 'TD_LEUCHTFEUER', 'TD_ABBRUCH', 'TD_KLIFFKANTE',
      'TD_ZUENDPLAN_SCHRITT', 'TD_ZUENDPLAN', 'TD_EFFEKT', 'TD_REGATTA_ERGEBNIS', 'TD_REGATTA_TONNE', 'TD_REGATTA', 'TD_BOOT',
      'TD_POLARE', 'TD_BOOT_KLASSE', 'TD_PAKET_WERT', 'TD_PAKET_PRESET', 'TD_STUETZPUNKT', 'TD_FAHRT', 'TD_ANSICHT',
      'TD_REGIELAUF', 'TD_UEBERNAHME', 'TD_LOG', 'TD_SITZUNG', 'TD_STURMFLUT_PEGEL', 'TD_STURMFLUT', 'TD_STICHWORT',
      'TD_REGIEBUCH', 'TD_BEFUND_QUELLE', 'TD_BEFUND', 'TD_SCHEMA');
  sichten t_namen := t_namen('TD_V_ANSICHT', 'TD_V_SITZUNG_ORT', 'TD_V_REGATTA_BAHN', 'TD_V_ZUENDPLAN', 'TD_V_KLIFFKANTE',
      'TD_V_ABBRUCH', 'TD_V_FAHRT', 'TD_V_LETZTE_SITZUNG', 'TD_V_BEFUND', 'TD_V_REGIELAUF');
  PROCEDURE still(s VARCHAR2) IS BEGIN EXECUTE IMMEDIATE s; EXCEPTION WHEN OTHERS THEN NULL; END;
BEGIN
  FOR i IN 1 .. sichten.COUNT LOOP
    still('DROP VIEW ' || sichten(i));
    DELETE FROM USER_SDO_GEOM_METADATA WHERE TABLE_NAME = sichten(i);
  END LOOP;
  FOR i IN 1 .. tabellen.COUNT LOOP
    still('DROP TABLE ' || tabellen(i) || ' CASCADE CONSTRAINTS PURGE');
    still('DROP SEQUENCE ' || tabellen(i) || '_SEQ');
    DELETE FROM USER_SDO_GEOM_METADATA WHERE TABLE_NAME = tabellen(i);
  END LOOP;
  COMMIT;
END;
/

-- Ende 99_abbau.sql
