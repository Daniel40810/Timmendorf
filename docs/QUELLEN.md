# Timmendorf – Quellen

Was die Szene als belegt, gemessen oder gestaltet [G] führt, steht im Werkbuch (Artefakt „Timmendorf Werkbuch“, Abschnitte 3 und 18). Diese Datei nennt die Rohdaten und wie sie in `daten/` kommen. Stand: Phase 2 (30.10.2026).

## Rohdaten (C:\Daten\Timmendorf, nur für `com.dan.timmendorf.tools`)

| Rohdatei | Stand | Lizenz | Werkzeug | Ergebnis in `daten/` |
|---|---|---|---|---|
| DGM1 Schleswig-Holstein, XYZ 1 m (`DGM1/`, 158 Kacheln; die 30 von 2005 gelten nicht) | 2022/2023 | © GeoBasis-DE/LVermGeo SH, dl-de/by-2-0 | `GelaendeImport`, `Dgm1` | `gelaende/kern.r16`, `hoehe.r16`, `fern.r16`, `quelle.u8` |
| bDOM, GeoTIFF 20 cm (`bDOM/`, 150 Kacheln) | 2023 | © GeoBasis-DE/LVermGeo SH, dl-de/by-2-0 | `BewuchsImport` | `kern/bewuchs.u8`, `gelaende/bewuchs_haupt.u8` |
| DOP20 RGBI, GeoTIFF 20 cm (`DOP20/`, 150 Kacheln) | 2025 | © GeoBasis-DE/LVermGeo SH, dl-de/by-2-0 | `LuftbildImport` | `kern/luftbild.rgbi`, `kern/klasse.u8`, `kern/farben.properties`, `gelaende/art.u8`, `gelaende/luftbild_haupt.rgb` |
| LOD2, CityGML 1.0 (`LOD2/`, 111 Kacheln) | 2023 ff. | © GeoBasis-DE/LVermGeo SH, dl-de/by-2-0 | `BautenImport` | `bauten/bauten.bin`, `bauten.properties` |
| ALKIS, NAS-Bestandsdatenauszug je Gemarkung (`ALKIS/`, 61 Dateien) | 2025/2026 | © GeoBasis-DE/LVermGeo SH, dl-de/by-2-0 | `AlkisImport` | `alkis/nutzung_kern.u8`, `nutzung_haupt.u8`, `objekte.txt` |
| GEBCO 2026 Grid, 15″ (`GEBCO_….zip`; der Download vom 30.10. reicht nur bis 10,90° O, besser N 54,5 · S 53,5 · W 10,0 · O 11,7 für den Fernring) | 2026 | GEBCO Compilation Group (2026), frei mit Namensnennung | `Gebco` | Bucht ab 900 m vom Land, Fernring |
| BSH HRDTM „1850 Lübeck approach“, 1/256′ (`1850_hrdtm_luebeck_approach.emo.zip`) | 2023 | BSH via EMODnet Bathymetry, CC BY 4.0 | `Bsh` | Grund der Travemündung (das Raster beginnt bei 10,876° O) |
| OpenStreetMap, Overpass-Export (`Overpass/`, 21 GeoJSON-Ebenen) | 30.10.2026 | © OpenStreetMap-Mitwirkende, ODbL | von Hand (Phase 1), `NetzImport` (Phase 3), `BauwerkeImport` (Phase 9: Ebenen 03, 12, 14, 18) | Küstenlinie `scene/kueste_osm.txt` (im Jar); `netz/fuss_…`, `strasse_…`, `bus_…`, `see_…` (Knoten und Kanten), `netz/poi.tsv`, `netz/pruefwege.tsv` |

## Gestaltet [G]

- Strandprofil unter Wasser bis 900 m vom Land: Vorstrand 1:40, Abfall nach H∞ = 19 m und L = 2,4 km, zwei Sandriffe 70 und 160 m vor dem Strand (im DOP20 zu sehen, Lage noch nicht gemessen).
- Grund des Hemmelsdorfer Sees bis 4,5 m, Trave bis 9,5 m, Hafenbecken bis 3,5 m (das DGM1 kennt nur die Oberfläche).
- Netze: Deck der Seebrücken 2,6 m NHN, Tempo ohne `maxspeed` nach der Straßenart, Gehen 1,34 m/s, Fahrt 5 kn, Fahrwasser ab 1,8 m Tiefe, Regattabahn Luv-Lee auf 045° (Lee 1,2 km, Luv 2,9 km vor dem Landende).
- Land ohne DGM1-Kachel außerhalb von GEBCO und der Fernring jenseits von GEBCO: die Bühne von Phase 1.
- Seit Phase 8 (Boote): Wege von Optimist, Segelschule, Fahrtenyachten, Schoner, Hobies und Kitern; Weg der Fähren vom Skandinavienkai durch Trave und Fahrwasser nach Nordost (10 kn im Fluss, 18 kn draußen), Liegezeiten; Kurs der Passat am Kai (340°); Böen ±18 %, ±6°; Patzer der Segelschule im Mittel alle 25 min je Boot.

