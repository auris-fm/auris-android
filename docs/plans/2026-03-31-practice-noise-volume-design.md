# Practice Noise Volume Design

## Decision

Simplify the background-noise practice control so listeners adjust a single `Noise volume` value instead of managing an enable switch plus three tuning sliders.

## UX

- Keep the existing noise environment selector (`Coffee shop`, `Busy street`, `Meeting room`).
- Remove the dedicated background-noise on/off switch.
- Remove the separate `Intensity`, `Eventfulness`, and `Spatial motion` sliders from the Effects screen.
- Add one `Noise volume` slider in the noise section.
- Interpret `0%` noise volume as background noise off.
- Interpret any value above `0%` as background noise on.

This keeps the other two practice filters unchanged: `Mask voice` and `Low-pass` remain explicit toggles.

## Data Model

The canonical state remains `PracticeFilters`, but the background-noise portion is simplified:

- `noiseIntensity` becomes the stored source of truth for user-controlled noise volume.
- `isBackgroundNoiseEnabled` is derived from `noiseIntensity > 0f` instead of being independently chosen in the UI.
- `noiseEventfulness` and `noiseSpatialMotion` are no longer user-tunable from the UI and should fall back to stable defaults when building the effective filter state.

## Playback Behavior

- Moving the slider updates background noise during playback without restarting audio.
- When the slider reaches `0%`, the playback pipeline should stop adding background noise.
- When the slider increases from `0%`, the playback pipeline should enable background noise using the selected environment and the slider value as volume/intensity.
- Existing environment selection behavior remains unchanged.

## Error Handling

- Existing unsupported/processing-failure messaging remains unchanged.
- If local playback cannot apply the filter, playback continues and the current failure message stays visible.

## Testing

- Player UI tests should cover conversion between slider progress and `PracticeFilters`, especially the `0% => disabled` rule.
- Playback processor tests should continue proving that higher noise volume produces higher output energy.
- Regression coverage should confirm no crash in pass-through/disabled paths when volume is `0%`.
