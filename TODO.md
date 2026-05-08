# TODO

- Improve recognition for quiet speech.
  - Add configurable digital gain before VAD/ASR input.
  - Evaluate simple AGC with RMS target and max gain limiting.
  - Tune VAD threshold and hangover time so soft speech is not cut off.
  - Consider noise suppression before gain if background noise is amplified.

- Add speaker voiceprint recognition.
  - Start with speaker identification for registered profiles.
  - Add a `SpeakerEmbeddingEngine` that extracts embeddings from 16kHz mono PCM.
  - Store registered speaker profiles locally with averaged embeddings from several enrollment clips.
  - Match incoming speech with cosine similarity and show "unknown speaker" below threshold.
  - Run embedding on 1.5-3 seconds of VAD-positive speech instead of every audio frame.
  - Smooth speaker decisions with repeated matches or short-window voting before updating UI.
  - Keep speaker model artifacts out of Git and document how to place them locally.
