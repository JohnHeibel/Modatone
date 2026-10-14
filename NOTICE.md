# Notices

Modatone is a port of [Baritone](https://github.com/cabaletta/baritone) to Minecraft 1.7.10 and the
GT New Horizons modpack. It is licensed under the GNU Lesser General Public License, version 3 or
(at your option) any later version: see `LICENSE` (LGPL-3.0) and `licenses/GPL-3.0.txt` (the GNU
General Public License, which the LGPL incorporates). It is not affiliated with or endorsed by the
Baritone project.

## Baritone

- Upstream: https://github.com/cabaletta/baritone, LGPL-3.0-or-later, copyright its contributors.
- `src/upstream/java` holds 155 files taken from upstream revision
  `d9cb2d91a06501c5bcba2181509d0df80361f413`. `UPSTREAM_SOURCES.json` lists each with its upstream
  path and the SHA-256 of the original file. They keep Baritone's licence header.
- Modifications: 82 of those files were changed by the ModdedBench project in 2026 for the
  Minecraft 1.7.10 / GT New Horizons port, and each says so in its header ("Modified by the
  ModdedBench project"). The other 74 are as upstream.
- `src/main` and `src/test` are adapter code and tests written by the ModdedBench contributors for
  this port. They are a derivative work of Baritone, LGPL-3.0-or-later, and say so in their headers.

## fastutil

The built jar bundles the unmodified `it.unimi.dsi.fastutil` classes of
[fastutil](https://github.com/vigna/fastutil) 8.5.15 (`it.unimi.dsi:fastutil:8.5.15` from Maven
Central), Apache License 2.0: `licenses/fastutil-Apache-2.0.txt`, `licenses/fastutil-NOTICE.txt`.
No fastutil source is in this repository.

## ModdedBench api

Modatone compiles against `dev.modbench.api` from ModdedBench (MIT). That module is not part of
this repository and is not bundled in the jar.

## Not distributed

Minecraft, Forge, GregTech and the GT New Horizons modpack are not distributed with this repository.
