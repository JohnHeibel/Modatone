# Modatone

A port of [Baritone](https://github.com/cabaletta/baritone), the Minecraft pathfinding bot, to
Minecraft 1.7.10 and the [GT New Horizons](https://www.gtnewhorizons.com/) modpack. It is the
navigation, mining and construction module of ModdedBench, a harness that lets an LLM agent play
GTNH, and it loads as a plain Forge mod (`modbench-baritone.jar`) beside ModdedBench's core mod.

Modatone is not affiliated with or endorsed by the Baritone project. Do not report its bugs upstream.

## What is here

| Path | What |
| --- | --- |
| `src/upstream/java` | 155 files from upstream Baritone at revision `d9cb2d9`, 82 of them modified for 1.7.10 (each says so in its header) |
| `UPSTREAM_SOURCES.json` | every upstream file, its original path and the SHA-256 of the original |
| `src/main/java/baritone/gtnh` | the port: Forge 1.7.10 terrain and movement adapters, jobs (walk, mine, build), GTNH specifics |
| `src/test` | JUnit tests, no game needed |
| `docs/` | [BARITONE_PORT](docs/BARITONE_PORT.md): what is ported, what is not, and the mining, construction and schematic contracts; [MOVEMENTS](docs/MOVEMENTS.md): how to add a kind of movement |

Modatone talks to the rest of ModdedBench only through `dev.modbench.api` (it registers itself with
`NavigationRegistry`); nothing in ModdedBench imports a Baritone class.

## Build

Modatone compiles against ModdedBench's small `api` module, which lives in the ModdedBench
repository (MIT) and is not copied here. JDK 25 runs the build; Gradle provisions the rest.

Inside ModdedBench, where this repository is the submodule `mods/baritone`:

```bash
git clone --recurse-submodules https://github.com/JohnHeibel/ModdedBench.git && cd ModdedBench
./gradlew :baritone:build
```

By itself, pointing at a ModdedBench checkout:

```bash
./gradlew build -PmodbenchApi=/path/to/ModdedBench/mods/api
```

Either way the jar is `build/libs/modbench-baritone-<version>.jar`. It bundles fastutil and carries
the licence texts under `META-INF/licenses`.

## Licence

LGPL-3.0-or-later, as Baritone is: `LICENSE` and `licenses/GPL-3.0.txt`. Third-party notices,
the upstream revision and the statement of modifications are in `NOTICE.md`.
