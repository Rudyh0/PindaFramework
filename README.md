# PindaFramework

Het all-in-one framework voor de server: core-functies, economy, shops, world control en backpacks in één plugin. Elke module wordt stap voor stap toegevoegd.

## Downloaden

Bij elke wijziging bouwt GitHub automatisch een nieuwe jar. Je vindt de nieuwste versie onder **[Releases](../../releases)** (bijv. `PindaFramework-0.1.0-b3.jar`). Het buildnummer zie je ingame met `/pinda`.

## Installeren

- Server: **Paper of Purpur 26.1 of nieuwer** (Java 25)
- Zet de jar in de map `plugins/` en start de server
- Bij de eerste start downloadt Paper eenmalig de SQLite-driver

## Wat zit erin

| Onderdeel | Status |
|---|---|
| Fundament (modules, taal, database, menu's, commando's) | ✅ |
| Setup bij eerste join + `/instellingen` + `/taal` | ✅ |
| Tips | ✅ |
| Core: homes, TPA, msg, AFK, kick/ban, broadcast, scoreboard, antilag | ⏳ |
| Economy | ⏳ |
| Shops | ⏳ |
| World control | ⏳ |
| Backpack | ⏳ |

## Commando's en permissies

| Commando | Aliassen | Permissie | Standaard |
|---|---|---|---|
| `/instellingen` | `/settings` | `pinda.settings.use` | iedereen |
| `/taal [nl\|en]` | `/language`, `/lang` | `pinda.language.use` | iedereen |
| `/pinda reload` | | `pinda.admin.reload` | op |
| `/pinda modules` | | `pinda.admin.modules` | op |
| `/pinda setup [speler]` | | `pinda.admin.setup` | op |

`pinda.admin` geeft alle beheerrechten. Aliassen pas je aan in `config.yml` onder `commands`.

## Bestanden

```
plugins/PindaFramework/
├── config.yml          standaardtaal, modules aan/uit, kleuren, prefix, geluiden
├── data.db             database (SQLite)
├── lang/
│   ├── nl.yml          alle Nederlandse teksten
│   └── en.yml          alle Engelse teksten
└── modules/
    ├── settings.yml    setup bij eerste join
    └── tips.yml        interval en instellingen voor tips
```

- **Teksten** gebruiken [MiniMessage](https://docs.advntr.dev/minimessage/format). Gebruik de thema-kleuren als tag: `<primary>`, `<secondary>`, `<text>`, `<muted>`, `<highlight>`, `<success>`, `<error>`, `<warning>`, en `<prefix>` voor de prefix.
- Een bericht leegmaken (`""`) zet het uit. Begin een bericht met `[actionbar]` om het boven de hotbar te tonen.
- **Nieuwe taal toevoegen:** kopieer `lang/en.yml` naar bijvoorbeeld `lang/de.yml`, vertaal de teksten en doe `/pinda reload`. De taal verschijnt automatisch in het menu.
- Komen er in een update nieuwe teksten of instellingen bij, dan worden die automatisch aan je bestaande bestanden toegevoegd. Jouw aanpassingen blijven staan.

## Zelf bouwen

```
./gradlew build
```

De jar komt in `build/libs/`. Vereist Java 25.
