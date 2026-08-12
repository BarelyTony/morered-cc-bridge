# More Red x CC: Tweaked Bridge

Lets CC: Tweaked computers read and write More Red bundled cable signals on
Minecraft 26.1.2 / NeoForge, via `redstone.getBundledInput`, `redstone.setBundledOutput`,
and friends.

Replaces the abandoned "More Red x CC:Tweaked Compat", which stopped at 1.21.1.

## Why this is not a capability bridge

The old compat mod bridged More Red's `ChanneledPowerSupplier` capability to CC's bundled
redstone provider. That capability no longer exists. More Red dropped its entire `api`
package in the 1.22 rewrite and handed all wire and cable connectivity to Commoble's
[Ex Machina](https://github.com/Commoble/exmachina) framework, so there is nothing left to
bridge in that direction.

Ex Machina's signal graph is the supported integration point, and its channel model happens
to line up exactly with what CC wants: seventeen channels, sixteen of which are dye colours,
against CC's 16-bit bundled mask. One bit per `DyeColor` ordinal, no translation table needed.

So the bridge is two halves meeting in a shared cache:

| Direction | Path |
|---|---|
| `setBundledOutput` -> cable | `ComputerSignalComponent`'s node `source` publishes one bit of the computer's bundled output into the graph |
| cable -> `getBundledInput` | the node's `graphListener` records what the graph carries into `BundledSignalCache`, which `MoreRedBundledProvider` reports back to CC |

Computers are attached to the graph through the `exmachina:signal_component` block datamap in
`src/main/resources/data/exmachina/data_maps/block/signal_component.json`, covering normal,
advanced and command computers, both turtles, and the redstone relay.

Note that **no More Red class is referenced anywhere in this mod.** More Red 26.1.x is absent
from every public Maven, and its cable internals (`WireBlockEntity`, `ChannelSet`) are private
implementation detail. Binding through Ex Machina's graph and a block-namespace check avoids
depending on either.

## Building

Requires a JDK 25 (Mojang ships Java 25 with 26.1.2, and More Red targets it).

```
./gradlew build
```

Output lands in `build/libs/redcc-bridge-neoforge-26.1.2-1.0.0.jar`.

## Dependencies

| | Version | Source |
|---|---|---|
| NeoForge | 26.1.2.94 | maven.neoforged.net |
| CC: Tweaked | 1.120.0 | maven.squiddev.cc (`-forge-api` is the NeoForge artifact) |
| Ex Machina | 26.1.0.0 | maven.commoble.net |
| More Red | 26.1.0.5 | CurseForge only - runtime dep, not compiled against |

Ex Machina is pinned to **26.1.0.0**, not the newer 26.1.0.2, because More Red 26.1.0.5 ships
26.1.0.0 inside itself via jar-in-jar and that is what actually loads. Do not install Ex Machina
as a separate mod jar - More Red provides it.

## Verification status

Verified: compiles clean; loads on a NeoForge 26.1.2.94 dedicated server alongside More Red
26.1.0.5 and CC: Tweaked 1.120.0; the signal component type registers; the datamap binds
without parse errors; the bundled redstone provider registers at common setup.

Verified in-world: `getBundledInput` reports the correct colour bit (a lever into a white cable
into a bundle reads as `colors.white`) and tracks live as signals change; `setBundledOutput`
drives cables and applies channel changes correctly, including adding and removing individual
colours from a multi-colour mask; and separate colours stay on separate channels.

## Bugs found in play, and their causes

**Colours bled into each other.** Ex Machina walks `Channel#getConnectableChannels()` when
expanding a graph, and the redstone channel connects to *all* channels. The component was
modelled on `CubeSignalComponent`, which reaches toward every neighbouring face - so touching a
single plain redstone node pulled all sixteen colours into one graph, and a graph carries one
power value shared by every node in it. Fixed by only attaching to blocks in the
`redcc_bridge:bundled_cables` tag, which keeps redstone out of the colour graphs.

**Outputs stuck, dropped, or swapped channels.** Ex Machina rebuilds signal graphs *only* on an
explicit `ExMachinaGameEvents#scheduleSignalGraphUpdate` game event - its own neighbour-notify
handler enqueues mechanical graph updates and nothing else. Ordinary wires are fine because their
blockstate changes when they change, but a computer's blockstate is identical whether it drives
sixteen channels or none, so `setBundledOutput` scheduled nothing and the cables kept showing
whatever the graph last computed for an unrelated reason. Fixed with a `NeighborNotifyEvent`
listener that schedules a signal graph update when the notifying block is a computer.

**Input was correct at boot and then frozen.** `updateSelfFromNeighborsAfterGraphUpdate`
defaults to false, so Ex Machina never block-updated the computer after a graph update
(`SignalGraphBuffer#tick`, the `nodesUpdatingSelf` pass). The cache tracked the cable fine, but
CC had no reason to re-read its input, so it served whatever it latched at startup. Fixed by
overriding it to true.

## Known behaviour

A computer reads back its own `setBundledOutput` through `getBundledInput` on the same network.
The graph carries one value per channel and cannot distinguish "I am driving white" from
"something else is driving white". This matches how a physical bundled cable behaves, but it
differs from CC's usual convention where a block's own output is excluded from its input.
Drive and read on different cables, or mask out your own output in Lua.

Note also that `setBundledOutput(side, mask)` replaces the entire output mask rather than adding
to it - use `colors.combine` / `colors.subtract` to change one channel at a time.

## Debugging

Reads and graph updates log at debug level under the `redcc_bridge` logger. Enable it in
`log4j2.xml` to trace which channels are firing and what the provider returns.
