# PrisonCore v2.11.4 (Paper 1.21.11)

One plugin with the core of a classic prison server: mines, A–Z ranks, prestige, selling, tokens, pickaxe enchants, crates, scoreboard and chat rank tags.

## Install
1. Drop `PrisonCore-2.11.4.jar` in `plugins/` and start the server once.
2. Edit the files in `plugins/PrisonCore/`, then run `/pa reload`.
   - `config.yml`: sell prices, tokens, scoreboard, chat, multipliers
   - `ranks.yml`: ranks A–Z, prices, prestige settings
   - `enchants.yml`: enchant costs, chances, max levels
   - `crates.yml`: crates, rewards, Key Finder odds
   - `messages.yml`: every message

Recommended alongside: **Vault**, **LuckPerms** (permissions) and **WorldEdit** (building). PrisonCore has its own money and tokens, so you don't need a separate economy plugin. If you use WorldGuard, allow `block-break` inside mine regions.

## Vault (shared money)
With **Vault** installed, PrisonCore registers itself as the server's economy. Shops, auction houses, jobs plugins and anything else built on Vault then read and change PrisonCore money. That works for offline players too. Look for `Registered PrisonCore as the Vault economy provider` in the startup log.

If you run **EssentialsX**, it has its own separate balance. Stop players using its money commands so there's only one balance. In `plugins/Essentials/config.yml`:
```yaml
disabled-commands:
  - balance
  - balancetop
  - pay
  - eco
```
Then `/bal`, `/pay` and `/money` go to PrisonCore. Tokens stay PrisonCore-only; Vault only handles money.

## Setting up a mine (example: mine A)
1. Build or clear the box where the mine goes.
2. Look at one corner and run `/mine pos1`. Look at the opposite corner and run `/mine pos2`.
3. Stand where players should arrive and run `/mine create A`. A mine named like a rank is locked to that rank automatically.
4. The block mix is filled in automatically from `config.yml > mine-presets` (A = stone/coal ... Z = netherite/sea lantern). Change it with `/mine setblocks A STONE:70 COAL_ORE:25 IRON_ORE:5` or `/mine preset A <preset>`.
5. The bedrock box (floor and walls, open top, 1-block air gap) is built automatically on `/mine create` (`mines.auto-shell`). Rebuild it with `/mine shell A`. It replaces whatever is in that space.
6. Optional: `/mine settimer A 300`, `/mine setpercent A 75`, `/mine seticon A COAL_ORE`

Mines reset on the timer **or** when the set percentage has been mined, whichever comes first. Players inside are lifted straight up onto the top of the mine (set `mines.reset-teleport: spawn` to send them to the mine spawn instead).

## Setting up crates
**Vote + Season crates (2.5):** shrines in the spawn crate plaza at (39,101,-9) vote (vault) and (39,101,9) season (ominous vault, amethyst canopy). Season crate has `effects:` (particle helix, spinning nether star, flowing gradient hologram). Vote keys: `crate givekey <player> vote 1` (hook a vote plugin to this). Season keys: /cores shop (3 for 300) and `crate givekey <player> season N`. Season 1 exclusive armor: `nebula` (Nebula Vanguard). Console placement: `/crate setat <crate> <world> <x> <y> <z>`.
**Crate filter (2.4):** left-click a crate (or `/cratefilter [crate]`, alias `/cfilter`) and click rewards to auto-delete them for yourself; filtered wins give nothing ("auto-deleted"), also on mass open. Saved per player (`crate-filters`).
1. Place a chest (or any block) at spawn.
2. Look at it and run `/crate set common` (also `rare`, `legendary`).
3. Give keys with `/crate givekey <player|all> <crate> [amount]`.

- Right-click with a key: open with the spin animation
- Shift + right-click: open every key in your hand at once
- Left-click: preview rewards and chances

## Player commands
| Command | What it does |
|---|---|
| `/rankup` (`/ru`), `/rankupmax` (`/mru`) | Rank up once / as many times as affordable |
| `/prestige` | Prestige at rank Z (asks for confirmation) |
| `/ranks` | Rank GUI |
| `/mine [name]`, `/mines` | Go to your best mine / a mine / mines GUI |
| `/sellall`, `/autosell` | Sell inventory / toggle autosell |
| `/prices` (`/worth`) | Everything sellable, with base price and your price |
| `/money` (`/bal`), `/pay` | Money |
| `/tokens`, `/tokens pay <player> <amount>` | Tokens |
| `/enchants` (`/e`) or right-click the pickaxe | Enchant upgrade GUI |
| `/pickaxe` | Get the prison pickaxe back |
| `/fly [player]` | Toggle flight, no creative needed (`prisoncore.fly`; others: `prisoncore.fly.others`; op by default). Donors: `lp group <rank> permission set prisoncore.fly true`. Remembered between logins. |
| `/bag` (`/bag sell`, `/bag deposit`) | Open, sell, or fill the ore bag from your inventory |
| `/condense` | Condense full stacks into the next tier |

