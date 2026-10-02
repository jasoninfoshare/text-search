# 开发说明 / Development Notes

本文件记录本仓库的本地开发与同步流程，与检索功能本身无关。

## 本地提交后同步到 GitHub

本机网络拦截了 `git push` 的数据面，因此用 `sync_to_github.py`（走 GitHub REST API）同步：

- 本地 `git commit` 后，`post-commit` 钩子会自动执行同步，无需手动操作；
- 也可手动运行：`python sync_to_github.py`（`-m "msg"` 指定提交说明，`--dry-run` 只看差异）；
- 每次同步在 GitHub 上生成 1 个 commit，只包含相对远端有变化的文件；远端多出而本地已删的文件默认同步删除（`--keep-extra` 可关闭）；
- token 放在仓库**外**的 `../.github-token`（即 `D:\Works\.github-token`，不会被上传），或用环境变量 `GITHUB_TOKEN`；token 吊销/过期后换新写入该文件即可。

注意：GitHub 上的提交历史由同步脚本生成，与本地 commit 历史不一一对应（本地多个 commit 可能合并为一次同步 commit）。
