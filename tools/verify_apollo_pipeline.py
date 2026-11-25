#!/usr/bin/env python3
"""Verify split pipeline numerics against the original export on actual audio.

Input is an interleaved stereo float32 file at 44100 Hz. Requires numpy and
onnxruntime 1.30.0. Does not require a phone or execute Qualcomm HTP kernels.
"""
import argparse
from pathlib import Path
import numpy as np
import onnxruntime as ort


def verify(source: Path, stages: Path, pcm: Path) -> None:
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.log_severity_level = 3
    full = ort.InferenceSession(str(source), options)
    front = ort.InferenceSession(str(stages / "frontend.onnx"), options)
    core = ort.InferenceSession(str(stages / "core.onnx"), options)
    back = ort.InferenceSession(str(stages / "backend.onnx"), options)
    encoder = ort.InferenceSession(str(stages / "encoder.onnx"), options)
    decoder = ort.InferenceSession(str(stages / "decoder.onnx"), options)
    blocks = [ort.InferenceSession(str(stages / f"block-{index}-fp16.onnx"), options) for index in range(6)]
    audio = np.fromfile(pcm, np.float32).reshape(-1, 2)
    for second in (0, 10, 60):
        start = second * 44100
        if start + 88200 > len(audio):
            continue
        chunk = np.ascontiguousarray(audio[start:start + 88200].T[None])
        expected = full.run(None, {full.get_inputs()[0].name: chunk})[0]
        spectrum = front.run(None, {front.get_inputs()[0].name: chunk})[0]
        prediction = core.run(None, {core.get_inputs()[0].name: spectrum})[0]
        actual = back.run(None, {back.get_inputs()[0].name: prediction})[0]
        error = actual - expected
        maximum = float(np.max(np.abs(error)))
        rms = float(np.sqrt(np.mean(error * error)))
        print(f"{second}s: max error={maximum:.8f}, RMS error={rms:.8f}", flush=True)
        if actual.shape != chunk.shape or not np.isfinite(actual).all() or rms > 0.001 or maximum > 0.02:
            raise AssertionError("split pipeline failed waveform parity")
        latent = encoder.run(None, {encoder.get_inputs()[0].name: spectrum})[0].astype(np.float16)
        for block in blocks:
            latent = block.run(None, {block.get_inputs()[0].name: latent})[0]
        hybrid_spec = decoder.run(None, {decoder.get_inputs()[0].name: latent.astype(np.float32)})[0]
        relative = float(np.sqrt(np.mean((hybrid_spec - prediction) ** 2)) /
                         max(1e-7, np.sqrt(np.mean(prediction ** 2))))
        print(f"{second}s: hybrid fp16 spectral relative RMS={relative:.6f}", flush=True)
        if not np.isfinite(hybrid_spec).all() or relative > 0.01:
            raise AssertionError("partitioned fp16 pipeline failed spectral parity")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("stages", type=Path)
    parser.add_argument("pcm", type=Path)
    args = parser.parse_args()
    verify(args.source, args.stages, args.pcm)
