# Example packs

These archives demonstrate the expected structure. They are intentionally small and use a permissive
`pack_format` range so they can be listed by several supported Minecraft versions. Adjust `pack.mcmeta` for the
exact target version when the pack needs to be fully compatible.

| File | Copy to |
| --- | --- |
| `resourcepacks/Example Resource Pack.zip` | `src/main/resources/resourcepacks/` |
| `datapacks/Example Data Pack.zip` | `src/main/resources/datapacks/` |

After a build the normalized names are `example_resource_pack.zip` and `example_data_pack.zip`. The original file
names are not changed.

The example archives are valid zip files. The plugin does not validate or modify their internal structure; a broken
archive is copied and left for the loader to reject.