- Seit Phase 9 (Bauwerke): Deck der Stege in Häfen 1,4 m NHN; Geländer 1,05 m, Pfähle alle 4 m, Laternen alle 24 m (Seebrücken) und 25 m (Promenade), 4,2 m hoch, Leuchte 3 000 cd/m²; Teehaus Wolkenlos 18 × 13 m mit Pagodendach in zwei Stufen (nach Wolkenlos1–2 und DOP20); Seebrücke Niendorf nach dem DOP20 (in OSM nicht als Seebrücke); Kutter und ihre Liegeplätze nach dem DOP20; Turm der DLRG (Stelzen 2,6 m, weiße Hütte, rotes Zeltdach); Alter Leuchtturm als bauchiger Backsteinturm mit Umgang auf 21,6 m und Kupferhaube; Strandkörbe in drei Reihen 16 bis 25 m hinter der Küstenlinie, alle 3,2 m, Felder von 37 m mit 8 m Gang, 62 % blau-weiß; Saison 15. April bis 30. September; rote Sektorgrenzen des Leuchtfeuers Travemünde (160–205°); Hafenfeuer Niendorf (F R/F G, 3 sm, 5 m); Kennung des Alten Leuchtturms bis 1972 (F W, 16 sm); Farben des Geschiebemergels am Brodtener Ufer und seine Festigkeit (`fracture`).

- Seit Phase 10 (Menschen und Verkehr): Ziele und Gewichte der Spaziergänger, Verweildauern; Zahl nach Stunde und Monat (Nachmittag im Hochsommer 1 200 auf den Wegen, nachts 2 %, im Winter 12 %); Badegäste je Korb (Strandtag × 55 %, jeder zweite Korb mit zwei), Handtücher, am Wasser; zwölf auf der Kaimauer; Kleidung; Autos (bis 400, parken 1 bis 10 min) und die Buslinie Scharbeutz–Niendorf (Linienweg nach den Haltestellen längs der Küste, 40 km/h, alle 30 min von 6 bis 22 Uhr – nicht der echte Fahrplan).

- Seit Phase 14 (Sturmflut 2023): die Stundenwerte des Pegels zwischen den belegten Ankern (Glättung, Scheitel um 23 Uhr MESZ), das Nachschwingen (26 h, 0,25 m), der Wind an der Bucht (bis 22 m/s aus 85°, belegt sind knapp 30 m/s an der Marienleuchte), Bedeckung und Schauer; Zeitpunkte, Längen (15–50 m) und Rückgang (1–5 m) der Abbrüche am Brodtener Ufer und die Form der Sturzkegel; Brecher an den Pfählen ab 0,8 m signifikanter Wellenhöhe.
- Seit Phase 13 (Wetter, Jahr, Leben): die elf Lagen der Bucht (Wind, Wolken, Regen, Sicht, Nebel, Temperatur), Streichlängen nach Richtung (Nordost 40 km, Ost bis Südost 12 km, vom Land 1,5 km, Nordwest 20 km), Klima des Jahrs wie an der Schlei (DWD-Mittel, gewürfeltes Jahr), Eis vom Rand her nach dem Modell des Noors, Reviere und Zahl der Möwen, Schwäne, Kormorane und Enten, Seehund etwa eine Stunde von acht.
- Seit Phase 12 (der Abend): der Anlass – Feuerwerk am Samstag, 1. August 2026, 22:45, vom Ponton 350 m vor der Seebrücke – ist erfunden; Mond und Sonne sind gerechnet (`com.dan.sky`). Leuchten: alle LED 4 000 K (nach Daniel weiß), Laternen 2 500 lm rundum, Straßenleuchten 5 000 lm in 6 m alle 32 m wechselseitig an Straßen bis 1,5 km hinter der Küste; Fenster halb Ferienwohnung, halb Wohnhaus; Zuschauer: 37 Plätze, 30 bis 45 min Verweilen, bis 700 am Strand, Andrang ab 50 min vorher.
- Seit Phase 11 (Feuerwerk und Lichter, nur Renderer): Lichtstärken der Sterne (farbig 1 500 cd, Weiß 6 000 cd, Gold 400 cd), Brenndauern, Breiten und Gewichte der Banden, Rauch (Diffusion 0,6 m²/s, Auftrieb 0,25 m/s); Lichtstärkeverteilungen der Leuchten, Dämmerungsschalter 35/70 lx, Lichtausbeuten und Anlaufzeiten, Tagesgänge der Fenster und ihre Leuchtdichten, Albedo und Rauigkeit nasser Flächen.

