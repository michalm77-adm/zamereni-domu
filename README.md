# Zaměření domu z fotografií

Aplikace pro zaměření rodinného domu z fotek jako podklad pro návrh hromosvodu (LPS).
Návrh architektury: [Aplikace pro zaměření domu: architektura](https://claude.ai/artifact/GxGZ4QQ4DpZF8jkxDLRVtT).

## Stav

Prototyp **režimu A** (měření v rovině terče) pro Android:

- průvodce volbou režimu při založení zakázky,
- focení hlavní kamerou nebo výběr fotky z galerie,
- detekce ChArUco terčů T1–T3 a A1–A3 (OpenCV),
- **automatické kótování**: snímek se narovná do roviny terče (pohled jako na výkrese), aplikace najde obrys zdi
  a otvory (okna, dveře) a vynese kóty ve vrstvách *Obrys*, *Otvory*, *Polohy otvorů* a *Ruční*, které lze vypínat,
- ruční kóty klepnutím na dva body, s odhadem nejistoty a varováním při extrapolaci daleko od terče,
- sdílení výsledků jako text.

Režim B (fotogrammetrie na PC) zatím není.

## Struktura

| Složka | Obsah |
| --- | --- |
| `core/` | Čisté Kotlin jádro bez Androidu: homografie, definice terčů, měření a nejistota. Testy běží na JVM. |
| `app/` | Android aplikace (Kotlin, Jetpack Compose, CameraX, OpenCV 4.10). |
| `terce/` | Tiskové PDF terčů v měřítku 1:1 a `terce_spec.json`. |
| `tools/` | Python skripty: generování terčů a testovacích dat, referenční automatické kótování (`auto_dims.py`) a syntetická fasáda (`synth_facade.py`). |

## Sestavení

- **Bez instalace:** každý push sestaví APK v GitHub Actions. Stáhnete ho v záložce *Actions* → poslední běh → *Artifacts* → `zamereni-domu-debug-apk`, rozbalíte a nainstalujete do telefonu (povolit instalaci z neznámých zdrojů).
- **Android Studio:** otevřít složku projektu a spustit konfiguraci `app`.
- **Příkazová řádka:** `./gradlew :core:test :app:assembleDebug`

Požadavky na telefon: Android 10 a novější.

## Přesnost (syntetický test)

Test `core` měří na syntetické fotce fasády 4000 × 3000 px s terčem T1 u rohu:

| Úsek | Skutečnost | Změřeno | Chyba |
| --- | --- | --- | --- |
| uvnitř terče | 0,764 m | 0,765 m | 0,1 % |
| výška 5,7 m u terče | 5,700 m | 5,812 m | 2,0 % (aplikace varuje: extrapolace 13×) |
| délka fasády 10 m | 10,000 m | 9,637 m | 3,6 % (aplikace varuje: extrapolace 23×) |

Daleko od terče chyba roste. Pro dlouhé fasády proto používejte dva terče (na každém konci) nebo foťte fasádu po částech.

## Další kroky

1. Kalibrace kamery telefonu (ChArUco deska) a odstranění zkreslení objektivu před měřením.
2. Lupa při umisťování bodů a přichytávání k hranám.
3. Měření se dvěma terči v jedné rovině (spojená homografie) pro dlouhé fasády.
4. Ukládání zakázek a export PDF protokolu.
5. Režim B: naváděný sběr snímků a odeslání do PC.
