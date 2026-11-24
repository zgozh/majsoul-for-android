"""Create desktop-reference replay cases and check the dual-model APK contents."""

import argparse
import hashlib
import json
from pathlib import Path
import sys
from zipfile import ZipFile


def check_apk(path):
    with ZipFile(path) as apk:
        required = {
            "assets/epsilon-u3-v413/m0/weights.bin",
            "assets/mortal-s42/mortal-s42.onnx",
        }
        for abi in ("arm64-v8a", "x86_64"):
            for library in ("djl_torch", "mortal_android", "onnxruntime", "onnxruntime4j_jni"):
                required.add(f"lib/{abi}/lib{library}.so")
        missing = required - set(apk.namelist())
        if missing:
            raise AssertionError(f"APK is missing {sorted(missing)}")


def reference(desktop):
    runtime = desktop / "models/mortal-s42"
    sys.path.insert(0, str(runtime))
    import inference
    import numpy as np
    import torch

    torch.set_num_threads(2)
    original = inference.load_engine("cpu")

    class Capture:
        def __getattr__(self, name):
            return getattr(original, name)

        def react_batch(self, observations, masks, invisible):
            result = original.react_batch(observations, masks, invisible)
            for obs, mask, q in zip(observations, masks, result[1]):
                calls.append({
                    "observation": hashlib.sha256(np.asarray(obs, dtype="<f4").tobytes()).hexdigest(),
                    "legal": [bool(flag) for flag in mask],
                    "q": [float(value) if flag else None for flag, value in zip(mask, q)],
                })
            return result

    fixtures = [
        (f"seat-{seat}-{draw}", seat, inference.synthetic_events(seat, draw))
        for seat in range(4) for draw in ("1p", "9s", "E")
    ]
    kan = inference.synthetic_events(0, "2m")
    kan[1]["tehais"][0] = ["2m"] * 3 + ["3p", "4p", "5pr", "4s", "5s", "6s", "7s", "8s", "E", "E"]
    fixtures.append(("concealed-kan-choice", 0, kan))
    chi = inference.synthetic_events()
    chi[1]["tehais"][0] = ["3m", "4m", "6m", "7m", "3p", "4p", "5pr", "4s", "5s", "6s", "E", "E", "E"]
    chi[1]["oya"] = 3
    chi[2:] = [{"type": "tsumo", "actor": 3, "pai": "?"},
               {"type": "dahai", "actor": 3, "pai": "5m", "tsumogiri": True}]
    fixtures.append(("three-chi-choices", 0, chi))
    fixtures.append(("no-decision", 0, inference.synthetic_events()[:-1]))

    cases = []
    for label, seat, events in fixtures:
        calls = []
        session = inference.Session(Capture(), seat)
        for event in events[:-1]:
            session.bot.react(json.dumps(event), can_act=False)
        outcome = session.react(events[-1]) or {"type": "none"}
        outcome.pop("meta", None)
        if outcome["type"] == "reach":
            discard = session.react(outcome)
            assert discard["type"] == "dahai"
            outcome["pai"] = discard["pai"]
        cases.append({
            "label": label, "request": {"model": "mortal-s42", "player_id": seat, "events": events},
            "reaction": outcome, "calls": calls,
        })
    return cases


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--desktop", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.apk:
        check_apk(args.apk)
        print("APK contains both models and both native ABIs")
    if args.desktop and args.output:
        cases = reference(args.desktop.resolve())
        args.output.write_text(json.dumps(cases, allow_nan=False), encoding="utf-8")
        print(f"Captured {len(cases)} Windows MJAI cases and {sum(len(c['calls']) for c in cases)} tensors")