## Welcome & guide arrows
- **First join:** a big WELCOME title with a line telling new players to ask in chat, plus a short chat message (`config.yml > first-join`).
- **/welcome:** when someone joins for the first time, everyone else has 60 seconds to type `/welcome` (`/wb`). Each player gets the reward once per newcomer. The reward is a list of console commands in `config.yml > welcome-reward.commands`; it's 1 Rare Key for now. Test it with `/pa firstjoin <player>`.
- **Guide arrows:** glowing arrows slide along the spawn floor from the spawn pad: purple to the Mines Hall, gold to the crates, aqua to the leaderboards (`config.yml > guides`). Each path is a list of `x,y,z` points; change them and run `/pa reload`.

## Public mines A–Z (dark mode)
`/pa setupmines [world] [spacing] [letter]` builds the 26 dark-mode mine islands that ship inside the jar (default: world `mines`, 200 blocks apart along X, floor at y=100) and creates mines A–Z on them: 51×51, 25 deep, bedrock box with an air gap, the rank's block mix, locked to the rank. Accent colour changes every 5 ranks (cyan, green, purple, orange, red). Running it again rebuilds them (pass a letter to rebuild just one).

## Mines Hall portal
`config.yml > portals`: stepping into the box runs a command as the player (default: `/mine`, their best mine).

## Pickaxe tiers
Ore Bag upgrades (2.12.1): left-click +1, right-click (or shift) +10; each upgrade shows the cost for +1 and +10 and what +10 gives.

Wooden → Stone (rank E) → Iron (rank K) → Gold (rank P) → Diamond (rank Z) → Netherite (Prestige 1), bought with tokens (50K / 400K / 2M / 8M / 25M) from the **Pickaxe Tier** button in the enchant menu. Speed never changes; each tier raises how far Fortune, Explosive, Jackhammer, Token Finder and Key Finder can be levelled (15/30/45/60/80/100% of max). `config.yml > pickaxe-tiers`.

## Enchant balance (1.9)
| Enchant | Max level | At max | Tokens to max |
|---|---|---|---|
| Fortune | 1000 | +600% drops (x7) | ~39M |
| Jackhammer | 1000 | 100% chance to break the whole layer | ~39M |
| Explosive | 1000 | 40% chance, radius 6 | ~24M |
| Token Finder | 1000 | 50% chance, x5 token finds | ~24M |
| Key Finder | 500 | 2% per block | ~10M |
Jackhammer and Explosive have a per-player cooldown (`cooldown-ms`) so a maxed pickaxe can't flood the server. The enchant menu shows Now / Next / Max for each enchant.

**Turning enchants off (2.11.5):** in `/enchants`, press **Q** on an enchant to disable it on that pickaxe, and press Q again to turn it back on. The icon turns into a barrier marked OFF and the pickaxe lore shows it struck through. Levels are kept. Disabled enchants never proc and give no bonus. Disabled Speed/Haste effects are removed, and disabling Efficiency drops the pickaxe to base mining speed.

## Private mines
`/pmine` (`/pm`): your own dark-mode mine island in world `pmines`, built the first time you use it. The block mix follows your rank. `/pmine menu`: size upgrades with tokens (21 → 27 → 33 → 41 → 51 wide), reset now, open/close to visitors, members. `/pmine invite|kick <player>`, `/pmine visit <player>`. `/pmine ranks` (or the emerald in `/pmine menu`): what every rank's mine is worth per block, with the player's multiplier, % gain over the previous rank and rankup cost. Settings: `config.yml > private-mines`; data: `private-mines.yml`. Permission `prisoncore.pmine` (everyone by default).
With `private-mines.replace-public-mines: true` (default) private mines are THE mines: `/mine`, `/mines` and the Mines Hall portal go to the player's own mine. It refills with the new rank's blocks on every rankup/prestige, and only resets while someone is nearby (idle mines refill when visited).

