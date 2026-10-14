# Colorful Lighting API

For mod developers who want their mod to work with (or integrate into) Colorful Lighting.
Everything lives in `net.camacraft.colorfullighting.api`; nothing outside that package is API.
Current `ColorfulLightingApi.API_VERSION`: 1.

## Do you even need the API?

Usually not. Work through this list top to bottom and stop at the first tier that covers you:

1. **You do nothing.** If your rendering passes packed light values ("combined light", the int
   that goes into `uv2`) through untouched, you are already compatible. Colorful Lighting's
   shaders detect and decode its own values and fall back to vanilla behavior for everything
   else.
2. **Your blocks/entities should emit colored light.** Ship JSON in your assets; no code and no
   dependency: `assets/colorful_lighting/light/emitters.json`, `entities.json`, `items.json`,
   `filters.json`. Unconfigured light sources also get an automatic color sampled from their
   texture, so even this is often unnecessary.
3. **Your code does math on packed light values.** Use `CLPackedLight` (see the three rules
   below). This is the tier most rendering mods land in.
4. **You render things yourself and want colored light.** Use `ColorfulLightingApi.getLightView`.
5. **Your light colors are dynamic** (position/state/time dependent). Register a
   `CLBlockLightColorProvider` / `CLEntityLightColorProvider`.
6. **You have a custom Level or renderer** (portal mirrors, ship worlds). Implement
   `CLSupportingLevel`, plus `CLClientLevel` for a custom renderer, plus `CLWrapperAttachments`
   for wrapper levels.

## Depending on the mod

Colorful Lighting is a client mod; make your dependency optional and client-side.

