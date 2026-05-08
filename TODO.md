# TODO

- Improve recognition for quiet speech.
  - Add configurable digital gain before VAD/ASR input.
  - Evaluate simple AGC with RMS target and max gain limiting.
  - Tune VAD threshold and hangover time so soft speech is not cut off.
  - Consider noise suppression before gain if background noise is amplified.
