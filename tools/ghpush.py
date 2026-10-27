#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""本机 github.com 直连/代理都不通（只有 api.github.com 通），所以 git push 走不了，
这里用 GitHub Git Data REST API 手动提交：blob -> tree -> commit -> ref。

用法:
  python3 tools/ghpush.py "提交说明"                # 提交 git ls-files 里的全部文件
  python3 tools/ghpush.py "提交说明" --rm a b c    # 同时在远端删除指定路径
"""
import base64
import json
import os
import subprocess
import sys
import urllib.request
import urllib.error

REPO_OWNER = "z15858167"
REPO = "hongguo3-android"
BRANCH = "main"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def token():
    out = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n",
        capture_output=True, text=True).stdout
    for line in out.splitlines():
        if line.startswith("password="):
            return line[len("password="):]
    sys.exit("拿不到 GitHub token")


TOK = token()
API = "https://api.github.com/repos/%s/%s" % (REPO_OWNER, REPO)


def req(method, path, payload=None, raw=False):
    url = API + path if path.startswith("/") else path
    data = None
    hdr = {"Authorization": "Bearer " + TOK,
           "Accept": "application/vnd.github+json",
           "User-Agent": "ghpush"}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        hdr["Content-Type"] = "application/json"
    r = urllib.request.Request(url, data=data, headers=hdr, method=method)
    try:
        with urllib.request.urlopen(r, timeout=120) as f:
            body = f.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        sys.exit("HTTP %s %s\n%s" % (e.code, method + " " + path,
                                     e.read().decode("utf-8", "replace")[:800]))
    if raw:
        return body
    return json.loads(body) if body else {}


def main():
    argv = sys.argv[1:]
    rms = []
    if "--rm" in argv:
        i = argv.index("--rm")
        rms = argv[i + 1:]
        argv = argv[:i]
    msg = argv[0] if argv else "update"
    files = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True,
                           text=True).stdout.split()
    if not files:
        sys.exit("没有可提交的文件")

    head = req("GET", "/git/ref/heads/" + BRANCH)["object"]["sha"]
    base_tree = req("GET", "/git/commits/" + head)["tree"]["sha"]

    tree = []
    total = 0
    for p in files:
        full = os.path.join(ROOT, p)
        if not os.path.isfile(full):
            continue
        with open(full, "rb") as f:
            content = f.read()
        total += len(content)
        mode = "100755" if os.access(full, os.X_OK) else "100644"
        blob = req("POST", "/git/blobs", {
            "content": base64.b64encode(content).decode("ascii"),
            "encoding": "base64"})
        tree.append({"path": p, "mode": mode, "type": "blob", "sha": blob["sha"]})

    # sha=null 表示从树上移除该路径（GitHub Git Data API 的删除语义）
    for p in rms:
        tree.append({"path": p, "mode": "100644", "type": "blob", "sha": None})

    new_tree = req("POST", "/git/trees", {"base_tree": base_tree, "tree": tree})
    commit = req("POST", "/git/commits",
                 {"message": msg, "tree": new_tree["sha"], "parents": [head]})
    req("PATCH", "/git/refs/heads/" + BRANCH, {"sha": commit["sha"]})
    print("已推送 %d 个文件、删除 %d 个（%.1f KB）-> %s"
          % (len(tree) - len(rms), len(rms), total / 1024.0, commit["sha"][:8]))


if __name__ == "__main__":
    main()