Colorful Lighting is not published to any public Maven repository, and it has no CurseMaven or
Modrinth Maven coordinates. You compile against a jar built from its source
(https://github.com/Camawama/colorful-lighting-sodium). `./gradlew build` there writes three jars
to `build/libs/`:

| Jar | What it is |
|---|---|
| `colorful_lighting-1.20.1-<version>.jar` | the mod, reobfuscated like any Forge release jar (MixinExtras not inside) |
| `colorful_lighting-1.20.1-<version>-all.jar` | the same plus the bundled MixinExtras: the jar players install |
| `colorful_lighting-1.20.1-<version>-api.jar` | only the `api` package, classes and sources, compiled in Mojang's official names |

**Option 1: a jar in your project.** Copy the jar into your project's `libs/` folder:

```gradle
repositories {
    flatDir { dirs 'libs' }
}
dependencies {
    // the full mod: libs/colorful_lighting-1.20.1-2.7.1.jar (the group part is ignored by flatDir)
    compileOnly fg.deobf("local:colorful_lighting-1.20.1:2.7.1")

    // or only the API, for compiling: libs/colorful_lighting-1.20.1-2.7.1-api.jar
    // (already in official names, so no fg.deobf)
    // compileOnly files('libs/colorful_lighting-1.20.1-2.7.1-api.jar')
}
```

- **The `-api` jar is for compiling only.** Its classes call into the rest of the mod, so the
  full mod must be installed at runtime.
- **Implementing `CLWrapperAttachments` needs the full jar.** Its default methods return internal
  types that the `-api` jar does not contain, so compile against the full jar if you implement it.
- **Running Colorful Lighting in your dev client.** Add the full jar with
  `runtimeOnly fg.deobf(...)`. Also add MixinExtras, which the plain jar does not bundle (Colorful
  Lighting uses `io.github.llamalad7:mixinextras-forge:0.4.1`).

**Option 2: your local Maven repository.** In Colorful Lighting's folder:

- `./gradlew publishToMavenLocal` publishes the mod jar and the `-api` jar to `~/.m2`.
- `./gradlew publish` publishes them to `mcmodsrepo/` in that folder.

The coordinates are `net.camacraft.colorful_lighting:<project folder name>:<version>`, with the
`-api` jar under the classifier `api`. The artifact id is the name of the folder the project was
cloned into (`settings.gradle` sets no project name), e.g. `colorful-lighting-sodium-mc1.20.1`.

```gradle
repositories { mavenLocal() }
dependencies {
    compileOnly fg.deobf("net.camacraft.colorful_lighting:colorful-lighting-sodium-mc1.20.1:2.7.1")
}
```

At runtime, guard your integration behind `ModList.get().isLoaded("colorful_lighting")` and keep
all `net.camacraft.colorfullighting.*` imports inside classes that are only loaded when the mod is
present (the standard optional-dependency pattern).

In `mods.toml`:

```toml
[[dependencies.yourmod]]
modId = "colorful_lighting"
mandatory = false
ordering = "NONE"
side = "CLIENT"
```

## The packed light format (the contract)

Wherever vanilla passes `LightTexture.pack(block, sky)` values, Colorful Lighting may pass:

```
bits  0..7   red   (0..255)
bits  8..15  green (0..255)
bits 16..19  sky   (0..15, vanilla sky light)
bits 20..27  blue  (0..255)
bits 28..31  marker, always 0xF  ->  every colored value is a NEGATIVE int
```

Vanilla-format values still occur at any time (toggled off, unsupported level, full-bright
constants), so always handle both. `CLPackedLight.isColored(packed)` tells them apart.

### The three rules

Mods only ever break on this format in three ways:

1. **No signed comparisons.** `Math.max(light, x)` silently discards colored values (negative).
   Use `CLPackedLight.maxWithLightLevel(packed, rawLevel)` or `CLPackedLight.max(a, b)`.
2. **No decompose/rebuild round trips.** `pack(block(l), sky(l))` turns a colored value into
   garbage. Keep the original value; if a vanilla path rebuilt it, restore with
   `CLPackedLight.preferColored(original, rebuilt)`.
3. **No half-int arithmetic.** Averaging/interpolating `light & 0xFFFF` and `light >>> 16` as
   scalars produces striping. If your pipeline needs scalar math, feed it
   `CLPackedLight.toVanilla(packed)` (correct brightness, hue dropped).

`CLPackedLight` is pure int math with zero dependencies; copying the logic into your own mod
instead of depending on the API is explicitly fine.

## Sampling colored light (`CLLightView`)

```java
// cache per level, e.g. in your renderer; valid for the level's lifetime
CLLightView view = ColorfulLightingApi.getLightView(level); // null if the level can't carry CL

int packed = view.samplePackedLight(pos);   // ready for uv2/renderToBuffer, either format
int rgb    = view.sampleBlockLightColor(pos); // 0xRRGGBB hue only, -1 when inactive
```

`samplePackedLight` returns exactly what Colorful Lighting feeds terrain/entity rendering
(including emissive-block handling), and degrades to plain vanilla values when colored lighting
is off, so it is always safe to use unconditionally. Both are safe from render and worker
threads.

## Dynamic light colors (providers)

For hues that JSON cannot express. Explicit user/pack JSON always wins over providers; providers
win over the automatic texture-sampled color. Return `-1` to defer. Providers run on worker
threads and hot paths: be fast, thread-safe, allocation-light.

```java
ColorfulLightingApi.registerBlockLightColorProvider((level, pos, state) -> {
    if (state.getBlock() != MyBlocks.AURORA_CRYSTAL.get()) return -1;
    return auroraColorAt(pos); // 0xRRGGBB
});
```

Brightness is not part of the provider; it still comes from the state's vanilla
`getLightEmission`. If your color changes without a block update, call
`view.notifyBlockChanged(pos)` to schedule a relight.

Entity variant: `registerEntityLightColorProvider(entity -> ...)`, consulted when entities act
as dynamic light sources.

## Custom levels and renderers

- `CLSupportingLevel` (marker): opt a custom client-side `Level` into colored lighting; a light
  engine and per-level state are created for it. Levels without it are untouched.
- `CLClientLevel`: implement when your level is rendered by something other than the vanilla
  `LevelRenderer`; you receive section-dirty callbacks so your renderer can re-mesh.
- `CLWrapperAttachments`: for wrapper levels that delegate to a real level; forwards all
  per-level state to the wrapped level so that, for example, render code can read the correct data
  instead of crashing the game.

## Stability

- Stable: the packed format, `CLPackedLight`, `ColorfulLightingApi`, `CLLightView`, both
  provider interfaces, `CLSupportingLevel`, `CLClientLevel`. Additive growth only;
  `API_VERSION` bumps on anything incompatible.
- Stable: `CLWrapperAttachments` may change, but you don't have to do anything with the code of
  this, so it doesn't matter. All methods that may be added or removed come with delegate defaults,
  an implementation will never be necessary.
- Everything outside `net.camacraft.colorfullighting.api` is internal and changes without notice.
