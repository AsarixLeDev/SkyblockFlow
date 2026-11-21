# Waypoints guide

## Start marking places

1. Open **F7 → Waypoints**, or type **/waypoints**.
2. Create a **set**, then add a **category**. Choose the category color.
3. Turn on **Waypoint creation** for that category, close the menus, and right-click blocks.
4. Right-click the same block again to remove its checkpoint. The held click and the offhand do not create duplicates.
5. Turn creation off in the category, or bind **Exit waypoint creation** under **Map & routes → Keys**.

Only one category edits at a time. Normal block interaction is intercepted while its creation mode is active. Creation pauses when the category/set is hidden or outside its range, and ends when leaving the world. Manual coordinates and optional point labels are available in each category.

## Sets and visibility

- A set's **Shown** switch controls all its categories. Each category has its own switch too.
- **Set options → Auto-hide outside coordinate range** accepts inclusive start/end block coordinates. Capture the current block as either end, or fit a box around existing checkpoints. Sets automatically return on re-entry, retaining the category switches.
- Waypoints are scoped to the server (or individual singleplayer save) and dimension.
- Hidden/out-of-range sets and hidden categories are excluded from minimap markers, in-world markers, and route selection.
- Rename, delete, or copy sets from their options. Deleting a set/category asks for confirmation.

## Minimap and set maps

**Map & routes → Map** controls the minimap, in-world markers, map size, radius, corner and margins. A separate configurable key toggles the minimap. Hiding the minimap leaves the route stream available.

**Open set map** displays cached surface terrain around the set. Drag to pan, scroll to zoom around the cursor, and use **Fit set** or **My position**. Click a checkpoint to select it; click again to cycle stacked checkpoints at different heights. Hover shows its category/label and exact coordinates. **Edit selected** edits/removes it, and Delete removes the selected point. **Inspect hidden** reveals hidden categories in this editing view.

Terrain comes from Minecraft map colors. It is sampled from real chunks already loaded by the client and cached on disk. The display always reads that cache; an unloaded chunk never edits or clears it. Recording covers all loaded chunks while the minimap is enabled or sets exist, regardless of the minimap radius. A stable, incremental queue revisits loaded chunks to refresh block changes. Moving away or hiding the minimap does not erase recorded terrain. Dark checkerboard cells mean terrain has not been recorded yet. Returning to the set map after leaving the area/server still shows recorded terrain. The map is a surface view; underground floors are not a separate layer in this version.

## Copy a layout to another location

1. Open the original set's map and select a checkpoint as the anchor.
2. Stand on the destination block.
3. Choose **Paste set here**. Its tooltip is “I'm on this block. paste the entire set here”.
4. Review the calculated X/Y/Z offset, choose the new set name, and save.

The selected checkpoint aligns with the block under your feet. All checkpoints, categories, colors, visibility settings, no-fly preference, and auto-hide bounds are copied. Original data is preserved and the copy has independent IDs. **Copy with offset** accepts explicit offsets. Terrain at the destination is sampled normally; the original terrain image is not moved.

## Routes

Enable **Map & routes → Routes → Enable routes**, and choose one set or all visible sets. Set:

- **Nearest checkpoints**: find 1–20 reachable checkpoints ranked by path length from the current search origin. The animated in-world trail guides you to the nearest; the minimap shows the paths. This is a nearest-target selector, not a fixed traveling-salesman itinerary.
- **Show animated route stream**: display moving beads directly in the game view. Independent of minimap visibility.
- **Consider flying**: off by default. Off uses ground routes; on permits 3D flying routes. It guides movement and never enables server flight permissions.
- **Allow flying routes** (per-set options): turn off for areas where flight is disabled. Any active set included in the search that forbids flight forces that search to use walking.
- **Arrival radius**: distance from the marked block's volume, including its top, at which a visit consumes it.
- **Re-enable after … other checkpoint visits**: a consumed point is excluded until that many subsequent checkpoint visits. With 5, five other visits release it. With 0, it is released immediately after leaving its proximity. If the history is at least as large as the available circuit, all points can become consumed; the clear key restarts the circuit.
- **Search radius / height**: bound the area searched around you. Routes require currently loaded terrain. Walking checks clearance/support, one-block steps, corner collisions and hazards; difficult partial-block arrangements can be treated conservatively.

Bind **Clear consumed checkpoints** under **Keys**, or use the clear button in Routes. Clearing history does not immediately consume the checkpoint you are already standing on. Visit history clears on world changes/disconnect.

## Performance and files

- One incremental multi-target Dijkstra search ranks candidates; goal indexing is incremental too.
- Route search work targets 1.25 ms per tick with a node budget and a 60,000-node search cap. Surface sampling has a separate 0.9 ms target and a maximum of 2,048 columns per tick. These are work budgets, not an FPS guarantee on every machine.
- Spatial buckets limit nearby-checkpoint scans. Minimap/inspector textures update at most four times per second; path drawing is simplified, clipped and capped.
- Map tiles are loaded/written on one background worker. Up to 512 tiles stay resident. Old disk tiles are pruned after roughly 128 MiB. Unloaded terrain is never requested from the server by this feature.
- `config/skyblockflow/waypoints.json` stores settings/sets; the previous save is kept as `.bak`. Writes use a temporary file and atomic replacement. Invalid files are preserved rather than overwritten.
- `config/skyblockflow/waypoint-maps/` contains disposable cached surface tiles. Checkpoints remain in the JSON when terrain tiles age out.
- Waypoints follow the configured overlay visibility setting. F1/celebrations hide the HUD normally.
