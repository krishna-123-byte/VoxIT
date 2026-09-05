#!/usr/bin/env python3
import argparse, hashlib, json
import onnx, onnxruntime as ort

EXPECTED = "6a752e443e848b125808a5dd941799c89b572df0db6ca3f610542f24268c5a6f"
def main():
    parser=argparse.ArgumentParser(); parser.add_argument("model"); args=parser.parse_args()
    data=open(args.model,"rb").read(); digest=hashlib.sha256(data).hexdigest()
    if digest != EXPECTED: raise SystemExit("Checksum mismatch")
    onnx.checker.check_model(onnx.load(args.model))
    session=ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])
    assert session.get_inputs()[0].name == "audio" and session.get_inputs()[0].shape == [1,64600]
    assert session.get_outputs()[0].name == "logits" and session.get_outputs()[0].shape == [1,2]
    print(json.dumps({"valid": True, "sha256": digest}))
if __name__ == "__main__": main()
