# PindaFramework

Het all-in-one framework voor de server: core-functies, economy, shops, world control en backpacks in één plugin. Elke module wordt stap voor stap toegevoegd.

## Downloaden

Bij elke wijziging bouwt GitHub automatisch een nieuwe jar. Je vindt de nieuwste versie onder **[Releases](../../releases)** (bijv. `PindaFramework-0.1.0-b3.jar`). Het buildnummer zie je ingame met `/pinda`.

## Installeren

- Server: **Paper of Purpur 26.1 of nieuwer** (Java 25)
- Zet de jar in de map `plugins/` en start de server
- Bij de eerste start downloadt Paper eenmalig de SQLite-driver

## Wat zit erin

Fundament (modules, taal NL/EN, database, menu's), setup bij eerste join, `/instellingen`, tips, teleports (homes, TPA, spawn, back), privéberichten, `/gm`, AFK, handige commando's, vanish/invsee, economy (contant + bank), shops met marktplaats, sloten met partners en een eigen rangensysteem.

## To-do

1. ~~Rangensysteem (Pinda, PindaMod, PindaAdmin) met prefix in chat en tablist~~ ✅
2. ~~Moderatie: kick, mute, tijdelijke en permanente ban, banlijst en logboek~~ ✅
3. Webpaneel: eigen webserver (IP + poort, later instelbare URL), eenmalige inloglink vanaf PindaMod, dashboard, spelers en rangen, moderatie, economy, shops, serverbeheer, statistieken, actielog
4. Overige basis: automatische broadcasts, scoreboard en leaderboards (+ PlaceholderAPI), antilag
5. World control: creepers en TNT zonder blokschade, phantoms uit, spawnrates, ender dragon respawn
6. Backpack: tweede inventory die bij doodgaan blijft liggen
7. Skills: level 0-99 (RuneScape-curve), geld bij tier-up, menu met voortgang
8. Bomen kappen (timber)
9. Live gaan: testopties uit, installatiehandleiding Ubuntu-VPS, automatische back-ups

## Rangen

PindaFramework heeft een eigen rangensysteem; LuckPerms is niet nodig. De rangen staan in `modules/ranks.yml`:

| Rang | Kleur | Prefix | Wat extra |
|---|---|---|---|
| **Pinda** | paars | `[Pinda]` | alle gewone commando's |
| **PindaMod** | blauw | `[Mod]` | geen teleport-wachttijd, socialspy, vanish, invsee (bekijken), `/fly`, `/heal`, `/feed`, `/gms`, `/gmsp`, `/tp`, CoreProtect inspecteren |
| **PindaAdmin** | goud | `[Admin]` | alles (operator) |

- Rang geven: `/rank set <speler> <rang>` (ook vanuit de console). Je eigen rang: `/rank`. Alle rangen: `/rank list`.
- Wie al operator is, wordt bij de eerste join automatisch PindaAdmin. Spelers zonder operator-rang verliezen hun operator-status.
- In `ranks.yml` kun je ook permissies van andere plugins zetten (bijv. `worldedit.*` of `coreprotect.rollback`).

## Commando's en permissies

| Commando | Aliassen | Permissie | Standaard |
|---|---|---|---|
| `/instellingen` | `/settings` | `pinda.settings.use` | iedereen |
| `/taal [nl\|en]` | `/language`, `/lang` | `pinda.language.use` | iedereen |
| `/pinda reload` | | `pinda.admin.reload` | op |
| `/pinda modules` | | `pinda.admin.modules` | op |
| `/pinda setup [speler]` | | `pinda.admin.setup` | op |
| `/sethome [naam]` | `/createhome` | `pinda.homes.use` | iedereen |
| `/home [naam]` | | `pinda.homes.use` | iedereen |
| `/delhome <naam>` | `/deletehome`, `/removehome` | `pinda.homes.use` | iedereen |
| `/homes` | | `pinda.homes.use` | iedereen |
| `/homes <speler>`, `/home speler:naam` | | `pinda.homes.others` | op |
| `/delhome speler:naam` | | `pinda.homes.others.delete` | op |
| `/tpa <speler>` | | `pinda.tpa.use` | iedereen |
| `/tpahere <speler>` | | `pinda.tpa.here` | iedereen |
| `/tpaccept [speler]`, `/tpdeny [speler]`, `/tpacancel` | `/tpyes`, `/tpno` | `pinda.tpa.use` | iedereen |
| `/spawn` | | `pinda.spawn.use` | iedereen |
| `/setspawn` | | `pinda.spawn.set` | op |
| `/back` | | `pinda.back.use` | iedereen |
| `/msg <speler> <bericht>` | `/tell`, `/w`, `/whisper`, `/m`, `/pm` | `pinda.msg.use` | iedereen |
| `/r <bericht>` | `/reply` | `pinda.msg.use` | iedereen |
| `/ignore [speler]` | `/unignore` | `pinda.ignore.use` | iedereen |
| `/socialspy` | `/spy` | `pinda.msg.socialspy` | op |
| `/gm <modus> [speler]` | | per modus | op |
| `/gmc`, `/gms`, `/gma`, `/gmsp` `[speler]` | | `pinda.gamemode.<modus>` | op |
| `/afk [reden]` | | `pinda.afk.use` | iedereen |
| `/fly [speler]` | | `pinda.fly` (+ `.others`) | op |
| `/heal [speler]` | | `pinda.heal` (+ `.others`) | op |
| `/feed [speler]` | `/eat` | `pinda.feed` (+ `.others`) | op |
| `/god [speler]` | `/godmode` | `pinda.god` (+ `.others`) | op |
| `/speed [lopen\|vliegen] <1-10> [speler]` | | `pinda.speed` (+ `.others`) | op |
| `/vanish [speler]` | `/v` | `pinda.vanish` (+ `.others`) | op |
| `/invsee <speler>` | | `pinda.invsee` | op |
| `/balance [speler]` | `/bal`, `/money`, `/geld`, `/saldo` | `pinda.eco.balance` (+ `.others`) | iedereen |
| `/pay <speler> <bedrag>` | `/betaal` | `pinda.eco.pay` | iedereen |
| `/bank [storten\|opnemen <bedrag\|alles>]` | | `pinda.eco.bank` | iedereen |
| `/baltop` | `/geldtop`, `/moneytop` | `pinda.eco.baltop` | iedereen |
| `/eco give\|take\|set <speler> <bedrag> [bank\|contant]` | | `pinda.eco.admin` | op |
| `/shop [markt\|beheer\|open\|sluit]` | `/winkel`, `/markt`, `/market` | `pinda.shop.use` | iedereen |
| `/partner [speler\|accept\|deny\|remove\|list]` | `/partners` | `pinda.partner.use` | iedereen |
| `/rank [info [speler]\|list]` | `/rang` | `pinda.rank.info` (+ `.others`), `pinda.rank.list` | iedereen |
| `/rank set <speler> <rang>` | | `pinda.rank.set` | op |
| `/kick <speler> [reden]` | | `pinda.mod.kick` | Mod |
| `/ban <speler> [duur] [reden]`, `/tempban <speler> <duur> [reden]` | | `pinda.mod.ban` (+ `.permanent`) | Mod (max 7d) / Admin |
| `/unban <speler>` | | `pinda.mod.unban` | Mod |
| `/mute <speler> [duur] [reden]`, `/unmute <speler>` | | `pinda.mod.mute` | Mod |
| `/warn <speler> <reden>` | `/waarschuw` | `pinda.mod.warn` | Mod |
| `/history <speler>`, `/banlist` | `/straffen`, `/bans` | `pinda.mod.history` | Mod |

Extra permissies:

| Permissie | Wat | Standaard |
|---|---|---|
| `pinda.homes.limit.<aantal>` | Meer homes, bijv. `pinda.homes.limit.10` (standaard 5, zie `modules/homes.yml`) | - |
| `pinda.homes.unlimited` | Onbeperkt homes | niemand |
| `pinda.back.death` | `/back` naar de plek waar je doodging | iedereen |
| `pinda.teleport.bypass.warmup` | Geen wachttijd | niemand |
| `pinda.teleport.bypass.cooldown` | Geen cooldown | niemand |
| `pinda.teleport.free` | Teleports altijd gratis | niemand |
| `pinda.msg.bypass` | Berichten sturen naar wie privéberichten uit heeft | op |
| `pinda.ignore.exempt` | Kan niet genegeerd worden | op |
| `pinda.gamemode.survival` / `creative` / `adventure` / `spectator` | Die spelmodus gebruiken | op |
| `pinda.gamemode.others` | Spelmodus van anderen veranderen | op |
| `pinda.afk.kick-exempt` | Nooit gekickt voor AFK | op |
| `pinda.vanish.see` | Onzichtbare staff toch zien | op |
| `pinda.invsee.modify` | Items aanpassen bij /invsee (anders alleen kijken) | op |
| `pinda.eco.keep-cash` | Geen contant geld verliezen bij doodgaan | niemand |
| `pinda.shop.sign` | Shopbord plaatsen met `[shop]` | iedereen |
| `pinda.shop.admin` | Shopborden van anderen afbreken | op |
| `pinda.lock.bypass` | Bij alle afgesloten kisten en deuren kunnen | op |

`pinda.admin` geeft alle beheerrechten. Aliassen pas je aan in `config.yml` onder `commands`, bijvoorbeeld:

```yaml
commands:
  home:
    aliases: [thuis]
```

## Bestanden

```
plugins/PindaFramework/
├── config.yml          servernaam, standaardtaal, modules aan/uit, kleuren, prefix, geluiden
├── teleport.yml        wachttijd, cooldown en kosten voor alle teleports
├── data.db             database (SQLite)
├── lang/
│   ├── nl.yml          alle Nederlandse teksten
│   └── en.yml          alle Engelse teksten
└── modules/
    ├── settings.yml    setup bij eerste join
    ├── tips.yml        interval en instellingen voor tips
    ├── homes.yml       aantal homes, geblokkeerde werelden
    ├── tpa.yml         verlooptijd, testoptie TPA naar jezelf
    ├── spawn.yml       spawnlocatie en wanneer spelers erheen gaan
    ├── back.yml        /back na doodgaan
    ├── msg.yml         privéberichten, testoptie berichten naar jezelf
    ├── afk.yml         automatisch AFK, kicken, tablist
    ├── staff.yml       vanish-instellingen
    ├── economy.yml     valuta, startbedrag, stortkosten, geld bij doodgaan, online-bonus
    ├── shop.yml        marketplace fee, belasting, shortcodes voor het shopbord
    ├── locks.yml       welke blokken op slot gaan, hoppers, bescherming, max partners
    ├── ranks.yml       rangen, kleuren, prefixes, permissies en de chatopmaak
    └── moderation.yml  max tempban voor mods, straffen openbaar of alleen staff
```

- **Teksten** gebruiken [MiniMessage](https://docs.advntr.dev/minimessage/format). Gebruik de thema-kleuren als tag: `<primary>`, `<secondary>`, `<text>`, `<muted>`, `<highlight>`, `<success>`, `<error>`, `<warning>`, `<prefix>` voor de prefix en `<server>` voor de servernaam (`server-name` in `config.yml`).
- Een bericht leegmaken (`""`) zet het uit. Begin een bericht met `[actionbar]` om het boven de hotbar te tonen.
- **Nieuwe taal toevoegen:** kopieer `lang/en.yml` naar bijvoorbeeld `lang/de.yml`, vertaal de teksten en doe `/pinda reload`. De taal verschijnt automatisch in het menu.
- **Updates:** nieuwe teksten en instellingen worden automatisch aan je bestanden toegevoegd. Teksten die je nooit hebt aangepast, krijgen bij een update ook de nieuwe standaardtekst. Teksten die je wél hebt aangepast, blijven altijd staan. Het framework houdt dat bij in de verborgen map `.defaults/`; laat die staan.

## Zelf bouwen

```
./gradlew build
```

De jar komt in `build/libs/`. Vereist Java 25.
