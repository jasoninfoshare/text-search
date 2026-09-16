# 全文检索功能（Full-Text Search）

企业级全文检索子系统，支持公文/公告/新闻的统一检索、AI 扩词、语义召回、权限过滤。

> 本仓库为功能节选（个人开发模块），已脱敏：内网地址、企业名称、凭据均已替换为占位符，仅供技术参考。

## 功能特性

- **统一检索**：公文、新闻、子公司动态、通知公告、信息公开一站式搜索
- **AI 扩词**：基于大模型的关键词联想/同义词扩展（弱口令→弱密码），本地同义词表优先、模型兜底
- **语义召回**：Embedding 向量化 + Milvus 向量库 + Reranker 精排，字面召回不足时语义补位
- **权限过滤**：按角色档位 + 共享人列表过滤公文可见性，无权限文档不出现在结果中
- **拼音/首字母检索**：字母输入自动映射中文候选词
- **搜索日志**：记录关键词/用户/零结果率，用于召回质量数据驱动优化

## 技术栈

- **后端**：Spring Boot + Elasticsearch + Milvus + vLLM(Embedding/Reranker)
- **前端**：Vue 2 + Element UI
- **AI**：大模型关键词扩展（OpenAI 兼容接口）

## 目录结构

```
backend/src/main/java/com/example/search/
├── controller/          # 检索与词典控制器
│   ├── ElasticDetailController.java   # 检索主入口 /es/querys
│   └── DictController.java            # 词典/分词/同义词接口
├── service/                          # 业务服务
│   ├── SemanticService.java           # 语义召回（向量检索）
│   ├── DictService.java               # 本地词典/同义词/拼音
│   └── SearchLogService.java          # 搜索日志记录
└── es/                               # ES 底层封装
    ├── config/                        # ES 客户端配置
    ├── entity/                        # 查询/排序/附件实体
    └── service/impl/ElasticServiceImpl.java  # 检索查询构建（权限过滤、多词组合、标题提权）

frontend/src/
├── views/fullsearch/index.vue         # 检索主页（搜索框/分类/结果/高亮）
└── api/fullSearch/fullSecrch.js       # 检索 API
```

## 脱敏说明

- 内网主机地址已替换为 `${ES_HOST}`、`${GPU_NODE_HOST}`、`${DB_HOST}` 等占位符；
- 企业名称已替换为泛化描述；
- 配置文件中的数据库密码、密钥**本就不在本功能代码内**（属全局配置，未纳入本节选）。

## 本地提交后同步到 GitHub

本机网络拦截了 `git push` 的数据面，因此用 `sync_to_github.py`（走 GitHub REST API）同步：

- 本地 `git commit` 后，`post-commit` 钩子会自动执行同步，无需手动操作；
- 也可手动运行：`python sync_to_github.py`（`-m "msg"` 指定提交说明，`--dry-run` 只看差异）；
- 每次同步在 GitHub 上生成 1 个 commit，只包含相对远端有变化的文件；远端多出而本地已删的文件默认同步删除（`--keep-extra` 可关闭）；
- token 放在仓库**外**的 `../.github-token`（即 `D:\Works\.github-token`，不会被上传），或用环境变量 `GITHUB_TOKEN`；token 吊销/过期后换新写入该文件即可。

注意：GitHub 上的提交历史由同步脚本生成，与本地 commit 历史不一一对应（本地多个 commit 可能合并为一次同步 commit）。

## 核心实现要点

- **权限过滤**：角色分档（公司领导全量 / 分公司按 tenantId / 部门主任按 deptId / 普通员工按共享人 userIds），fail-closed；
- **召回分层**：精确匹配 → 本地同义词/模板 → 拼音 → AI 模型兜底 → 语义向量召回，逐层降级且互不污染；
- **多词组合**：组内同义词 OR、组间 AND，避免泛词组合淹没；
- **标题提权**：constant_score 常量提权，标题命中排最前，不受正文词频累积影响。
