# Native CSV trips and route viewing

The existing WheelLog CSV format and Trips page remain the source of trip data.
Historical CSV duration, energy and distance summaries retain upstream behavior;
extended logs use absolute timestamps for accurate midnight crossings.
The main-toolbar recording button opens Start, Pause and Stop commands and
disables commands that do not apply. Manual Start does not use the automatic
movement threshold. The button can be hidden in Main menu buttons.

Automatic recording follows the existing logging preferences and movement
threshold. Disabling location stops GPS collection. A wheel disconnection keeps
GPS movement recording but does not repeat stale wheel readings. Long gaps form
separate segments in the same trip. Stationary drift and unconfirmed GPS movement
are filtered. Manual pause is explicit; automatic standing pause starts after
five minutes. Stop completes a session and the next Start makes a new file.
Normal exit and crash recovery preserve the configured same-day continuation.

Trip cards have lightly dimmed route previews drawn in solid red, independently
of map rendering preferences. Their menus support rename, Share CSV and GPX
export. The route viewer has solid rendering by default, optional speed coloring,
a timeline, selected-point telemetry, 60x replay with Play/Pause, following and a
latest-point action. OSM attribution stays visible on the full map. Distances in
the Values tab are in kilometers. Missing PWM remains unknown; valid zero is
retained and historical wheel rows can enrich GPS samples within a bounded gap.

No second recorder or standalone map dashboard is introduced. The default order matches upstream: Main, Values, Smart BMS when available,
Graph, Trips and Events; disabled pages remain hidden. Additional dashboard ordering is in a separate change.

Tests cover CSV parsing, GPS/wheel association, pause and recovery, playback,
export, route segments, thumbnail behavior and day/night theme surfaces.
Physical-device BLE/GPS and background-service verification is still required.
