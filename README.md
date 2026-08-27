# OminousVaultPredictor

Standalone Minecraft Java Edition **1.21.11** ominous-trial-vault loot predictor,
with a Swing GUI (`runGui`) plus the original CLI.

## How it works

Loot model is transcribed 1:1 from vanilla 1.21.11 data-pack tables
(misode/mcmeta `1.21.11-data`; single primary source - not independently
diffed against Mojang jars):

    chests/trial_chambers/reward_ominous
      Pool 1: rolls=1               -> rare(w8) / common(w2) nested refs
      Pool 2: rolls=uniform(1..3)   -> common
      Pool 3: rolls=1, random_chance(0.75) gate -> unique (weights 3,3,2,1,1)
    reward_ominous_rare    weight sum 29   reward_ominous_common   sum 15
    reward_ominous_unique  weight sum 10 (heavy_core = w1 => 7.5%/vault)

Each of these FOUR tables declares its own `random_sequence`, so the tool
simulates four Xoroshiro128PlusPlus streams in lockstep. Because per-open
consumption is deterministic, a scalar offset (#prior opens on your world)
still aligns all four when recovering from known loot.

## GUI

```powershell
.\gradlew runGui
```

- Enter world seed, add vaults YOU opened (structured editor keeps simulator
  string format), press *Recover offset* -> see how many opens happened before.
- Pick target item, *Find my loot!* simulates forward and names the exact
  future vault containing it (when recovery is enabled).
- Odds tab always shows analytic probabilities/expected open counts.
- File menu saves/loads profiles (.properties).

Known-loot strings are SEMICOLON-separated items because enchant lists contain
commas, e.g. `diamond_axe[efficiency 3,unbreaking 2]; emerald x9`.

## Build & test (JDK 21)
```powershell
.\gradlew build
```

## CLI
```powershell
.\gradlew run --args="--seed S"
.\gradlew run --args="--seed S --vault-file vaults.txt --save-state st.props"
.\gradlew run --args="--seed S --known-loot-file known.txt"          # offset recovery + next-vault prediction
.\gradlew run --args="--state-file st.props ..."                     # full four-stream resume
```

## Caveats

- Only meaningful where NOTHING ELSE consumed these sequences between your
  observations (singleplayer / you being the sole ominous-vault opener).
  RandomSequences exists precisely to resist prediction otherwise.
- Engine-level RandomSequences defaults (salt/include flags) remain assumed
  from decompiled models, not data files - live-world validation pending.
- Loot-table source was verified against a single primary mirror; a second
  independent diff would raise confidence further.
