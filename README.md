# OminousVaultPredictor

Standalone Minecraft Java Edition ominous-trial-vault loot predictor, with a
Swing GUI (`runGui`), CLI, and client-side Fabric mod.

## Supported Minecraft versions

The loot model and Fabric mod are currently built and verified for **Minecraft
Java Edition 1.21.11 only**. The Fabric metadata pins the mod to 1.21.11, so
Minecraft will reject it on other versions. No other game version has been
validated yet.

The desktop GUI and CLI run separately from Minecraft on Java 21. Their
predictions are accurate only for vanilla 1.21.11 loot tables and random
sequence behavior. You can use the desktop app alongside another game version,
but treat predictions as unverified unless that version's ominous-vault loot
tables and random-sequence behavior have been checked against this model.

## Minecraft client mod

### Install

1. Use Minecraft Java Edition 1.21.11 and Java 21.
2. Install Fabric Loader 0.19.2 or later for 1.21.11, then install Fabric API
   for 1.21.11 (the build uses 0.141.3+1.21.11).
3. Download `OminousVaultPredictor-fabric-1.21.11.jar` from the latest GitHub
   release and place it in that game's `mods` folder.
4. Start the Fabric profile and join a world. This is a client-side mod; it
   does not need to be installed on the server.

To build the mod yourself, run `./gradlew -p fabric build` (or
`.\gradlew.bat -p fabric build` in Windows PowerShell). The JAR is written
to `fabric/build/libs`.

### Use in chat

Commands run on your client and send feedback to your local chat. The shortcut
`/ovp` and full prefix `/ominusvaultpredictor` accept the same commands.

- Enter the known world seed with `/ovp seed <seed>`.
- Look at an ominous vault and run `/ovp capture start` before inserting the
  key. Wait for all drops to eject, then run `/ovp capture stop`. Chat shows
  the numbered capture and its loot.
- Review the loot and run `/ovp capture save`, or use `/ovp capture edit <loot>`
  to replace the draft. Use the same semicolon-separated notation as the CLI.
  `/ovp capture cancel` discards the draft or stops a capture in progress.
- Use `/ovp log`, `/ovp remove <number>`, or `/ovp clear` to manage the saved
  history. The seed and history are stored locally per save or server address.
- Run `/ovp recover` to see the prior offset and total through the last logged
  vault. Use `/ovp predict heavy_core` or select another item from the command
  suggestions. `/ovp help` lists the commands.

Keep the selected vault loaded during capture. If the game reports an
incomplete capture, supply the full loot using `capture edit` before saving
it. Log consecutive openings and review the loot before using it for recovery.

### Desktop GUI and CLI

Download `OminousVaultPredictor-desktop.jar` from a GitHub release and run it
with Java 21, or build from source using the instructions below. The desktop
app does not need Fabric and can be used without launching Minecraft.

## How it works

The model uses the loot tables and enchantment rules from the vanilla
Minecraft Java 1.21.11 server:

    chests/trial_chambers/reward_ominous
      Pool 1: rolls=1               -> rare(w8) / common(w2) nested refs
      Pool 2: rolls=uniform(1..3)   -> common
      Pool 3: rolls=1, random_chance(0.75) gate -> unique (weights 3,3,2,1,1)
    reward_ominous_rare    weight sum 29   reward_ominous_common   sum 15
    reward_ominous_unique  weight sum 10 (heavy_core = w1 => 7.5%/vault)

The root table creates a single world-seeded Xoroshiro128PlusPlus sequence.
The nested tables use the same loot context and draw from that sequence.
Offset recovery replays it to find where your observed openings fit.

## GUI

```powershell
.\gradlew runGui
```

- Enter the world seed and choose *Log vault*. Use the quick picks or search for
  items and enchantments. Save consecutive openings in the order they happened.
  Select a vault and choose *Edit* to correct its contents.
- Press *Recover offset*. Add more observations if several offsets match.
- Pick target item, *Find my loot!* simulates forward and names the exact
  future vault containing it, starting after your logged openings when recovery
  is enabled.
- Odds tab always shows analytic probabilities/expected open counts.
- File menu saves/loads profiles (.properties).

Known-loot strings are SEMICOLON-separated items because enchant lists contain
commas, e.g. `diamond_axe[efficiency 3,unbreaking 2]; emerald x9`.
Recovery accepts merged or separate stacks of the same item and any enchantment order.

## Build & test (JDK 21)

```powershell
.\gradlew build
.\gradlew.bat -p fabric build
```

The first command builds and tests the desktop program. The second builds the
1.21.11 Fabric client mod. Pushing a `v*` tag creates a GitHub release with
both JARs attached.

## CLI

```powershell
.\gradlew run --args="--seed S"
.\gradlew run --args="--seed S --vault-file vaults.txt --save-state st.props"
.\gradlew run --args="--seed S --known-loot-file known.txt"          # offset recovery + next-vault prediction
.\gradlew run --args="--state-file st.props ..."                     # resume a saved sequence
```

## Caveats

- Predictions assume the default vanilla 1.21.11 loot tables and sequence settings.
  Any unlogged ominous vault openings after your first observation can shift the forecast.
- CLI RNG snapshots from the old four-stream model must be regenerated from the
  world seed and vault history. Saved GUI profiles still work.

