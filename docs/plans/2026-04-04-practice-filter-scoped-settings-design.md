# Practice Filter Scoped Settings Design

## Decision

Make practice filters follow the same settings scope as the existing playback effects controls.

## UX

- `All podcasts` edits the global/default practice-filter values.
- `This podcast` edits the current podcast's override practice-filter values.
- Switching tabs swaps the visible values and the active playback values without copying settings between scopes.
- The tab selection continues to use the existing `overrideGlobalEffects` toggle so playback effects and practice filters stay aligned.

## Data Model

- Keep `Settings.globalPracticeFilters` as the persisted global/default practice-filter source of truth.
- Add persisted per-podcast practice-filter fields to `Podcast` for:
  - background noise enabled
  - noise environment
  - noise intensity
  - voice masking enabled
  - low-pass enabled
- Add matching `*_modified` timestamps so podcast overrides continue to follow the existing sync/update pattern used by playback effects.
- Expose a derived `Podcast.practiceFilters` value, mirroring `Podcast.playbackEffects`.

## Playback Behavior

- When `overrideGlobalEffects` is `false`, playback uses `Settings.globalPlaybackEffects` and `Settings.globalPracticeFilters`.
- When `overrideGlobalEffects` is `true`, playback uses `Podcast.playbackEffects` and `Podcast.practiceFilters`.
- Editing from the Effects screen writes to the currently selected scope and immediately applies the effective practice filters to the active player.

## Persistence

- Add Room columns and a migration for the per-podcast practice-filter fields.
- Extend `PodcastDao` and `PodcastManager` with a single update path for writing podcast-scoped practice filters, similar to `updateEffectsBlocking(...)`.
- Keep global practice-filter persistence in `SettingsImpl`.

## Error Handling

- Existing practice-filter apply status and unsupported-output messages remain unchanged.
- Unsupported playback outputs still refuse application at the player layer, but the chosen scoped values remain persisted in the selected settings scope.

## Testing

- Add `PlaybackState` coverage proving the effective practice filters follow `overrideGlobalEffects` the same way playback effects do.
- Add ViewModel coverage proving saving writes to global settings for `All podcasts` and to the current podcast for `This podcast`.
- Keep existing playback processor regression tests to ensure the active filter payload still reaches the player correctly.
