# Colorful Lighting: Multiplatform Porting Game Plan

Goal: one codebase that ships Colorful Lighting for Forge and NeoForge across 1.20.2 through 26.2
(and later Fabric), with full support for BOTH Sodium and Embeddium, matching the version coverage
of the original mod. Physics-mod compatibility is part of the goal: Valkyrien Skies AND
Sable / Create Aeronautics, each supported wherever it exists (see section 5).

This document is the plan. Nothing in it is started yet.

---

## 1. Where we are today

- This repository is Forge 1.20.1 only, built with ForgeGradle 6, Java 17.
- Renderer compat targets Embeddium only (mixins into `me.jellysquid.mods.sodium.*` classes, which
  is the package Embeddium 0.3.x kept from its Sodium 0.5 fork).
- There is also an old, outdated NeoForge 1.21.1 port living outside this repo. It works but only
  supports Embeddium, which breaks with the modern Sodium-based ecosystem (Create Aeronautics etc.).
- Good news: the code is already half-prepared for this. The engine (propagation, storage,
  sampling, config) lives in `common/` and talks to the game through accessor interfaces
  (`ClientAccessor`, `LevelAccessor`, `BlockStateAccessor`). The Forge-specific and
  renderer-specific code is mostly contained in `mixin/`, `compat/`, `event/`, and `accessors/`.

## 2. The three big decisions (recommendations)

### Decision A: new repository, not a branch here

Make a NEW repository (e.g. `colorful-lighting`) for the multiplatform project. Reasons:

- The multiloader project has a completely different folder layout (multiple Gradle subprojects),
  different build system, and different file histories. Forcing that into a branch of this repo
  makes every merge and diff meaningless.
- This repo stays the stable, shippable Forge 1.20.1 mod while the new one grows. No risk to the
  live product.
- Once the new repo reaches parity on Forge 1.20.1, this repo gets archived (or kept as a
  maintenance branch for hotfixes only) and releases move over.

### Decision B: toolchain = Stonecutter + multiloader layout

Two separate problems need two tools:

1. Many GAME VERSIONS from one codebase: use **Stonecutter** (Gradle plugin). It keeps one source
   tree with version-conditional code comments and generates a build per target. This is how mods
   that ship 20+ versions do it. The alternative (one git branch per version) does not survive 40+
   targets; every bugfix would need 40 cherry-picks.
2. Many LOADERS per game version: use the standard **multiloader layout** (common + forge +
   neoforge + fabric subprojects), with **Architectury Loom** (or ModDevGradle for NeoForge
   targets) as the per-loader toolchain. We write loader code once per loader, not per version.

The build matrix is then (version tier) x (loader), and Stonecutter's "chiseled build" produces
every jar in one command.

### Decision C: don't treat 42 targets as 42 ports, group them into API tiers

Most adjacent Minecraft versions are source-compatible for what this mod touches. Define tiers,
port per tier, and let each built jar declare a version RANGE where possible:

| Tier | Versions | What breaks going in |
|------|----------|----------------------|
| T1 | 1.20.1 (current) | baseline, Java 17, Sodium 0.5 family |
| T2 | 1.20.2 - 1.20.4 | networking rewrite, registry changes |
| T3 | 1.20.5 - 1.20.6 | Java 21, data components, big internal renames |
| T4 | 1.21 - 1.21.1 | LTS-size modded audience, Sodium 0.6 (package rename), NeoForge stabilized |
| T5 | 1.21.2 - 1.21.3 | render/chunk internals shuffle |
| T6 | 1.21.4 - 1.21.5 | model/render pipeline changes |
| T7 | 1.21.6 - 1.21.8 | verify on port |
| T8 | 1.21.9 - 1.21.11 | verify on port |
| T9 | 26.1 - 26.2 | new versioning era, verify everything on port |

Exact breakage lists for T5+ get confirmed during the port (render internals moved a lot after
1.21.1; treat the table as a starting map, not gospel). The point stands: the unit of work is a
tier, not a version. Expect roughly 9 units of porting work, not 42.

## 3. Target architecture (new repo layout)

