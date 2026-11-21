# OminousVaultPredictor

Standalone Minecraft Java Edition **1.21.11** ominous-trial-vault loot predictor,
with a Swing GUI (`runGui`) plus the original CLI.

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
```

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
