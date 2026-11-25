# Architecture

Lightshade is a client-only Fabric mod for Minecraft 26.3. It works with the game's Vulkan renderer
(Blaze3D on top of the `renderpearl` GPU API) and with Sodium's chunk renderer. It doesn't fork or
replace either one. It hooks a few narrow points:

1. **Terrain:** it swaps Sodium's compiled chunk programs for the pack's own.
2. **Shadows:** it renders the sun's shadow maps from Sodium's chunk meshes.
3. **Post-processing:** it runs the pack's pass graph on the finished frame.

This page is for contributors. Pack authors only need [PACK_FORMAT.md](PACK_FORMAT.md).

```
src/main/java/no/julian/lightshade/
├── LightshadeClient.java      startup, O key, conflict guard, Apply transaction (compile → save → roll back)
├── config/                    LightshadeConfig (atomic JSON), SettingsDraft, QualityPresets
├── pack/PackManager.java      lists packs (built-in, folders, ZIPs), reads names/tags/options/presets/icons
├── render/
│   ├── LightshadeRenderer     the active State: terrain pipelines, graph passes, targets, frame uniform
│   ├── RenderPack             bounded, immutable manifest + source parser (the security boundary)
│   ├── PackShaderSource       serves pack sources and read-only minecraft:/sodium: includes
│   ├── ShadowMaps             1–3 cascaded sun shadow maps from Sodium's chunk meshes
│   ├── FeatureShadowRenderer  replays entity/block-entity draws into the shadow maps
│   ├── MaterialMap            per-atlas-cell material kinds (plants, leaves, emissive) from baked models
│   ├── NoiseVolume            tiling 3D Perlin-Worley noise stored as a 2D slice atlas
│   ├── PointLights            light-emitting blocks near the camera, for per-pixel point lights
│   ├── DeferredGpuCleanup     fenced, render-thread-only release of retired GPU objects
│   └── GpuProfile             optional per-stage GPU timings (-Dlightshade.profile=true)
├── mixin/                     the hooks listed below
├── integration/               Sodium's Video Settings page, WorldPass (opaque/translucent hand-over)
└── ui/                        LightshadePackScreen, LightshadeSettingsScreen, PackSelection, OptionText, PackIcons
```

## The hooks

| Mixin | Target | What it does |
| --- | --- | --- |
| `RenderSystemMixin` | `RenderSystem.getCompiledPipelineNullable` | Records every pipeline the game compiles. When asked for a terrain pipeline it has a replacement for, it returns the pack's version instead (one per Sodium variant: opaque, cutout, OIT phases). |
| `SodiumRenderMixin` | Sodium `DefaultChunkRenderer.render` | Right after Sodium sets a chunk pipeline on the pass, binds Lightshade's own uniforms: `LsFrame`, the opaque snapshot, materials, noise, shadow cascades. |
| `LevelRendererMixin` | `LevelRenderer` | Fills `LsFrame` at the start of the frame. Wraps the main world pass in `WorldPass`, so the opaque snapshot is copied outside any render pass between opaque and translucent geometry. Renders the entity shadows, and composites the pass graph at the end of the frame. |
| `LevelExtractorMixin` | `LevelExtractor` | Keeps entities just out of view in the frame, because their shadows can still fall into view. |
| `CloudRendererMixin` | `CloudRenderer` | Turns vanilla clouds off when the pack draws its own sky (`ownsSky`, Overworld only). |
| `WeatherEffectRendererMixin` | `WeatherEffectRenderer` | Removes vanilla rain columns when the pack draws its own rain (`ownsRain`). |
| `MinecraftReloadMixin` | `Minecraft` | After a resource reload, recompiles the pipelines that read Minecraft/Sodium includes. |
| `OptionsScreenMixin` | `OptionsScreen` | Adds the full-width **Shader Packs...** button. |

All injections are `require = 1` (`defaultRequire`), so a game or Sodium update that moves a target stops
the game at startup with a clear error, instead of silently rendering wrong.

## Applying a pack

`LightshadeClient.apply` is a transaction:

1. **Validate.** `PackManager.describe` checks the options against the manifest; values it doesn't offer fall back to their defaults.
2. **Compile.** On the render thread, `RenderPack.load` reads and checks the pack. Then a new `State` compiles every graph pass, allocates the targets (checked against `budgetMiB`), builds the shadow maps, and compiles a terrain replacement for every Sodium pipeline seen so far. If anything fails, the old state keeps rendering and the error goes to the menu.
3. **Swap.** The new `State` becomes active. The old one is handed to `DeferredGpuCleanup`, not closed immediately (see the next section).
4. **Save.** `LightshadeConfig.save` writes `config/lightshade.json` atomically: a temporary file, then an atomic move. If saving fails, the previous pack is applied again, so the saved file and what's on screen never disagree.

