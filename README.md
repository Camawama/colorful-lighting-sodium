# ℹ️ Fork Information

This mod is a direct fork of erykczy's [Colorful Lighting](https://github.com/erykczy/colorful-lighting). Our main goal with this fork is to extend mod compatibility for use in larger modpacks. If you find an incompatible mod, please report it on the [GitHub Issues](https://github.com/Camawama/colorful-lighting-sodium/issues) page.

# ❓ How It Works

This mod is built on top of Minecraft's vanilla lighting engine. It works by storing an extra set of data alongside the vanilla light levels, representing the color of the light in that block. When a light-emitting block is placed, the mod checks a list of configurable "emitters" to see if it should have a color. If it does, the color is propagated outwards, block by block, diminishing with distance, just like the vanilla engine.

When light passes through a semi-transparent block, the mod checks a list of "filters". These filters can change the color of the light passing through them. For example, red-stained glass can be configured to filter out all but the red component of light. The amount of light that is blocked can also be configured.

To avoid performance issues, all of this light propagation is handled on a separate thread, which means it won't slow down your game. The mod also uses techniques like trilinear interpolation to smooth out the colors between blocks, which creates a more natural look.

# ✨ Features

*   Different blocks can emit different colors
*   Light can change color when passing through certain blocks
*   Light can be absorbed by specific blocks
*   Emitted colors and filtered colors can be customized in resource packs
*   Block states can define different colors
*   NBT can define different colors, for blocks, entities and items (a beacon lit by its effect, a charged creeper, a potion by its type)
*   The mod is client-side - you can play with it on ANY server while still experiencing colorful lighting!

# 🔗 Mod Compatibility

