#!/usr/bin/env python3
import argparse, hashlib, importlib.util, json
from pathlib import Path
import numpy as np
import onnx, onnxruntime as ort, torch

CHECKPOINT_SHA256 = "814331d088032bb4c3fa61cc014789eadeed464209dd094ab3a2dd6ffbdce27a"
EXPECTED_ONNX_SHA256 = "6a752e443e848b125808a5dd941799c89b572df0db6ca3f610542f24268c5a6f"

def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as stream:
        for chunk in iter(lambda: stream.read(65536), b""): h.update(chunk)
    return h.hexdigest()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--aasist-repo", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    checkpoint = args.aasist_repo / "models/weights/AASIST-L.pth"
    if sha256(checkpoint) != CHECKPOINT_SHA256: raise SystemExit("Official checkpoint checksum mismatch")
    spec = importlib.util.spec_from_file_location("aasist", args.aasist_repo / "models/AASIST.py")
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    config = json.loads((args.aasist_repo / "config/AASIST-L.conf").read_text())
    base = module.Model(config["model_config"])
    base.load_state_dict(torch.load(checkpoint, map_location="cpu", weights_only=True)); base.eval()
    class OutputOnly(torch.nn.Module):
        def __init__(self, model): super().__init__(); self.model = model
        def forward(self, audio): return self.model(audio)[1]
    model = OutputOnly(base).eval(); torch.manual_seed(42); sample = torch.randn(1, 64600) * .02
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with torch.no_grad(): reference = model(sample).numpy()
    torch.onnx.export(model, sample, args.output, input_names=["audio"], output_names=["logits"], opset_version=17, do_constant_folding=True)
    onnx.checker.check_model(onnx.load(args.output))
    session = ort.InferenceSession(str(args.output), providers=["CPUExecutionProvider"])
    actual = session.run(["logits"], {"audio": sample.numpy()})[0]
    difference = float(np.max(np.abs(reference - actual)))
    if difference > 1e-4: raise SystemExit(f"Parity failure: {difference}")
    digest = sha256(args.output)
    if digest != EXPECTED_ONNX_SHA256: raise SystemExit(f"Unexpected ONNX checksum {digest}; use the pinned environment")
    print(json.dumps({"sha256": digest, "bytes": args.output.stat().st_size, "max_abs_difference": difference, "input": [1,64600], "output": [1,2]}, indent=2))

if __name__ == "__main__": main()
