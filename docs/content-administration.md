# Content configuration administration

Development commands:

| Command | Permission |
| --- | --- |
| `/ba config item disable <namespace:item>` | `blocksadder.config.item.disable` |
| `/ba config item delete <namespace:item>` | `blocksadder.config.item.delete` |
| `/ba config recipes disable <namespace:item>` | `blocksadder.config.recipes.disable` |
| `/ba config recipes delete <namespace:item>` | `blocksadder.config.recipes.delete` |
| `/ba config namespace disable <namespace>` | `blocksadder.config.namespace.disable` |
| `/ba config namespace delete <namespace>` | `blocksadder.config.namespace.delete` |

Every command also requires `blocksadder.config`. Operators and `blocksadder.admin` receive these permissions by default. Explicitly denied action permissions remain denied.

The first invocation reads the editable YAML files and previews affected definition, recipe and file counts. Repeat the identical command within thirty seconds to apply that exact plan. A different request replaces the pending preview. An intervening source-file edit invalidates the preview.

Item edits change only the named raw item definition. Recipe edits find recipes that consume or create the item, including recipes in other namespaces and both original recipe families and canonical definitions. Disable sets `enabled: false`; delete removes the matching YAML entry.

Namespace disable sets the namespace files' top-level `enabled: false` and disables dependent recipes in other files. Namespace delete removes content sections from the matching files and deletes dependent recipes. It preserves the YAML files, namespace metadata and resource assets. This avoids deleting an entire content folder or unrelated assets.

The editor writes replacements atomically where the filesystem supports it, reloads the full candidate registry and restores exact prior source bytes if validation fails. It also restores the active registry. Remove or disable recipes before deleting an individually referenced item; otherwise strict validation can reject the edit and restore it.

Original command behavior was recovered from the supplied `xt` class. This implementation keeps its item/recipe/namespace operations and replaces its delayed failures with candidate validation, stale-preview detection and rollback. It adds a namespace-file enable flag so disabling a namespace also covers its newer definition families.

Actual Spigot and Paper 1.21.11 scenarios verified preview-only behavior, repeated-command application, cross-file recipe matching, item and namespace operations, stale-preview rejection and byte-exact rollback after a referenced-item deletion failed validation. Exact development candidates are recorded separately from the historical release report.
