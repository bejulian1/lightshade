# Lightshade shader pack format (manifest version 1)

A Lightshade pack is a **folder** or a **ZIP** in `.minecraft/shaderpacks/`. At its root it has a
`lightshade.json` manifest and, optionally, a `pack.png` icon; its GLSL sources can sit anywhere inside.
Lightshade replaces Sodium's terrain programs with the pack's own, can render sun shadow maps with the
pack's shadow vertex program, and then runs the pack's post-processing graph on the frame.

The quickest way to start is to unzip `Lightshade-Daylight-0.0.1.zip` (or the Aurora ZIP), rename it,
and edit it. Both built-in packs are written in exactly this format.

```
MyPack/
├── lightshade.json             manifest (required, at the root)
├── pack.png                    icon (optional): square PNG, 16–512 px, at most 256 KiB
└── shaders/
    ├── terrain.vsh             terrain vertex program   (named in "terrain")
    ├── terrain.fsh             terrain fragment program (named in "terrain")
    ├── shadow.vsh              shadow-caster vertex program (optional, named in "shadow")
    ├── bloom.fsh, display.fsh  one fragment program per graph pass (named in "passes")
    └── include/
        ├── frame.glsl          the LsFrame uniform block (copy it from a built-in pack)
        ├── pack.glsl           pack-wide #defines (the built-in packs include it everywhere)
        └── …                   your own includes
```

## Contents

