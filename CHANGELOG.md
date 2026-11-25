# Changelog

All notable changes to Lightshade Shader Loader are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.0.1] - 2026-11-25

The first public release.

### Added

**The loader**

- A Vulkan shader loader for Minecraft 26.3, for Fabric Loader 0.19.5+, Fabric API 0.164.0+26.3 and Sodium 0.9.3-beta.1+mc26.3.
- Replaces Sodium's terrain programs for every variant: opaque, cutout and order-independent transparency.
- Runs a post-processing graph of up to 32 passes, with 8-bit or 16-bit float targets at 1/8 to full resolution.
- Sun shadow maps with 1–3 cascades, including entities and block entities, and an adjustable tilt of the sun's path.
- A per-frame uniform block, an opaque-scene snapshot for refraction and water depth, a material map from baked block models, a tiling 3D noise volume, and up to 32 per-pixel point lights.
- Applying a pack is a transaction: it compiles first and saves after. If anything fails, the previous pack keeps running and the menu explains why.
- Fenced, deferred release of GPU resources, so switching packs never frees memory the GPU is still using.
- The pack sandbox: bounded manifest and source parser, path-traversal and symlink protection, size limits and a GPU memory budget (see `SECURITY.md`).
- Detects other shader loaders: Fabric refuses to start alongside Sulkan or Iris, and Lightshade switches itself off when other known shader loaders are installed.

**The menu**

- Opened with **O**, **Options → Shader Packs...** or Sodium's **Video Settings → Lightshade Shaders**.
- A two-column pack selector in the style of Minecraft's resource pack screen, with pack icons, badges, descriptions, ▶/◀ arrows, drag-and-drop of pack ZIPs, and Low/Medium/High/Ultra presets under the active pack.
- A settings screen with seven tabs (Lighting, Shadows, Water, Sky, Nature, Atmosphere, Advanced): draggable fill bars with −/+ buttons, ON/OFF switches, changed values shown in gold, and a tooltip on every option.
- Translations: English, plus Norwegian Bokmål (`nb_no`, `no_no`).

**Built-in packs**

- **Lightshade Daylight** (`Vanilla+`): warm sun and sky-blue shade, soft Poisson shadows, clear turquoise water, refined clouds, cosy torchlight. 38 options.
- **Lightshade Aurora** (`Cinematic`): volumetric clouds, calm golden sunsets, three shadow cascades, caustics, the Milky Way, shooting stars, emerald-teal northern lights, wet ground in rain, ember-red Nether and violet End. 50 options.
- Both are also available as standalone ZIPs to start your own pack from.

**Tools**

- An offline toolchain: `build.py`, `test.py`, `check_shaders.py` (which also validates your own pack folder or ZIP), `deliver.py`, `icons.py` and `render_docs_images.py`.

### Known limitations

- Live GPU testing so far covers one Linux desktop with an NVIDIA RTX 2080. Other GPUs and macOS haven't been tested yet.

[Unreleased]: https://github.com/bejulian1/lightshade/compare/v0.0.1...HEAD
[0.0.1]: https://github.com/bejulian1/lightshade/releases/tag/v0.0.1
