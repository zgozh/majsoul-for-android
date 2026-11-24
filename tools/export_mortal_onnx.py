# SPDX-License-Identifier: AGPL-3.0-or-later
"""Export the desktop Mortal-S42 policy and verify it against the original runtime."""

import argparse
from pathlib import Path
import sys
from tempfile import TemporaryDirectory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('desktop_app', type=Path, help='Windows portable package app directory')
    parser.add_argument('output', type=Path, help='Path for the verified ONNX model')
    args = parser.parse_args()

    import numpy as np
    import onnx
    import onnxruntime as ort
    import torch

    model_root = args.desktop_app.resolve() / 'models' / 'mortal-s42'
    if not (model_root / 'inference.py').is_file():
        parser.error(f'Mortal-S42 runtime is missing from {model_root}')
    sys.path.insert(0, str(model_root))
    import inference
    from libriichi.consts import ACTION_SPACE, obs_shape

    torch.set_num_threads(2)
    desktop = inference.load_engine('cpu')
    shape = tuple(obs_shape(desktop.version))

    class Policy(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.brain = desktop.brain
            self.dqn = desktop.dqn

        def forward(self, observation, legal):
            return self.dqn(self.brain(observation), legal)

    class VerifiedEngine:
        def __init__(self, session):
            self.session = session
            self.compared = 0

        def __getattr__(self, name):
            return getattr(desktop, name)

        def react_batch(self, observations, masks, invisible):
            actions, expected, returned, greedy = desktop.react_batch(observations, masks, invisible)
            legal = np.stack(masks)
            (q,) = self.session.run(['q'], {'observation': np.stack(observations), 'legal': legal})
            np.testing.assert_array_equal(np.isneginf(q), ~legal)
            np.testing.assert_allclose(q[legal], np.asarray(expected)[legal], rtol=1e-5, atol=1e-4)
            chosen = q.argmax(axis=-1)
            np.testing.assert_array_equal(chosen, actions)
            self.compared += len(observations)
            return chosen.tolist(), q.tolist(), returned, greedy

    args.output.parent.mkdir(exist_ok=True, parents=True)
    with TemporaryDirectory(dir=args.output.parent) as scratch:
        model = Path(scratch) / 'mortal-s42.onnx'
        observation = torch.zeros((1, *shape), dtype=torch.float32)
        legal = torch.ones((1, ACTION_SPACE), dtype=torch.bool)
        torch.onnx.export(
            Policy().eval(), (observation, legal), str(model),
            input_names=['observation', 'legal'], output_names=['q'],
            dynamic_axes={name: {0: 'batch'} for name in ('observation', 'legal', 'q')},
            opset_version=17, dynamo=False, external_data=False,
        )
        onnx.checker.check_model(str(model))
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        candidate = VerifiedEngine(ort.InferenceSession(str(model), options, providers=['CPUExecutionProvider']))
        result = inference.smoke(candidate)
        print(f'Mortal-S42 ONNX parity passed: {candidate.compared} observations, '
              f'{len(result["synthetic_mjai_cases"])} MJAI cases.')
        del candidate
        model.replace(args.output)
    print(args.output.resolve())


if __name__ == '__main__':
    main()
