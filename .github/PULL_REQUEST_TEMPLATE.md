## What and why

<!-- What does this change, and why is it needed? Link the issue it closes, if any: "Closes #12". -->

## Type of change

- [ ] Bug fix
- [ ] New feature or option
- [ ] Built-in pack look (Daylight / Aurora)
- [ ] Pack format / loader API
- [ ] Translation
- [ ] Docs, tools or build

## Checklist

- [ ] `python3 tools/build.py` exits 0 (no javac warnings)
- [ ] `python3 tools/test.py` exits 0 (SPIR-V, unit tests, translations, contracts, naming)
- [ ] New or changed menu texts exist in `en_us`, `nb_no` and `no_no`
- [ ] New options have a `#ifndef` default in the shared GLSL and translations for name and description
- [ ] `docs/PACK_FORMAT.md` is updated if the pack format changed
- [ ] `CHANGELOG.md` has an entry under *Unreleased*
- [ ] Regenerated `docs/images/` if the menu, icons or sky changed (`python3 tools/render_docs_images.py`)

## Tested in game

<!-- Which GPU, driver and OS? Before/after screenshots for anything visual. Write "offline only" if you could not run it. -->