| Wofür | Quelle |
|---|---|
| Gehgeschwindigkeit und Dichte | Weidmann, U. (1993): Transporttechnik der Fussgänger, ETH Zürich, IVT Schriftenreihe 90 (v₀ = 1,34 m/s, σ = 0,26 m/s, ρ_max = 5,4 P/m², γ = 1,913 m⁻²) |
| Verkehrsfluss | Greenshields, B. D. (1935): A Study of Traffic Capacity, Proc. Highway Research Board 14 |

## Quellen der Bauwerke (seit Phase 9)

| Was | Quelle |
|---|---|
| Leuchtfeuer Travemünde im Maritim: Fl WR 4s, 114,7 m, 19/15 sm, seit 1974 | Wikipedia „Maritim Travemünde“; visit-luebeck.com „Old lighthouse“ |
| Alter Leuchtturm Travemünde: 1539, Backstein, 31 m, bis 1972 in Betrieb | visit-luebeck.com; OSM (Höhe, Baujahr) |
| Nordermole Oc RG 4s, 12,3 m, 5,5 sm, Sektoren; Leitfeuer Iso WRG 4s; gelbe Festfeuer | OpenStreetMap `seamark:light:*` (Verzeichnis C 1362 ff.) |
| Seebrücken, Stege, DLRG | OpenStreetMap (Overpass 30.10.2026) |

## Literatur der Physik (seit Phase 7 und 8)

| Wofür | Quelle |
|---|---|
| Polaren J/70 | ORC One-Design-Zertifikat J/70 (data.orc.org, `j70.od` 2019) |
| Vergleich der Jollen | RYA Portsmouth Numbers 2025 (Optimist 1640, ILCA 7 1100, 420 1107) |
| Segelkräfte, Gaffel und Rahsegel | C. A. Marchaj, *Aero-Hydrodynamics of Sailing*, 1979; Beiwerte nach dem Verlauf bei Hazen 1980 [G] |
| Reibung | ITTC 1957 |
| Kite | M. L. Loyd, „Crosswind Kite Power“, *J. Energy* 4 (1980); G. M. Dadd et al., „Comparison of two kite force models …“, *J. Aircraft* 48 (2011) [Beiwerte G] |
| Wegerecht | Wettfahrtregeln Segeln 2025–2028, Regeln 10 bis 12 (vereinfacht) |
| Kelvin-Muster, stationäre Phase | J. Lighthill, *Waves in Fluids*, 1978, Kap. 3.10; Lord Kelvin 1887 |
| Engerer Keil bei hoher Froude-Zahl | M. Rabaud, F. Moisy, „Ship wakes: Kelvin or Mach angle?“, *Phys. Rev. Lett.* 110 (2013) |

## Quellen von Feuerwerk und Lichtern (seit Phase 11)

