#!/usr/bin/env python3
"""No APK build. Windows: python native/test-directory-manifest.py --windows-host
Linux: c++ -std=c++17 -O2 -Wall -Wextra -Werror native/syncmeta/syncmeta.cpp -o /tmp/syncmeta
       python3 native/test-directory-manifest.py --native /tmp/syncmeta
"""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import threading

REPO = Path(__file__).resolve().parent.parent
SOURCE = REPO / "native/syncmeta/syncmeta.cpp"
PARSER = REPO / "app/src/main/java/com/rootsync/android/engine/DirectoryManifest.kt"


def header(count, flags=0):
    return b"RSMAN1\0\0" + struct.pack("<IIQ", 1, flags, count)


def record(path, kind=2, target=b"", digest=b"", nanos=123, size=3):
    if isinstance(path, str):
        path = path.encode("utf-8")
    return struct.pack("<BqqIIIB", kind, size, -2, nanos, len(path), len(target), len(digest)) + path + target + digest


def windows_host(tmp):
    source = SOURCE.read_text(encoding="utf-8")
    sha = source[source.index("struct DmSha256 {"):source.index("struct DmFd {")]
    harness = """#include <array>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <stdexcept>
void dm_require(bool ok, const char* reason) { if(!ok) throw std::runtime_error(reason); }
""" + sha + """
int main(int argc, char** argv) {
    if(argc!=2) return 1;
    FILE* f=fopen(argv[1],"rb"); if(!f) return 2;
    DmSha256 sha; unsigned char b[777]; size_t n;
    while((n=fread(b,1,sizeof(b),f))) sha.update(b,n);
    if(ferror(f)) return 3;
    fclose(f);
    for(auto v:sha.finish()) printf("%02x",v);
    return 0;
}
"""
    cpp = tmp / "sha.cpp"
    cpp.write_text(harness, encoding="utf-8")
    assert cpp.read_text(encoding="utf-8") == harness
    compiler = shutil.which("g++") or r"C:\msys64\ucrt64\bin\g++.exe"
    exe = tmp / "sha.exe"
    subprocess.run([compiler, "-std=c++17", "-O2", "-Wall", "-Wextra", "-Werror",
                    str(cpp), "-o", str(exe)], check=True)
    env = dict(os.environ)
    env["PATH"] = str(Path(compiler).parent) + os.pathsep + env.get("PATH", "")
    for size in [0, 1, 3, 55, 56, 63, 64, 65, 127, 128, 1000000]:
        data = b"abc" if size == 3 else bytes((i * 37) % 256 for i in range(size))
        fixture = tmp / "hash-input"
        fixture.write_bytes(data)
        result = subprocess.check_output([str(exe), str(fixture)], env=env).decode("ascii")
        assert result == hashlib.sha256(data).hexdigest(), size
    print("PASS: production SHA256, 11 vectors including padding boundaries and 1MB", flush=True)
    fixtures = tmp / "fixtures"
    fixtures.mkdir()
    good = {
        "empty": header(0),
        "missing": header(0, 1),
        "strict_missing": header(0, 3),
        "unicode": header(3, 2) + record("目录", 1, size=0) +
                   record("目录/a\n\t\\:😀", digest=hashlib.sha256(b"abc").digest()) +
                   record("目录/link", 3, target="../目标\n".encode("utf-8")),
        "max_entries": header(200000) + b"".join(record(f"{i:06d}") for i in range(200000)),
    }
    bad = {
        "truncated": header(1) + b"\x02",
        "too_many": header(200001),
        "duplicate": header(2) + record("a") * 2,
        "unsorted": header(2) + record("z") + record("a"),
        "absolute": header(1) + record("/x"),
        "dotdot": header(1) + record("../x"),
        "dot": header(1) + record("."),
        "double_slash": header(1) + record("a//b"),
        "nul": header(1) + record(b"a\0b"),
        "utf8": header(1) + record(b"\xff"),
        "surrogate": header(1) + record(b"\xed\xa0\x80"),
        "excluded": header(1) + record(".rsync-partial"),
        "history": header(1) + record(".rootsync-history"),
        "missing_parent": header(1) + record("a/b"),
        "link_parent": header(2) + record("a", 3, target=b"other") + record("a/b"),
        "strict_no_hash": header(1, 2) + record("a"),
        "loose_hash": header(1) + record("a", digest=b"x" * 32),
        "dir_hash": header(1, 2) + record("a", 1, digest=b"x" * 32),
        "nanos": header(1) + record("a", nanos=1000000000),
        "size": header(1) + record("a", size=-1),
        "target": header(1) + record("a", target=b"x"),
        "empty_target": header(1) + record("a", 3),
        "missing_entries": header(1, 1) + record("a"),
        "flags": header(0, 4),
        "trailing": header(0) + b"x",
        "long_path": header(1) + record("a" * 16385),
        "unknown_kind": header(1) + record("a", 4),
        "text_budget": header(1100) + b"".join(record(f"{i:04d}" + "x" * 16000) for i in range(1100)),
    }
    for prefix, cases in [("good", good), ("bad", bad)]:
        for name, data in cases.items():
            (fixtures / f"{prefix}-{name}").write_bytes(data)
    with (fixtures / "bad-file_budget").open("wb") as file:
        file.truncate(64 * 1024 * 1024 + 1)
    kotlin = tmp / "ParserFixture.kt"
    kotlin.write_text("""import com.rootsync.android.engine.*
import java.io.File
import java.io.IOException
fun main(args: Array<String>) {
    var tested=0
    File(args[0]).listFiles()!!.sortedBy { it.name }.forEach { file ->
        val result = try { DirectoryManifest.read(file) } catch (e: IOException) {
            check(file.name.startsWith("bad-")) { file.name + ": " + e }
            null
        }
        if (result != null) {
            check(file.name.startsWith("good-")) { "Accepted invalid " + file.name }
            if (file.name == "good-unicode") {
                check(result.strictHashes && !result.missingRoot)
                check(result.entries[1].relativePath == "目录/a\\n\\t\\\\:😀")
                check(result.entries[1].mtimeSeconds == -2L && result.entries[1].mtimeNanos == 123)
                check(result.entries[1].sha256 == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
                check(result.entries[2].symlinkTarget == "../目标\\n")
            }
            if (file.name == "good-max_entries") check(result.entries.size == 200000)
        }
        tested++
    }
    println("PASS: Kotlin parser " + tested + " fixtures including 200000 entries under -Xmx256m")
}
""", encoding="utf-8")
    assert "目录" in kotlin.read_text(encoding="utf-8")
    cache = REPO / ".gradle-local/caches/modules-2/files-2.1"
    deps = [
        "org.jetbrains.kotlin/kotlin-compiler-embeddable",
        "org.jetbrains.kotlin/kotlin-stdlib", "org.jetbrains.kotlin/kotlin-script-runtime",
        "org.jetbrains.kotlin/kotlin-reflect", "org.jetbrains.kotlin/kotlin-daemon-embeddable",
        "org.jetbrains.intellij.deps/trove4j", "org.jetbrains/annotations",
        "org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm",
    ]
    cp = os.pathsep.join(str(jar) for dep in deps for jar in (cache / dep).rglob("*.jar"))
    java = shutil.which("java") or r"C:\Program Files (x86)\jdk-17.0.6+10\bin\java.exe"
    jar = tmp / "parser.jar"
    subprocess.run([java, "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib", "-no-reflect", "-classpath", cp, "-d", str(jar),
                    str(PARSER), str(kotlin)], check=True)
    subprocess.run([java, "-Xmx256m", "-cp", str(jar) + os.pathsep + cp,
                    "ParserFixtureKt", str(fixtures)], check=True)


