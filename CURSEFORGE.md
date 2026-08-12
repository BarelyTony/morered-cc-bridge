# More Red × CC: Tweaked Bridge

Lets **CC: Tweaked** computers read and write **More Red** bundled cables through the standard
bundled redstone API — `redstone.getBundledInput` and `redstone.setBundledOutput`. Put a computer
against a bundled cable and all sixteen colour channels are yours from Lua.

This is a rebuild of the abandoned *More Red x CC:Tweaked Compat* by YuRaNnNzZZ, which stopped at
Minecraft 1.21.1. Same idea, new internals — the old approach is no longer possible, for reasons
described further down.

## Requirements

- Minecraft **26.1.2**
- NeoForge **26.1.2** or newer
- **CC: Tweaked** 1.118 or newer — **[download from Modrinth](https://modrinth.com/mod/cc-tweaked)**
- **More Red** 26.1.0.5 or newer — available here on CurseForge

**CC: Tweaked is no longer published to CurseForge.** The page here stops at Minecraft 1.21.1 and
will not work with this mod. Get it from Modrinth instead.

**Do not install Ex Machina separately.** More Red ships it internally via jar-in-jar. Adding a
standalone copy will cause problems.

## Usage

Place a computer, turtle, or redstone relay directly against a More Red bundled cable — or any
single-colour cable, junction, or relay. Then, from Lua:

```lua
-- Read every colour currently on the cable behind the computer
local input = redstone.getBundledInput("back")

-- Is white on?
if colors.test(input, colors.white) then
  print("white is live")
end

-- Drive red and blue
redstone.setBundledOutput("back", colors.combine(colors.red, colors.blue))
```

`setBundledOutput` **replaces** the whole mask rather than adding to it, so change one channel at
a time by reading first:

```lua
local out = redstone.getBundledOutput("back")

out = colors.combine(out, colors.orange)   -- turn orange on, leave others alone
redstone.setBundledOutput("back", out)

out = colors.subtract(out, colors.red)     -- turn red off
redstone.setBundledOutput("back", out)
```

Colour bits map exactly to Minecraft's dye colours, so `colors.white` is More Red's white channel,
`colors.red` is red, and so on for all sixteen.

### Supported blocks

Computer (Normal, Advanced, Command), Turtle (Normal, Advanced), and the Redstone Relay.

## Things to know

**A computer reads back its own output.** If a computer drives white on a network and reads that
same network, it sees white. The cable carries one value per channel and cannot distinguish "I am
driving this" from "something else is". This matches a real bundled cable, but it differs from CC's
usual convention of excluding a block's own output. Drive and read on different cables, or mask
out your own output in Lua.

**Writes land at end of tick.** A `setBundledOutput` followed by a `getBundledInput` in the same
tick will read the old value. Sleep or wait for an event between them.

**Other mods' cables can opt in.** Attachment is driven by the `redcc_bridge:bundled_cables` block
tag rather than hardcoded IDs, so any coloured cable added to that tag works without a code change.
Plain redstone wire is deliberately excluded — including it would merge all sixteen colours onto
one signal.

**Computer-like blocks are listed explicitly.** The six CC blocks above are assigned by datamap, so
a third-party block that behaves like a computer will not be picked up automatically.

## How it works

The obvious design — bridging More Red's old `ChanneledPowerSupplier` capability to CC's bundled
redstone provider — is no longer possible. More Red dropped its `api` package entirely in the 1.22
rewrite and handed all wire and cable connectivity to Commoble's **Ex Machina** framework, so there
is no capability left to bridge. That is very likely why the original compat mod stopped, rather
than simply falling behind on versions.

Ex Machina's signal graph is the supported integration point, and its channel model — sixteen dye
colours plus plain redstone — lines up exactly with CC's 16-bit bundled mask, one bit per dye
colour. So this mod registers a signal component that puts computers into the graph on every
colour channel, publishes the computer's bundled output into it, caches what comes back, and
reports that cache to CC.

No More Red class is referenced anywhere. The binding is by block ID through Ex Machina.

## Credits

This mod is only glue. The actual work belongs to other people:

- **More Red** and the **Ex Machina** signal graph framework — by [Commoble](https://github.com/Commoble) (MIT)
- **CC: Tweaked** — by Daniel Ratcliffe, Aaron Mills and SquidDev, originally created by Daniel Ratcliffe
- **More Red x CC:Tweaked Compat**, which this replaces for 26.1.2 — by [YuRaNnNzZZ](https://github.com/YuRaNnNzZZ/More-Red-CCT-Compat) (MIT)

Released under the MIT license.
