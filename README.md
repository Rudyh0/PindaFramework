# PindaFramework

Het all-in-one framework voor de server: core-functies, economy, shops, world control en backpacks in één plugin. Elke module wordt stap voor stap toegevoegd.

## Downloaden

Bij elke wijziging bouwt GitHub automatisch een nieuwe jar. Je vindt de nieuwste versie onder **[Releases](../../releases)** (bijv. `PindaFramework-0.1.0-b3.jar`). Het buildnummer zie je ingame met `/pinda`.

## Installeren

- Server: **Paper of Purpur 26.1 of nieuwer** (Java 25)
- Zet de jar in de map `plugins/` en start de server
- Bij de eerste start downloadt Paper eenmalig de SQLite-driver

## Wat zit erin

Fundament (modules, taal NL/EN, database, menu's), setup bij eerste join, `/instellingen`, tips, teleports (homes, TPA, spawn, back), privéberichten, `/gm`, AFK, handige commando's, vanish/invsee, economy (contant + bank), shops met marktplaats, sloten met partners, een eigen rangensysteem, moderatie, skills, slapen met een percentage, bomen in één keer omhakken, toplijsten met een scoreboard, automatische aankondigingen, antilag, een eigen MOTD, Discord-webhooks, PlaceholderAPI-placeholders en een webpaneel waarin je alles kunt instellen.

## To-do

1. ~~Rangensysteem (Pinda, PindaMod, PindaAdmin) met prefix in chat en tablist~~ ✅
2. ~~Moderatie: kick, mute, tijdelijke en permanente ban, banlijst en logboek~~ ✅
3. ~~Webpaneel: eigen webserver (IP + poort, later instelbare URL), eenmalige inloglink vanaf PindaMod, dashboard, spelers en rangen, moderatie, economy, shops, serverbeheer, statistieken, actielog~~ ✅
4. ~~Overige basis: automatische broadcasts, scoreboard en leaderboards (+ PlaceholderAPI), antilag~~ ✅
5. World control: creepers en TNT zonder blokschade, phantoms uit, spawnrates, ender dragon respawn
6. Backpack: tweede inventory die bij doodgaan blijft liggen
7. ~~Skills: level 0-99 (RuneScape-curve), geld bij level-up, menu met voortgang~~ ✅
8. ~~Bomen kappen (timber), naar het voorbeeld van UltimateTimber: hele boom om, valanimatie, sapling terugplanten, geen random loot~~ ✅
9. Live gaan: testopties uit, installatiehandleiding Ubuntu-VPS, automatische back-ups

## Rangen

PindaFramework heeft een eigen rangensysteem; LuckPerms is niet nodig. De rangen staan in `modules/ranks.yml`:

| Rang | Kleur | Prefix | Wat extra |
|---|---|---|---|
| **Pinda** | paars | `[Pinda]` | alle gewone commando's |
| **PindaMod** | blauw | `[Mod]` | geen teleport-wachttijd, socialspy, vanish, invsee (bekijken), `/fly`, `/heal`, `/feed`, `/gms`, `/gmsp`, `/tp`, CoreProtect inspecteren, moderatie, webpaneel (bekijken en straffen) |
| **PindaAdmin** | goud | `[Admin]` | alles (operator) |

- Rang geven: `/rank set <speler> <rang>` (ook vanuit de console). Je eigen rang: `/rank`. Alle rangen: `/rank list`.
- Wie al operator is, wordt bij de eerste join automatisch PindaAdmin. Spelers zonder operator-rang verliezen hun operator-status.
- In `ranks.yml` kun je ook permissies van andere plugins zetten (bijv. `worldedit.*` of `coreprotect.rollback`).

## Skills

Acht skills zoals in RuneScape: **Mijnbouw, Houthakken, Vissen, Vechten, Koken, Landbouw, Boogschieten en Alchemie**. Elke skill gaat van level 0 tot 99 en elk level is ongeveer 10% duurder dan het vorige.

| Skill | XP voor |
|---|---|
| Mijnbouw | steen en ertsen (zeldzamer = meer XP) |
| Houthakken | stammen en paddenstoelblokken |
| Vissen | vis, schatten en rommel |
| Vechten | mobs verslaan met zwaard, bijl of hand |
| Koken | gebakken eten uit een oven/smoker halen, eten craften |
| Landbouw | volgroeide gewassen, meloenen/pompoenen/suikerriet, bessen plukken, dieren fokken |
| Boogschieten | mobs verslaan met boog of kruisboog (extra XP van ver) |
| Alchemie | drankjes brouwen |

- Bij elke **level-up** krijg je **contant geld** (oplopend per level, extra bij 50, 75 en 99). Level 50, 75 en 99 worden aan de hele server gemeld.
- Bij elke XP zie je een **melding boven je hotbar**; uit te zetten in `/instellingen`.
- **Tegen misbruik:** zelf neergezette blokken en steen uit een generator geven geen XP, mobs uit spawners maar een kwart, en geen XP in creative.
- `/skills` toont je voortgang, `/skills top` de ranglijst. Admins geven een **XP-boost** voor iedereen met `/skills boost 2 1u`.
- Alle XP-waarden, de curve en de beloningen staan in `modules/skills.yml`. Met de standaardinstellingen is level 50 een paar uur spelen en is 99 een echte prestatie.

## Slapen

Als genoeg spelers in een wereld slapen (standaard **50%**), wordt de nacht of het onweer overgeslagen: de tijd spoelt in een paar seconden door en het weer klaart op. Iedereen in de wereld ziet wie er slaapt en hoeveel er nog nodig zijn. AFK-spelers, staff in vanish en spelers in creative/spectator tellen niet mee. Instellen in het paneel onder **Instellingen › Slapen**, of in `modules/sleep.yml`.

## Bomen kappen

Naar het voorbeeld van UltimateTimber: hak met een **bijl** het onderste blok van een boom om en de **hele boom valt om**, weg van je. Hij kantelt echt om zijn voet en komt met een klap neer; het hout ligt waar de boom neerkwam.

- **Sapling terugplanten:** waar de boom stond komt meteen een nieuwe sapling (bij 2x2-bomen zoals dark oak vier stuks), die je de eerste seconden niet per ongeluk kapot kunt slaan.
- **Geen extra loot:** je krijgt alleen wat de blokken in Minecraft zelf opleveren (hout, en uit bladeren af en toe een sapling, stokje of appel).
- **Bouwwerken zijn veilig:** een boom heeft natuurlijke bladeren nodig (zelf geplaatste bladeren tellen niet), moet op natuurlijke grond staan, en zodra er zelf geplaatst hout of iets gebouwds (planken, trappen, glas, deuren, bordjes, ...) aan vastzit, valt er niets om. De bladeren van een boom ernaast blijven staan.
- **De bijl slijt** per blok hout (unbreaking telt mee). Gaat je bijl daardoor kapot, dan valt de boom niet om.
- Elk blok hout geeft **Houthakken-XP**, claims worden gerespecteerd en CoreProtect logt alles.
- Alle boomsoorten: eik, berk, spar, jungle, acacia, dark oak, mangrove, kers, azalea en pale oak. Aan/uit per speler in `/instellingen` of met **`/timber`**. Alles instelbaar in het paneel onder **Instellingen › Bomen kappen** (gebukt of niet, creative, wachttijd, animatie, drops in je inventory, ...).
- Nieuwe toplijst: **meeste bomen gekapt**.

## Toplijsten en scoreboard

Zeven toplijsten: **rijkste spelers** (contant + bank), **hoogste skills** (totaal level), **langst online**, **meeste spelerkills**, **meeste mobkills**, **vaakst doodgegaan** en **meeste bomen gekapt**. Speeltijd, kills en doden komen uit de statistieken van Minecraft zelf, dus ook wat spelers deden voordat PindaFramework erop stond telt mee.

- **`/top`** opent een menu met alle toplijsten en je eigen plek op elke lijst. **`/top geld`** (of `skills`, `speeltijd`, `kills`, `mobkills`, `doden`) zet een toplijst in de chat.
- **Scoreboard** rechts in beeld: wisselt elke 10 seconden naar de volgende toplijst, met de top 10 en onderaan je eigen plek. Spelers zetten het zelf uit in `/instellingen`. Teams van het gewone scoreboard (kleuren van `/team`, teams van andere plugins) worden overgenomen.
- In het paneel onder **Toplijsten** zie je alle lijsten en stel je het scoreboard in (met voorbeeld). Staff of testaccounts haal je van de lijsten met `hidden-players` in `modules/leaderboards.yml`.

## Aankondigingen

Om de zoveel minuten (standaard 10) een bericht in de chat voor iedereen, op volgorde of willekeurig. Beheer ze in het paneel onder **Aankondigingen**: toevoegen en bewerken met de teksteditor, volgorde aanpassen, meteen versturen, of een eenmalige aankondiging sturen. In een aankondiging werken `<player>`, `<online>`, `<max>`, `<server>` en `%placeholders%` van PlaceholderAPI. Klikbare acties (een commando uitvoeren als iemand klikt) kan alleen iemand met toegang tot **Teksten** toevoegen, zodat niemand via een aankondiging een admin een commando kan laten uitvoeren.

## Antilag

- **Losse items opruimen:** elke 15 minuten, met een aftelling in de chat vooraf (60, 30, 10 en 5-4-3-2-1 seconden). Waardevolle items (diamant, netherite, shulkerboxen, elytra, ...), items met een eigen naam, geld en spullen die er net liggen (bijv. na doodgaan) blijven liggen.
- **Maximum mobs per chunk** bij fokken, spawners en eieren (standaard 40 per soort, kippen 50, villagers 20, iron golems 10). Wie fokt, krijgt een melding als het vol is. Gewone mobs 's nachts tellen niet mee.
- **Ingrijpen bij lag:** zakt de TPS 15 seconden onder de 15, dan krijgt staff een melding met de drukste chunk (klik om erheen te gaan), komt er een melding in Discord en worden losse items opgeruimd. Als het weer goed gaat, komt er een melding dat het voorbij is.
- **`/lag`** laat TPS, ticktijd, chunks, entities en items zien; **`/lag chunks`** de drukste chunks; **`/lag clear [seconden|nu]`** ruimt items op.
- In het paneel onder **Prestaties**: live TPS, drukste chunks, werelden, opruimen met één klik en de belangrijkste instellingen.

## PlaceholderAPI

Optioneel. Staat [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) op de server, dan werken `%placeholders%` in de aankondigingen en op het scoreboard, en kunnen andere plugins (TAB, hologrammen, ...) deze placeholders van PindaFramework gebruiken:

| Placeholder | Wat |
|---|---|
| `%pinda_rank%`, `%pinda_rank_prefix%`, `%pinda_rank_color%` | Rang, prefix (met kleuren) en rangkleur |
| `%pinda_money%`, `%pinda_cash%`, `%pinda_bank%` | Geld (totaal, contant, bank); met `_raw` alleen het getal |
| `%pinda_skills_total%`, `%pinda_skill_<skill>%` | Totaal level, of het level in één skill (bijv. `%pinda_skill_mining%`) |
| `%pinda_playtime%`, `%pinda_kills%`, `%pinda_mobkills%`, `%pinda_deaths%` | Speeltijd, kills en doden |
| `%pinda_position_<lijst>%`, `%pinda_value_<lijst>%` | Je plek en waarde op een toplijst (`money`, `skills`, `playtime`, `kills`, `mobkills`, `deaths`, `trees`) |
| `%pinda_top_<lijst>_<plek>_name%`, `%pinda_top_<lijst>_<plek>_value%` | Wie er op een plek staat, bijv. `%pinda_top_money_1_name%` |
| `%pinda_afk%`, `%pinda_name_colored%`, `%pinda_tps%`, `%pinda_online%` | AFK (true/false), naam in de rangkleur, TPS, aantal online |

## Discord

Koppeling via **webhooks** (geen bot nodig), in te stellen in het paneel onder **Discord**:

- **Serverstatus:** één bericht in een kanaal dat zichzelf elke minuut bijwerkt met online/offline, aantal spelers, TPS, versie, sinds wanneer de server aan staat en wie er online is.
- **Staffmeldingen:** bans, mutes, kicks, waarschuwingen, unbans, rangwijzigingen, inloggen op het paneel en (optioneel) alle paneelacties, plus server gestart/gestopt en lag (lage TPS) met de drukste chunk.

Webhook maken: in Discord bij het kanaal **Kanaal bewerken › Integraties › Webhooks › Nieuwe webhook › Webhook-URL kopiëren**, en in het paneel plakken. Met de knop **Testbericht sturen** zie je meteen of het werkt.

## Alles via het paneel

Het idee: PindaFramework installeren en daarna alles in de browser inrichten.

- **Instellingen:** elk configbestand als formulier met schakelaars, getallen en lijsten, met de uitleg uit het bestand erbij. Opslaan herlaadt de module meteen.
- **Teksten:** elke melding, elk menu en elke tip, in elke taal. Met knoppen voor themakleuren, Minecraft-kleuren, eigen kleur, verloop, vet/cursief/onderstreept en placeholders, en een voorbeeld van hoe het er in Minecraft uitziet. Onder **Uiterlijk** pas je de servernaam, de prefix en de themakleuren aan.
- **Server:** tijd, weer, mededelingen, whitelist, MOTD (met voorbeeld zoals in de serverlijst), `server.properties` (wat kan, werkt meteen) en spelregels per wereld.
- **Rangen:** rangen maken en bewerken, met een lijst van alle bekende permissies om uit te kiezen.
- **Toplijsten, Aankondigingen en Prestaties:** alle toplijsten en het scoreboard, de automatische aankondigingen en de antilag.

## Webpaneel

Een eigen beheerwebsite, ingebouwd in de plugin (geen extra software nodig).

1. Typ in-game **`/panel`** (vanaf PindaMod). Je krijgt een link in de chat. De link werkt **één keer** en is 5 minuten geldig.
2. **Tweestapsverificatie (verplicht):** de eerste keer scan je een QR-code met een authenticator-app naar keuze (Google Authenticator, Microsoft Authenticator, Authy, Bitwarden, …). Daarna vul je bij elke login de code van 6 cijfers uit de app in. Na 5 foute codes vervalt de link.
3. Na 60 minuten zonder activiteit (of maximaal 12 uur) log je vanzelf uit. `/panel logout` logt je overal uit.
4. **Telefoon kwijt?** Een admin reset de 2FA met `/panel 2fa reset <speler>` (ook vanuit de console) of via het spelersprofiel in het paneel.

**Wat kan er in?**

| Onderdeel | Wat | Permissie | Wie |
|---|---|---|---|
| Dashboard | online, TPS, geheugen, uptime, grafiek van 24 uur, werelden | `pinda.panel.use` | Mod |
| Spelers | zoeken, profiel (live positie, saldo, straffen, shop, transacties) | `pinda.panel.players` | Mod |
| Straffen | kicken, waarschuwen, muten, bannen en opheffen (zelfde regels als in-game) | `pinda.panel.moderate` | Mod |
| Economie | totalen, rijkste spelers, alle transacties | `pinda.panel.economy.view` | Mod |
| Saldo aanpassen | geven, afnemen, instellen (bank of contant) | `pinda.panel.economy.edit` | Admin |
| Rangen | rang geven (alleen lager dan je eigen rang, behalve operators) | `pinda.panel.ranks` | Admin |
| Shops | alle shops en hun aanbod bekijken | `pinda.panel.shops` | Mod |
| Skills | ranglijsten en levels per speler | `pinda.panel.skills` | Mod |
| Skills aanpassen | levels zetten, XP geven, resetten, XP-boost starten | `pinda.panel.skills.edit` | Admin |
| Toplijsten | alle toplijsten; het scoreboard instellen kan met `pinda.panel.config` | `pinda.panel.players` | Mod |
| Aankondigingen | automatische aankondigingen beheren en meteen versturen | `pinda.panel.broadcasts` | Admin |
| Prestaties | TPS, drukste chunks, werelden, items opruimen; antilag instellen met `pinda.panel.config` | `pinda.panel.server` | Admin |
| Instellingen | alle configbestanden als formulier, MOTD, server.properties, spelregels per wereld, Discord | `pinda.panel.config` | Admin |
| Teksten | alle meldingen, menu's en tips bewerken met kleuren, verloop en opmaak (met voorbeeld), servernaam, prefix en themakleuren | `pinda.panel.texts` | Admin |
| Rangen bewerken | rangen maken, kleuren, prefix, gewicht, erven en permissies, chatopmaak, standaardrang | `pinda.panel.ranks.edit` | Admin |
| Speleracties | spelmodus, healen, eten, vliegen, naar spawn, teleporteren, items geven, bericht sturen, inventory en enderkist bekijken en items weghalen, homes verwijderen | `pinda.panel.players.manage` | Admin |
| 2FA resetten | de authenticator van iemand anders ontkoppelen | `pinda.panel.security` | Admin |
| Shop sluiten | een shop dichtzetten | `pinda.panel.shops.manage` | Admin |
| Server | tijd, weer, mededeling, whitelist, opslaan, `/pinda reload` | `pinda.panel.server` | Admin |
| Server stoppen | | `pinda.panel.stop` | Admin |
| Console | live meelezen en commando's uitvoeren | `pinda.panel.console` | Admin |
| Logboek | wie wat deed in het paneel, actieve sessies | `pinda.panel.log` | Admin |

**Bereikbaar maken** (`modules/panel.yml`):

- **Laptop/thuis:** niets instellen. De link gebruikt automatisch het IP-adres van je laptop in je netwerk, bijvoorbeeld `http://192.168.1.20:8085`. Werkt op elk apparaat in hetzelfde netwerk.
- **VPS:** zet `public-url: "http://<ip-van-je-vps>:8085"` en open de poort: `sudo ufw allow 8085/tcp`.
- **Eigen adres met https (aanrader als je live gaat):** zet nginx of Caddy ervoor, zet `public-url: "https://panel.jouwdomein.nl"`, `bind: "127.0.0.1"` en `behind-proxy: true`. Met Caddy is dat één regel: `panel.jouwdomein.nl { reverse_proxy 127.0.0.1:8085 }`.

**Veiligheid:** inloglinks zijn lang en willekeurig en werken maar één keer; ze verdwijnen direct uit je adresbalk. Het paneel controleert bij elke actie opnieuw je rang, dus wie een lagere rang krijgt, verliest meteen zijn rechten. Wie geen operator is, kan alleen iets doen bij zichzelf of bij spelers met een lagere rang, alleen rangen onder zijn eigen rang beheren, en alleen permissies weggeven die hij zelf heeft. De standaardrang, de operator-instellingen en de instellingen van het paneel zelf kan alleen een operator aanpassen. Elke actie komt in het logboek én in de serverconsole. Zonder https gaat het verkeer onversleuteld over het netwerk: prima thuis, maar gebruik https zodra het paneel via internet bereikbaar is.

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
| `/panel [logout]` | `/paneel`, `/webpanel` | `pinda.panel.use` | Mod |
| `/panel 2fa reset <speler>` | | `pinda.panel.security` | op |
| `/skills [speler]`, `/skills top [skill]` | `/skill`, `/vaardigheden`, `/levels` | `pinda.skills.use` (+ `.others`) | iedereen |
| `/skills set\|addxp\|reset <speler> ...`, `/skills boost <x> <duur>\|stop` | | `pinda.skills.admin` | op |
| `/top [lijst]` | `/toplijst`, `/leaderboard`, `/lb` | `pinda.top.use` | iedereen |
| `/timber` | `/bomenkappen`, `/treefeller` | `pinda.timber.use` | iedereen |
| `/lag`, `/lag chunks` | `/antilag` | `pinda.antilag.use` | Mod |
| `/lag clear [seconden\|nu]`, `/lag tp <wereld> <x> <z>` | | `pinda.antilag.admin` | op |

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
| `pinda.sleep.exempt` | Telt niet mee bij het aantal spelers dat moet slapen | niemand |
| `pinda.antilag.notify` | Een melding krijgen als de server laggt | Mod |
| `pinda.timber.bypass-cooldown` | Geen wachttijd tussen twee bomen | op |

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
    ├── moderation.yml  max tempban voor mods, straffen openbaar of alleen staff
    ├── skills.yml      XP per blok/mob/item, levelcurve, geld per level-up, meldingen
    ├── sleep.yml       percentage slapers, doorspoelen, meldingen
    ├── timber.yml      bomen kappen: wanneer, bijl, terugplanten, drops, animatie, boomsoorten
    ├── motd.yml        de MOTD in de serverlijst (meerdere varianten)
    ├── leaderboards.yml welke toplijsten, hoe vaak bijwerken, verborgen spelers
    ├── scoreboard.yml  het scoreboard: wisselen, plekken, eigen plek, werelden
    ├── broadcasts.yml  automatische aankondigingen en hoe vaak
    ├── antilag.yml     items opruimen, maximum mobs per chunk, ingrijpen bij lag
    ├── discord.yml     webhooks voor het statusbericht en staffmeldingen
    └── panel.yml       webpaneel: poort, adres (public-url), hoe lang inloggen geldig is
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