def native(binary, tmp):
    root = tmp / "root"
    root.mkdir()
    output = tmp / "result"
    def run(path=root, strict=1, ok=True):
        result = subprocess.run([str(binary), "manifest", str(path), str(output), str(strict)],
                                capture_output=True)
        assert (result.returncode == 0) == ok, result.stderr
        assert output.exists() == ok
        return result

    missing = tmp / "not-created"
    run(missing)
    assert not missing.exists() and output.read_bytes() == header(0, 3)
    (root / "目录").mkdir()
    names = ["empty", "abc", "目录/换行\n制表\t引号'\"\\😀"]
    for name, data in zip(names, [b"", b"abc", b"a" * 1000000]):
        (root / name).write_bytes(data)
    (root / "link").symlink_to("目录", target_is_directory=True)
    (root / "dangling").symlink_to("../不存在")
    for directory in [root, root / "目录"]:
        for exclude in [".rsync-partial", ".rootsync-history"]:
            (directory / exclude).mkdir()
            (directory / exclude / "hidden").write_bytes(b"ignore")
    run()
    first = output.read_bytes()
    run()
    assert output.read_bytes() == first
    assert first[:24] == header(6, 2)
    pos, paths = 24, []
    for _ in range(6):
        kind, size, seconds, nanos, plen, tlen, hlen = struct.unpack_from("<BqqIIIB", first, pos)
        pos += 30
        path = first[pos:pos+plen].decode("utf-8")
        pos += plen
        target = first[pos:pos+tlen].decode("utf-8")
        pos += tlen
        digest = first[pos:pos+hlen]
        pos += hlen
        paths.append(path.encode("utf-8"))
        st = (root / path).lstat()
        assert size == st.st_size and seconds * 1000000000 + nanos == st.st_mtime_ns
        if kind == 2:
            assert digest == hashlib.sha256((root / path).read_bytes()).digest()
        if kind == 3:
            assert target == os.readlink(root / path)
    assert pos == len(first) and paths == sorted(paths)
    run(strict=0)
    run(strict=2, ok=False)
    os.mkfifo(root / "fifo")
    run(ok=False)
    (root / "fifo").unlink()
    root_link = tmp / "root-link"
    root_link.symlink_to(root, target_is_directory=True)
    run(str(root_link) + "/", ok=False)
    (root / "invalid-\udcff").write_bytes(b"x")
    run(ok=False)
    (root / "invalid-\udcff").unlink()
    locked = root / "locked"
    locked.mkdir()
    locked.chmod(0)
    if os.geteuid() != 0:
        run(ok=False)
    else:
        runtime = tmp / "public-runtime"
        runtime.mkdir(mode=0o777)
        runtime.chmod(0o777)
        tmp.chmod(0o755)
        denied_output = runtime / "denied"
        denied_output.write_bytes(header(0))
        denied = subprocess.run([str(binary), "manifest", str(locked / "child"),
                                 str(denied_output), "1"],
                                preexec_fn=lambda: (os.setgid(65534), os.setuid(65534)),
                                capture_output=True)
        assert denied.returncode != 0 and not denied_output.exists()
    locked.chmod(0o755)
    changing = root / "changing"
    changing.write_bytes(b"x" * (32 * 1024 * 1024))
    stop = threading.Event()
    def mutate():
        with changing.open("r+b", buffering=0) as file:
            i = 0
            while not stop.is_set():
                file.seek(0)
                file.write(bytes([i % 256]))
                i += 1
    writer = threading.Thread(target=mutate)
    writer.start()
    try:
        run(ok=False)
    finally:
        stop.set()
        writer.join()
    snapshot = tmp / "snapshot"
    subprocess.run([str(binary), "snapshot", str(root), str(snapshot)], check=True)
    assert snapshot.read_bytes().startswith(b"RSMETA1\0")
    subprocess.run([str(binary), "restore", str(root), str(snapshot)], check=True)
    subprocess.run([str(binary), "verify", str(root), str(snapshot), "0"], check=True)
    subprocess.run([str(binary), "filelist", str(root), str(tmp / "filelist"),
                    "0", "9999999999999"], check=True)
    print("PASS: native metadata/hash/links/exclusions/missing/errors/mutation/legacy fixtures")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--windows-host", action="store_true")
    parser.add_argument("--native", type=Path)
    args = parser.parse_args()
    if not args.windows_host and not args.native:
        parser.error("choose --windows-host or --native")
    with tempfile.TemporaryDirectory(prefix="directory-manifest-") as directory:
        tmp = Path(directory)
        if args.windows_host:
            windows_host(tmp)
        if args.native:
            native(args.native.resolve(), tmp)
