#!/usr/bin/env python3
"""Split the fixed Apollo export into CPU FFTs and a QNN-compatible spectral core.

Requires onnx, numpy and onnxruntime. The manifest pins every bundled model by hash.
Run: python tools/prepare_apollo_npu.py model.onnx app/src/main/assets/upscale
"""
import argparse
import ast
import copy
import hashlib
import json
from pathlib import Path

import numpy as np
import onnx
from onnx import helper, numpy_helper
from onnxruntime.transformers.float16 import convert_float_to_float16


def prepare(source: Path, destination: Path, license_file: Path | None = None) -> None:
    model = onnx.load(source)
    shape = [d.dim_value for d in model.graph.input[0].type.tensor_type.shape.dim]
    if shape != [1, 2, 88200]:
        raise ValueError(f"Expected the stereo two-second Apollo export, got {shape}")
    stft = [n for n in model.graph.node if n.op_type == "STFT"]
    dft = [n for n in model.graph.node if n.op_type == "DFT"]
    if len(stft) != 1 or len(dft) != 1:
        raise ValueError("Expected one STFT and one inverse DFT")
    # Keep the spectral layout used by the waveform export; no sample reordering.
    spectral_input = stft[0].output[0]
    spectral_output = dft[0].input[0]
    extractor = onnx.utils.Extractor(model)
    stages = {
        "frontend.onnx": extractor.extract_model([model.graph.input[0].name], [spectral_input]),
        "core.onnx": extractor.extract_model([spectral_input], [spectral_output]),
        "backend.onnx": extractor.extract_model([spectral_output], [model.graph.output[0].name]),
    }
    core = stages["core.onnx"]
    # Express reciprocal and norms through elementary ops for a portable core.
    nodes = []
    one = "petal_qnn_one"
    core.graph.initializer.append(numpy_helper.from_array(np.array([1.0], np.float32), one))
    for index, node in enumerate(core.graph.node):
        if node.op_type == "Reciprocal":
            nodes.append(helper.make_node("Div", [one, node.input[0]], list(node.output), name=node.name))
        elif node.op_type == "ReduceL2":
            squared = f"petal_qnn_squared_{index}"
            summed = f"petal_qnn_summed_{index}"
            nodes.append(helper.make_node("Mul", [node.input[0], node.input[0]], [squared]))
            reduction = helper.make_node("ReduceSum", [squared, *node.input[1:]], [summed])
            reduction.attribute.extend(node.attribute)
            nodes.append(reduction)
            nodes.append(helper.make_node("Sqrt", [summed], list(node.output), name=node.name))
        else:
            nodes.append(node)
    del core.graph.node[:]
    core.graph.node.extend(nodes)
    core = onnx.shape_inference.infer_shapes(core)
    onnx.checker.check_model(core)
    stages["core.onnx"] = core
    # Compile only the six heavy BSNet blocks on HTP. One giant graph causes
    # excessive allocator use during Qualcomm's target-sequencing pass.
    latent_shape = [2, 80, 256, 201]
    shapes = {v.name: [d.dim_value for d in v.type.tensor_type.shape.dim]
              for v in core.graph.value_info}
    if shapes.get("stack") != latent_shape:
        raise ValueError("Unexpected Apollo latent layout")
    boundaries = ["stack"]
    for layer in range(6):
        candidates = []
        for node in core.graph.node:
            metadata = {p.key: p.value for p in node.metadata_props}
            scopes = ast.literal_eval(metadata.get("pkg.torch.onnx.name_scopes", "[]"))
            if f"net.{layer}" in scopes:
                candidates.extend(o for o in node.output if shapes.get(o) == latent_shape)
        if not candidates:
            raise ValueError(f"Cannot locate BSNet block {layer}")
        boundaries.append(candidates[-1])
    core_extractor = onnx.utils.Extractor(core)
    stages["encoder.onnx"] = core_extractor.extract_model([spectral_input], [boundaries[0]])
    stages["decoder.onnx"] = core_extractor.extract_model([boundaries[-1]], [spectral_output])
    for index, (start, end) in enumerate(zip(boundaries, boundaries[1:])):
        block = core_extractor.extract_model([start], [end])
        # Explicit fp16 I/O keeps format conversion outside HTP.
        half_block = convert_float_to_float16(copy.deepcopy(block), keep_io_types=False)
        onnx.checker.check_model(half_block)
        stages[f"block-{index}-fp16.onnx"] = half_block
    destination.mkdir(parents=True, exist_ok=True)
    files = {}
    for name, stage in stages.items():
        data = stage.SerializeToString()
        (destination / name).write_bytes(data)
        files[name] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        print(f"{name}: {len(stage.graph.node)} nodes, {len(data):,} bytes")
    manifest = {
        "version": "apollo-spectral-v2-blocks",
        "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "files": files,
    }
    (destination / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    if license_file is not None:
        (destination / "Apollo-LICENSE.txt").write_bytes(license_file.read_bytes())
    (destination / "ATTRIBUTION.txt").write_text(
        "Apollo by JusperLee and contributors: https://github.com/JusperLee/Apollo\n"
        "Licensed under CC BY-SA 4.0; see Apollo-LICENSE.txt.\n"
        "PetalMusic splits the waveform export into FFT and spectral stages and\n"
        "rewrites reciprocal/norm operations using equivalent elementary math.\n"
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--license-file", type=Path)
    args = parser.parse_args()
    prepare(args.source, args.destination, args.license_file)