```
colorful-lighting/
  common/            engine + game logic, loader-agnostic
    lightengine/     propagation, storage, sampling (today's common/)
    platform/        interfaces the engine needs (today's accessors, expanded)
    render/          vanilla-renderer lighting path (works with NO sodium/embeddium)
  renderer/
    sodium05/        adapter for Sodium 0.5.x AND Embeddium (same class layout, me.jellysquid.*)
    sodium06/        adapter for Sodium 0.6+ (net.caffeinemc.*, new shader/vertex systems)
  forge/             Forge entrypoint, events, Oculus compat
  neoforge/          NeoForge entrypoint, events, Iris compat
  fabric/            (later) Fabric entrypoint
  shaders/           core-shader resource packs, per renderer generation
```

Key architectural rules:

- The engine NEVER imports loader or Sodium classes. Everything goes through `platform/`
  interfaces. (We already live by this rule for loader code; extend it to the renderer.)
- Define a `RendererAdapter` interface: light data injection into chunk meshing, vertex format
  extension, shader patching, rebuild scheduling. `sodium05`, `sodium06`, and the vanilla path
  each implement it. Detect at runtime which renderer is present and install the right adapter.
- The vanilla-renderer path is a first-class citizen. It is the fallback everywhere and the ONLY
  path on loaders/versions where no Sodium-family renderer exists (see section 4).
- Mixins into Sodium/Embeddium stay `@Pseudo` with string targets so a missing renderer never
  crashes the game.

## 4. Sodium vs Embeddium reality check (this shapes the whole plan)

- **Embeddium** is a fork of Sodium 0.5. On 1.20.1 Forge it kept Sodium's `me.jellysquid.*`
  packages, which is why our current mixins work. Embeddium was discontinued in early 2025 and
  never went past 1.21.1. It is the PAST target set.
- **Sodium proper** added official NeoForge support in 0.6 (1.21.1+), and 0.6 renamed packages to
  `net.caffeinemc.*` and reworked the chunk shader and vertex format internals. Sodium does not
  ship for LexForge. It is the FUTURE target set, and it is what Create Aeronautics-style packs run.
- Consequence for the matrix:
  - NeoForge 1.21.1: support BOTH (Embeddium via `sodium05` adapter, Sodium via `sodium06`).
  - NeoForge 1.21.2+: Sodium (`sodium06`) plus vanilla fallback.
  - Forge 1.20.1: Embeddium only (unchanged, already done).
  - Forge 1.20.2+: there is essentially no Sodium-family renderer on LexForge. Ship with the
    vanilla-renderer path, and investigate community forks (e.g. Celeritas) as optional adapters.
    Set download-page expectations accordingly.
- The `sodium06` adapter is the single biggest NEW piece of work in this plan: new mixin targets,
  new vertex format hooks, new core-shader patching. Budget it like a feature, not a port.
- Iris replaces Oculus on 1.21+; shader-pack compat code needs the same adapter treatment.

## 5. Physics mod compatibility (Valkyrien Skies, Sable, Create Aeronautics)

Colored lighting must work on moving ships and contraptions, and there are two ecosystems to
cover. This is a support-both situation, not a pick-one: VS has the established install base,
Sable / Create Aeronautics is the newer ecosystem, and packs will run one or the other depending
on version and loader.

- Valkyrien Skies: already supported on 1.20.1 Forge (per-ship extra light regions keyed by ship
  id, cross-context light sampling through ship transforms). This carries into the new
  architecture and gets re-verified on every tier where VS ships.
- Sable / Create Aeronautics: first-class compat target alongside VS. Their audience runs the
  modern Sodium ecosystem, so this compat depends on the `sodium06` adapter from Phase 2 (this is
  the exact combination that broke the old NeoForge 1.21.1 port: Embeddium-only meant no Create
  Aeronautics packs).

How to build it without doing the work twice:

1. Generalize the existing VS compat into a small physics-mod interface. Both ecosystems need the
   same two primitives the VS compat already implements: "extra light regions beyond the view
   area, keyed by an owner id" and "coordinate transform between world space and contraption
   space".
