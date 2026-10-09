# PindaFramework

Het all-in-one framework voor de server: core-functies, economy, shops, world control en backpacks in één plugin. Elke module wordt stap voor stap toegevoegd.

## Downloaden

Bij elke wijziging bouwt GitHub automatisch een nieuwe jar. Je vindt de nieuwste versie onder **[Releases](../../releases)** (bijv. `PindaFramework-0.1.0-b3.jar`). Het buildnummer zie je ingame met `/pinda`.

## Installeren

Er zijn twee manieren:

- **Bij een Minecraft-host** (kant-en-klare server): zet de jar in de map `plugins/` en start de server. Paper of Purpur 26.1 of nieuwer (Java 25). Bij de eerste start downloadt Paper eenmalig de database-drivers. Wil je MySQL van je host gebruiken, zie [Database](#database-sqlite-of-mysql).
- **Op een eigen, kale VPS** (Ubuntu): één installer zet alles neer, inclusief het dev-paneel. Zie [Dev-paneel (PindaHost)](#dev-paneel-pindahost).

## Wat zit erin

Fundament (modules, taal NL/EN, database, menu's), setup bij eerste join, `/instellingen`, tips, teleports (homes, TPA, spawn, back), privéberichten, `/gm`, AFK, handige commando's, vanish/invsee, economy (contant + bank), shops met marktplaats, sloten met partners, een eigen rangensysteem, moderatie, skills, slapen met een percentage, een rugtas, bomen in één keer omhakken, toplijsten met een scoreboard, automatische aankondigingen, antilag, wereldbeheer (explosies zonder gaten, phantoms, spawnen, ender dragon), een eigen MOTD, Discord-webhooks, PlaceholderAPI-placeholders en een webpaneel waarin je alles kunt instellen.

## To-do

1. ~~Rangensysteem (Pinda, PindaMod, PindaAdmin) met prefix in chat en tablist~~ ✅
2. ~~Moderatie: kick, mute, tijdelijke en permanente ban, banlijst en logboek~~ ✅
3. ~~Webpaneel: eigen webserver (IP + poort, later instelbare URL), eenmalige inloglink vanaf PindaMod, dashboard, spelers en rangen, moderatie, economy, shops, serverbeheer, statistieken, actielog~~ ✅
4. ~~Overige basis: automatische broadcasts, scoreboard en leaderboards (+ PlaceholderAPI), antilag~~ ✅
5. ~~World control: creepers en TNT zonder blokschade, phantoms uit, spawnrates, ender dragon respawn~~ ✅
6. ~~Backpack: tweede inventory die bij doodgaan blijft liggen~~ ✅
7. ~~Skills: level 0-99 (RuneScape-curve), geld bij level-up, menu met voortgang~~ ✅
8. ~~Bomen kappen (timber), naar het voorbeeld van UltimateTimber: hele boom om, valanimatie, sapling terugplanten, geen random loot~~ ✅
9. Live gaan: testopties uit, installatiehandleiding Ubuntu-VPS
10. ~~Database: MySQL/MariaDB naast SQLite, omzetten van SQLite naar MySQL~~ ✅
11. Dev-paneel (PindaHost) voor een eigen VPS:
    - ~~installer, gebruikers met 2FA, setup, dashboard, databasebeheer~~ ✅
    - ~~Minecraft-server installeren (Purpur, ook oudere versies), EULA, standaardplugins, starten/stoppen, console~~ ✅
    - ~~bestanden bewerken en uploaden (server en website)~~ ✅
    - ~~website op het eigen domein, met HTTPS~~ ✅
    - ~~backups (inplannen, terugzetten, downloaden, uploaden) en dagelijkse herstart~~ ✅
    - poorten en firewall (UFW)
    - updates vanuit GitHub
    - inloggen op het staff-paneel via Discord (nooit zonder 2FA)

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

## Rugtas

Een extra inventory van 3 rijen, te openen met **`/backpack`**, `/bp`, `/rugtas`, `/rugzak`, `/rt` of `/rz`.

- Het laatste vakje is een **vaste gouden staaf** die laat zien hoeveel contant geld je bij je hebt. Die kun je er niet uit halen; klik erop voor de bank.
- **Doodgaan:** je rugtas (alles erin) en je contante geld liggen dan als zwevende rugtas op de plek waar je doodging, met je naam erboven. De eerste **2 minuten** kan alleen jij hem openen, daarna iedereen. Het geld pak je door op de gouden staaf te klikken.
- **Wie hem opent en weer sluit, laat hem in rook opgaan:** wat er nog in zat, is dan weg. Opent niemand hem, dan verdwijnt hij na **15 minuten** met alles erin.
- Staat keepInventory aan, dan houd je je rugtas en je geld.
- Staff bekijkt rugtassen met `/backpack <speler>` (ook offline) en in het paneel op het spelersprofiel, waar je ook items kunt weghalen en ziet waar een gevallen rugtas ligt.
- Instellingen in het paneel onder **Instellingen › Rugtas** (grootte, spelmodi, werelden, tijden).

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

## Wereld

Alles hieronder stel je in het paneel in onder **Wereld** (of in `modules/world.yml`), en werkt meteen:

- **Explosies zonder blokschade:** per soort aan of uit: creepers, TNT, mijnkarren met TNT, ghasts, de wither, end crystals, bedden en respawn anchors. Standaard maken creepers, TNT, ghasts en de wither geen gaten meer; spelers en mobs krijgen wel gewoon schade. TNT laat andere TNT nog wel afgaan (kanonnen blijven werken), en itemframes, schilderijen, harnasstandaarden en items op de grond blijven heel. Werelden waar alles gewoon kapot mag (bijv. een mijnwereld) zet je in een lijst.
- **Gesloop:** endermen pakken standaard geen blokken meer op. Akkers kapot springen kun je uitzetten.
- **Phantoms:** standaard uit. Je kunt spelers ook zelf laten kiezen in `/instellingen`.
- **Mobs spawnen:** hoeveel mobs er tegelijk vanzelf rondlopen, per groep in procenten van normaal (monsters, dieren, waterdieren, vissen, gloei-inktvissen, vleermuizen, axolotls). Daarnaast een kans per mob, bijv. creepers 50% of vleermuizen 0%. Spawners, eieren en fokken gaan gewoon door.
- **Ender dragon:** komt na het verslaan vanzelf terug (standaard na 2 uur). Minecraft laat de draak alleen terugkomen als er iemand bij het eiland in de End is; is daar niemand, dan gebeurt het zodra er iemand komt. Elke keer ligt er een drakenei, en iedereen hoort wie hem heeft verslagen. Met **`/dragon`** zie je hoe het met de draak gaat; **`/dragon respawn`** of de knop in het paneel laat hem meteen terugkomen.
- **Spelregels** (gamerules) per wereld, zoals keepInventory of vuur dat zich verspreidt, staan in het paneel bij **Server › Spelregels**.

## Database (SQLite of MySQL)

Standaard bewaart PindaFramework alles in één bestand: `plugins/PindaFramework/data.db` (SQLite). Daar hoef je niets voor te doen. Liever een MySQL- of MariaDB-database, bijvoorbeeld van je host? Pas dan `plugins/PindaFramework/database.yml` aan:

```yaml
type: mysql
mysql:
  host: 127.0.0.1
  port: 3306
  database: pindacraft
  user: pindacraft
  password: "je-wachtwoord"
  ssl: false          # true als de database op een andere server staat
convert-from-sqlite: true
```

- Met `convert-from-sqlite: true` zet de plugin bij de volgende start **alles** uit `data.db` over naar MySQL en controleert per tabel of alles er is. Daarna wordt het vanzelf weer `false` en blijft het oude bestand bewaard als `data.db.omgezet-<datum>`. Gaat er iets mis, dan draait de server gewoon op SQLite verder en staat de fout in de console.
- `database.yml` staat bewust **niet** in het webpaneel voor staff (er staat een wachtwoord in). Met het dev-paneel hoef je dit bestand nooit zelf aan te passen.
- De plugin gebruikt eigen tabellen (`pinda_...`) en kan dus prima een database delen met andere plugins.

## Dev-paneel (PindaHost)

Voor een eigen VPS: een los webpaneel voor developers, naast het webpaneel voor staff. Het draait als eigen dienst, dus het blijft bereikbaar als de Minecraft-server crasht.

**Installeren** op een kale Ubuntu-server (als root):

```bash
curl -fsSL https://github.com/Rudyh0/PindaFramework/releases/latest/download/install.sh | sudo bash
```

De installer zet alle software neer (Java 25, MariaDB, nginx, UFW en het dev-paneel), alles uit Ubuntu zelf (alleen Java komt van Adoptium als Ubuntu het nog niet heeft), en vraagt één ding: hoe je het paneel wilt bereiken.

- **Met een domein**, bijv. `dev.jouwdomein.nl`: HTTPS via nginx. Loopt het via **Cloudflare** met de proxy aan (oranje wolk), zet dan bij SSL/TLS de modus op **Full**: nginx heeft een eigen certificaat waarmee Cloudflare versleuteld naar je server praat. De echte IP van bezoekers wordt gewoon herkend, en poort 80 en 443 staan in de firewall alleen open voor Cloudflare. Zonder Cloudflare vraagt de installer een certificaat aan bij **Let's Encrypt** (dat wordt vanzelf verlengd); wijst het domein nog niet naar de server, draai de installer dan later nog een keer.
- **Alleen IP:poort**, bijv. `https://1.2.3.4:8443`: met een eigen certificaat (je browser waarschuwt één keer; de installer toont de vingerafdruk om te controleren).

De installer controleert de download van het dev-paneel met een controlegetal (SHA-256), houdt SSH altijd open (ook op een andere poort dan 22) en werkt ook op een VPS zonder IPv6. Aan het eind krijg je een **setupcode**. Open het paneel, vul de code in en maak de eerste beheerder, met verplichte **2FA** (authenticator-app). Daarna loop je de setup door: servernaam, adres voor spelers, **SQLite of MySQL**, en precies welke **DNS-records** je moet maken (A-records en het **SRV-record** voor `play.jouwdomein.nl`). Het adres voor spelers moet in Cloudflare op *DNS only* (grijze wolk): Minecraft-verkeer kan niet door de proxy.

Wat er nu in zit:

- **Dashboard**: draait de Minecraft-server, database, webserver, firewall? Plus processor, geheugen, schijf en hoe lang de VPS al aan staat.
- **Server**: installeert **Purpur** (standaard de nieuwste stabiele versie; een oudere of nieuwere versie en build kiezen kan ook). De download wordt gecontroleerd met het controlegetal van Purpur. De server draait als eigen dienst (`pinda-minecraft`) onder de gebruiker `minecraft`, nooit als root, en start na een crash vanzelf opnieuw.
  - Bij de **eerste start** accepteer je de **Minecraft EULA** in een venster. Daarna zet het paneel **PindaFramework** (van GitHub) en de nieuwste versies van **ViaVersion, PlaceholderAPI, CoreProtect en WorldEdit** (van Modrinth, voor jouw Minecraft-versie) in `plugins/`, en start de server. De rangen van PindaFramework regelen de rechten: PindaAdmin is operator en mag alles (ook WorldEdit), PindaMod mag met CoreProtect inspecteren, opzoeken en teleporteren.
  - **Console**: alles uit `logs/latest.log` live, en opdrachten sturen (via RCON, alleen vanaf de VPS zelf, over één vaste verbinding; ook lange antwoorden zoals `help` komen heel aan). Spelers online, geheugen en uptime staan erboven.
  - **Plugins**: alle plugins met versie, aan/uit zetten, verwijderen, en de standaardplugins met één knop bijwerken.
  - **Instellingen**: hoeveel geheugen de server krijgt (met de vlaggen van Aikar), en overstappen naar een andere versie of build (alleen `server.jar` wordt vervangen).
- **Bestanden**: een bestandsbrowser zoals in Pterodactyl voor de hele servermap. Bladeren, bestanden bewerken in een editor met kleuren (YAML, properties, JSON, …; Ctrl+S om op te slaan), uploaden met slepen (ook hele mappen, in stukken van 16 MB zodat het via Cloudflare werkt), downloaden (mappen als zip), hernoemen, verplaatsen, inpakken als zip en uitpakken (.zip, .tar, .tar.gz), verwijderen. Het paneel blijft altijd binnen de map: symlinks of trucs van een plugin brengen het nooit naar andere bestanden op de VPS.
- **Website**: een eigen bestandsbrowser voor de website (`/opt/pinda/website`). Alles wat je daar uploadt, staat meteen online op het domein van de website; `index.html` is de voorpagina. Via Cloudflare werkt HTTPS meteen; zonder Cloudflare vraag je met één knop een certificaat aan bij Let's Encrypt.
- **Backups**: de servermap, de website en alle databases samen in één zip. Elke nacht automatisch (vaste tijden of elke paar uur, per dag van de week), of nu met één knop en een notitie. De server kan blijven draaien: het paneel zet opslaan even uit (`save-off`, `save-all flush`) zodat de wereld heel in de backup komt. Cache, libraries, versions en logs gaan niet mee (instelbaar).
  - **Bewaren**: van de automatische backups blijven de nieuwste (standaard 7); handmatige, geüploade en vastgezette backups blijven altijd staan. Vastzetten kan met het punaise-icoon.
  - **Downloaden** (ook hervatten) en een gedownloade backup weer **uploaden**, bijvoorbeeld naar een nieuwe VPS.
  - **Terugzetten** per onderdeel: servermap, website en/of elke database apart. Standaard eerst een backup van de huidige stand. De server wordt netjes gestopt en daarna weer gestart. Lukt het uitpakken niet, dan staat de oude map er gewoon weer; een kapotte database-dump zet de vorige inhoud terug.
  - **Dagelijkse herstart** op een vaste tijd (en dagen), met meldingen voor spelers 5 minuten, 1 minuut en 10 seconden vooraf, en eventueel eerst een backup. Staat de server uit, dan blijft hij uit.
- **Databases** (MariaDB): databases en gebruikers aanmaken en verwijderen, wachtwoorden en toegang, downloaden als `.sql` en `.sql` inladen. PindaFramework met één knop **omzetten naar MySQL** (de server wordt herstart en zet alles over). Krijgt de plugin geen verbinding meer, dan geeft de sleutel bij zijn gebruiker een nieuw wachtwoord dat meteen in `database.yml` komt. MariaDB is alleen vanaf de VPS zelf bereikbaar. Een `.sql` inladen gebeurt met een tijdelijke gebruiker die alleen bij die ene database kan, dus een bestand kan nooit bij andere databases of bij de server zelf.
- **Gebruikers**: beheerders en developers, met een tijdelijk wachtwoord en verplichte 2FA bij de eerste keer inloggen. Uitzetten, 2FA of wachtwoord resetten. Bij 2FA resetten krijgt de gebruiker ook een nieuw tijdelijk wachtwoord, zodat iemand met alleen het oude wachtwoord nooit 2FA kan omzeilen.

| | Beheerder | Developer |
|---|---|---|
| Dashboard en logboek bekijken | ✓ | ✓ |
| Databases bekijken, aanmaken en downloaden | ✓ | ✓ |
| Databases verwijderen of `.sql` inladen | ✓ | |
| Databasegebruikers, wachtwoorden en toegang | ✓ | |
| PindaFramework omzetten naar MySQL | ✓ | |
| Server starten, stoppen, console, plugins, geheugen | ✓ | ✓ |
| Bestanden van de server en de website | ✓ | ✓ |
| Server installeren of van versie wisselen | ✓ | |
| HTTPS-certificaat voor de website aanvragen | ✓ | |
| Backups maken, downloaden en vastzetten | ✓ | ✓ |
| Backups terugzetten, uploaden en verwijderen; de planning | ✓ | |
| Gebruikers van het dev-paneel beheren | ✓ | |

Een developer kan dus alles wat de server zelf kan: plugins en bestanden neerzetten en aanpassen. Geef die rol alleen aan mensen die je daarmee vertrouwt.
- **Logboek**: wie deed wat en vanaf welk IP.

Op de server zelf (als root):

| Opdracht | Wat |
|---|---|
| `pinda-host setup-code` | De setupcode voor de eerste beheerder |
| `pinda-host reset-2fa <naam>` | 2FA van een gebruiker resetten (telefoon kwijt); geeft ook een tijdelijk wachtwoord |
| `pinda-host reset-password <naam>` | Tijdelijk wachtwoord geven |
| `pinda-host fingerprint` | Vingerafdruk van het eigen certificaat (zonder domein) |
| `journalctl -u pinda-host -f` | Meekijken wat het dev-paneel doet |

Alles staat in `/opt/pinda`: `server/` (Minecraft), `website/`, `backups/` en `panel/` (instellingen, gebruikers, logboek). Opnieuw installeren of bijwerken: draai de installer nog een keer, je instellingen en gebruikers blijven staan.

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
- **Wereld:** explosies, phantoms, hoeveel mobs er spawnen en de ender dragon (status, terug laten komen).

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
| Rugtas | de rugtas van een speler bekijken (ook offline) en zien waar een gevallen rugtas ligt; items weghalen met `pinda.panel.players.manage` | `pinda.panel.players` | Mod |
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
| `/backpack` | `/bp`, `/rugtas`, `/rugzak`, `/rt`, `/rz` | `pinda.backpack.use` | iedereen |
| `/backpack <speler>` | | `pinda.backpack.others` (+ `.edit` om aan te passen) | Mod (bekijken) / op |
| `/lag`, `/lag chunks` | `/antilag` | `pinda.antilag.use` | Mod |
| `/lag clear [seconden\|nu]`, `/lag tp <wereld> <x> <z>` | | `pinda.antilag.admin` | op |
| `/dragon [respawn]` | `/draak`, `/enderdragon` | `pinda.world.dragon` | op |

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
├── database.yml        SQLite of MySQL (niet in het staff-paneel; beheerd door het dev-paneel)
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
    ├── backpack.yml    rugtas: grootte, spelmodi, gouden staaf, gevallen rugtas bij doodgaan
    ├── motd.yml        de MOTD in de serverlijst (meerdere varianten)
    ├── leaderboards.yml welke toplijsten, hoe vaak bijwerken, verborgen spelers
    ├── scoreboard.yml  het scoreboard: wisselen, plekken, eigen plek, werelden
    ├── broadcasts.yml  automatische aankondigingen en hoe vaak
    ├── antilag.yml     items opruimen, maximum mobs per chunk, ingrijpen bij lag
    ├── world.yml       explosies, endermen, akkers, phantoms, spawnen, ender dragon
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
