# VoxIT voice-integrity ML workspace

This workspace reproduces the AASIST-L ONNX artifact used by the Android import path. It does not contain datasets, recordings, checkpoints, or generated models.

## Reproduce the model

1. Clone `https://github.com/clovaai/aasist` and checkout `a04c9863f63d44471dde8a6abcb3b082b07cd1d1`.
2. Verify `models/weights/AASIST-L.pth` SHA-256 is `814331d088032bb4c3fa61cc014789eadeed464209dd094ab3a2dd6ffbdce27a`.
3. Create a Python 3.9 environment and run `pip install -r ml/requirements.txt`.
4. Run `python ml/export_aasist_l.py --aasist-repo /path/to/aasist --output ml/artifacts/aasist-l.onnx`.
5. Run `python ml/validate_onnx.py ml/artifacts/aasist-l.onnx`.
6. Import the resulting 648,428-byte ONNX file from VoxIT's Upload Recording screen.

The export command checks the source checkpoint, validates ONNX, compares PyTorch and ONNX logits on generated data, and requires a maximum absolute difference below `1e-4`.

## Evaluation

`dataset-manifest.schema.json` defines path-only metadata. Keep all audio outside Git. Run `evaluate.py` with a CSV containing `path,split,speaker_id,source_group,generator_family,label,score` after producing real model scores. It rejects cross-split speaker/source leakage and writes metrics to stdout. Calibration and threshold selection must use validation only; the final test split must never be used for tuning.

Generated tones and mocked logits are plumbing tests only, not accuracy evidence.
