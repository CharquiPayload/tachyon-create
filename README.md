# Tachyon: Create

Tachyon's bots at work in a Create factory. A bot turns a hand crank for you, puts things into
Create's machines (clicking them in, or throwing them on top the way you would), takes out what
they made, and keeps your blaze burners lit. Everything it does, it does with a player's hands: in
reach, in sight, and with what it carries.

- **Crank**: it holds a hand crank for a while, forwards or backwards. Cranking makes it hungry, the
  same as it makes you.
- **Feed**: into a depot, basin, millstone, crushing wheels, saw, chute, belt, funnel, deployer or
  item drain, or fuel into a blaze burner. A press or a mixer means the depot or basin under it.
- **Collect**: whatever a depot, basin, millstone, saw, chute, deployer or item drain holds.
- **Stoke**: a standing order to keep the blaze burners around it lit, only the ones heating a basin
  or a tank, with just the fuel that fits.

It's an expansion: a jar of its own next to Tachyon's, using only Tachyon's expansion API. It never
changes Create or Tachyon.

## Installing

Minecraft 1.21.1, NeoForge 21.1, Tachyon 0.5.0 or newer, and Create 6.0. Put
`tachyon-create-<version>.jar` in the server's `mods/` folder.

## Using it

| Command | What it does |
|---|---|
| `/bot <who> crank [<seconds>] [back]` | turns the crank you look at, or the nearest one it sees |
| `/bot <who> feed [<item> [<count>]]` | puts that into the machine you look at; no item means fuel for a blaze burner |
| `/bot <who> collect` | takes out what the machine you look at holds |
| `/bot <who> stoke [<range>]` | keeps the blaze burners around it lit until you stop it |

Each also takes `at <x y z>` for a machine you're not looking at. A bot's page in the menu has the
same in its **Create** group, with its crank time and burner range. Near Create machines, its mind
can do all of it when asked in the chat ("crank this for a minute", "keep the burners going").

## Building

It compiles against Tachyon's jar and Create's, which are on no Maven repository it uses: put them in
`libs/` as `libs/tachyon.jar` and `libs/create.jar` (or pass `-PtachyonJar=` and `-PcreateJar=`), with
the jars Create carries inside it in `libs/create-inside/`. Then `./gradlew build`.

`./gradlew runServer` starts a server in `run/` to try it, with Tachyon's and Create's jars in
`run/mods/`. It registers a `script` brain there that runs the tools by hand, with no model:
`/bot Ada set brain script`, then `/bot Ada say crank {"seconds": 5}`.

## License

MIT.