1. [The manifest](#1-the-manifest)
2. [Options and presets](#2-options-and-presets)
3. [The pass graph](#3-the-pass-graph)
4. [What the shaders can read](#4-what-the-shaders-can-read)
5. [`#include` rules](#5-include-rules)
6. [Limits and safety rules](#6-limits-and-safety-rules)
7. [Validating a pack offline](#7-validating-a-pack-offline)
8. [Menu texts and translations](#8-menu-texts-and-translations)

## 1. The manifest

```json
{
  "version": 1,
  "name": "My Pack",
  "tag": "Vanilla+",
  "description": "One or two sentences for the pack list and its tooltip.",
  "budgetMiB": 384,
  "ownsSky": true,
  "ownsRain": false,
  "terrain": {"vertex": "shaders/terrain.vsh", "fragment": "shaders/terrain.fsh"},
  "shadow": {"vertex": "shaders/shadow.vsh"},
  "options": { "...": "see section 2" },
  "presets": [ "...see section 2" ],
  "passes": [ "...see section 3" ]
}
```

| Field | Required | Meaning |
| --- | --- | --- |
| `version` | yes | Must be `1`. |
| `name` | no | Shown in the menu. Defaults to the file or folder name. |
| `tag` | no | Short badge on the pack card, such as `Vanilla+` or `Cinematic`. Only the first 24 characters are used. |
| `description` | no | Plain text for the pack card (two lines) and its tooltip (in full). |
| `budgetMiB` | no | Ceiling on the GPU memory the pass targets and shadow maps may use: 16–1024, default 256. If the pack would allocate more, Apply fails before anything changes. |
| `ownsSky` | no | `true`: the pack draws its own sky and clouds, so Lightshade turns vanilla's clouds off in the Overworld. |
| `ownsRain` | no | `true`: the pack draws its own rain, so Lightshade removes vanilla's rain columns. Snow stays. |
| `terrain` | yes | `vertex` and `fragment`: paths to the programs that replace Sodium's chunk shaders. |
| `shadow` | no | `vertex`: your own shadow-caster vertex program (for example to make leaves wave in their shadows too). Without it, the loader uses its own plain caster. |
| `options` | no | Player-facing settings; they become `#define`s (section 2). |
| `presets` | no | Named quality levels shown under the active pack and in the settings screen. |
| `passes` | yes | The post-processing graph, 1–32 passes, ending at `$screen` (section 3). |

All paths are relative to the pack root and use `/` (see [limits](#6-limits-and-safety-rules)).

## 2. Options and presets

Every option becomes a preprocessor define in **every** program of the pack: terrain, shadow and all
passes. The loader inserts `#define KEY value` right after `#version 450`. Whole numbers are written as
integers (`#define SHADOWS 1`); everything else as floats (`#define EXPOSURE 1.1`). So use
`float(KEY)` whenever you need a float, and `#if KEY > 0` for branches decided at compile time.

```json
"options": {
  "SHADOWS":         {"label": "Sun shadows",  "category": "Shadows", "default": 1.0, "values": [0.0, 1.0]},
  "SHADOW_DISTANCE": {"label": "Shadow range", "category": "Shadows", "default": 96.0, "values": [64.0, 96.0, 128.0]},
  "EXPOSURE":        {"label": "Brightness",   "category": "Atmosphere", "description": "Overall brightness.",
                      "default": 1.0, "values": [0.8, 0.9, 1.0, 1.1, 1.2]},
  "MY_FIXED_CONSTANT": 3.0
}
```

| Key | Meaning |
| --- | --- |
| option name | `[A-Z][A-Z0-9_]{0,63}`: upper case, digits and `_`. |
| `default` | The value used until the player changes it. It is always one of the offered choices. |
| `values` | The choices, in any order. The menu sorts them; the bar's fill shows where the current value sits among them. Each must be finite, with an absolute value of at most 1,000,000. |
| `label` | The option's name in the menu. Without one, `MY_OPTION` is shown as "My option". |
| `category` | The tab it appears on. One of **Lighting, Shadows, Water, Sky, Nature, Atmosphere, Advanced**; anything else is matched by keyword (`"Colour grading"` → Atmosphere) or goes to **Other**. |
| `description` | Tooltip text. |
| a bare number | A fixed define that the player cannot change. |

**How the menu draws an option**

- `values` of exactly `[0, 1]` makes the option an **ON/OFF switch**.
- Anything else is a **fill bar** with **−**/**+** buttons. It shows a percentage (`1.0` → `100%`) unless the key is one of the built-in formats:
  - `SHADOW_RESOLUTION` → `px`
  - `SHADOW_DISTANCE` → blocks
  - `SUN_TILT` → degrees
  - `SUN_SIZE` and `MOON_SIZE` → `×`
  - `WATER_REFLECTION_QUALITY`, `CLOUD_QUALITY`, `GODRAYS`, `VOLUMETRIC_LIGHT`, `AMBIENT_OCCLUSION` → Off / Low / Medium / High / Ultra.

The values the player has saved are checked again at every Apply. A value that the manifest doesn't offer
(say, from an older version of your pack) falls back to the default.

### Presets

```json
"presets": [
  {"name": "Low",   "values": {"SHADOWS": 0.0, "SHADOW_DISTANCE": 64.0}},
  {"name": "High",  "values": {"SHADOWS": 1.0, "SHADOW_DISTANCE": 128.0}}
]
```

A preset sets only the options it names, so keep presets to performance options and leave the player's
look settings alone. Every key must be an option of the pack, and every value one of that option's
`values`; otherwise the pack fails to load and the menu shows the error. Presets named `Low`, `Medium`,
`High` and `Ultra` are translated in the menu; any other name is shown as you wrote it. The menu
highlights the preset that matches the current values, if one does.

## 3. The pass graph

After the world is drawn, Lightshade runs `passes` in order. Each pass draws one full-screen triangle
with the loader's own vertex program, which hands your fragment program `layout(location=0) in vec2 uv`
(0..1 across the target).

```json
"passes": [
  {"name": "bloom",   "fragment": "shaders/bloom.fsh",   "inputs": {"Scene": "$scene"},
   "output": "bloom", "scale": 0.5, "format": "RGBA16_FLOAT"},
  {"name": "display", "fragment": "shaders/display.fsh", "inputs": {"Scene": "$scene", "Bloom": "bloom"},
   "output": "$screen"}
]
```

| Field | Meaning |
| --- | --- |
| `name` | `[a-z][a-z0-9_]{0,47}`, unique within the pack. |
| `fragment` | The fragment program. |
| `inputs` | `{"SamplerName": "target"}`. Each key is the name of a `uniform sampler2D` in your program (`[A-Za-z][A-Za-z0-9_]{0,63}`); each value is a built-in input or the `output` of an **earlier** pass. |
| `output` | A new target name (`[a-z][a-z0-9_]{0,47}`, not reused), or `$screen` for the last pass. |
| `scale` | Size of the target relative to the screen: 0.125–1, default 1. |
| `format` | `RGBA8_UNORM` (default) or `RGBA16_FLOAT` (for HDR intermediates). |

Rules the loader enforces when it loads the pack:

- A pass may read only built-in inputs and targets written by earlier passes, and never its own output.
- Only the **last** pass writes `$screen`. It must use `scale` 1 and `RGBA8_UNORM`, and its output is already display-encoded (sRGB).
- The graph has 1 to 32 passes.

**Built-in inputs**

| Input | Contents |
| --- | --- |
| `$scene` | The frame after the world, entities and particles are drawn. |
| `$depth` | Its depth buffer. |
| `$opaque` | A copy of the scene taken after opaque terrain, before translucent geometry (water, glass). |
| `$opaqueDepth` | The depth of that copy, so you can measure water depth or refract. |
| `$shadow` | The outermost sun shadow map, when the pack has a `shadow` program. |
| `$noise` | A tiling noise texture made by the loader (also bound to terrain as `LsNoise`). |

## 4. What the shaders can read

### The frame block (`frame.glsl`)

Every program can declare the per-frame uniform block below. Copy `shaders/include/frame.glsl` from a
built-in pack rather than retyping it: the layout must match exactly (std140, 1296 bytes).

```glsl
layout(std140) uniform LsFrame {
    mat4 LsProjection; mat4 LsView; mat4 LsInverseProjection; mat4 LsInverseView;
    vec4 LsTime;        // seconds, overworld clock, rain 0..1, dimension (0 overworld, -1 nether, 1 end, 2 other)
    vec4 LsSun;         // xyz: direction to the sun
    vec4 LsCamera;      // xyz: absolute camera position
    vec4 LsRenderSize;  // width, height, opaque snapshot ready (0/1), wetness around the camera 0..1
    vec4 LsWaterRect[3]; vec4 LsEmissionRect[16];   // atlas rectangles of water and emissive sprites
    vec4 LsFogColor; vec4 LsFogDistances; vec4 LsHeldPosition[2]; vec4 LsHeldColor[2];
    vec4 LsEnvironment; // camera fluid (0 air, 1 water, 2 lava, 3 powder snow), moon phase, eye sky light, eye block light
    vec4 LsNativeSky;   // Minecraft's own sky colour for this biome and time
    vec4 LsMoon;        // xyz: direction to the moon, w: moon phase
    vec4 LsLightInfo;   // x: number of point lights, y: radius where they replace light levels
    vec4 LsLights[32];  // light-emitting blocks near the camera: xyz relative to the camera, w = level + 16 * colour class
};
```

`frame.glsl` also provides `lsLinear()` and `lsDisplay()` (sRGB ↔ linear), `lsViewPosition()`,
`lsWorldPosition()` and `LS_PI`.

### Terrain programs

The terrain programs replace Sodium's chunk shaders, so they are compiled with Sodium's own defines
(`USE_VERTEX_COMPRESSION`, `ALPHA_CUTOUT`, and the `OIT*` family for order-independent transparency) and
can include `<sodium:chunk_vertex.glsl>`, `<sodium:globals.glsl>` and `<minecraft:oit.glsl>` for its
vertex decoding and transparency output. Besides Sodium's own samplers (`u_BlockTex`, `u_LightTex`,
`u_SectionTimeInfo`) and push constants, Lightshade binds:

| Name | Type | Contents |
| --- | --- | --- |
| `LsFrame` | uniform block | The frame block above. |
| `LsOpaque`, `LsOpaqueDepth` | `sampler2D` | The opaque snapshot, for refraction and water depth in translucent terrain. |
| `LsMaterials` | `sampler2D` | A per-atlas-pixel material map (water, leaves, plants, emissive …) built from the block atlas. |
| `LsNoise` | `sampler2D` | The loader's tiling noise. |
| `LsShadowFrame` | uniform block | `mat4 LsLightMatrix; vec4 LsShadowInfo;`: the light's matrix and cascade data. |
| `LsShadowMap`, `LsShadowMid`, `LsShadowNear` | `sampler2D` | Shadow cascades, from the full range down to the nearest. With fewer cascades, the names point at the same map. |

### Shadow maps and the shadow program

Lightshade renders the sun's shadow maps for every pack. Their setup comes from options with fixed names;
define them in your pack to give players control over them:

| Option | Default | Meaning |
| --- | --- | --- |
| `SHADOWS` | `1` | `0` turns shadow maps off. |
| `SHADOW_RESOLUTION` | `2048` | Pixels per side, at least 512 and at most the GPU's limit. |
| `SHADOW_DISTANCE` | `96` | Range in blocks, 32–256. |
| `SHADOW_CASCADES` | `1` | `1`–`3` maps, from a sharp one near the player to the full range. |
| `SUN_TILT` | `0` | Leans the sun's path south, in degrees, so north and south faces are lit differently. |

All cascades count against `budgetMiB`.

If the manifest names a `shadow` vertex program, it replaces the loader's plain caster. It receives
Sodium's chunk vertex format and is compiled with `USE_VERTEX_COMPRESSION` and all your options. It can
read `LsFrame`, `LsMaterials` and the `LsShadowFrame` block, and must write `gl_Position` through
`LsLightMatrix`. The loader supplies the fragment stage: solid depth for full blocks, and alpha-tested
depth for cutout textures.

## 5. `#include` rules

```glsl
#include <lightshade:sky.glsl>        // your pack: shaders/include/sky.glsl, else shaders/sky.glsl
#include <sodium:chunk_vertex.glsl>   // read-only, from Sodium's resources
#include <minecraft:oit.glsl>         // read-only, from Minecraft's resources
```

- `lightshade:` resolves inside **your pack only**: first `shaders/include/<path>`, then `shaders/<path>`.
- `minecraft:` and `sodium:` read `assets/<namespace>/shaders/include/<path>` from the game's resources. They're read-only, and limited to 1 MiB each.
- No other namespaces are allowed.
- Guard your includes with `#ifndef`/`#define`: a file may be pulled in by several others.

## 6. Limits and safety rules

A pack is data: JSON and GLSL. It can't run code on the player's computer, and the loader checks every
path before reading it.

- Only `lightshade.json`, `*.glsl`, `*.vsh` and `*.fsh` are read as sources, plus `pack.png` as the icon.
- At most 256 source files, 1 MiB per file, 16 MiB in total.
- Paths may only contain `A–Z a–z 0–9 _ . / -`: no absolute paths, no backslashes, no `..`.
- In ZIPs, duplicate entries are refused.
- In folders, symbolic links (and anything that resolves outside the pack) are refused.
- A pack's file or folder name may only contain letters, digits, spaces, `.`, `-` and `_`.

These limits are described in more detail in [SECURITY.md](../SECURITY.md).

## 7. Validating a pack offline

```bash
python3 tools/check_shaders.py path/to/MyPack        # a folder
python3 tools/check_shaders.py path/to/MyPack.zip    # or a ZIP
```

This compiles every program in your pack to SPIR-V with the same shaderc library Minecraft 26.3 ships.
It doesn't start the game. It covers:

- the defaults, every preset, all options at their lowest, all at their highest, and mixed extremes (such as shadows off with everything else maxed out),
- each option's lowest and highest value one at a time,
- every terrain variant Sodium builds (opaque, alpha-cutout and the transparency phases).

A warning counts as a failure. Setup is described in [CONTRIBUTING.md](../CONTRIBUTING.md).

## 8. Menu texts and translations

For your own pack, the menu shows the `name`, `tag`, `description` and option `label`/`description`
from your manifest, as written.

The built-in packs instead use translation keys, so their texts follow the game language:

- `lightshade.pack.<id>`, `lightshade.pack.<id>.tag`, `lightshade.pack.<id>.desc`
- `lightshade.option.<KEY>` and `lightshade.option.<KEY>.desc`
- `lightshade.category.<tab>`
- `lightshade.preset.<name>`

Any option key a built-in pack shares with yours (for example `EXPOSURE`) is translated in your pack
too.
