package com.example.search.service;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语义检索，Embedding 向量化 + Milvus 召回
 */
@Slf4j
@Service
public class SemanticService {

    @Value("${es.semantic.enabled:false}")
    private boolean enabled;
    @Value("${es.semantic.embeddingUrl:http://${GPU_NODE_HOST}:5003/v1/embeddings}")
    private String embeddingUrl;
    @Value("${es.semantic.embeddingModel:Embedding01}")
    private String embeddingModel;
    @Value("${es.semantic.milvusUrl:http://${GPU_NODE_HOST}:19530}")
    private String milvusUrl;
    @Value("${es.semantic.collection:official_doc_vec}")
    private String collection;
    @Value("${es.semantic.timeout:6000}")
    private int timeout;
    @Value("${es.semantic.rerankEnabled:true}")
    private boolean rerankEnabled;
    @Value("${es.semantic.rerankUrl:http://${GPU_NODE_HOST}:5002/rerank}")
    private String rerankUrl;
    @Value("${es.semantic.rerankModel:Qwen3-Reranker-8B}")
    private String rerankModel;
    /** 语义召回最低相似度阈值：score 低于该值丢弃；默认 0 = 不过滤。注意 IP/COSINE 越大越相似，L2 越小越相似，按日志 score 分布设定 */
    @Value("${es.semantic.minScore:0}")
    private double minScore;

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isRerankEnabled() {
        return rerankEnabled;
    }

    /** 对候选文档按相关度重排，返回新顺序的索引数组，失败返回 null */
    public int[] rerank(String query, List<String> documents) {
        if (!rerankEnabled || query == null || query.trim().isEmpty() || documents == null || documents.isEmpty()) {
            return null;
        }
        try {
            JSONArray docs = new JSONArray();
            for (String d : documents) {
                docs.add(d == null ? "" : d);
            }
            JSONObject body = new JSONObject();
            body.set("model", rerankModel);
            body.set("query", query);
            body.set("documents", docs);
            String resp = HttpUtil.createPost(rerankUrl)
                    .header("Content-Type", "application/json")
                    .body(body.toString()).timeout(timeout).execute().body();
            JSONArray results = JSONUtil.parseObj(resp).getJSONArray("results");
            if (results == null || results.isEmpty()) {
                return null;
            }
            int[] order = new int[results.size()];
            for (int i = 0; i < results.size(); i++) {
                order[i] = results.getJSONObject(i).getInt("index");
            }
            return order;
        } catch (Exception e) {
            log.warn("Reranker 重排失败，保持原序: {}", e.getMessage());
            return null;
        }
    }

    /** 对候选文档按相关度打分，返回与 documents 下标对齐的得分数组，失败返回 null */
    public double[] rerankScores(String query, List<String> documents) {
        if (!rerankEnabled || query == null || query.trim().isEmpty() || documents == null || documents.isEmpty()) {
            return null;
        }
        try {
            JSONArray docs = new JSONArray();
            for (String d : documents) {
                docs.add(d == null ? "" : d);
            }
            JSONObject body = new JSONObject();
            body.set("model", rerankModel);
            body.set("query", query);
            body.set("documents", docs);
            String resp = HttpUtil.createPost(rerankUrl)
                    .header("Content-Type", "application/json")
                    .body(body.toString()).timeout(timeout).execute().body();
            JSONArray results = JSONUtil.parseObj(resp).getJSONArray("results");
            if (results == null || results.isEmpty()) {
                return null;
            }
            double[] scores = new double[documents.size()];
            java.util.Arrays.fill(scores, Double.NEGATIVE_INFINITY);
            for (int i = 0; i < results.size(); i++) {
                JSONObject r = results.getJSONObject(i);
                Integer idx = r.getInt("index");
                Double s = r.getDouble("relevance_score");
                if (idx != null && idx >= 0 && idx < scores.length) {
                    scores[idx] = (s == null ? 0d : s);
                }
            }
            return scores;
        } catch (Exception e) {
            log.warn("Reranker 打分失败: {}", e.getMessage());
            return null;
        }
    }

