# AASIST-L for VoxIT — experimental model card

- Model ID: `aasist-l-asvspoof2019-la-v1`
- Architecture: AASIST-L, 85,306 parameters
- Source/checkpoint/licence: NAVER Clova official AASIST repository commit `a04c986...`; `AASIST-L.pth`; MIT
- Training data: ASVspoof 2019 Logical Access train partition
- Published evaluation: upstream reports 0.99% EER and 0.0309 min t-DCF on ASVspoof 2019 LA
- VoxIT accuracy evaluation: not performed; no dataset downloaded
- Input/output: float32 `[1,64600]` raw 16 kHz mono waveform; logits `[1,2]`, class 1 bona fide
- Android score: softmax class-0 probability; uncalibrated
- Intended use: research warning for uploaded speech, never identity or fraud determination
- Out of scope: subtype claims, speaker verification, criminal intent, reliable replay detection
- Major risks: domain, language, channel, codec, microphone, noise, and unseen-generator shift