2. Keep the VS adapter as the first implementation of that interface (pure refactor, verified
   against today's behavior).
3. Write the Sable / Create Aeronautics adapter as the second implementation, against whatever
   API they expose for contraption position/transform and block access.
4. Verify which Minecraft versions and loaders Sable / Create Aeronautics actually release for
   during Phase 2/3, and pin the adapter to those targets. API stability there is an open
   question until we are hands-on.

## 6. Phased roadmap

### Phase 0: scaffold (no gameplay code yet)
1. Create the new repo with Stonecutter + multiloader template, targets: `1.20.1-forge` only.
2. CI (GitHub Actions): build all targets on every push.

### Phase 1: migrate 1.20.1 Forge into the new architecture (parity port)
1. Move engine into `common/`, entrypoint/events into `forge/`, Embeddium mixins into
   `renderer/sodium05`.
2. Extract the `RendererAdapter` interface while moving; the vanilla `mixin/render` path becomes
   the default adapter.
3. Definition of done: the new repo's 1.20.1 Forge jar behaves identically to this repo's jar
   (same test world, beacons, doors, VS, Flywheel, Oculus, DH).

### Phase 2: NeoForge 1.21.1 with FULL Sodium support (the big win)
1. Add `1.21.1-neoforge` target; port entrypoint/events to NeoForge idioms.
2. Write the `sodium06` adapter (mixins, vertex format, shader patching for Sodium 0.6+).
3. Keep the `sodium05` adapter working there for Embeddium users.
4. This release replaces the outdated NeoForge 1.21.1 port and unlocks the Sodium-only ecosystem.

### Phase 3: spread across NeoForge versions
1. 26.2 (newest) next: forces every remaining API break to the surface early.
2. Then fill tiers between: 1.21.4/1.21.5, 1.21.2/3, 1.21.6-8, 1.21.9-11, 26.1.x, 1.20.2-1.20.6.
3. Each tier: fix compile breaks behind Stonecutter conditions, port shader assets, run the test
   checklist.

### Phase 4: Forge (non-1.20.1) versions
1. Same tiers, vanilla-renderer path only (plus any viable fork adapters).
2. Cheap once Phase 3 is done: loader code is shared with the 1.20.1 Forge module, version code is
   shared with the NeoForge tiers.

### Phase 5: Fabric
1. Add `fabric/` module; the engine and both renderer adapters carry over (Sodium is native to
   Fabric; Iris likewise). Mostly entrypoint/event/config plumbing.

### Ongoing
- Compat mods (VS, Sable / Create Aeronautics, Create/Flywheel, DH, Wakes, dynamic lights) get
  re-verified per tier and gated behind availability checks, since not all of them exist on all
  versions/loaders.

## 7. Release and versioning scheme

- One mod version per release train, e.g. `3.0.0`, built for every target:
  `colorful_lighting-3.0.0+mc1.21.1-neoforge.jar`.
- Publish via CI to Modrinth and CurseForge with per-target metadata (minotaur +
  curseforgegradle plugins), so a full-matrix release is one tag push.
- Keep the simple CHANGELOG.md format, one list per release.

## 8. Per-target test checklist (paste into each port PR)

- boots with renderer installed, and with NO renderer (vanilla path)
- beacon/torch colored light propagates, colors correct, no washout regression
- doors/trapdoors block and blend correctly
- tinted glass blocks light (known corner-leak issue tracked separately)
- toggle engine off/on in config: no stale/garbage chunk lighting
- shader pack via Oculus/Iris loads, auto-patcher runs
- Flywheel/Create instancing lit correctly (where Create exists)
- Valkyrien Skies ship lighting (where VS exists)
- Sable / Create Aeronautics contraption lighting (where they exist)
- Distant Horizons LODs (where DH exists)
- performance sanity: fly through fresh chunks, watch propagator drain logs

## 9. Risks and open questions

- Sodium 0.6+ internals are a moving target; pin exact supported Sodium versions per tier and
  enforce with version ranges in mod metadata.
- Render internals in 1.21.2+ and the 26.x era need hands-on verification; the tier table's
  breakage notes are provisional.
- Core-shader resource packs are per-renderer AND per-version; plan for generated/templated
  shader assets rather than hand-copied packs.
- LexForge 1.21+ player base is small; decide how much testing effort those targets deserve vs
  just shipping the vanilla path.
- The old NeoForge 1.21.1 repo: mine it for anything already ported (mappings fixes, event
  differences) before writing Phase 2 from scratch.
- Sable / Create Aeronautics release status, supported versions/loaders, and API surface are
  unverified; confirm hands-on before scheduling their adapter (section 5).

## 10. Immediate next steps (in order)

1. Create the new repository from a Stonecutter multiloader template.
2. Set up CI building the empty scaffold for `1.20.1-forge`.
3. Begin Phase 1 by moving `common/` over and making the accessor layer the hard boundary.
4. First milestone to celebrate: new repo's 1.20.1 Forge jar passes the section 8 checklist.
