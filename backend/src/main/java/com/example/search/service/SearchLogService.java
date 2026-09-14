package com.example.search.service;

import cn.hutool.core.date.DateUtil;
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
 * 全文检索使用日志，写入 ES 索引 search_log
 */
@Slf4j
@Service
public class SearchLogService {

    @Value("${es.searchlog.enabled:true}")
    private boolean enabled;
    @Value("${es.searchlog.url:http://${ES_HOST}:9200}")
    private String esUrl;
    @Value("${es.searchlog.index:search_log}")
    private String index;

    /** 记录一次搜索 */
    public void record(String keyword, String type, String method, Long userId, String userName, Long deptId, Long tenantId, int total) {
        if (!enabled || keyword == null || keyword.trim().isEmpty()) {
            return;
        }
        try {
            JSONObject doc = new JSONObject();
            doc.set("keyword", keyword.trim());
            doc.set("type", type);
            doc.set("method", method);
            doc.set("userId", userId);
            doc.set("userName", userName);
            doc.set("deptId", deptId);
            doc.set("tenantId", tenantId);
            doc.set("total", total);
            doc.set("hasResult", total > 0);
            doc.set("ts", System.currentTimeMillis());
            doc.set("createTime", DateUtil.now());
            HttpUtil.createPost(esUrl + "/" + index + "/_doc")
                    .header("Content-Type", "application/json")
                    .body(doc.toString()).timeout(3000).execute();
        } catch (Exception e) {
            log.warn("搜索日志记录失败: {}", e.getMessage());
        }
    }

    /** 最近 days 天的检索分析 */
    public Map<String, Object> stats(int days) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            long since = System.currentTimeMillis() - (long) days * 86400000L;
            String json = "{\"size\":0,\"track_total_hits\":true,"
                    + "\"query\":{\"range\":{\"ts\":{\"gte\":" + since + "}}},"
                    + "\"aggs\":{"
                    + "\"topKeywords\":{\"terms\":{\"field\":\"keyword.keyword\",\"size\":20}},"
                    + "\"zeroResult\":{\"filter\":{\"term\":{\"hasResult\":false}},\"aggs\":{\"kw\":{\"terms\":{\"field\":\"keyword.keyword\",\"size\":20}}}},"
                    + "\"topUsers\":{\"terms\":{\"field\":\"userName.keyword\",\"size\":10}},"
                    + "\"uniqueUsers\":{\"cardinality\":{\"field\":\"userId\"}}"
                    + "}}";
            String resp = HttpUtil.createPost(esUrl + "/" + index + "/_search")
                    .header("Content-Type", "application/json")
                    .body(json).timeout(5000).execute().body();
            JSONObject r = JSONUtil.parseObj(resp);
            JSONObject agg = r.getJSONObject("aggregations");
            out.put("days", days);
            out.put("totalSearches", r.getJSONObject("hits").getJSONObject("total").getInt("value"));
            out.put("uniqueUsers", agg.getJSONObject("uniqueUsers").getInt("value"));
            out.put("zeroResultCount", agg.getJSONObject("zeroResult").getInt("doc_count"));
            out.put("topKeywords", buckets(agg.getJSONObject("topKeywords")));
            out.put("zeroResultKeywords", buckets(agg.getJSONObject("zeroResult").getJSONObject("kw")));
            out.put("topUsers", buckets(agg.getJSONObject("topUsers")));
        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    /** 热门搜索词 */
    public List<String> hotWords(int size, int days) {
        List<String> words = new ArrayList<>();
        if (!enabled) {
            return words;
        }
        try {
            long since = System.currentTimeMillis() - (long) days * 86400000L;
            String json = "{\"size\":0,"
                    + "\"query\":{\"range\":{\"ts\":{\"gte\":" + since + "}}},"
                    + "\"aggs\":{\"hot\":{\"terms\":{\"field\":\"keyword.keyword\",\"size\":" + size + "}}}}";
            String resp = HttpUtil.createPost(esUrl + "/" + index + "/_search")
                    .header("Content-Type", "application/json")
                    .body(json).timeout(4000).execute().body();
            JSONObject r = JSONUtil.parseObj(resp);
            JSONObject agg = r.getJSONObject("aggregations");
            if (agg != null && agg.getJSONObject("hot") != null) {
                JSONArray bs = agg.getJSONObject("hot").getJSONArray("buckets");
                if (bs != null) {
                    for (Object o : bs) {
                        JSONObject b = (JSONObject) o;
                        String key = b.getStr("key");
                        if (key != null && !key.trim().isEmpty()) {
                            words.add(key.trim());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("热门词聚合失败: {}", e.getMessage());
        }
        return words;
    }

    /** 最近 days 天搜索次数 >= minCount 且 0 结果的词 */
    public List<Map<String, Object>> zeroResultWords(int days, int minCount) {
        List<Map<String, Object>> words = new ArrayList<>();
        if (!enabled) return words;
        try {
            long since = System.currentTimeMillis() - (long) days * 86400000L;
            String json = "{\"size\":0,"
                    + "\"query\":{\"bool\":{\"must\":[{\"range\":{\"ts\":{\"gte\":" + since + "}}},{\"term\":{\"hasResult\":false}}]}},"
                    + "\"aggs\":{\"kw\":{\"terms\":{\"field\":\"keyword.keyword\",\"size\":100,\"min_doc_count\":" + minCount + "}}}}";
            String resp = HttpUtil.createPost(esUrl + "/" + index + "/_search")
                    .header("Content-Type", "application/json")
                    .body(json).timeout(5000).execute().body();
            JSONObject r = JSONUtil.parseObj(resp);
            JSONObject agg = r.getJSONObject("aggregations");
            if (agg != null && agg.getJSONObject("kw") != null) {
                JSONArray bs = agg.getJSONObject("kw").getJSONArray("buckets");
                if (bs != null) {
                    for (Object o : bs) {
                        JSONObject b = (JSONObject) o;
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("keyword", b.getStr("key"));
                        m.put("count", b.getInt("doc_count"));
                        words.add(m);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("0 结果词聚合失败: {}", e.getMessage());
        }
        return words;
    }

    private List<Map<String, Object>> buckets(JSONObject termsAgg) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (termsAgg == null) {
            return list;
        }
        JSONArray bs = termsAgg.getJSONArray("buckets");
        if (bs != null) {
            for (Object o : bs) {
                JSONObject b = (JSONObject) o;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", b.get("key"));
                m.put("count", b.get("doc_count"));
                list.add(m);
            }
        }
        return list;
    }
}
