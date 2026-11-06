# Third-party notices

## MCreator API (compile only)

`libs/mcreator-api.jar` contains class files from the MCreator 2026.1 runtime and is used exclusively to compile this
plugin. It is not packaged into `optional-packs.zip`. MCreator is distributed by Pylo under its own license; see the
MCreator distribution license.

## Log4j API (compile only)

`libs/log4j-api-2.25.3.jar` is the Apache Log4j 2 API, licensed under the Apache License 2.0. It is used only for
compilation and is not packaged into the plugin.

## Gradle wrapper

The Gradle wrapper scripts and `gradle-wrapper.jar` are from Gradle 9.8.0 and are licensed under the Apache License
2.0.

## Minecraft and loader APIs

The plugin does not redistribute Minecraft, Forge, NeoForge, Fabric Loader, or Fabric API classes. Generated hooks use
the APIs supplied by the selected MCreator generator at build time.
