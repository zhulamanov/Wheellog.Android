# Dashboard information blocks

Blocks below the speedometer can be enabled, disabled and reordered in the existing
Info blocks preference. Arrow buttons have localized accessibility labels. Changes
to the active selection are applied with OK; cancel leaves that selection intact.
Long-press replacement on the dashboard remains available.

Persisted choices use stable resource-name IDs, independent of the display language.
Existing translated labels are migrated using bundled language resources, preserving
selection and order. The renderer and long-press replacement consume the IDs too,
including when the Activity locale differs from the Application locale.

Up to ten named presets store the selected blocks and their complete order. A preset
can be loaded, updated, renamed or deleted. Names are trimmed, limited to 60 characters
and unique without case sensitivity. Deleting a preset does not clear the current
blocks. Preset IDs and block IDs are language independent. New controls have English
and Russian resources; the existing language preference is used.

InfoBlocksTest covers migration, empty selections, locale changes, order, preset CRUD,
the ten-preset limit and duplicate names. WheelViewPaletteTest additionally exercises
a localized Activity with preferences created in a different language.
