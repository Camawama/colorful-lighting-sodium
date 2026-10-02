# Colorful Lighting: Sodium/Embeddium Edition

A client-side Minecraft mod that makes block light **colored**: torches glow warm, soul fire glows blue, and stained glass tints the light passing through it. All of it is configurable through resource packs, and none of it requires the server to have the mod.

> **Requires:** Minecraft **1.20.1** · Forge **47+** · [Embeddium](https://github.com/FiniteReality/embeddium) (optional, recommended) · Oculus (optional, for shader packs)

## ℹ️ Fork Information

This mod is a direct fork of erykczy's [Colorful Lighting](https://github.com/erykczy/colorful-lighting). Our main goal with this fork is to extend mod compatibility for use in larger modpacks. If you find an incompatible mod, please report it on the [GitHub Issues](https://github.com/Camawama/colorful-lighting-sodium/issues) page.

## ❓ How It Works

This mod is built on top of Minecraft's vanilla lighting engine. It works by storing an extra set of data alongside the vanilla light levels, representing the color of the light in that block. When a light-emitting block is placed, the mod checks a list of configurable "emitters" to see if it should have a color. If it does, the color is propagated outwards, block by block, diminishing with distance, just like the vanilla engine.

When light passes through a semi-transparent block, the mod checks a list of "filters". These filters can change the color of the light passing through them. For example, red-stained glass can be configured to filter out all but the red component of light. The amount of light that is blocked can also be configured.

All of this light propagation runs on a separate background thread, so the game keeps running while colors fill in after chunks load. (How aggressively it fills in, and the trade-off against mesh-rebuild stutter, is tunable; see [`lightUpdateSpeed`](#%EF%B8%8F-client-config) in the client config.) The mod also uses techniques like trilinear interpolation to smooth out the colors between blocks, which creates a more natural look.

## ✨ Features

*   Different blocks can emit different colors
*   Light can change color when passing through certain blocks
*   Light can be absorbed by specific blocks
*   Emitted colors and filtered colors can be customized in resource packs
*   Block states can define different colors
*   NBT can define different colors, for blocks, entities and items (a beacon lit by its effect, a charged creeper, a potion by its type)
*   Modded light sources with no configured color automatically glow in a color sampled from their own texture
*   Water subtly tints light passing through it, deepening with distance and matching the biome's water color (swamp water filters light differently than ocean water)
*   The mod is client-side, so you can play with it on ANY server while still experiencing colorful lighting!

## 🔗 Mod Compatibility

<details>
<summary><b>Supported &amp; tested mods</b> (click to expand)</summary>

*   **[Embeddium](https://github.com/FiniteReality/embeddium)**: Fully compatible and recommended, but **no longer required**: colored lighting now works with vanilla rendering too.
*   **[Starlight](https://github.com/PaperMC/Starlight)**: Works seamlessly with Starlight.
*   **[True Darkness](https://github.com/grondag/darkness)**: Compatible with True Darkness.
*   **[Oculus](https://github.com/Asek3/Oculus)**: Colorful Lighting ships a two-tier shader patcher. Specific shader packs get a **dedicated patch applied automatically as the pack loads**: currently the Complementary family (including Euphoria Patches) and the BSL family (v8 through v10, plus forks like Insanity). Just select the pack in the Oculus shader GUI and colored lighting works; no patched copy needed. Every other pack falls back to the generic auto-patcher (similar to Euphoria Patches): run `/cl patchshaders` to scan your `shaderpacks` folder and create a patched `<Pack> + ColorfulLighting` copy of every recognized pack, then select that copy in the shader GUI. Set `autoPatchShaderpacks = true` in the client config to run the scan automatically on every startup. Unpatched packs still disable the engine automatically, and unknown packs that read the lightmap through standard `gl_MultiTexCoord1` patterns still get the compatibility decode (correct light levels, no rainbow artifacts), just without the color tint.
*   **Dynamic Light Mods**: Most dynamic light mods now work ([Sodium Dynamic Lights](https://modrinth.com/mod/sodium-dynamic-lights), [Torcy](https://www.curseforge.com/minecraft/mc-mods/torcy), [AtomicStryker's Dynamic Lights](https://github.com/AtomicStryker/atomicstrykers-minecraft-mods)). [Lively Lighting](https://github.com/Camawama/LivelyLighting) is the recommended companion: it is the only dynamic light mod that also works with `filters.json` and `entities.json` entries, and dynamic sources emit light with their configured color (the lingering-light bug is fixed).
*   **[Wakes: Reforged](https://www.curseforge.com/minecraft/mc-mods/wakes-reforged)**: Works perfectly with wakes and tints them accordingly.
*   **[Flywheel](https://github.com/Engine-Room/Flywheel) / [Create](https://github.com/Creators-of-Create/Create)**: Flywheel-rendered objects (Create contraptions, etc.) render with colorful lighting since Colorful Lighting 2.4.0. Supported versions: Flywheel `1.0.0-beta-214` through `1.0.8` with Create `6.0.x` (tested with Flywheel 1.0.5). OpenGL 4.5 is **no longer required**; on older GPUs and drivers (down to and below GL 4.3, including GL 4.1 Macs) the mod automatically falls back to a buffer-texture path instead of crashing. Ponder scenes also work fine, with no more red tint issue.
*   **[Valkyrien Skies](https://github.com/ValkyrienSkies/Valkyrien-Skies-2)**: Colored lighting renders on ships, including dynamic light sources moving between ships and the world. *Recently added and still being tested; please report any issues.*
*   **[Epic Fight](https://www.curseforge.com/minecraft/mc-mods/epic-fight-mod)**: Patched player and mob models (zombies, skeletons, players, etc.) render with correct colored lighting instead of appearing dark.
*   **HBM's Nuclear Tech Modernized**: Machines render with full colored lighting and no striped light glitches. If a future HBM update changes its shaders, the compat auto-disables and falls back to plain correct brightness.
*   **[Flerovium](https://modrinth.com/mod/flerovium)**: Held and dropped items, and the block-breaking cracks overlay, render with correct lighting instead of appearing dark.
*   **[AsyncParticles](https://modrinth.com/mod/asyncparticles)**: Particles render with correct colored lighting instead of appearing dark.
*   **Flopper**: The fluid inside floppers renders with correct lighting.

> The Epic Fight, HBM, Flerovium, AsyncParticles and Flopper compats are recent additions; please report any issues.

</details>

## 🧩 For Mod Developers (API)

Colorful Lighting has a small, stable Java API in the `net.camacraft.colorfullighting.api` package for mods that want to integrate. In most cases you don't need it at all:

*   **Most mods are already compatible.** If your rendering passes packed light values through untouched, everything just works.
*   **Want your blocks or entities to emit colored light?** Ship JSON files in your own assets (`assets/colorful_lighting/light/emitters.json`, etc.); no code and no dependency needed. See the [Resource Pack Tutorial](#%EF%B8%8F-resource-pack-tutorial) below.

The API is for the cases beyond that:

*   `CLPackedLight`: safe int math on packed light values, for mods that compare, combine or rebuild them (this is where most rendering mods break).
*   `ColorfulLightingApi.getLightView`: sample colored light at any position for your own rendering, safe from render and worker threads.
*   Color providers: register dynamic light colors that JSON can't express (position, state or time dependent).
*   Custom level/renderer hooks: opt custom `Level`s (portal mirrors, ship worlds) into colored lighting.

The dependency is optional and client-side, and `CLPackedLight` is dependency-free int math you may simply copy into your mod. For the full overview, including the packed light format, setup instructions and stability guarantees, see the **[API documentation](docs/API.md)**.

## 🖼️ Resource Pack Tutorial

In your resource pack's namespace folder (where folders like `textures` and `models` are located), create a `light` folder. There you can create the JSON files described below.

**Color values:** every file uses the same syntax for colors:

| Syntax | Example | Meaning |
| --- | --- | --- |
| `"vanilla"` | `"vanilla"` | Same color used in vanilla lighting |
| Hex string | `"#00FF00"` | RGB in hex |
| Dye name | `"red"` | Any Minecraft dye name |
| RGB array | `[0, 255, 255]` | RGB as numbers 0-255 |
| `;N` suffix | `"purple;5"` | Optional level after `;` as a hex digit `0`-`F` (15). For **emitters/items** it overrides the emitted light level; for **filters/absorbers** it sets how much light is blocked (0 = full pass, 15 = fully blocked). |

> The JSON snippets below use `//` comments for explanation. Remove them in a real file.

<details>
<summary><b>emitters.json</b>: what light colors blocks emit</summary>

### Simple Format

```
{
    "minecraft:torch": "vanilla",           // same color as vanilla lighting
    "minecraft:glowstone": "#00FF00",       // color in hex
    "minecraft:red_candle": "red",          // Minecraft dye name
    "minecraft:redstone_lamp": [0, 255, 255], // RGB array
    "minecraft:soul_torch": "purple;5",     // ";5" overrides the emitted light level
    "betterendforge:aurora_crystal": "auto" // color sampled from the block's texture
}
```

`"auto"` samples the emission color from the block's own texture (bright pixels dominate, so a
lamp glows in the color of its glowing part) and applies the block's color provider tint at the
block's actual position — made for blocks whose color changes with position or randomness, like
Better End's aurora crystals. Light sources with no entry at all get the same treatment
automatically unless you turn off `autoEmitterColors` in the client config.

Liquids are sampled from their fluid's still texture under the fluid type's tint rather than
from their (empty) block model, per block state: a liquid whose look follows its state (a burning
liquid, Flow Fun!'s Liquid Redstone by its power) glows in the color of that state.
`/cl debug autocolor <namespace>` lists the light color of every light-emitting block state of a
mod in the log.

### Block State Format

You can define different colors for different block states.

```
{
    "minecraft:furnace": {
        "default": "orange",
        "states": {
            "lit=true": "red",
            "lit=false": "#000000"
        }
    }
}
```

When several `states` entries match, the one constraining the most properties wins
(`lit=true,signal=true` beats `lit=true`).

</details>

<details>
<summary><b>NBT rules (variants)</b>: colors from block-entity, entity or item NBT (works in every file)</summary>

Use a `variants` array to pick a color from a block entity's NBT, a block state, or both. The
**first** variant whose conditions all hold wins, so order them most-specific first. `default` is
used when nothing matches.

```
{
    "minecraft:beacon": {
        "default": "white",
        "variants": [
            { "nbt": "{Primary:1}", "color": "#00ffff;15" },
            { "nbt": "{Primary:5}", "color": "#ff4400;15" }
        ]
    },
    "minecraft:furnace": {
        "default": "#ff4400;1",
        "variants": [
            { "state": "lit=true", "nbt": "{Items:[{id:\"minecraft:diamond\"}]}", "color": "cyan;15" },
            { "state": "lit=true", "color": "orange;7" }
        ]
    }
}
```

On 1.20.1 a beacon stores its effect as an int id in `Primary`/`Secondary` (`-1` when unset):
speed `1`, haste `3`, strength `5`, jump boost `8`, regeneration `10`, resistance `11`. So the beacon
above glows cyan on speed and orange on strength.

*   `nbt` is an **SNBT string**, the same syntax as `/data` and `/give`. Types matter:
    `{powered:1b}` (byte) does not match `{powered:1}` (int).
*   Matching is a **subset** test, like vanilla's `/execute if block`: the rule's tags must be
    contained in the target's NBT. For lists, a rule matches if *any* element matches, so
    `{Items:[{id:"minecraft:diamond"}]}` means "contains a diamond anywhere".
*   `variants` works in `emitters.json`, `filters.json`, `absorbers.json`, `entities.json` and
    `items.json`. In `entities.json` and `items.json` there is no block state, so use `nbt` alone.
*   A variant with no `state` and no `nbt` is an error; use `default` for the unconditional value.

> **Only NBT the client actually has can be matched.** This mod is client-side, so a rule can only
> see what the server bothered to send. If a rule never seems to match, that is almost always why.

Minecraft sends a block entity's NBT to the client in two situations: when its chunk is sent, and
when the block entity explicitly broadcasts an update. Only these vanilla block entities do the
latter, so **only these update their light the instant their NBT changes**:

`campfire` · `sign` · `spawner` · `conduit` · `command_block` · `structure_block`

Everything else only picks up NBT changes when its chunk is next sent to the client: on rejoin, or
when you move out of and back into render distance. Most modded block entities *do* broadcast,
because they need to render their own contents.

Two cases worth calling out:

*   **Chests, barrels, hoppers and other containers never send their `Items` to the client at all.**
    A rule on container contents will never fire, not even after a chunk reload.
*   **Beacons never broadcast their effect**: vanilla just marks the chunk unsaved. Colorful Lighting
    works around this for the beacon *you* set: the effect is applied to your client's beacon as the
    change is sent. A beacon changed by another player or by `/data` still updates only when its chunk
    reloads.

Block entities named by an NBT rule are re-read once per tick, and light is re-propagated only when
the resolved color or brightness actually changes. (A lit furnace rewrites `CookTime` every tick, and
that must not cause a relight.) Blocks with no NBT rules are never read at all, so this costs nothing
unless you use it.

</details>

<details>
<summary><b>filters.json</b>: how light is tinted or blocked passing through blocks</summary>

This file defines how light is colored when passing through blocks (like stained glass). The `;N`
suffix here specifies light **absorption** (0 = all light passes, 15 = all light blocked).

### Simple Format

```
{
    "minecraft:red_stained_glass": "#00FF00", // color in hex
    "minecraft:green_stained_glass": "red",   // dye name
    "minecraft:glass": [0, 255, 255],         // RGB array
    "minecraft:oak_door": "white;5"           // absorption after ";" (0=full pass, 15=blocked)
}
```

### Block State Format

Similar to emitters, filters can also depend on block states.

```
{
    "minecraft:stained_glass_pane": {
        "default": "white",
        "states": {
            "waterlogged=true": "blue"
        }
    },
    "minecraft:oak_door": {
        "default": "white;15",
        "states": {
            "open=true": "white;0",
            "open=false": "white;15"
        }
    }
}
```

### Multiply Mode and Biome Water

The full string syntax is `"<color>;<absorption>;<mode>;<strength>"`, where the last two parts are
optional:

```
{
    "minecraft:water": "biome_water;1;multiply;0.5",  // the default water filter
    "minecraft:slime_block": "#36ff4d;7;multiply"     // a multiply filter with a fixed color
}
```

*   **mode** — `clamp` (default) caps each color channel at the filter color, so one block of
    stained glass tints light fully and further blocks add nothing. `multiply` tints the light a
    little on **every block crossed** instead: light through one block of water barely shifts,
    light through ten blocks comes out deeply colored. Use it for realistic media like water.
*   **`biome_water`** as the color resolves the filter color from the **biome's water color at
    that block**, so murky swamp water and clear ocean water filter light differently.
*   **strength** — `0`-`1`, softens the color toward white (`0.5` = half strength). Handy to keep
    `multiply` filters subtle.

</details>

<details>
<summary><b>absorbers.json</b>: blocks that eat light around them</summary>

This file defines which blocks will completely eat (absorb) light around them. You can define the
color of light it absorbs (or white for all light) as well as how much light it absorbs.

```
{
    "minecraft:end_portal": "#FFFFFF;15",
    "minecraft:end_gateway": "#FFFFFF;15"
}
```

</details>

<details>
<summary><b>entities.json</b>: what light color entities emit (requires Lively Lighting)</summary>

Define what light color entities emit (**requires** [Lively Lighting](https://github.com/Camawama/LivelyLighting)).

```
{
    "minecraft:creeper": "#00FF00", // color in hex
    "minecraft:blaze": "orange"     // dye name
}
```

Entity NBT works here too. A charged creeper, for example:

```
{
    "minecraft:creeper": {
        "default": "#00FF00",
        "variants": [
            { "nbt": "{powered:1b}", "color": "#00FFFF" }
        ]
    }
}
```

</details>

<details>
<summary><b>items.json</b>: what light color held items emit (requires a dynamic light mod)</summary>

Define what light color held items emit (**requires** a dynamic light mod such as
[Lively Lighting](https://github.com/Camawama/LivelyLighting)).

```
{
    "minecraft:torch": "#00FF00",       // color in hex
    "minecraft:lava_bucket": "orange",  // dye name
    "minecraft:glowstone": "yellow;2"   // held glowstone glows at 2 even though the block stays 15
}
```

Without an explicit level, a held block glows at its block's light level (glowstone item = 15) and
other items glow at a mid-range level. The `;level` override takes precedence for both.

Item NBT is matched against the stack's tag, the same one `/give` takes:

```
{
    "minecraft:potion": {
        "default": "#ff00ff",
        "variants": [
            { "nbt": "{Potion:\"minecraft:strong_healing\"}", "color": "#ff0000;10" }
        ]
    }
}
```

> Note: with Lively Lighting the light level is chosen by Lively Lighting itself; the `;level`
> override applies to the client-side dynamic light mods (SodiumDynamicLights, Torcy,
> AtomicStryker's Dynamic Lights).

</details>

<details>
<summary><b>moon_phases.json</b>: light intensity per moon phase</summary>

Define a separate light intensity/vibrancy value for each moon phase.

```
{
  "0": 0.45,
  "1": 0.60,
  "2": 0.75,
  "3": 0.90,
  "4": 1.0,
  "5": 0.90,
  "6": 0.75,
  "7": 0.45
}
```

</details>

## ⚙️ Client Config

`config/colorful_lighting-client.toml`

| Option | Default | What it does |
| --- | --- | --- |
| `enabled` | `true` | Master switch for colored lighting. |
| `autoEmitterColors` | `true` | Automatically sample a light color from the block's texture for light sources with no configured color (typically modded blocks). Bright pixels dominate the sample, so a lamp glows in the color of its glowing part rather than its casing. Disable to make unconfigured light sources emit plain white light. |
| `autoPatchShaderpacks` | `false` | Scan the `shaderpacks` folder on every startup and create patched copies of recognized packs (same as running `/cl patchshaders`; requires Oculus). |
| `lightUpdateSpeed` | `FASTEST` | How quickly colored light fills in after chunks load. |

<details>
<summary><b>lightUpdateSpeed</b>: details and tuning</summary>

Colored light is not sent by the server; it is recalculated on your machine, on a background thread,
*after* a chunk has already loaded and rendered. Every chunk the propagator finishes makes the
renderer rebuild and re-upload that chunk's mesh. Finishing chunks faster fills the light in sooner
but concentrates those uploads, which is what can stutter when you walk into an area full of light
sources (a Nether portal is the worst case: changing dimension discards all colored light, and lava
means nearly every chunk is packed with emitters).

| Value | Pauses for | Roughly | Effect |
| --- | --- | --- | --- |
| `FASTEST` | nothing | 100% speed | Default. Fills in as fast as the CPU allows. |
| `FAST` | 1/4 of the time it worked | ~80% speed | Slight throttle. |
| `BALANCED` | as long as it worked | ~50% speed | Spreads mesh rebuilds out. |
| `GENTLE` | 3x as long as it worked | ~25% speed | Lightest touch; noticeably slower fill-in. |

The pause scales with the work actually done. A propagation pass cannot stop in the middle of a
chunk, and one Nether chunk costs far more than any fixed pause would offset, so a constant pause
throttles almost nothing. Pausing for a *multiple of the work* gives a predictable duty cycle.

The log line `Colored light drain: N chunks in M ms (X chunks/s, lightUpdateSpeed=...)` reports the
measured throughput each time the light finishes filling in, so you can see what a setting actually
does on your machine.

> **Change this with the game closed.** Forge validates the config on load and silently rewrites
> invalid values back to the default (the value needs quotes: `lightUpdateSpeed = "GENTLE"`), and
> editing the file while the game is running can race its config watcher. The log line
> `Colored light engine reset (lightUpdateSpeed=...)` reports the value actually in force; trust
> that over the file.

</details>

## 🗺️ Roadmap

A lot of people have asked where this project is headed, so here are the current plans:

*   **Renderers**: Both Embeddium and vanilla rendering are supported today; Embeddium is recommended but no longer required. Full support for **Sodium** itself is planned.
*   **Loaders and versions**: Today, the mod supports **Forge 1.20.1 only**. The goal is to support **Fabric, Forge and NeoForge** on every Minecraft version from **1.20.1 up to 26.2**. There are no plans to backport below 1.20.1.
*   **Shader patcher**: The dedicated per-pack patcher is implemented and working (see the Oculus entry in Mod Compatibility), with the generic auto-patcher as the fallback for everything else. Dedicated patches for more shader packs, such as Sildur's Vibrant, are planned.

No timelines are promised; follow the [GitHub repository](https://github.com/Camawama/colorful-lighting-sodium) for progress.

## ⚠️ Known Issues / Planned Fixes

*   **[Distant Horizons](https://gitlab.com/distant-horizons-team/distant-horizons)**: Lights rendered inside LODs will not have color. A fix is in development for a future release.
*   **[Cracker's Wither Storm Mod](https://www.curseforge.com/minecraft/mc-mods/crackers-wither-storm-mod)**: **Not compatible.** The game crashes when the Wither Storm is spawned in. Because that mod's source code is private and closed, there are no plans to add compatibility or fix the crash.