# Phase 4 model evaluation

Research date: 2026-09-05. Only original papers, official repositories, official challenge pages, and official runtime documentation were used.

| Candidate | Provenance and licence | Task/input | Mobile assessment | Decision |
|---|---|---|---|---|
| AASIST | [NAVER Clova official repository](https://github.com/clovaai/aasist), [paper](https://arxiv.org/abs/2110.01200), MIT; repository includes `AASIST.pth` | Binary bona-fide/spoof, ASVspoof 2019 LA, raw 16 kHz, 64,600 samples | 297,866 parameters; 1.28 MB checkpoint; ONNX export works but is larger/slower than AASIST-L | Valid provenance, not selected because the official light variant is smaller |
| AASIST-L | Same official repository/paper/MIT; repository includes `AASIST-L.pth` | Same binary LA task and preprocessing | 85,306 parameters; 426,428-byte checkpoint; fixed `[1,64600]` float input; exported ONNX is 643,404 bytes | **Selected** for uploaded-audio experiments |
| ASVspoof RawNet2 baseline | [Official ASVspoof 2021 repository](https://github.com/asvspoof-challenge/2021), [paper](https://arxiv.org/abs/2011.01108), MIT; official download script/checkpoint | Binary LA/DF/PA variants, raw waveform | Larger recurrent/raw-waveform network; GRU and custom sinc front end make mobile export and latency less attractive | Not selected |
| ASVspoof LFCC-LCNN baseline | [Official ASVspoof 2021 repository](https://github.com/asvspoof-challenge/2021), BSD-3-Clause; official download script/checkpoint | Binary LA/DF/PA variants, LFCC front end | Requires exact LFCC implementation plus LCNN/BiLSTM parity; greater preprocessing risk on Android | Not selected |
| SSL anti-spoofing systems | Official research implementations exist, but commonly depend on large wav2vec 2.0 encoders | Binary spoof detection, typically 16 kHz | Hundreds of MB and high RAM/latency; outside this prototype's mobile budget | Rejected for this phase |

## Selected checkpoint and export

- Repository commit: `a04c9863f63d44471dde8a6abcb3b082b07cd1d1`
- Official checkpoint: `models/weights/AASIST-L.pth`
- Checkpoint SHA-256: `814331d088032bb4c3fa61cc014789eadeed464209dd094ab3a2dd6ffbdce27a`
- Licence: MIT, copyright NAVER Corp.
- Training/evaluation described upstream: ASVspoof 2019 Logical Access.
- Upstream published result: 0.99% EER and 0.0309 minimum t-DCF for AASIST-L on the in-domain ASVspoof 2019 LA evaluation protocol. These are **not VoxIT measurements**.
- Input: raw mono float32 waveform, 16 kHz, exactly 64,600 samples. Upstream evaluation truncates longer clips and repeat-pads shorter clips.
- Output: two logits. Upstream evaluation uses index 1 as the bona-fide score; VoxIT reports softmax probability for index 0 as an uncalibrated spoof/synthetic score.
- Export: opset 17, fixed `audio [1,64600]` → `logits [1,2]`; canonical artifact size 648,428 bytes.
- Verified ONNX SHA-256: `6a752e443e848b125808a5dd941799c89b572df0db6ca3f610542f24268c5a6f`.
- Desktop parity on a generated deterministic tensor: maximum absolute logit difference `1.6689300537109375e-06`.
- Desktop ONNX CPU timing from this development Mac: 94.9 ms for one generated window. This is not an Android performance claim.

## Scope and scientific limitations

AASIST-L is a binary countermeasure trained for ASVspoof 2019 Logical Access attacks, which include synthetic speech and voice conversion. It is not a reliable TTS-versus-VC-versus-clone classifier, does not identify a speaker, and was not trained as a general replay detector. ASVspoof 2021 reports show substantial degradation under codec/channel and cross-dataset shifts. Hindi, Hinglish, mobile microphones, telephone/VoIP channels, current commercial cloning systems, and unseen generator families have not been evaluated by VoxIT.

The app therefore labels the probability as an **uncalibrated experimental score**, quality-gates unusable recordings, reports inconclusive outcomes, and keeps transcript scam risk separate. No detector accuracy evaluation was performed because no dataset was downloaded.

## Deployment decision

ONNX Runtime Android is the official local runtime and supports Android ARM64 and x86_64. The model is not committed. A user exports it reproducibly from the official checkout and imports it with Android's Storage Access Framework. VoxIT accepts only the exact SHA-256 and tensor contract above; it never falls back to Demo Mode or another model.

## Evaluation design

Future manifests must enforce speaker- and source-recording-disjoint train/validation/test splits, group every derived or compressed version with its source, reserve generator families for an unseen-generator test, and select thresholds only on validation data. Reports must stratify language/accent, speaker, microphone, noise, codec, telephone/VoIP channel, attack family, and replay/re-recording condition. Required metrics are accuracy, precision, recall, F1, ROC-AUC, PR-AUC, FPR, FNR, EER, and confusion matrix at the exact Android threshold.

## Future consent-based speaker verification

Speaker verification should be a separate Phase 5 system. Enrollment would require explicit consent, multiple prompted clean utterances, local encrypted embeddings, liveness/spoof gating, user-visible deletion, and a separately calibrated mismatch threshold. A mismatch would remain a verification prompt, never proof of fraud.