Terrain pipelines Sodium creates later (a new transparency phase, say) are compiled the first time they
appear (`RenderSystemMixin` → `State.terrain`).

## Deferred GPU cleanup

The Vulkan backend may still be using a texture, buffer or pipeline for frames that haven't finished on
the GPU, and creating a fence or freeing memory while a render pass is open is illegal. So retired objects
never get closed in place:

- `DeferredGpuCleanup.defer` only queues a closure. It is safe to call from inside a pass, and never touches the encoder.
- `fence` is called at fixed points outside every render pass. It hands the whole batch to `RenderSystem.queueFencedTask`, which runs it once the GPU has passed that point.
- `closeNow` is used only at shutdown, after rendering has stopped.

If rendering throws inside a frame, the active state is disabled and deferred the same way. The menu
shows why, and the game keeps drawing with its own pipelines.

## Shadow mapping

`ShadowMaps` keeps 1–3 `D32_FLOAT` cascades. The nearest is sharp and close to the player; each next one
is a constant factor wider, up to `SHADOW_DISTANCE`.

- **Drawing:** each cascade re-draws Sodium's chunk meshes from the sun with two pipelines: a *solid* one with no texture and no discard (so the GPU keeps its early depth test), and a *cutout* one for leaves and plants. `FeatureShadowRenderer` then replays the frame's prepared entity and block-entity draws into the same maps.
- **The sun:** Minecraft's sun runs straight overhead. `SUN_TILT` leans its path south, so north and south walls aren't lit edge-on all day.
- **Receivers:** terrain reads the maps through `LsShadowFrame`/`LsShadowMap`/`LsShadowMid`/`LsShadowNear`. The packs use receiver-plane depth bias and normal offset against acne. Daylight softens edges with a 12-tap rotated Poisson disk; Aurora uses a penumbra that grows with the distance to the blocker.
- **Budget:** the maps count against the pack's `budgetMiB`.

## The frame

Once per frame, `LightshadeRenderer.updateFrame` fills the 1296-byte `LsFrame` uniform (std140):

- camera matrices and position, sun and moon directions, time, rain and wetness,
- the dimension, fog colour and distances, Minecraft's own sky colour, eye light levels,
- held light sources, the atlas rectangles of water and emissive sprites,
- up to 32 point lights from `PointLights`.

The graph's targets are created at the scaled sizes from the manifest and recreated when the window size
changes.

## The menu

- `PackSelection` holds the menu's state: available versus active pack, per-pack option drafts, and whether there are unapplied changes. It contains no GUI code, so it can be unit-tested.
- `LightshadePackScreen` and `LightshadeSettingsScreen` only draw that state.
- `OptionText` formats values (percentages, blocks, `px`, `°`, `×`, Off/Low/High) through a translator it is given, which keeps it testable as well.
- Nothing reaches the renderer or `config/lightshade.json` until Apply or Done.

## Offline test harness

The tools never start Minecraft:

- **`tools/build.py`** compiles with `javac --release 25 -Xlint:all -Werror`, against the exact Minecraft 26.3 client, Fabric Loader 0.19.5, Fabric API 0.164.0 and Sodium 0.9.3-beta.1 JARs (the classpath comes from Prism Launcher's own metadata). It then assembles the two built-in packs from `packs/common` plus each pack's own files, into the JAR and into standalone ZIPs with fixed timestamps.
- **`tools/check_shaders.py`** loads `libshaderc` from Minecraft's LWJGL natives through `ctypes`. It compiles every program of a pack to SPIR-V for:
  - the defaults, every preset, all options at their lowest and at their highest, and mixed extremes,
  - each option's ends one at a time,
  - every Sodium terrain variant.

  `#include`s resolve the same way as in the game. A warning counts as a failure.
- **`tools/test.py`** runs the SPIR-V check and compiles and runs `src/test/java` (the config, pack loading and limits, presets, the settings draft, the menu state, option text and the conflict guard). It also checks:
  - that every translation key exists in all three languages,
  - the JAR and ZIP contents (entrypoints, mixins, icons, packs, metadata),
  - the community files and README images,
  - a naming guard over sources, docs and built archives.
- **`tools/deliver.py`** copies the release files and their SHA-256 sums, and only after `test.py` passes.

What the offline harness can't prove is how a real GPU and driver run the packs. That still needs a
live test; see the status note in the [README](../README.md#status-and-roadmap).