## 2.0 systems
- **Token Fortune** enchant (unlocks with a Stone pickaxe): multiplies every Token Finder find, up to x5. Jackhammer layers also roll Token Finder and Key Finder on a share of their blocks (`enchants.yml > jackhammer.finder-share`, 3%).
- **Cores** (premium currency, <#3ab8ff>◉): `/cores`, `/cores pay <player> <amount>`, `/cores shop` (entries in `config.yml > cores.shop`). Admin: `/pa give|take|set cores <player> <amount>`. Placeholder `%prisoncore_cores%`, scoreboard `{cores}`.
- **Armor sets** (`config.yml > gear`): Scrap Exo (common keys), Plasma Weave (rare), Quantum Aegis (legendary). Every piece rolls quality 0-100 (90+ is rare, broadcast); stats: fortune, token gain, sell, key finder, proc chance; full set bonus. `/pa givearmor <player> <set> [piece] [quality]`.
- **Pets** (`config.yml > pets`): Pet Capsules from keys / Core Shop, right-click to hatch. 6 types (Nano Drone, Quantum Fox, Plasma Wisp, Void Cat, Cyber Golem, Star Whale) x 7 rarities (Common 50% ... Celestial 0.05%, power x1 ... x100). Level 1-100 from mining; rarer pets level slower but end far stronger. `/pets` to equip, shift-click to withdraw as a tradeable item. `/pa givepet <player> [amount]` or `/pa givepet <player> hatch <rarity>`.
- **Highest-ranked board** on every private mine island.
- **Paid ranks** (`config.yml > donor-ranks` + `permission-multipliers`): Spark, Ion, Nova, Pulsar, Quasar, Nebula, Singularity. Permission `prisoncore.donor.<id>` shows the tag in chat, `prisoncore.multiplier.<id>` gives the sell bonus.
- New crate rewards (old file kept as `crates-old.yml`).

## Prestige scaling & Prestige Points
Every prestige multiplies all rankup costs and the next prestige cost by 4, and the whole sell multiplier by 2 (`ranks.yml > prestige.cost-multiplier-per-prestige / sell-multiplier-per-prestige`). First prestige: $20B.
Prestige Points: reaching prestige N gives `base + increase * (N-1)` points (10, 15, 20...). `/ppoints` (`/pp`), placeholder `%prisoncore_prestige_points%`, scoreboard `{ppoints}`, admin `/pa give|take|set ppoints <player> <n>`. Spent on Colony Clearance (below).

## Colony Clearance (`/citizen`, `/colony`, `/clearance`)
City ranks bought in order with Prestige Points (config.yml `citizenship.levels`, values are totals per level). Chat tag `{citizen}`, placeholder `%prisoncore_citizen%`, admin `/pa setcitizen <player> <level>`.

| Level | PP | Min prestige | Perks (total) |
|---|---|---|---|
| Resident | 100 | 1 | 2 active pets, +9 pet storage |
| Technician | 150 | 2 | + 10% token gain, +0.25x sell |
| Engineer | 250 | 3 | 3 pets, +15% fortune, +2 pmine invites |
| Officer | 400 | 4 | /fly in private mines, 20% token gain, +0.5x sell |
| Councilor | 600 | 5 | 4 pets, +18 storage, 30% fortune, +4 invites |
| Governor | 1000 | 7 | /fly everywhere, 5 pets, +27 storage, 45% fortune, 35% token gain, +1.0x sell, +6 invites |

Multiple active pets all follow the player and all give stats; `/pets` is paginated (45 per page).

## 2.11.4 Starter items
New players get only: Prison Pickaxe, Ore Bag, Colony Navigator and 1 Common Key (`first-join.commands`). Essentials' newbie kit is off (`plugins/Essentials/config.yml > newbies.kit: ''`), so no stone tools.

## 2.11.3 Server list
MiniMessage MOTD (`server-list.line1/line2`, gradients work) + server icon `plugins/PrisonCore/server-icon.png` (64x64, DRKMODE "D" logo; replace the file and restart to change it).

## 2.11.2 Contraband
`/contraband` (/shop, /cb): categories (Storage, Building Blocks, Stairs & Slabs, Lighting, Decor, Food & Tools) bought with money; left-click 1 bundle, shift-click 8. Items are tagged contraband (lore "can't be sold"): /sell, autosell and the bag ignore them, and blocks broken in the cells world drop tagged again. Prices in `config.yml > contraband`. Cell shop tutorial step 1 points to it.

## 2.11 New player tutorial
Starts on first join (after the WELCOME title), `/tutorial` replays, `/tutorial skip` ends: 7 steps that complete themselves - open the Navigator, `/pmine`, mine 50 blocks, open the bag, sell, open enchants, rank up. Title + chat (clickable commands) per step, action-bar hint, progress saved on the player. First completion gives 3 Common + 1 Rare key (`tutorial.reward-commands`). In the Navigator Help page too.

## 2.11 Cells & shops
World `cells` (void, `mv create cells normal -g VoidGen`), built by `/cell build`: 2 floors (A, B), corridor along X with 16 cells per side per floor (64 cells, ids A1..A32, B1..B32), each 7x7x5 inside, barred fronts with a 3-wide doorway, dark deepslate/blackstone with cyan accents, lobby with a lodestone **lift** at the west end. Writes Essentials warp `cells` (-4.5, 101, 0.5).
- `/cells` menu (my cell / shop directory / how it works), `/cell buy` (stand in or in front of a free cell; **$1T**, one per player, kept until offline `inactive-days` 30), `/cell home`, `/cell trust|untrust <p>`, `/cell name <text>`, `/cell abandon` (twice, no refund), admin `/cell build`, `/cell reset <id>`.
- Shops: look at a chest in your cell, hold the item, `/cell shop <price> [amount]`; customers right-click the chest for a buy menu (1/4/16 bundles), money goes to the owner (offline too). Floating item + price above each shop. `/cell unshop`, breaking the chest removes it. Owners right-click to restock, shift-right-click for the customer view.
- Protection in the cells world: build/containers only in your own (or trusted) cell; no PvP, pistons, fire spread, explosions, liquids leaving a cell. Admins in creative bypass.
- **Shop tutorial** after `/cell buy`: title, a Cell Shop Guide book, and 3 guided steps that advance by themselves (place a chest -> `/cell shop` -> stock it), with an action-bar hint while in the cell world and clickable commands; ends with a "shop open" summary. `/cell tutorial` replays, `/cell tutorial skip` ends it (step saved on the player).
- Door signs above each cell: FOR SALE + price, or the shop name/owner. Data: plugins/PrisonCore/cells.yml.

## 2.10.3
- Jackhammer `key-finder-share` 0.00005 (another 1/20).
- Full-bag alert while mining (`bag-full-alert`): gold "BAG FULL" action bar + sound every 2s and a title every 30s; red "ORES LOST" once the inventory is full too. Shows the autosell countdown.

## 2.10.2
- Condensing turned off (`condense.enabled: false`): the inventory no longer compacts ore, so the Ore Bag matters. Existing enchanted items still sell and can be deposited in the bag. /condense says it's off; the price list hides enchanted tiers.

## 2.10.1
- Jackhammer key rolls: `key-finder-share` 0.001 (1/10 again). Single blocks keep the normal Key Finder chance (max 2%).
- Ore Bag deposit takes condensed items as their block tier (Enchanted Coal = 64 Coal Blocks of space, same value).
- Condensed item lore shows "Contains: N x item (= N x block)" and the bag space it takes; old items are updated on join/condense.

## 2.10 Colony Navigator & auto-fly
- Everyone gets the **Colony Navigator** (custom head, hotbar slot 8, can't be dropped/placed). Right-click or `/menu` (/nav, /gui): warps (`navigator.warps` + any Essentials warp not listed is added automatically) and **Help & Features** (`navigator.help`: hover explains, click opens: ranks, enchants, pickaxe upgrade, bag, autosell, pmine, crates/filter, pets, wardrobe, clearance, prestige points, store, earnings, fly, auction, warps).
- Players who can fly (prisoncore.fly or clearance in that world) are flying when they join; flight is re-allowed when entering a world where they may fly.

## 2.9
- **Crate animation** (`crates.animation-style: world`): rewards orbit the crate, speed up, collapse, and the prize pops out above it with a burst and a "<player> won <reward>" tag everyone sees. One opening per crate at a time. `gui` = old spinning menu. `animation-tier` per crate: 1 quick shuffle (common, vote ~2s) / 2 orbit ring (rare ~4s) / 3 legendary ascension: golden pillars, items ride a turning wheel, get pulled up, lightning strike, prize descends and lands with a shockwave + title (~7s) / 4 season: purple version with double rings, 3 lightning strikes, sky beam, sonic boom (~8.5s).
- **Big leaderboards**: `leaderboards.displays` text displays (scale 1.45) on the spawn board walls replace the DecentHolograms sp_money / sp_prestige / sp_blocks (disabled with `/dh h disable`).
- **/wardrobe** (/sets): store one full set per armor type; a complete set gives token-bonus x (avg quality/100)^1.5: scrap 5%, plasma 15%, quantum 40%, nebula 75% at 100% (`gear.wardrobe`). Stored as set+piece+quality in player data.

## 2.8 balance
- Autosell is never faster than 1 minute (`autosell.donor-floor-minutes`).
- Prestige fortune: all drops x(1 + 0.5 x prestige) (`ranks.yml > prestige.fortune-per-prestige`); bag space upgrades cost x2 per prestige (`bag.yml > space.cost.prestige-multiplier`), so bags never keep up for long.
- Crates: `money-rankup: 0.03` = 3% of the next rankup/prestige cost; `scale-tokens: true` multiplies tokens by (1 + 0.15 x rank) x (1 + 0.5 x prestige) (`crates.yml > token-scaling`). Common/rare now also roll the next armor tier and more keys.
- Armor per piece at 100% (fortune / token / sell): Scrap .20/.12/.08, Plasma .50/.30/.15, Quantum 1.20/.70/.40, Nebula 1.80/1.00/.60 (+ set bonuses).

## /store (2.7)
`/store` (aliases /buy, /webshop; `/cores shop` opens it too): paid ranks for Cores + the item shop (`cores.shop`, `enabled: false` hides an entry; Legendary Keys are no longer sold). Ranks: upgrades cost only the difference; given with `store.rank-command` (`lp user %player% parent add %rank%`).
| Rank | Cores | Perks (totals) |
|---|---|---|
| Spark | 500 | +0.10x sell, +10% bag |
| Ion | 1,200 | +0.20x, +20% bag, autosell 1m faster, +5% tokens |
| Nova | 2,500 | +0.35x, /fly, +30% bag, 2m faster, +1 invite, +10% tokens |
| Pulsar | 5,000 | +0.50x, fly, +45% bag, 3m, +2 invites, +2 pet slots, +15% tokens |
| Quasar | 9,000 | +0.75x, fly, +60% bag, 4m, +3 invites, +1 pet, +20% tokens |
| Nebula | 15,000 | +1.00x, fly, +80% bag, 5m, +4 invites, +2 pets, +30% tokens |
| Singularity | 25,000 | +1.50x, fly, +100% bag, 6m, +5 invites, +2 pets, +40% tokens |
Donor autosell can't go below `autosell.donor-floor-minutes` (2). Perks: `donor-ranks` in config.yml.

## Custom heads (2.6)
Any icon can be a custom head: `head: "<texture>"` (minecraft-heads.com Value, textures.minecraft.net URL, or texture id) on crate reward items, Cores shop entries, `pets.types.<type>.head`, `pets.capsule.head`, `cores.head`; crate `effects.item: "head:<texture>"` floats a spinning head over the crate (`hologram-height` per crate). Defaults: drone/amethyst fox/wisp/galaxy cat/blue robot/cosmic whale pets, energy-core capsule, blue core for Cores, storage-component heads over common (aqua)/vote (green)/legendary (yellow), dark-matter core over rare.

## Money multipliers (2.3)
All multiply the whole sell multiplier (shown as `{multiplier}`):
- **Merchant** pickaxe enchant (tokens, slot 20): x(1 + 0.002/level), x3 at 1000; tier-capped like Fortune.
- **Bag Multiplier** (bag upgrade, tokens): x(1 + 0.04/level) on bag sales, x3 at level 50.
Fortune stays as extra drops (`enchants.yml > fortune-boosts: drops`), so it fills the bag.

## Earnings per minute
Every minute players get a chat line with the money and tokens they earned from mining and selling, plus the 5-minute average. `/earnings` (`/epm`) shows the rates, `/earnings toggle` turns the chat line off (remembered). `config.yml > earnings`.

## Warps (Essentials, `/warp <name>`)
| Warp | Where |
|---|---|
| `crates` | east plaza, facing the crate shrines (spawn 40.5, 100, 0.5) |
| `leaderboards` | west plaza, facing the leaderboard screens (spawn -44.5, 100, 0.5) |
| `mines` | inside the Mines Hall, facing the portal (spawn 0.5, 103, -41.5) |
Files: `plugins/Essentials/warps/<name>.yml`. Default group has `essentials.warp` + `essentials.warp.list`.
**Rule for this server: every new place players need to reach gets a warp as soon as it's added.**

## Admin commands (`prisoncore.admin`)
- `/pa give|take|set money|tokens <player> <amount>` (amounts like `5k`, `2.5m`, `1b` work; works on offline players)
- `/pa setrank <player> <rank>`, `/pa setprestige <player> <n>`
- `/pa enchant <player> <enchant> <level>` (they must hold the prison pickaxe)
- `/pa reload`, `/pa save`, `/pa setupmines`, `/pa firstjoin <player>`
- `/mine ...` (run `/mine help`), `/crate ...`

## Sell multiplier
`1 + rank bonus + prestige bonus + permission bonus`
- Rank: +0.02 per rank after A (`ranks.yml > sell-multiplier-per-rank`). A = x1.00, B = x1.02 ... Z = x1.50
- Prestige: +0.05 per prestige (`ranks.yml > prestige.sell-bonus-per-prestige`)
- Permission: best of `prisoncore.multiplier.<vip|mvp|elite>` (`config.yml`). Ops do **not** get these automatically; give them with LuckPerms.

## Tokens
- Mining: each block has a 50% chance to give 1 token (`config.yml > tokens`).
- Token Finder: starts at a 10% chance and grows to 30% at max level.
- Admin: `/pa give tokens <player> <amount>`.

## PlaceholderAPI
With PlaceholderAPI installed, PrisonCore adds `%prisoncore_...%` placeholders for TAB, holograms, scoreboards and chat plugins:
- Player: `rank`, `rank_prefix`, `next_rank`, `prestige`, `prestige_tag`, `money`, `money_raw`, `money_commas`, `tokens`, `tokens_raw`, `tokens_commas`, `blocks`, `blocks_raw`, `multiplier`, `rankup_cost`, `progress`, `progress_bar`, `bag`, `bag_used`, `bag_capacity`, `bag_percent`, `autosell`, `mine`
- Leaderboards (top 10, refreshed every 5 minutes): `top_<money|tokens|prestige|blocks>_<1-10>_<name|value>`, e.g. `%prisoncore_top_money_1_name%` and `%prisoncore_top_money_1_value%`

## Keep inventory
On by default (`config.yml > keep-inventory`): players keep items and XP on death.

## Ore drops & condensing
Ores drop their item, not the ore block: coal ore gives coal, iron ore an iron ingot, diamond ore a diamond (`config.yml > ore-drops`). Fortune multiplies these drops.
Every 64 of a tier automatically turns into 1 of the next, in this order: **Coal → Coal Block → Enchanted Coal → Enchanted Coal Block**. Each tier is worth 64x the one below, so condensing never gains or loses money. Players can also run `/condense`. Enchanted items can't be placed or used in crafting, and vanilla 9-item block recipes are disabled (they'd turn 9 coal into a block worth 64).

## Ore Bag
**Timed autosell (2.2):** `/autosell` no longer sells each block. Everyone with autosell on gets bag + inventory sold together on their own timer: `autosell.interval-minutes` (15) minus `minus-per-prestige` (0.5) per prestige, floor `min-minutes` (5) → P10 10m, P20 5m ( warnings at 1 min and 10 s; scoreboard `{autosell}`, PAPI `%prisoncore_autosell_next%`). Everything mined, including Jackhammer/Explosive blocks, goes bag → inventory, and is lost when both are full ("BAG FULL" action bar). Manual `/sellall` and the bag's Sell button still work any time. `interval-minutes: 0` restores the old instant autosell.
Bag space: `base * (1+growth)^level` = 10K → ~2.9M at lvl 50 → ~836M at lvl 100, bought with **money** ($100K ×1.18 per level). Bag multiplier: tokens (25K ×1.15 per level), **multiplies** bag sales by 1 + 0.04/level (x3 at level 50).
Everyone gets an Ore Bag (`/bag`, or right-click it). Everything mined goes into the bag first; when it's full, items go to the inventory. `/sellall` sells the inventory **and** the bag.
Upgrades (tokens, in the bag menu): **Space** (+1,000 per level), **Multiplier** (+0.02 per level, applies when selling from the bag), and **Autosell** (one-time unlock: the bag sells itself when full). Settings: `bag.yml`. The bag's contents are saved with the player, so bags can't be duplicated.

## Pickaxe
Everyone gets a prison pickaxe (wooden to start) on join or respawn if they don't have one in their inventory. It is never dropped on death.
Every prison pickaxe mines at the same speed whatever its material (wood, stone, iron...). Only Efficiency changes speed (`enchants.yml > efficiency > speed-per-level`):
| Efficiency | Breaks instantly |
|---|---|
| 0 (new pickaxe, about wooden pick + Efficiency II) | nothing |
| 1 | nothing (faster) |
| 2 | stone, andesite, diorite, granite |
| 3 | ores, deepslate |
| 4 | deepslate ores, ore blocks, end stone |
| 5 (max) | everything in mines, including obsidian, ancient debris and netherite blocks |

## Enchants
Efficiency, Fortune, Speed, Haste, Explosive, Jackhammer, Token Finder, Key Finder. All are bought with tokens.
- **Jackhammer** breaks the whole layer of the mine you're in.
- **Explosive** breaks a sphere (radius 2–4) inside the mine.
- **Token Finder** gives a random bonus token payout.
- **Key Finder** gives a crate key; odds per crate are set in `crates.yml > key-finder`.
Blocks broken by Jackhammer/Explosive are sold instantly.

## Permissions
- `prisoncore.admin`: all admin commands (op)
- `prisoncore.build`: break/place outside mines (op)
- `prisoncore.mine.bypass`: use every mine (op); `prisoncore.mine.<name>`: one specific mine
- `prisoncore.autosell`: use `/autosell` (everyone by default)
- `prisoncore.multiplier.<vip|mvp|elite>`: sell bonus from `config.yml`

## Data
Player data: `plugins/PrisonCore/players/<uuid>.yml`. Mines: `mines.yml`. Crate blocks: `crate-locations.yml`.

## Building from source
`mvn package` (Java 21). The jar ends up in `target/`. Vault API comes from JitPack and is compile-only.

## Spawn (server setup notes)
- World `spawn` (void, Multiverse + VoidGen). Built from `spawn3.schem`, pasted with its centre (the fountain) at 0,100,0. Spawn pad and `/setspawn`: 0.5, 100, 44.5, facing north.
- Always night: `advance_time false` and time 18000 in `spawn` and `world`. Monster spawning is off in both (`spawn_monsters false`). In 1.21.11 the gamerules use the new names (`advance_time`, `spawn_monsters`).
- Protection: WorldGuard `__global__` flags in `spawn`: no build/break/place, no PvP, no fall damage, invincible, hunger kept full (feed flags), no item drops, mob spawning off. Use and interact are allowed so the crates work.
- Crates (`crate-locations.yml`): common chest at 46,101,-9, rare ender chest at 46,101,0, legendary beacon at 46,101,9.
- DecentHolograms: `sp_money`, `sp_prestige`, `sp_blocks` (leaderboard screens, west plaza), `sp_mines` (portal in the Mines Hall), `sp_welcome` (above the arch), `sp_info` (in front of the spawn pad). Edit with `/dh l set <hologram> 1 <line> <text>`.
- EssentialsSpawn: `spawn-on-join: true`, `newbies.spawnpoint: default`.

- 2.9: Jackhammer key rolls use `jackhammer.key-finder-share: 0.01` (1/3 of the token `finder-share` 0.03).
- 2.9: pet slots from paid ranks: Ion +1, Nova +1, Pulsar +2, Quasar +2, Nebula +3, Singularity +4 (up to 10 pets float around you). Season crate `rewards-per-key: 3` (all three shown descending in the animation; mass-open gives 3 per key).

## /stats (2.11.6)
`/stats [player]` (also /mystats, /multipliers, /boosts, /profile) and **My Stats** in Navigator > Help. Anyone can view anyone online. It shows every number that affects earnings and where each one comes from:
- **Profile**: rank, prestige, clearance, donor rank, balances, blocks mined, money and tokens per minute (5-minute average).
- **Money Multiplier**: base + rank + prestige + donor + each gear source, then × prestige factor × Merchant, then × Ore Bag Multiplier for bag sales.
- **Fortune**: pickaxe Fortune + each source, × prestige → drops per block.
- **Tokens**: prestige + sources (wardrobe included), × Token Fortune; per-block tokens; Token Finder chance (with boosts) and find size.
- **Key Finder**: chance per block, with boosts.
- **Jackhammer & Explosive**: chance per block, with proc-chance boosts.
- **Pickaxe**: tier and every enchant level/cap, OFF marks. Click opens /enchants.
- **Ore Bag & Autosell**: space, multiplier, worth now; autosell interval split into base, prestige and donor. Click opens the bag.
- **Pets**: slots, owned, what active pets give.
- **All Bonuses**: every stat grouped by source (Armor, Full set bonus, Pets, Colony Clearance, Donor rank, Wardrobe).

Config v23 adds the Help entry (after Ranks) and the Q tip to the Enchants entry on existing servers.

## Cosmetics (2.12.0)
`/cosmetics` (also /chatcolor, /namecolor, /prefix, /title, /suffix) and **Cosmetics** in Navigator > Help:
- **Chat color** and **Name color**: one shared palette (`cosmetics.colors`), including hex colors, gradients and a rainbow.
- **Title (prefix)**: donor rank + clearance tag (default), donor only, clearance only, none, or an unlocked title (`cosmetics.titles`: Inmate, Miner, Smuggler, Veteran, Foreman, Tycoon, Stargazer, Warden, Void Walker, Legend, Event Horizon).
- **Suffix**: a symbol after the name (`cosmetics.suffixes`).
- **/nick <name>** | `/nick off`: 3-16 letters/numbers/_, can't be another player's name or a blocked word. Unlocked by Officer clearance or NOVA+ (`cosmetics.nick.require`). PrisonCore takes /nick over from Essentials.

Unlocks: `require:` = `none`, `clearance:<1-6>`, `donor:<id>` (that rank or higher), `prestige:<n>`, `perm:<node>`; a list means any of them. Unlocks are listed on every level in /citizen and every rank in /store. `prisoncore.cosmetics.all` (op) unlocks everything. Choices are saved on the player and dropped automatically if the unlock is lost.

Chat: `chat.format` uses `{title}{prestige}{rank} {name}{suffix}`. Hovering a name shows the real name, rank, prestige and clearance; clicking starts a /msg.
Placeholders for TAB: `%prisoncore_title%`, `%prisoncore_display_name%` (colored name/nick), `%prisoncore_suffix%`, `%prisoncore_name_color%` (for nametags), `%prisoncore_nick%`.

## Escalating prestige costs (2.12.2)
Each prestige multiplies rankup and prestige costs by a growing step: x4 at P1, then +0.5 per prestige (x4.5, x5, x5.5...), capped at x10 per prestige (`ranks.yml > prestige.cost-multiplier-growth: 0.5`, `cost-multiplier-max: 10`; growth 0 = old flat x4). Sell stays x2 per prestige, so every prestige is harder than the one before: total cost factor P5 2,970 (was 1,024), P10 68.9M (was 1.05M).

## 2.12.5: shutdown save fix, staff titles, custom prefix
- **Data loss fix:** on restart, players are kicked before plugins shut down; their quit-save was queued as a background task that never ran, and the final save skipped them because they'd left. Now quit-saves during shutdown are written immediately, and the shutdown save also flushes any queued quit-saves. Autosave every 2 minutes (was 5).
- **Staff titles** in /prefix: Owner, Admin, Mod, Helper, Builder (`perm:prisoncore.title.<id>`; `perm:` requirements are not unlocked by `prisoncore.cosmetics.all`, so only the people given the permission see them).
- **/prefix custom <text>** and **/suffix custom <text>** (`prisoncore.cosmetics.custom`, op): &-codes, &#hex or MiniMessage, max 32 visible characters; `off` removes it. Also selectable in the menus.

## Prestige drops x1.5 (2.12.7)
`ranks.yml > prestige.drop-multiplier-per-prestige: 1.5`: every prestige multiplies all block drops by 1.5, compounding (P3 x3.4, P5 x7.6, P7 x17, P10 x58; was 1 + 0.5 x prestige). Sell stays x2 per prestige, so income grows ~x3 per prestige while costs grow x4, x4.5, x5... (each prestige still harder). `sell-linear-per-prestige` (0 = off) is an optional linear sell factor. Set drop-multiplier-per-prestige to 0 to go back to the old linear drops.

## Gems (2.13.0)
`/gems` (/gem, /sockets) and **Gems** in Navigator > Help. `config.yml > gems`.
- **Slots** unlock with prestige: P10, P13, P16, P20, P25 (`gems.slots`).
- **Types** (each gives one stat, added to /stats as source "Gems"): Abundance (money multiplier, up to +2.00x), Prospect (drops, up to +100%), Flux (tokens, up to +100%), Fate (key finder, up to +50%), Surge (Jackhammer/Explosive chance, up to +50%). Value = max x (level/14)^1.6.
- **Fusion:** right-click a gem in your inventory while /gems is open: 2 identical gems -> 1 of the next level. Chances +1->+2 100%, 95, 90, 85, 78, 70, 62, 54, 46, 38, 30, 22, +13->+14 15%. A failed fusion destroys one of the two gems. Fusing +10 or higher is broadcast.
- **Socket:** left-click a gem in your inventory; click a socketed gem to take it out. Sockets are saved on the player.
- **Drops:** 1 in 20,000 per mined block (not Jackhammer/Explosive layers) from Prestige 10; 10% are +2, 2% +3. Gems are named items: they can't be sold or bagged, but can be traded/sold in cell shops or the auction house.
- **Admin:** `/gems give <player> <type|random> [level] [amount]` (for crate rewards: `gems give %player% random 1`).

## Bag Multiplier only on autosell (2.13.1)
The Bag Multiplier (up to x3) is only applied when the autosell timer sells the bag. A manual /sell (or /bag sell) sells the bag at the plain price and tells the player how much autosell would have paid. So players want a bag big enough to hold everything until autosell, which is what makes Bag Space upgrades matter. `bag.yml > multiplier.manual-sell: true` turns this off.

## Replicator enchant + pet storage (2.14.0)
- **Replicator** (enchants.yml > enchants.replicator, slot 24): unlocks at Prestige 7 (`min-prestige`). Multiplies every drop: x(1 + 0.003 x level), x4 at level 1000. Applies to the mined block and Jackhammer/Explosive layers, so more ores = more money but the Ore Bag fills much faster. Cost: base 5000 + 3000/level tokens (~1.5B to max). Shown in /stats under Fortune. Locked ("???") below P7.
- **Pet storage:** storage = 45 base + clearance + donor rank `pet-storage` (Spark 9, Ion 18, Nova 27, Pulsar 36, Quasar 45, Nebula 63, Singularity 90) + bought areas. Players can buy 2 extra storage areas of +45 each from the **Pet Storage** button in /pets (`pets.storage-upgrades`: costs 100M and 1B tokens; currency tokens|money|cores).
