#!/usr/bin/env python3
"""
sync_to_github.py — 把本地仓库已跟踪的文件同步到 GitHub（走 REST API）

适用于公司网络拦截 git push 数据面的场景。
每次同步在 GitHub 上生成 1 个 commit（只包含相对远端有变化的文件），
远端多出来而本地没有的文件默认会被删除（镜像同步，可用 --keep-extra 关闭）。

用法:
    python sync_to_github.py            # 同步（commit message 取本地 HEAD 的提交说明）
    python sync_to_github.py -m "msg"   # 指定 GitHub 侧 commit message
    python sync_to_github.py --dry-run  # 只看会做什么，不真正上传

Token 来源（按优先级）:
    1. 环境变量 GITHUB_TOKEN
    2. 仓库上级目录下的 .github-token 文件（本机即 D:\\Works\\.github-token）
"""

import argparse
import base64
import json
import os
import subprocess
import sys
import urllib.request
import urllib.error

REPO = "jasoninfoshare/text-search"
BRANCH = "main"
API = "https://api.github.com"

REPO_DIR = os.path.dirname(os.path.abspath(__file__))
TOKEN_FILE = os.path.join(os.path.dirname(REPO_DIR), ".github-token")


def load_token():
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if token:
        return token
    if os.path.isfile(TOKEN_FILE):
        with open(TOKEN_FILE, "r", encoding="utf-8") as f:
            token = f.read().strip()
        if token:
            return token
    sys.exit(
        "找不到 GitHub token。请设置环境变量 GITHUB_TOKEN，"
        f"或把 token 写入文件: {TOKEN_FILE}"
    )


def api_request(method, path, token, payload=None):
    url = f"{API}{path}"
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    req.add_header("User-Agent", "local-sync-script")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        sys.exit(f"GitHub API 错误: {method} {url} -> {e.code}\n{detail}")
    except urllib.error.URLError as e:
        sys.exit(f"网络错误（公司网络可能拦截了 api.github.com）: {e}")


def git(*args):
    return subprocess.run(
        ["git", *args], cwd=REPO_DIR, capture_output=True, text=True, check=True
    ).stdout


def local_files():
    """返回 {path: (mode, blob_sha)}，基于 git 已跟踪文件 + 工作区内容。"""
    entries = {}
    for line in git("ls-files", "-s").splitlines():
        meta, path = line.split("\t", 1)
        mode = meta.split()[0]
        sha = git("hash-object", "--", path).strip()
        entries[path] = (mode, sha)
    return entries


def remote_files(token):
    """返回 (head_commit_sha, tree_sha, {path: (mode, blob_sha)})。"""
    ref = api_request("GET", f"/repos/{REPO}/git/refs/heads/{BRANCH}", token)
    head_sha = ref["object"]["sha"]
    commit = api_request("GET", f"/repos/{REPO}/git/commits/{head_sha}", token)
    tree_sha = commit["tree"]["sha"]
    tree = api_request(
        "GET", f"/repos/{REPO}/git/trees/{tree_sha}?recursive=1", token
    )
    if tree.get("truncated"):
        sys.exit("远端文件树过大被截断，暂不支持（仓库文件太多了）。")
    entries = {
        t["path"]: (t["mode"], t["sha"]) for t in tree["tree"] if t["type"] == "blob"
    }
    return head_sha, tree_sha, entries


def main():
    parser = argparse.ArgumentParser(description="同步本地仓库到 GitHub（REST API）")
    parser.add_argument("-m", "--message", help="GitHub 侧 commit message")
    parser.add_argument("--keep-extra", action="store_true",
                        help="不删除远端多出来的文件（默认镜像删除）")
    parser.add_argument("--dry-run", action="store_true", help="只打印差异，不上传")
    args = parser.parse_args()

    token = load_token()
    local = local_files()
    head_sha, base_tree, remote = remote_files(token)

    to_upload = [p for p, (m, s) in local.items()
                 if remote.get(p) != (m, s)]
    to_delete = [] if args.keep_extra else [p for p in remote if p not in local]

    if not to_upload and not to_delete:
        print("已是最新：远端与本地一致，无需同步。")
        return

    print(f"待上传 {len(to_upload)} 个文件:")
    for p in to_upload:
        print(f"  + {p}")
    print(f"待删除 {len(to_delete)} 个文件:")
    for p in to_delete:
        print(f"  - {p}")

    if args.dry_run:
        print("(dry-run，未做任何修改)")
        return

    tree_entries = []
    for path in to_upload:
        with open(os.path.join(REPO_DIR, path), "rb") as f:
            content = base64.b64encode(f.read()).decode("ascii")
        blob = api_request("POST", f"/repos/{REPO}/git/blobs", token,
                           {"content": content, "encoding": "base64"})
        tree_entries.append({"path": path, "mode": local[path][0],
                             "type": "blob", "sha": blob["sha"]})
    for path in to_delete:
        tree_entries.append({"path": path, "mode": remote[path][0],
                             "type": "blob", "sha": None})

    tree = api_request("POST", f"/repos/{REPO}/git/trees", token,
                       {"base_tree": base_tree, "tree": tree_entries})

    local_head = git("rev-parse", "HEAD").strip()
    message = args.message or git("log", "-1", "--pretty=%s").strip()
    message += f"\n\nSynced from local commit {local_head}"
    commit = api_request("POST", f"/repos/{REPO}/git/commits", token,
                         {"message": message, "tree": tree["sha"],
                          "parents": [head_sha]})

    api_request("PATCH", f"/repos/{REPO}/git/refs/heads/{BRANCH}", token,
                {"sha": commit["sha"]})

    print(f"同步完成: https://github.com/{REPO}/commit/{commit['sha']}")


if __name__ == "__main__":
    main()