*   **Embeddium Support**: Embeddium is fully compatible and a REQUIREMENT.
*   **Starlight Support**: Works seamlessly with Starlight.
*   **True Darkness Support**: Compatible with True Darkness.
*   **Oculus Support**: Colorful Lighting now ships a shaderpack auto-patcher (similar to Euphoria Patches). On startup it scans your `shaderpacks` folder and creates a patched `<Pack> + ColorfulLighting` copy of every recognized pack — select that copy in the Oculus shader GUI and the Colored Light Engine stays enabled. Unpatched packs still disable the engine automatically. Run `/cl patchshaders` to re-scan without restarting, or set `autoPatchShaderpacks = false` in the client config to opt out.
*   **Dynamic Light Mods**: Most Dynamic Light mods are NOW WORKING! [Lively Lighting](https://github.com/Camawama/LivelyLighting) is still the only known working Dynamic Lighting Mod compatible with the filters.json entries.
*   **Wakes: Reforged**: Works perfectly with wakes and tints them accordingly
*   **Flywheel**: Flywheel rendered objects now render with colorful lighting since the Colorful Lighting 2.4.0 update (tested with flywheel 1.0.5).
*   **Distant Horizons**: Lights rendered inside LODs will not have color. This is something we are working on for a future release.
*   **Flerovium**: Held and dropped items will appear dark. This is something we are working on for a future release.
*   **AsyncParticles**: Particles appear dark. This is something we are working on for a future release.
*   **Lively Lighting**: Dynamic light sources will emit light with their respective color. Fixed lingering light!
*   **Immersive Portals**: Does not crash, but the light from the Nether will bleed into the Overworld when an immersive portal is being rendered. This may be fixed in the future.
*   **Ponder (Create)**: Ponder works fine in recent updates, no more red tint issue!

# 🖼️ Resource Pack Tutorial

In your resourcepack's namespace folder (where folders like `textures` and `models` are located), create a `light` folder. There, you can create an `emitters.json` file, which defines what light colors blocks emit.

## emitters.json

This file defines the color of light emitted by blocks. You can specify a simple color or use a more complex object to handle block states.

### Simple Format

```
{
          "minecraft:torch": "vanilla",               // Same color used in Vanilla lighting
    "minecraft:glowstone": "#00FF00",           // color in hex
    "minecraft:red_candle": "red",              // Minecraft dye name
    "minecraft:redstone_lamp": [0,255,255],     // RGB array
    "minecraft:soul_torch": "purple;5",         // override light level emission value after ';' is a hex number from 0 to F (F = 15)
}
```

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

### NBT Format

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
*   Matching is a **subset** test, like vanilla's `/execute if block` — the rule's tags must be
    contained in the target's NBT. For lists, a rule matches if *any* element matches, so
    `{Items:[{id:"minecraft:diamond"}]}` means "contains a diamond anywhere".
*   `variants` works in `emitters.json`, `filters.json`, `absorbers.json`, `entities.json` and
    `items.json`. In `entities.json` and `items.json` there is no block state, so use `nbt` alone.
*   A variant with no `state` and no `nbt` is an error — use `default` for the unconditional value.

> **Only NBT the client actually has can be matched.** This mod is client-side, so a rule can only
> see what the server bothered to send. If a rule never seems to match, that is almost always why.

Minecraft sends a block entity's NBT to the client in two situations: when its chunk is sent, and
when the block entity explicitly broadcasts an update. Only these vanilla block entities do the
latter, so **only these update their light the instant their NBT changes**:

`campfire` · `sign` · `spawner` · `conduit` · `command_block` · `structure_block`

Everything else only picks up NBT changes when its chunk is next sent to the client — on rejoin, or
when you move out of and back into render distance. Most modded block entities *do* broadcast,
because they need to render their own contents.

Two cases worth calling out:

*   **Chests, barrels, hoppers and other containers never send their `Items` to the client at all.**
    A rule on container contents will never fire, not even after a chunk reload.
*   **Beacons never broadcast their effect** — vanilla just marks the chunk unsaved. Colorful Lighting
    works around this for the beacon *you* set: the effect is applied to your client's beacon as the
    change is sent. A beacon changed by another player or by `/data` still updates only when its chunk
    reloads.

Block entities named by an NBT rule are re-read once per tick, and light is re-propagated only when
the resolved color or brightness actually changes — a lit furnace rewrites `CookTime` every tick, and
that must not cause a relight. Blocks with no NBT rules are never read at all, so this costs nothing
unless you use it.

## filters.json

This file defines how light is colored when passing through blocks (like stained glass). You can also specify light absorption (0-15), where 0 allows all light to pass and 15 blocks all light.

### Simple Format

```
{
    "minecraft:red_stained_glass": "#00FF00", // color in hex
    "minecraft:green_stained_glass": "red",   // dye name
    "minecraft:glass": [ 0, 255, 255 ],       // RGB array
          "minecraft:oak_door": "white;5"           // light absorption (0=full pass, 15=blocked)
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
```

## absorbers.json

This file defines which blocks will completely eat (absorb) light around them. You can define the color of light it absorbs (or white for all light) as well as how much light it absorbs.

```
{
	"minecraft:end_portal": "#FFFFFF;15",
	"minecraft:end_gateway": "#FFFFFF;15"
}
```

## entities.json

Define what light color entities emit (REQUIRES [LIVELY LIGHTING](https://github.com/Camawama/LivelyLighting)).

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

## items.json

Define what light color held items emit (REQUIRES a dynamic light mod such as [LIVELY LIGHTING](https://github.com/Camawama/LivelyLighting)).

```
{
    "minecraft:torch": "#00FF00",       // color in hex
    "minecraft:lava_bucket": "orange",  // dye name
    "minecraft:glowstone": "yellow;2"   // override light level after ';' (hex 0..F); held glowstone glows at 2 even though the block stays 15
}
```

Without an explicit level, a held block glows at its block's light level (glowstone item = 15) and other items glow at a mid-range level. The `;level` override takes precedence for both.

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

> Note: with Lively Lighting the light level is chosen by Lively Lighting itself; the `;level` override applies to the client-side dynamic light mods (SodiumDynamicLights, Torcy, AtomicStryker's Dynamic Lights).

## moon_phases.json

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

# ⚙️ Client Config

`config/colorful_lighting-client.toml`

| Option | Default | What it does |
| --- | --- | --- |
| `enabled` | `true` | Master switch for colored lighting. |
| `autoPatchShaderpacks` | `true` | Create patched copies of shaderpacks that decode colored lighting (requires Oculus). |
| `lightUpdateSpeed` | `FASTEST` | How quickly colored light fills in after chunks load. |

## lightUpdateSpeed

Colored light is not sent by the server — it is recalculated on your machine, on a background thread,
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
chunk, and one Nether chunk costs far more than any fixed pause would offset — so a constant pause
throttles almost nothing. Pausing for a *multiple of the work* gives a predictable duty cycle.

The log line `Colored light drain: N chunks in M ms (X chunks/s, lightUpdateSpeed=...)` reports the
measured throughput each time the light finishes filling in, so you can see what a setting actually
does on your machine.

> **Change this with the game closed.** Forge validates the config on load and silently rewrites
> invalid values back to the default (the value needs quotes: `lightUpdateSpeed = "GENTLE"`), and
> editing the file while the game is running can race its config watcher. The log line
> `Colored light engine reset (lightUpdateSpeed=...)` reports the value actually in force — trust
> that over the file.

# ‼️ Working Shader Packs

*   [https://github.com/Waterpicker/Super-Duper-Vanilla](https://github.com/Waterpicker/Super-Duper-Vanilla) (patched by hand)

The built-in auto-patcher additionally supports (tested against the versions listed):

| Pack | Colored light quality |
|---|---|
| Complementary Reimagined r5.8.1 (incl. + Euphoria Patches 1.9.3) | Full — tint wired into block lighting |
| Complementary Unbound r5.8.1 (incl. + Euphoria Patches 1.9.3) | Full — tint wired into block lighting |
| rethinking-voxels r0.1-beta9 | Full (also has its own voxel colored light — consider using one or the other) |
| AstraLex V93.0 | Full — tint wired into block lighting (disable its own "Colored Lighting" option) |
| MakeUp Ultra Fast 9.5c | Approximate — weighted vertex-color tint |
| Mellow v3.2 | Approximate — weighted vertex-color tint |
| miniature 2.18.12 | Approximate — weighted vertex-color tint |
| photon v1.3b | Approximate — weighted vertex-color tint (deferred pack) |
| Bliss v2.1.2 | Approximate — weighted vertex-color tint (deferred pack) |

Unknown packs that read the lightmap through standard `gl_MultiTexCoord1` patterns will still get the compatibility decode (correct light levels, no rainbow artifacts), just without the color tint.

# ⚠️ Known Issues/Planned Fixes

