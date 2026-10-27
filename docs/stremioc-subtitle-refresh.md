# StremioC manual subtitle refresh

The native name action still selects a subtitle. The refresh icon fetches only
that row's originating addon list (when available) and subtitle body. It does
not select the row, modify Media3 track selection, rebuild the subtitle list,
or reload the video. Rows collapsed by CloudStream to a shared URL with
conflicting addon provenance are not offered a refresh action.

Successful SRT/VTT parsing prepares a separate SubtitleView. Only a selected,
successfully refreshed subtitle hides the native SubtitleView. Failure keeps
the previous display. Cue timing and styled Media3 cues are retained separately
so the native global subtitle offset is never modified by parsing.

Media3 events drive selection, seek, playback speed, pause and buffering changes.
The native CloudStream offset Apply/Reset actions also emit a seek event.
Only cue boundaries schedule display work; there is no recurring subtitle
state timer. The pre-existing dialog discovery timer remains for decorating
native dialogs and discovering replacement Player objects. It does not fetch,
parse, inspect subtitle selection/offset, or render cues.

Requests are cancelled at the HTTP call and guarded by generation, video
instance and Player identity before applying results. At most four parsed
refreshes are retained for the current video. Switching videos or destroying
the fragment releases the cache, overlay, listeners and scheduled cue work.

## Automated validation

Run `./gradlew :StremioC:testDebugUnitTest :StremioC:make`.

Unit tests cover overlapping cues, exact boundaries, gaps, arbitrary forward
and backward seeks, empty timelines, URL changes, duplicate IDs, missing IDs
and language mismatches. They do not establish Android UI or performance behavior.

## Device acceptance

Use the official CloudStream host and enable Elevate when testing coexistence.

- Select a subtitle, refresh its unchanged URL with changed content, and verify
  selection, language grouping, scroll position and StremioC order remain intact.
- Refresh an unselected row and verify the current subtitle remains visible;
  select the refreshed row using the native Apply action afterwards.
- Exercise same-name groups using the right-hand option rows on touch and TV
  D-pad navigation. Name and refresh actions must remain separately reachable.
- Refresh while an old cue is visible into a file with a gap at the playhead;
  the old cue must disappear. HTTP, malformed-file and ambiguous-match failures
  must preserve the last valid display.
- Seek both directions; test 0.5x/2x, pause/resume, buffering, positive/negative
  subtitle delay and reset while paused. Verify against native subtitle timing.
- Change style and enter/leave PiP repeatedly. Compare size and bottom position
  with Elevate's native subtitle view; only one subtitle display should appear.
- Switch to embedded subtitles, disable subtitles, change episodes and exit
  during a slow request. No late result may attach; embedded selection must
  continue to work and native alpha must be restored.
- Compare frame timing, allocations/GC and retained views with refresh unused,
  active and after repeated refresh/exit cycles on a physical device. Build and
  unit tests alone cannot certify absence of frame drops or memory regressions.
