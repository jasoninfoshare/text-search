# Full-Text Search · 全文检索功能

**English** | [中文说明](#中文说明)

An enterprise full-text search subsystem providing unified retrieval over official documents, announcements, and news — with LLM-based query expansion, semantic recall, reranking, and fail-closed role-based permission filtering.

> This repository is a sanitized excerpt of a module I built. Internal hostnames, company names, and credentials have been replaced with placeholders; published for technical reference only.

## Features

- **Unified retrieval** across official documents, news, subsidiary updates, notices, and public-disclosure content
- **LLM query expansion** — keyword association and synonym expansion, with a local synonym dictionary taking priority and the model used only as fallback
- **Semantic recall** — embedding vectorization, Milvus vector store, and a reranker, filling in where lexical matching falls short
- **Permission filtering** — role-tiered visibility plus per-document sharing lists; unauthorized documents never appear in results
- **Pinyin / initials search** — Latin input mapped to Chinese candidate terms
- **Search-quality logging** — keywords, users, and zero-result rate recorded to drive relevance work with data

## Core implementation notes

- **Layered recall** — exact match → local synonym/template → pinyin → LLM fallback → semantic vector recall. Each stage degrades independently, without contaminating the others.
- **Permission model** — fail-closed role tiers: company leadership (full access), subsidiary by `tenantId`, department head by `deptId`, regular employee by shared `userIds`.
- **Multi-term combination** — synonyms OR-ed within a group, groups AND-ed together, so generic terms cannot flood the result set.
- **Title boosting** — `constant_score` so title hits rank first, unaffected by body-text term-frequency accumulation.

## Stack

| Layer | Technology |
| --- | --- |
| Backend | Spring Boot · Elasticsearch · Milvus · vLLM (embedding / reranker) |
| Frontend | Vue 2 · Element UI |
| AI | LLM keyword expansion over an OpenAI-compatible API |

**Entry points** — `ElasticDetailController` (`/es/querys`) for search, `ElasticServiceImpl` for query construction, permission filtering and ranking, `SemanticService` for vector recall, `DictService` for the local dictionary, synonyms and pinyin. Full tree below.

---

## 中文说明

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

## 核心实现要点

- **权限过滤**：角色分档（公司领导全量 / 分公司按 tenantId / 部门主任按 deptId / 普通员工按共享人 userIds），fail-closed；
- **召回分层**：精确匹配 → 本地同义词/模板 → 拼音 → AI 模型兜底 → 语义向量召回，逐层降级且互不污染；
- **多词组合**：组内同义词 OR、组间 AND，避免泛词组合淹没；
- **标题提权**：constant_score 常量提权，标题命中排最前，不受正文词频累积影响。
