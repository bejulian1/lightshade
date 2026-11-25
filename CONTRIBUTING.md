# Contributing to Lightshade

Thanks for helping! Bug reports, pack-author feedback, translations, shader improvements and code are all
welcome. By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Reporting bugs and ideas

- **Bugs:** use the *Bug report* issue form. These help most:
  - your GPU and driver version, OS, and the exact versions of Minecraft, Fabric Loader, Fabric API and Sodium,
  - which pack and preset you had on,
  - what you see, ideally with a screenshot,
  - the end of `logs/latest.log`.
- **Ideas:** use the *Feature request* form.
- **Security issues:** don't open an issue. See [SECURITY.md](SECURITY.md).

## Development setup

You need **Python 3.10+**, a **JDK 25**, and the files a Minecraft 26.3 + Fabric install already has.
Nothing is downloaded during the build.

1. **JDK 25:** set `JAVA_HOME`. Without it, the tools look in `~/.local/share/jdks/` and `/usr/lib/jvm/`.
2. **Minecraft 26.3 libraries:** launch Minecraft 26.3 with Fabric Loader 0.19.5 once in [Prism Launcher](https://prismlauncher.org/). The tools find Prism's data folder on their own (Flatpak, native Linux, macOS, Windows); to point them at a different one, set `LIGHTSHADE_PRISM_CACHE`.
3. **Fabric API and Sodium:** the build compiles against exactly `fabric-api-0.164.0+26.3.jar` and `sodium-fabric-0.9.3-beta.1+mc26.3.jar`. Either:
   - copy both into `./libs/` (ignored by git), or
   - set `LIGHTSHADE_MODS` to a folder that holds them, such as the `mods/` folder of a Prism instance.

   Without `LIGHTSHADE_MODS`, the tools also look in your Prism instances' `mods/` folders.
4. **For `tools/render_docs_images.py` only:** Pillow (`pip install pillow`).

```bash
export JAVA_HOME=~/.local/share/jdks/jdk-25
export LIGHTSHADE_MODS=~/.local/share/PrismLauncher/instances/MyInstance/minecraft/mods   # optional
```

## Build, test, deliver

```bash
python3 tools/build.py     # build/: the JAR and both pack ZIPs; javac -Xlint:all -Werror
python3 tools/test.py      # every offline check; exit code 0 only when all of it passes
python3 tools/deliver.py   # dist/ (or --to <folder>): release files + LIGHTSHADE-SHA256SUMS.txt
```

`tools/test.py` runs:

1. SPIR-V compilation of every program of both packs, for the defaults, every preset, the extremes and every Sodium terrain variant (`tools/check_shaders.py`),
2. the Java unit tests in `src/test/java`,
3. translation checks for `en_us`, `nb_no` and `no_no`,
4. checks of the JAR and ZIP contents, the community files and README images,
5. a naming guard.

To try a build in the game, copy `build/lightshade-shader-loader-0.0.1.jar` into a test instance's
`mods/` folder alongside Fabric API and Sodium.

## Pull requests

- Keep each pull request to one topic, and explain *why* in the description. The template asks for the rest.
- Before you push, `python3 tools/build.py && python3 tools/test.py` must end with **exit code 0**. Zero javac warnings and zero SPIR-V warnings are part of that.
- If you change what players see, add a before/after screenshot, and say which GPU it ran on.
- New Java belongs in the package that owns the concern (`config`, `pack`, `render`, `ui`, `integration`, `mixin`). Menu logic that can be tested without a GPU goes in plain classes like `PackSelection` and `OptionText`, with a test in `LightshadeTests`.
- Mixins stay narrow: one clear target, `require = 1`, and a comment that says why the hook is needed.
- Code and docs are in English. The code style follows the surrounding file.

## Changing the built-in packs

The two packs are assembled from `packs/common/` plus each pack's own folder (`packs/daylight/`,
`packs/aurora/`). A file in the pack's folder wins over the shared one at the same path.
`LIGHTSHADE_AURORA` (from `shaders/include/pack.glsl`) selects the cinematic paths in the shared sources.

**Adding an option:**

1. Add it to the pack's `lightshade.json`: `label`, `category`, `default`, `values`.
2. Give it a default in the shared GLSL with `#ifndef KEY` / `#define KEY …`, so the other pack still compiles.
3. Add `lightshade.option.KEY` and `lightshade.option.KEY.desc` to **all three** language files.
4. If it is a performance option, consider adding it to the presets.
5. Run `tools/test.py`: the SPIR-V check covers the new option's extremes automatically.

**Changing icons:** edit `tools/icons.py` and run it. Icons are generated, not drawn by hand.

**Changing README images:** run `python3 tools/render_docs_images.py` after changing the sky code, the menu or the icons. Commit the PNGs in `docs/images/`.

## Translations

- Language files live in `src/main/resources/assets/lightshade/lang/`.
- `en_us.json` is the source of truth. Every other language must have exactly the same keys; `tools/test.py` enforces this.
- `nb_no` and `no_no` (Norwegian Bokmål) are kept identical.
- The pack names *Lightshade Daylight* and *Lightshade Aurora* are proper names: don't translate them. Do translate their tags and descriptions.
- Keep the UI strings short. Option names must fit a half-width bar at GUI scale 2, and descriptions are tooltips: one or two sentences.
- Keep `%s` placeholders, and keep `−`, `+`, `·` and `…` as they are.

To add a language, copy `en_us.json` to `<code>.json` (for example `de_de.json`), translate the values,
and add the code to the list in `tools/test.py`.

## License

By contributing, you agree that your contributions are licensed under the [MIT License](LICENSE).
