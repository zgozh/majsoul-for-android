"""Build the pinned Mortal v4 encoder and stage its Android runtime assets."""

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
from zipfile import ZipFile


HERE = Path(__file__).resolve().parent
BUILD = HERE.parents[1] / "build"
WORK = BUILD / "mortal-work"
OUTPUT = BUILD / "mortal-android"
REVISION = "0cff2b52982be5b1163aa9a62fb01f03ce91e0d2"
ORT = "1.23.2"
ORT_SHA = "82048d1f462218adae4ba76477089ab0ba76093d84f733540066db1a8ba6b827"
TARGETS = {"arm64-v8a": "aarch64-linux-android", "x86_64": "x86_64-linux-android"}


def fetch(path, url):
    if not path.is_file():
        part = path.with_suffix(".part")
        subprocess.run(["curl.exe", "-fsSL", "--retry", "2", "-o", part, url], check=True)
        part.replace(path)
    return path


def without_python(path, text):
    if path == "state/obs_repr.rs":
        start = text.index("#[pymethods]")
        end = text.index("\nimpl PlayerState {", text.index("fn encode_obs_py", start))
        text = text[:start] + text[end:]
    if path in ("state/mod.rs", "consts.rs"):
        text = text.split("pub(crate) fn register_module(", 1)[0]
    return re.sub(r"(?m)^\s*(?:use (?:pyo3|numpy|crate::py_helper)::.*;|"
                  r"#\[(?:pyclass|pymethods|pyfunction|pyo3|new|getter)\b.*\])\n", "\n", text)


def stage_sources(archive, crate):
    src = crate / "src"
    src.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(HERE / "Cargo.toml", crate / "Cargo.toml")
    if (HERE / "Cargo.lock").is_file():
        shutil.copyfile(HERE / "Cargo.lock", crate / "Cargo.lock")
    shutil.copyfile(HERE / "lib.rs", src / "lib.rs")
    with ZipFile(archive) as bundle:
        if bundle.comment.decode() != REVISION:
            raise ValueError("Mortal source archive has the wrong revision")
        root = bundle.namelist()[0].split("/", 1)[0]
        prefix = f"{root}/libriichi/src/"
        core = {"array.rs", "chi_type.rs", "consts.rs", "hand.rs", "macros.rs",
                "mjai/event.rs", "rankings.rs", "tile.rs", "vec_ops.rs"}
        for entry in bundle.infolist():
            if entry.is_dir() or not entry.filename.startswith(prefix):
                continue
            name = entry.filename.removeprefix(prefix)
            if name not in core and not name.startswith(("algo/", "state/")):
                continue
            target = src / name
            target.parent.mkdir(parents=True, exist_ok=True)
            content = bundle.read(entry)
            if target.suffix == ".rs":
                content = without_python(name, content.decode("utf-8")).encode("utf-8")
            target.write_bytes(content)
        (src / "mjai/mod.rs").write_text("mod event;\npub use event::{Event, EventWithCanAct};\n", encoding="utf-8")

        # Reuse the upstream action mapping, including chi position and red tiles.
        agent = bundle.read(prefix + "agent/mortal.rs").decode("utf-8")
        mapping = agent.split("        let event = match action {", 1)[1].split("        let mut meta", 1)[0]
        mapping = "        let event = match action {" + mapping
        mapping = mapping.replace("let Some(kan_idx) = kan_select_idx", "let Some(kan_action) = kan_action")
        mapping = mapping.replace("must_tile!(self.actions[kan_idx])", "must_tile!(kan_action)")
        decoder = """// SPDX-License-Identifier: AGPL-3.0-or-later
use anyhow::{Context, Result, ensure};
use crate::{must_tile, tu8};
use crate::mjai::Event;
use crate::state::PlayerState;

pub fn reaction(state: &PlayerState, actor: u8, action: usize, kan_action: Option<usize>) -> Result<Event> {
    let akas_in_hand = state.akas_in_hand();
    let cans = state.last_cans();
"""
        (src / "decode.rs").write_text(decoder + mapping + "    Ok(event)\n}\n", encoding="utf-8")
        assets = OUTPUT / "assets/mortal-s42"
        assets.mkdir(parents=True, exist_ok=True)
        (assets / "libriichi-LICENSE").write_bytes(bundle.read(f"{root}/LICENSE"))


def compile_native(crate, ndk, cargo):
    clang = ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
    for abi, target in TARGETS.items():
        env = dict(os.environ)
        variable = "CARGO_TARGET_" + target.upper().replace("-", "_")
        env[variable + "_LINKER"] = str(clang)
        env[variable + "_RUSTFLAGS"] = (
            f"-C link-arg=--target={target}26 -C link-arg=-Wl,-z,max-page-size=16384"
        )
        lock = ["--locked"] if (HERE / "Cargo.lock").is_file() else []
        subprocess.run([cargo, "build", "--release", "--target", target, *lock],
                       cwd=crate, env=env, check=True)
        dest = OUTPUT / "jniLibs" / abi
        dest.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(crate / f"target/{target}/release/libmortal_android.so", dest / "libmortal_android.so")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True, help="Verified Mortal-S42 ONNX export")
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--cargo", default="cargo.exe")
    args = parser.parse_args()

    WORK.mkdir(parents=True, exist_ok=True)
    source = fetch(WORK / "mortal.zip", f"https://codeload.github.com/Equim-chan/Mortal/zip/{REVISION}")
    crate = WORK / "port"
    stage_sources(source, crate)
    ort = fetch(WORK / f"onnxruntime-android-{ORT}.aar",
                f"https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/"
                f"{ORT}/onnxruntime-android-{ORT}.aar")
    with ort.open("rb") as handle:
        if hashlib.file_digest(handle, "sha256").hexdigest() != ORT_SHA:
            raise ValueError("Unexpected ONNX Runtime AAR")
    compile_native(crate, args.ndk.resolve(), args.cargo)
    shutil.copyfile(ort, OUTPUT / "onnxruntime-android.aar")
    shutil.copyfile(args.model, OUTPUT / "assets/mortal-s42/mortal-s42.onnx")
    print(f"Mortal Android artifacts are in {OUTPUT}")


if __name__ == "__main__":
    main()