    /** 批量文本向量化，返回与 texts 下标对齐的向量列表，失败返回 null */
    public List<List<Float>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return null;
        }
        try {
            JSONObject body = new JSONObject();
            body.set("model", embeddingModel);
            JSONArray input = new JSONArray();
            for (String t : texts) {
                input.add(t == null ? "" : t);
            }
            body.set("input", input);
            String resp = HttpUtil.createPost(embeddingUrl)
                    .header("Content-Type", "application/json")
                    .body(body.toString()).timeout(timeout).execute().body();
            JSONArray data = JSONUtil.parseObj(resp).getJSONArray("data");
            if (data == null) {
                return null;
            }
            List<List<Float>> out = new ArrayList<>(data.size());
            for (Object o : data) {
                JSONArray emb = ((JSONObject) o).getJSONArray("embedding");
                List<Float> v = new ArrayList<>(emb.size());
                for (Object x : emb) {
                    v.add(((Number) x).floatValue());
                }
                out.add(v);
            }
            return out;
        } catch (Exception e) {
            log.warn("批量向量化失败: {}", e.getMessage());
            return null;
        }
    }

    /** 余弦相似度（-1 ~ 1，越大越相似） */
    public static double cosine(List<Float> a, List<Float> b) {
        if (a == null || b == null || a.size() != b.size() || a.isEmpty()) {
            return 0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.size(); i++) {
            double x = a.get(i), y = b.get(i);
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        return (na == 0 || nb == 0) ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 文本向量化，返回 float 列表 */
    public List<Float> embed(String text) {
        JSONObject body = new JSONObject();
        body.set("model", embeddingModel);
        JSONArray input = new JSONArray();
        input.add(text);
        body.set("input", input);
        String resp = HttpUtil.createPost(embeddingUrl)
                .header("Content-Type", "application/json")
                .body(body.toString()).timeout(timeout).execute().body();
        JSONArray emb = JSONUtil.parseObj(resp).getJSONArray("data").getJSONObject(0).getJSONArray("embedding");
        List<Float> v = new ArrayList<>(emb.size());
        for (Object o : emb) {
            v.add(((Number) o).floatValue());
        }
        return v;
    }

    /** 语义召回，返回按相关度排序的命中列表 */
    public List<Map<String, Object>> search(String query, int topK) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!enabled || query == null || query.trim().isEmpty()) {
            return result;
        }
        try {
            List<Float> vec = embed(query);
            // 构造二维向量数组
            JSONArray inner = new JSONArray();
            for (Float f : vec) {
                inner.add(f);
            }
            JSONArray data = new JSONArray();
            data.add(inner);
            JSONArray outFields = new JSONArray();
            outFields.add("doc_id");
            outFields.add("title");
            JSONObject body = new JSONObject();
            body.set("collectionName", collection);
            body.set("data", data);
            body.set("annsField", "vector");
            body.set("limit", topK);
            body.set("outputFields", outFields);
            String resp = HttpUtil.createPost(milvusUrl + "/v2/vectordb/entities/search")
                    .header("Content-Type", "application/json")
                    .body(body.toString()).timeout(timeout).execute().body();
            JSONObject obj = JSONUtil.parseObj(resp);
            JSONArray hits = obj.getJSONArray("data");
            if (hits != null) {
                for (Object o : hits) {
                    JSONObject hit = (JSONObject) o;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("docId", hit.get("doc_id"));
                    m.put("title", hit.getStr("title"));
                    m.put("score", hit.get("distance"));
                    result.add(m);
                }
            } else {
                log.warn("Milvus 搜索无 data，响应: {}", resp != null ? resp.substring(0, Math.min(200, resp.length())) : "null");
            }
        } catch (Exception e) {
            log.warn("语义召回失败，降级: {}", e.getMessage());
        }
        return result;
    }

    /** 语义召回的 doc_id 列表（按相似度阈值 minScore 过滤；阈值=0 时不过滤） */
    public List<Long> searchIds(String query, int topK) {
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> m : search(query, topK)) {
            Object d = m.get("docId");
            double score = 0;
            try { score = Double.parseDouble(String.valueOf(m.get("score"))); } catch (Exception ignore) {}
            log.info("语义召回候选 docId={} score={} title={}", d, score, m.get("title"));
            if (minScore > 0 && score < minScore) continue;
            if (d instanceof Number) ids.add(((Number) d).longValue());
        }
        return ids;
    }
}
