"""Build the Android CPU runtime for Epsilon-Prism 3P u3-v413."""

import argparse
from hashlib import file_digest
import os
from pathlib import Path
import shutil
import subprocess
from tempfile import TemporaryDirectory
from zipfile import ZipFile


HERE = Path(__file__).resolve().parent
OUTPUT = HERE.parents[1] / "build" / "epsilon-android"
DJL = "c06319c0d78892606daf2c2136cd73c24cf8c1dc"
EPSILON = "c3b4683dcc6c3dee29177c9f6e57f536078446d8"
TORCH = "2.7.1"
TORCH_CHECKSUMS = {
    "arm64-v8a": "d0a301d0ebb550355751fe8559bd203e4a8217cc13378814a67c30c3123c4ef2",
    "x86_64": "2cb4c855c249ec238a4c6e2a204d8a5f92a12a077194362f9ffc9ac37d9a7667",
}


def run(*args, cwd=None):
    subprocess.run([str(arg) for arg in args], cwd=cwd, check=True)


def archive(cache, name, url):
    path = cache / name
    if not path.is_file():
        partial = path.with_suffix(".part")
        run("curl.exe", "--fail", "--location", "--retry", "2", "--output", partial, url)
        partial.replace(path)
    return path


def source(cache, work, repo, commit, archive_name, patch_name):
    path = archive(cache, archive_name, f"https://codeload.github.com/{repo}/zip/{commit}")
    with ZipFile(path) as bundle:
        if bundle.comment.decode() != commit:
            raise ValueError(f"Unexpected revision in {path}")
        root = work / bundle.namelist()[0].split("/", 1)[0]
        bundle.extractall(work)
    patch = HERE / patch_name
    run("git", "apply", "--whitespace=nowarn", patch, cwd=root)
    return root, patch


def java_runtime(work, java, model, source_dir, patch, output):
    classes = work / "epsilon-classes"
    classes.mkdir()
    jar = model / "epsilon-prism-3p-1.0-SNAPSHOT-all.jar"
    java_paths = [
        source_dir / line.removeprefix("+++ b/")
        for line in patch.read_text(encoding="utf-8").splitlines()
        if line.startswith("+++ b/")
    ]
    run(java / "javac.exe", "-encoding", "UTF-8", "-cp", jar, "-d", classes,
        *java_paths, HERE / "EpsilonMjai.java")
    with ZipFile(jar) as upstream, ZipFile(output / "epsilon.jar", "w") as bundle:
        replacements = {p.relative_to(classes).as_posix(): p for p in classes.rglob("*.class")}
        for entry in upstream.infolist():
            if entry.filename not in replacements and not entry.filename.startswith("jnilib/win-"):
                bundle.writestr(entry, upstream.read(entry))
        for name, compiled in replacements.items():
            bundle.write(compiled, name)


def native_runtime(args, work, source_dir, output):
    engine = source_dir / "engines/pytorch/pytorch-engine"
    native = source_dir / "engines/pytorch/pytorch-native"
    include = native / "build/include"
    include.mkdir(parents=True)
    run(args.java_home / "bin/javac.exe", "-h", include, "-d", work / "jni-classes",
        engine / "src/main/java/ai/djl/pytorch/jni/PyTorchLibrary.java")
    for abi in args.abi or TORCH_CHECKSUMS:
        name = f"torch-{TORCH}-{abi}.zip"
        url = f"https://publish.djl.ai/pytorch/{TORCH}/android_native/{abi}_native.zip"
        path = archive(args.downloads, name, url)
        with path.open("rb") as file:
            if file_digest(file, "sha256").hexdigest() != TORCH_CHECKSUMS[abi]:
                raise ValueError(f"Checksum mismatch in {path}")
        install = native / "libtorch_android" / abi
        with ZipFile(path) as bundle:
            bundle.extractall(install)
        (install / "install/include").rename(install / "include")
        build = work / f"build-{abi}"
        run(args.cmake, "-S", native, "-B", build, "-G", "Ninja",
            f"-DCMAKE_MAKE_PROGRAM={args.ninja}",
            f"-DCMAKE_TOOLCHAIN_FILE={args.ndk / 'build/cmake/android.toolchain.cmake'}",
            f"-DANDROID_ABI={abi}", "-DANDROID_PLATFORM=android-23", "-DBUILD_ANDROID=ON",
            "-DANDROID_STL=c++_static")
        run(args.cmake, "--build", build, "--parallel", args.jobs)
        target = output / "jniLibs" / abi
        target.mkdir(parents=True)
        strip = args.ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe"
        run(strip, "--strip-unneeded", "-o", target / "libdjl_torch.so", build / "libdjl_torch.so")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True, help="Epsilon model directory from the Windows distribution")
    parser.add_argument("--ndk", type=Path, required=True, help="Android NDK r28c directory")
    parser.add_argument("--java-home", type=Path, default=os.getenv("JAVA_HOME"), help="JDK 21 directory")
    parser.add_argument("--cmake", default="cmake.exe")
    parser.add_argument("--ninja", default=shutil.which("ninja.exe"))
    parser.add_argument("--downloads", type=Path, default=OUTPUT.parent / "epsilon-downloads")
    parser.add_argument("--abi", action="append", choices=TORCH_CHECKSUMS)
    parser.add_argument("--jobs", type=int, default=4)
    args = parser.parse_args()
    if not args.java_home or not args.ninja:
        parser.error("Set --java-home and --ninja or add them to the environment")
    args.downloads.mkdir(parents=True, exist_ok=True)

    with TemporaryDirectory(prefix="epsilon-android-") as tmp:
        work = Path(tmp)
        output = work / "output"
        assets = output / "assets/epsilon-u3-v413"
        for name in ("policy.json", "m0/model.json", "m0/weights.bin", "LICENSE"):
            target = assets / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(args.model / name, target)
        epsilon, patch = source(args.downloads, work, "KoutaChan/Epsilon", EPSILON,
                                "epsilon-source.zip", "epsilon-java.patch")
        java_runtime(work, args.java_home / "bin", args.model, epsilon, patch, output)
        djl, _ = source(args.downloads, work, "KoutaChan/djl-rocm", DJL,
                        "djl-rocm.zip", "djl-android.patch")
        native_runtime(args, work, djl, output)
        shutil.copytree(output, OUTPUT, dirs_exist_ok=True)
    print(f"Android Epsilon artifacts are in {OUTPUT}")


if __name__ == "__main__":
    main()