| Was | Quelle |
|---|---|
| Steighöhe und Bildgröße nach Kaliber, Ballistik der Bomben | Kosanke, K. L., Kosanke, B. J. (1990): Pyrotechnic Ballistics; Praxisregel „100 ft Steighöhe, 70 ft Bild je Zoll“ |
| Banden der Farbsalze (SrCl, CaCl, BaCl, CuCl, Na-D) | Kosanke, K. L., Kosanke, B. J. u. a. (2004): Pyrotechnic Chemistry; Shimizu, T. (1981): Fireworks – The Art, Science and Technique |
| Normspektralwerte CIE 1931 (analytische Näherung) | Wyman, C., Sloan, P.-P., Shirley, P. (2013): Simple Analytic Approximations to the CIE XYZ Color Matching Functions, JCGT 2(2) |
| Farbtemperatur aus x, y | McCamy, C. S. (1992): Correlated color temperature as an explicit function of chromaticity coordinates, Color Res. Appl. 17 |
| Beleuchtung von Straßen (Klasse P3) | DIN EN 13201-2:2016 |
| Mikrofacetten GGX, Smith-Abschattung | Walter, B. u. a. (2007): Microfacet Models for Refraction through Rough Surfaces, EGSR; Heitz, E. (2014): Understanding the Masking-Shadowing Function, JCGT 3(2) |
| Rauigkeit des Meeres aus dem Wind | Cox, C., Munk, W. (1954): Measurement of the Roughness of the Sea Surface from Photographs of the Sun's Glitter, JOSA 44 |
| Nasse Flächen werden dunkler | Lekner, J., Dorf, M. C. (1988): Why some things are darker when wet, Applied Optics 27 |
| Tagesablauf (Schlafen, Aufstehen) | Statistisches Bundesamt: Zeitverwendungserhebung 2012/13 |

## Quellen von Wetter und Jahr (seit Phase 13)

| Was | Quelle |
|---|---|
| Seegang bei begrenzter Streichlänge | Hasselmann, K. u. a. (1973): Measurements of wind-wave growth and swell decay during the Joint North Sea Wave Project (JONSWAP), Dt. Hydrogr. Z. A8(12) |
| Monatsmittel Travemünde/Lübeck | Deutscher Wetterdienst, Klimadaten Deutschland 1991–2020 (gerundet) |

## Quellen der Sturmflut 2023 (seit Phase 14)

| Was | Quelle |
|---|---|
| Ablauf, Scheitel Travemünde 1,77 m NHN, Dauern über 1,25/1,50/1,75 m (Lübeck-Bauhof), Wind, Nachschwingen | Nöthel, H. (2024): Ablauf der Ostseesturmflut vom Oktober 2023, Die Küste 94, BAW, https://izw.baw.de/publikationen/die-kueste/0/09_KFKI_DieKueste_k094109_Ablauf_Ostseesturmflut_Noethel_A.pdf |
| Wasserstände der Pegel, Füllung der Ostsee | BSH: Schwere Sturmflut an der Ostsee vom 20. Oktober 2023, https://www.bsh.de/DE/THEMEN/Wasserstand_und_Gezeiten/Sturmfluten/_Anlagen/Downloads/Ostsee_Sturmflut_20231020.pdf |
| Travemünde: höchster Stand seit 1979 | travemuende-aktuell.de: Letztes Sturmhochwasser in dieser Höhe vor 44 Jahren |
| Überblick, Schäden an der Lübecker Bucht | Wikipedia: Ostseesturmhochwasser 2023 |
| Rückgang des Brodtener Ufers im Mittel | Landesamt für Umwelt Schleswig-Holstein (Küstenschutz) |

## Quellen des Klangs (seit Phase 15)

Alles erzeugt, keine Aufnahmen (F33 A). Was nach Gehör und Beschreibungen gebaut ist, trägt [G]; die Pegel der Klänge stehen in `Renderer/src/com/dan/soundscape/README.md`.

| Was | Quelle |
|---|---|
| Fährhorn: 70 bis 200 Hz, mindestens 143 dB in 1 m für Schiffe ab 200 m | Kollisionsverhütungsregeln (KVR/COLREGs), Anlage III, Abschnitt 1 |
| Langer Ton beim Ablegen: 4 bis 6 s | KVR Regel 32 (Begriff „langer Ton“), Regel 34 (Manöver- und Warnsignale) |
| Startverfahren mit Schallsignalen 5–4–1–0 | Wettfahrtregeln Segeln (World Sailing), Regel 26 |
| Zerlegung: Dauer der N-Welle wächst mit der dritten Wurzel der Ladung | Ähnlichkeitsgesetz für Druckwellen nach Hopkinson und Cranz |
| Energie des Seegangs je m Wellenkamm P = ρ g² H² T / 64π | lineare Wellentheorie (Tiefwasser), etwa Holthuijsen (2007), Waves in Oceanic and Coastal Waters |
| Anteil der Brandung, der als Schall abstrahlt (3·10⁻⁷) | aus Sylt Phase 14 [G] |
| Masten in Niendorf (120) und im Passathafen (300), Fallen je Mast und s, Flaggen, Schritte, Stimmen | [G] |
