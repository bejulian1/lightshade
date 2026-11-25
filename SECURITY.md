# Security policy

## Supported versions

| Version | Supported |
| --- | --- |
| 0.0.x | ✅ |

## Reporting a vulnerability

**Please don't report security problems in public issues.** Use GitHub's private vulnerability
reporting instead: **Security → Report a vulnerability** on
[github.com/bejulian1/lightshade](https://github.com/bejulian1/lightshade/security/advisories/new).

Please include:

- the affected version,
- a minimal pack or steps that reproduce the problem,
- what an attacker could achieve.

You should hear back within 7 days. We'll agree on a disclosure date with you once a fix is ready, and
credit you in the changelog unless you'd rather not be named.

## What a shader pack can and cannot do

Players download shader packs from strangers, so Lightshade treats every pack as **untrusted data**:

- **No code runs on the CPU.** A pack is a JSON manifest, GLSL sources and an optional PNG icon. Lightshade never loads classes, scripts or native libraries from a pack.
- **Shaders run only on the GPU, through the game's own Vulkan pipeline compiler.** Every pass, target and format is declared in the manifest and checked before anything is compiled (section 3 of [PACK_FORMAT.md](docs/PACK_FORMAT.md)).
- **GPU memory is capped** by the pack's declared `budgetMiB` (16–1024). A pack that would allocate more fails to apply, and the previous pack keeps running.

## How the loader reads packs

`RenderPack` (`src/main/java/no/julian/lightshade/render/RenderPack.java`) is the only place pack files
are read for rendering. It enforces the following.

**Pack names**

- A pack's file or folder name must match `[A-Za-z0-9_. -]+`.
- `.` and `..` are refused.
- The resolved path must stay inside `shaderpacks/` after normalisation.

**Paths inside a pack**

- Only `[A-Za-z0-9_./-]` is allowed.
- No absolute paths, no backslashes, no `..` segment.
- This applies to manifest references and archive entries alike.

**Symbolic links**

- A pack folder that is a symlink is refused.
- So is any file inside a pack whose real path leaves the pack.
- The pack list skips symlinked entries in `shaderpacks/` altogether.

**ZIP archives**

- Entries are read through the JDK's `ZipFile` and never extracted to disk.
- Duplicate entry names are refused.

**Size limits**

- At most 256 source files, each at most 1 MiB, 16 MiB in total.
- Every read is bounded, so an oversized or endless stream fails early.
- Pack icons are read up to 256 KiB, must start with the PNG signature, and must be square, 16–512 px.

**Includes**

- `#include <lightshade:...>` resolves only inside the pack itself.
- `<minecraft:...>` and `<sodium:...>` are read-only from the game's resources, up to 1 MiB.
- Every other namespace is refused.

**Options**

- Option names must match `[A-Z][A-Z0-9_]{0,63}`.
- Values must be finite, with an absolute value of at most 1,000,000.
- A value must be one of the choices the manifest offers, or the default is used.

## Files Lightshade writes

**Settings:** `config/lightshade.json`

- It is written atomically: a temporary file in the same folder, then a move.
- When read back, it is limited to 256 KiB and strictly validated.
- A broken file is left untouched, and the defaults are used until the next Apply.

**Dropped packs:** dragging packs onto the menu copies them into `shaderpacks/`.

- The name must pass the same rule as above.
- A dropped folder must contain `lightshade.json`.
- Symlinks inside a dropped folder are skipped, and every target path is checked to stay inside `shaderpacks/`.

## Out of scope

- GPU driver bugs triggered by valid SPIR-V.
- Performance problems from a pack that stays within its declared budget.
- Anything that requires the attacker to already control the player's `mods/` folder.
