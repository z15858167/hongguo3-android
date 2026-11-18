#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""本机 github.com 直连/代理都不通（只有 api.github.com 通），所以 git push 走不了，
这里用 GitHub Git Data REST API 手动提交：blob -> tree -> commit -> ref。

分支策略：dev 开发，main 只接受 PR 合并。

用法:
  python3 tools/ghpush.py "提交说明"                  # 推到 dev，并确保 dev->main 的 PR 存在
  python3 tools/ghpush.py "提交说明" --no-pr           # 只推 dev，不开 PR
  python3 tools/ghpush.py "提交说明" --main            # 直接推 main（仅紧急修复，正常别用）
  python3 tools/ghpush.py "提交说明" --branch feat-x   # 推到别的分支
  python3 tools/ghpush.py "提交说明" --rm a b c        # 同时在远端删除指定路径
  python3 tools/ghpush.py --status                     # 看分支、PR、最近构建
  python3 tools/ghpush.py --merge                      # 把 dev->main 的 PR 合并掉
  python3 tools/ghpush.py --wait                       # 推完后等 CI 跑完并打印结果
"""
import argparse
import base64
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

REPO_OWNER = "z15858167"
REPO = "hongguo3-android"
DEV = "dev"
MAIN = "main"
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


def req(method, path, payload=None, raw=False, ok404=False):
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
        body = e.read().decode("utf-8", "replace")
        if ok404 and e.code == 404:
            return None
        if e.code == 422 and "pull request" in body:
            sys.exit("main 有分支保护：不能直接推，请推到 dev，再用 "
                     "`python3 tools/ghpush.py --merge` 合并 PR。")
        sys.exit("HTTP %s %s\n%s" % (e.code, method + " " + path, body[:800]))
    if raw:
        return body
    return json.loads(body) if body else {}


def head_sha(branch):
    """分支头；分支不存在返回 None。"""
    r = req("GET", "/git/ref/heads/" + branch, ok404=True)
    return r["object"]["sha"] if r else None


def ensure_branch(branch, from_sha):
    """分支不存在就从 from_sha 建出来（等价 git push origin main:refs/heads/dev）。"""
    if head_sha(branch):
        return
    req("POST", "/git/refs", {"ref": "refs/heads/" + branch, "sha": from_sha})
    print("已创建远端分支 %s（基于 %s）" % (branch, from_sha[:8]))


def open_prs():
    return req("GET", "/pulls?state=open&per_page=50") or []


def find_pr(head, base):
    for p in open_prs():
        if p["head"]["ref"] == head and p["base"]["ref"] == base:
            return p
    return None


def ensure_pr(title, body):
    p = find_pr(DEV, MAIN)
    if p:
        print("PR 已存在：#%d %s" % (p["number"], p["html_url"]))
        return p
    main = head_sha(MAIN)
    dev = head_sha(DEV)
    if main == dev:
        print("dev 与 main 一致，暂不需要 PR")
        return None
    p = req("POST", "/pulls", {"title": title, "head": DEV, "base": MAIN, "body": body})
    print("已开 PR：#%d %s" % (p["number"], p["html_url"]))
    return p


def update_local_ref(ref, sha):
    subprocess.run(["git", "update-ref", ref, sha], cwd=ROOT,
                   capture_output=True, text=True)


def push(branch, msg, rms):
    head = head_sha(branch)
    if head is None:
        head = head_sha(MAIN)
        ensure_branch(branch, head)
        head = head_sha(branch)
    base_tree = req("GET", "/git/commits/" + head)["tree"]["sha"]

    files = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True,
                           text=True).stdout.split()
    if not files:
        sys.exit("没有可提交的文件")

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
    req("PATCH", "/git/refs/heads/" + branch, {"sha": commit["sha"]})
    print("已推送 %d 个文件、删除 %d 个（%.1f KB）-> %s %s"
          % (len(tree) - len(rms), len(rms), total / 1024.0,
             branch, commit["sha"][:8]))
    update_local_ref("refs/remotes/origin/" + branch, commit["sha"])
    return commit["sha"]


def latest_run(sha=None):
    d = req("GET", "/actions/runs?per_page=20")
    runs = d.get("workflow_runs", [])
    if sha:
        runs = [r for r in runs if r["head_sha"].startswith(sha) or r["head_sha"] == sha]
    return runs[0] if runs else None


def wait_ci(sha, timeout_min=40):
    print("等待 CI ...")
    t0 = time.time()
    while time.time() - t0 < timeout_min * 60:
        r = latest_run(sha)
        if r is None:
            time.sleep(15)
            continue
        if r["status"] == "completed":
            print("CI %s（%s）%s" % (r["conclusion"], r["head_branch"], r["html_url"]))
            return r
        time.sleep(20)
    print("等太久了，自己去看：https://github.com/%s/%s/actions" % (REPO_OWNER, REPO))
    return None


def show_status():
    main = head_sha(MAIN)
    dev = head_sha(DEV)
    print("main : %s" % (main[:8] if main else "-"))
    print("dev  : %s %s" % (dev[:8] if dev else "-",
                            "（与 main 相同）" if dev and dev == main else ""))
    cmp = req("GET", "/compare/%s...%s" % (MAIN, DEV or MAIN))
    print("dev 领先 main %d 个提交" % cmp.get("ahead_by", 0))
    for p in open_prs():
        print("PR #%d [%s] %s -> %s  %s"
              % (p["number"], p["state"], p["head"]["ref"], p["base"]["ref"], p["html_url"]))
    if not open_prs():
        print("PR  : 无")
    r = latest_run()
    if r:
        print("最近构建：%s %s %s %s"
              % (r["head_branch"], r["status"], r["conclusion"] or "", r["html_url"]))
    rel = req("GET", "/releases?per_page=5") or []
    print("最近发布：")
    for x in rel:
        print("  %-24s %s" % (x["tag_name"], x["name"]))


def merge_pr():
    p = find_pr(DEV, MAIN)
    if not p:
        print("没有 dev -> main 的待合并 PR")
        return
    if p.get("mergeable") is False:
        sys.exit("PR #%d 有冲突，先在 dev 上把 main 合过来" % p["number"])
    # 用 merge commit（不用 squash）：保住 dev 的历史，下次 PR 才不会一直显示"未合并"
    r = req("PUT", "/pulls/%d/merge" % p["number"],
            {"merge_method": "merge",
             "commit_title": "%s (#%d)" % (p["title"].splitlines()[0], p["number"])})
    if not r.get("merged"):
        sys.exit("合并失败：%s" % r.get("message"))
    print("已合并 PR #%d -> main（%s）" % (p["number"], r["sha"][:8]))
    m = head_sha(MAIN)
    update_local_ref("refs/heads/main", m)
    update_local_ref("refs/remotes/origin/main", m)
    print("本地 main 已同步到 %s" % m[:8])


def main():
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("msg", nargs="?", default="update")
    ap.add_argument("--branch", default=None)
    ap.add_argument("--main", action="store_true",
                    help="直接推 main（main 有分支保护，会被 GitHub 拒绝；正常请用 --merge）")
    ap.add_argument("--no-pr", action="store_true")
    ap.add_argument("--rm", nargs="*", default=[])
    ap.add_argument("--merge", action="store_true")
    ap.add_argument("--status", action="store_true")
    ap.add_argument("--wait", action="store_true")
    a = ap.parse_args()

    if a.status:
        show_status()
        return
    if a.merge:
        merge_pr()
        return

    branch = MAIN if a.main else (a.branch or DEV)

    # 推送前先做静态体检，不通过就不推（避免又一次"只有真机能发现问题"）
    preflight = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'preflight.py')
    if subprocess.run([sys.executable, preflight]).returncode != 0:
        sys.exit('preflight 未通过，已取消推送')

    sha = push(branch, a.msg, a.rm)

    if branch == DEV and not a.no_pr:
        ensure_pr(a.msg.splitlines()[0],
                  "自动从 dev 提的 PR。合并到 main 后会发一个版本化的正式 release。")
    if a.main:
        print("注意：这次是直接推 main，会立刻发布正式版本")

    if a.wait:
        wait_ci(sha)


if __name__ == "__main__":
    main()
