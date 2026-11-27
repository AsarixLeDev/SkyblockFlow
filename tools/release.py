"""Validate explicit release declarations and render the two release assets."""
import argparse
import os
import re
import subprocess
import zipfile
import json
from pathlib import Path

def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()

def require(ok, message):
    if not ok:
        raise SystemExit(message)

def read_record(path):
    match = re.fullmatch(r"Type: (Feature|Fix|Improvement|Performance|Refactor)\nAuthor: ([^\n()]+)\nSummary: ([^\n]+)\nDetails: *([^\n]*(?:\n [^\n]+)*)\n*", path.read_text(encoding="utf8"))
    require(match and re.fullmatch(r"[a-z0-9-]+", path.stem), f"Invalid change record: {path}")
    return match.groups()

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--ref", default="")
    p.add_argument("--commit", default="HEAD")
    p.add_argument("--render", action="store_true")
    p.add_argument("--verify-jar",type=Path)
    args = p.parse_args()
    msg = git("show", "-s", "--format=%B", args.commit)
    values = []
    for field in ("Version", "Tested", "Mode"):
        hits = re.findall(r"^Flow-" + field + r": (\S+)$", msg, re.M)
        require(len(hits) == 1, f"Supply one developer Flow-{field} trailer.")
        values.append(hits[0])
    version, tested, mode = values
    require(tested in ("yes", "no") and mode in ("dev", "public", "beta"), "Invalid declaration")
    require(re.fullmatch(r"(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)" + ("" if mode == "public" else r"\.(?:0|[1-9]\d*)"), version), "Invalid version")
    expected = version + {"dev":"-dev", "beta":"-beta", "public":""}[mode]
    props = git("show", args.commit + ":gradle.properties")
    require(f"mod_version={expected}" in props.splitlines(), "Version differs from fabric build")
    publishing = args.ref.startswith("refs/tags/")
    if args.ref == "refs/heads/main" or publishing and mode != "beta":
        require(tested == "yes", "Untested changes belong on a branch")
    if publishing:
        require(mode != "dev", "A dev push cannot publish")
        tag = "SkyblockFlow-" + ("PUBLIC-" + version if mode == "public" else expected)
        require(args.ref == "refs/tags/" + tag, "Release tag differs from declaration")
        if tested == "yes" or mode == "public":
            require(subprocess.run(["git","merge-base","--is-ancestor",args.commit,"origin/main"]).returncode == 0, "Release commit must be on main")
        if mode == "beta":
            series, n = version.rsplit(".",1)
            others = git("tag","--list",f"SkyblockFlow-{series}.*-beta").splitlines()
            prev = [int(t.split(".")[-1].split("-")[0]) for t in others if t != tag]
            require(int(n) == max(prev,default=0)+1,"Beta number must follow its series")
    files = sorted(Path("release/changes").glob("*.txt"))
    records = {f:read_record(f) for f in files}
    if args.render:
        pattern = "SkyblockFlow-PUBLIC-*" if mode == "public" else "SkyblockFlow-*"
        described = subprocess.run(["git","describe","--tags","--abbrev=0","--match",pattern,args.commit+"^"],capture_output=True,text=True)
        tags = described.stdout.strip().splitlines() if described.returncode==0 else []
        baseline = next((t for t in tags if mode != "public" or t.startswith("SkyblockFlow-PUBLIC-")), None)
        prior = set(git("ls-tree","-r","--name-only",baseline,"release/changes").splitlines()) if baseline else set()
        require(not prior or not git("diff","--name-only","--diff-filter=MDR",baseline,args.commit,"--","release/changes"), "Published change records must be immutable")
        out = Path("build/release"); out.mkdir(parents=True,exist_ok=True)
        selected = [f for f in files if str(f) not in prior]
        require(selected,"Release has no change entries")
        title = f"SkyblockFlow {version} {mode.title()}"
        notes = ["# " + title, ""]
        if tested == "no":
            notes.extend(["> Untested beta (tested=no). In-game verification is still pending.", ""])
        details = ["Format: 1",""]
        for f in selected:
            kind, author, summary, _ = records[f]
            notes.append(f"- [{kind}] {summary} ({author})")
            details.extend(["["+f.stem+"]",f.read_text().strip(),""])
        (out/"RELEASE_NOTES.md").write_text("\n".join(notes)+"\n")
        (out/"CHANGE_DETAILS.txt").write_text("\n".join(details)+"\n")
    if args.verify_jar:
        with zipfile.ZipFile(args.verify_jar) as jar:
            require(jar.testzip() is None,"Corrupt release JAR")
            metadata=json.loads(jar.read("fabric.mod.json"))
            require(metadata["id"]=="skyblockflow" and metadata["version"]==expected,"Wrong Fabric ID or version")
    if os.getenv("GITHUB_ENV"):
        with open(os.environ["GITHUB_ENV"],"a") as env:
            for field,value in zip(("VERSION","TESTED","MODE"), values):
                print(f"FLOW_{field}={value}",file=env)

if __name__ == "__main__":
    main()
