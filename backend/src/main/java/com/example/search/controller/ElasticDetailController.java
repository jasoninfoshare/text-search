package com.example.search.controller;

import cn.hutool.core.bean.BeanUtil;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.example.common.core.domain.AjaxResult;
import com.example.common.core.page.TableDataInfo;
import com.example.common.utils.ServletUtils;
import com.example.common.utils.SnowFlake;
import com.example.common.utils.StringUtils;
import com.example.search.es.entity.SearchMoreBean;
import com.example.search.es.service.ElasticService;
import com.example.framework.web.service.TokenService;
import com.example.search.domain.OaPublishContent;
import com.example.search.service.OaPublishContentService;
import com.example.search.service.impl.OaPublishContentServiceImpl;
import com.example.search.util.CustomDateFormat;
import com.example.search.util.TextSegmenterUtils;
import com.example.system.core.domain.model.LoginUser;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.example.common.annotation.Log;
import com.example.common.enums.BusinessType;
import com.example.search.service.SemanticService;
import com.example.search.service.SearchLogService;
import com.example.search.service.DictService;
import org.springframework.beans.factory.annotation.Value;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/es")
public class ElasticDetailController {

	@Autowired
	private ElasticService elasticService;

	@Autowired
	private TokenService tokenService;

	@Autowired
	private OaPublishContentServiceImpl oaPublishContentService;

	@Autowired
	private SemanticService semanticService;

	@Autowired
	private DictService dictService;

	@Autowired
	private SearchLogService searchLogService;

	@Autowired
	private com.example.system.service.ISysDeptService sysDeptService;

	@Autowired
	private org.springframework.data.redis.core.RedisTemplate<String, String> redisTemplate;

	@Autowired
	private co.elastic.clients.elasticsearch.ElasticsearchClient esClient;

	/** 同义词表词条（用于搜索建议候选） */
	@org.springframework.web.bind.annotation.GetMapping("/dictWords")
	public com.example.common.core.domain.AjaxResult dictWords() {
		return com.example.common.core.domain.AjaxResult.success(dictService.allSynonymWords());
	}

	/** 相关搜索推荐：搜索过该词的用户还搜了哪些词 */
	@org.springframework.web.bind.annotation.GetMapping("/relatedSearches")
	public com.example.common.core.domain.AjaxResult relatedSearches(@org.springframework.web.bind.annotation.RequestParam String keyword) {
		if (StringUtils.isEmpty(keyword)) return com.example.common.core.domain.AjaxResult.success(java.util.Collections.emptyList());
		try {
			// 1. 找搜索过该词的用户
			co.elastic.clients.elasticsearch.core.SearchResponse<java.util.Map> userResp = esClient.search(s -> s
					.index("search_log")
					.size(0)
					.query(q -> q.term(t -> t.field("keyword.keyword").value(keyword)))
					.aggregations("userIds", a -> a.terms(t -> t.field("userId").size(50))),
					java.util.Map.class);
			java.util.List<Long> userIds = new java.util.ArrayList<>();
			if (userResp.aggregations() != null && userResp.aggregations().get("userIds") != null) {
				userResp.aggregations().get("userIds").lterms().buckets().array().forEach(b -> userIds.add(b.key()));
			}
			if (userIds.isEmpty()) return com.example.common.core.domain.AjaxResult.success(java.util.Collections.emptyList());

			// 2. 找这些用户搜索的其他词
			co.elastic.clients.elasticsearch.core.SearchResponse<java.util.Map> kwResp = esClient.search(s -> s
					.index("search_log")
					.size(0)
					.query(q -> q.bool(b -> b
							.must(m -> m.terms(t -> t.field("userId").terms(tq -> tq.value(userIds.stream().map(co.elastic.clients.elasticsearch._types.FieldValue::of).collect(java.util.stream.Collectors.toList())))))
							.mustNot(m -> m.term(t -> t.field("keyword.keyword").value(keyword)))
					))
					.aggregations("keywords", a -> a.terms(t -> t.field("keyword.keyword").size(10))),
					java.util.Map.class);
			java.util.List<String> related = new java.util.ArrayList<>();
			if (kwResp.aggregations() != null && kwResp.aggregations().get("keywords") != null) {
				kwResp.aggregations().get("keywords").sterms().buckets().array().forEach(b -> related.add(b.key().stringValue()));
			}
			return com.example.common.core.domain.AjaxResult.success(related);
		} catch (Exception e) {
			log.error("相关搜索查询失败", e);
			return com.example.common.core.domain.AjaxResult.success(java.util.Collections.emptyList());
		}
	}

	/** 检索使用统计，最近 days 天 */
	@org.springframework.web.bind.annotation.GetMapping("/searchStats")
	public com.example.common.core.domain.AjaxResult searchStats(@org.springframework.web.bind.annotation.RequestParam(value = "days", defaultValue = "30") int days) {
		return com.example.common.core.domain.AjaxResult.success(searchLogService.stats(days));
	}

	/** 测试同义词扩展 */
	@org.springframework.web.bind.annotation.GetMapping("/testSynonyms")
	public com.example.common.core.domain.AjaxResult testSynonyms(@org.springframework.web.bind.annotation.RequestParam String keyword) {
		return com.example.common.core.domain.AjaxResult.success(dictService.synonyms(keyword));
	}

	/** 热门搜索词 */
	@org.springframework.web.bind.annotation.GetMapping("/hotwords")
	public com.example.common.core.domain.AjaxResult hotwords(
			@org.springframework.web.bind.annotation.RequestParam(value = "size", defaultValue = "10") int size,
			@org.springframework.web.bind.annotation.RequestParam(value = "days", defaultValue = "90") int days) {
		return com.example.common.core.domain.AjaxResult.success(searchLogService.hotWords(size, days));
	}

	/**
	 * IK 远程词典接口：供 ES analysis-ik 插件的 remote_ext_dict 每分钟轮询，实现词典热更新免重启。
	 * 输出 ext_dict + 同义词表全量词条（一词一行，text/plain, UTF-8）；
	 * 支持 If-Modified-Since / Last-Modified 条件请求（IK 靠它判断词典是否变化）。
	 */
	@org.springframework.web.bind.annotation.GetMapping(value = "/dict", produces = "text/plain;charset=UTF-8")
	public org.springframework.http.ResponseEntity<String> remoteDict(
			@org.springframework.web.bind.annotation.RequestHeader(value = "If-Modified-Since", required = false) String ifModifiedSince) {
		long lastMod = dictService.dictLastModified();
		if (ifModifiedSince != null && lastMod > 0) {
			try {
				long ifMod = java.time.ZonedDateTime.parse(ifModifiedSince, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
						.toInstant().toEpochMilli();
				// 服务器时间精确到秒，lastMod 截断到秒再比较，避免毫秒差导致每次全量拉取
				if (lastMod / 1000 <= ifMod / 1000) {
					return org.springframework.http.ResponseEntity.status(304).build();
				}
			} catch (Exception ignore) {}
		}
		String body = String.join("\n", dictService.exportDictWords()) + "\n";
		return org.springframework.http.ResponseEntity.ok()
				.lastModified(lastMod)
				.body(body);
	}

	@org.springframework.web.bind.annotation.GetMapping("/aiExpandOnly")
	public com.example.common.core.domain.AjaxResult aiExpandOnly(@org.springframework.web.bind.annotation.RequestParam String keyword) {
		if (StringUtils.isEmpty(keyword)) return com.example.common.core.domain.AjaxResult.success(java.util.Collections.emptyList());
		// 联想词只与关键词有关，与角色/分页无关：单独缓存 10 分钟，避免重复查词典/调模型
		String expandCacheKey = "es:aiexpand:" + cn.hutool.crypto.digest.DigestUtil.md5Hex(keyword.replaceAll("[\\s\\u3000]+", ""));
		try {
			String cached = redisTemplate.opsForValue().get(expandCacheKey);
			if (StringUtils.isNotEmpty(cached)) {
				return com.example.common.core.domain.AjaxResult.success(com.alibaba.fastjson2.JSON.parseObject(cached, List.class));
			}
		} catch (Exception ignore) {}
		List<String> expand = new ArrayList<>();
		String kwNoSpace = keyword.replaceAll("[\\s\\u3000]+", "");
		// 人名/机构名不扩词
		if (dictService.isPersonOrOrg(kwNoSpace)) {
			return com.example.common.core.domain.AjaxResult.success(java.util.Collections.emptyList());
		}
		// 本地词典优先
		try {
			String segKw = dictService.segment(keyword);
			if (StringUtils.isNotEmpty(segKw) && !segKw.equals(keyword)) expand.add(segKw);
			List<String> expandSources = new ArrayList<>();
			if (StringUtils.isNotEmpty(kwNoSpace)) expandSources.add(kwNoSpace);
			if (StringUtils.isNotEmpty(segKw) && !segKw.equals(kwNoSpace)) expandSources.add(segKw);
			for (String src : expandSources) {
				for (String w : dictService.synonyms(src)) if (!expand.contains(w)) expand.add(w);
				for (String w : dictService.patternExpansions(src)) if (!expand.contains(w)) expand.add(w);
			}
		} catch (Exception e) {
			log.warn("本地词典扩展失败: {}", e.getMessage());
		}
		// 拼音/首字母通道
		if (kwNoSpace.matches("[a-zA-Z]{2,}")) {
			try {
				for (String w : dictService.pinyinWords(kwNoSpace)) {
					if (!expand.contains(w)) expand.add(w);
				}
			} catch (Exception ignore) {}
		}
		// AI 模型兜底（"不扩词"标记词不走：已知没有好的扩展，避免模型随机联想污染）
		if (expand.isEmpty() && aiExpandEnabled && StringUtils.isNotEmpty(aiExpandUrl) && !dictService.isNoExpand(kwNoSpace)) {
			try {
				List<String> ext = expandKeywords(kwNoSpace);
				if (ext != null && !ext.isEmpty()) expand.addAll(ext);
			} catch (Exception e) {
				log.warn("AI 扩词失败: {}", e.getMessage());
			}
		}
		try {
			// 缓存 10 分钟： synonym.txt 是 5 秒热重载的，缓存太久会导致词典更新迟迟不体现到联想词
			redisTemplate.opsForValue().set(expandCacheKey, com.alibaba.fastjson2.JSON.toJSONString(expand), 10, java.util.concurrent.TimeUnit.MINUTES);
		} catch (Exception ignore) {}
		return com.example.common.core.domain.AjaxResult.success(expand);
	}

	private Long parseLong(String s) {
		try {
			return (s == null || s.trim().isEmpty() || "null".equals(s.trim())) ? null : Long.parseLong(s.trim());
		} catch (Exception e) {
			return null;
		}
	}

	/** 纯语义检索，验证 Embedding 到 Milvus 链路 */
	@PostMapping("/semantic")
	public com.example.common.core.domain.AjaxResult semantic(@RequestBody Map<String, String> map) {
		String kw = map.get("keyword");
		int topK = map.get("topK") != null ? Integer.parseInt(map.get("topK")) : 10;
		return com.example.common.core.domain.AjaxResult.success(semanticService.search(kw, topK));
	}

	@Value("${ai.expand.enabled:false}")
	private boolean aiExpandEnabled;
	@Value("${ai.expand.url:}")
	private String aiExpandUrl;
	@Value("${ai.expand.model:Qwen3-30A3B}")
	private String aiExpandModel;
	@Value("${ai.expand.timeout:2500}")
	private int aiExpandTimeout;
	@Value("${ai.expand.max:8}")
	private int aiExpandMax;
	/** 关键词长度达到此值才走语义召回 */
	@Value("${es.semantic.minLen:4}")
	private int semanticMinLen;
	/** 广召回候选池大小 */
	@Value("${es.semantic.rerankPoolSize:60}")
	private int rerankPoolSize;
	/** 语义召回 topK：取 Milvus 最近的前 N 条（配合 minScore 阈值控制噪声） */
	@Value("${es.semantic.recallTopK:10}")
	private int recallTopK;

	/** 调用大模型（当前为 Qwen3-30B-A3B）把检索词扩展为相关词，返回去重、去原词后的列表 */
	private List<String> expandKeywords(String keyword) {
		List<String> result = new ArrayList<>();
		String sys = "你是一名熟悉某新能源集团业务与政企办公文档（公文、通知公告、新闻、制度文件）全文检索的关键词扩展专家。" +
				"请根据用户输入的检索词，输出在**该集团业务语境下意思相同或高度相近、可互相替换**的中文表达，用于扩大检索召回。请严格按以下规则处理：\n" +
				"(1)【核心原则】扩展词必须能在企业文档中直接替换原词而不改变检索意图，优先给出集团/行业内部的官方或惯用表述。严禁把\"同一领域/主题下的不同概念\"当成同义词。\n" +
				"(2)【禁止扩展的类型】若检索词属于以下任一情况，直接输出空数组[]，禁止硬凑：完整或较长的制度文件名/通知标题、文号编号（如\"136号文件\"）、人名（如\"陶旭\"）、项目代号/活动名（如\"AKTD工作方案\"）、机构/公司名称、专有技术名词/产品名、节日/日期/地点、无真正同义词的单字/短语。\n" +
				"(3)【严禁主题相关但概念不同的词】这是最容易出错的规则。扩展词必须是原词的\"同义/近义\"说法，而不是同一主题下的其他关联概念。错误示例（必须避免）：\"算电协同\"是\"算力与电力协同/融合\"，不要扩展为\"源网荷储协同/源网荷储互动/源网荷储一体化\"（源网荷储是电源、电网、负荷、储能的协同，是另一个概念）；\"大模型\"不要扩展为\"人工智能/机器学习/深度学习/算法\"（过于宽泛）；\"智慧电厂\"不要扩展为\"智能电网/智慧能源/数字化转型\"；\"数据贯通\"不要扩展为\"数据治理/数据中台/数字化建设\"。\n" +
				"(4)【禁止简单复合】禁止只通过在原词前后加字得到的复合词，例如：\"调研\"→\"调研报告\"、\"报销\"→\"报销流程\"、\"党建\"→\"党建工作\"；也禁止给出更宽泛或含义偏移的词，例如：\"评优\"→\"考核/评价/绩效\"、\"算电协同\"→\"电力\"或\"算力\"。\n" +
				"(5)【短语处理规则】对【修饰语+中心词】的短语，优先替换中心词，再替换修饰语，组合成语义相近的完整短语，不要只替换一部分导致语义漂移。正确示例：\"数字化评优\"→[\"信息化评优\",\"数智化评优\",\"数字化评先\",\"数字化表彰\"]；\"智慧电厂\"→[\"智慧电站\",\"智慧场站\",\"智能电厂\",\"智慧新能源电厂\"]；\"数据贯通\"→[\"数据打通\",\"数据互联\",\"数据共享\",\"数据协同\"]；\"安可替代\"→[\"安全可靠应用替代\",\"信创替代\",\"国产化替代\"]；\"算电协同\"→[\"算力电力协同\",\"算电融合\",\"算力与电力协同\",\"算力与新能源协同\"]。\n" +
				"(6)【通用办公词】对通用办公场景词汇，输出实际办公中的同义/近义说法。示例：\"请示\"→[\"申请\",\"呈请\",\"报批\",\"报请\"]；\"补助\"→[\"补贴\",\"津贴\",\"补偿\"]；\"报销\"→[\"费用报销\",\"报账\"]；\"报表\"→[\"统计表\",\"报告表\"]；\"假期\"→[\"休假\",\"放假\",\"节假日\"]；\"发票\"→[\"票据\",\"增值税发票\"]。\n" +
				"(7)【自检要求】生成结果前请逐条检查：每个扩展词是否可以直接替换原词？如果替换后检索意图变了，必须删除。\n" +
				"(8)【输出格式】只输出一个JSON字符串数组，最多" + aiExpandMax + "个，按相关度从高到低排序；如果没有任何符合上述标准的扩展词，必须输出[]。不要解释、不要思考过程、不要输出原词本身。";
		JSONObject body = new JSONObject();
		body.put("model", aiExpandModel);
		JSONArray messages = new JSONArray();
		messages.add(new JSONObject().set("role", "system").set("content", sys));
		// few-shot 示例：校准输出格式与领域口径（2 个正例 + 1 个负例）
		messages.add(new JSONObject().set("role", "user").set("content", "数字化评优"));
		messages.add(new JSONObject().set("role", "assistant").set("content", "[\"信息化评优\",\"数智化评优\",\"数字化评先\",\"数字化表彰\"]"));
		messages.add(new JSONObject().set("role", "user").set("content", "算电协同"));
		messages.add(new JSONObject().set("role", "assistant").set("content", "[\"算力电力协同\",\"算电融合\",\"算力与电力协同\",\"算力与新能源协同\"]"));
		messages.add(new JSONObject().set("role", "user").set("content", "陶旭"));
		messages.add(new JSONObject().set("role", "assistant").set("content", "[]"));
		messages.add(new JSONObject().set("role", "user").set("content", keyword));
		body.put("messages", messages);
		body.put("temperature", 0.2);
		body.put("max_tokens", 256);
		body.put("chat_template_kwargs", new JSONObject().set("enable_thinking", false));
		log.error("[AI扩词调试] 请求: url={}, keyword={}, body={}", aiExpandUrl, keyword, body.toString());
		String resp = HttpUtil.createPost(aiExpandUrl)
				.header("Content-Type", "application/json")
				.body(body.toString())
				.timeout(aiExpandTimeout)
				.execute().body();
		log.error("[AI扩词调试] 响应: {}", resp);
		JSONObject obj = JSONUtil.parseObj(resp);
		String content = obj.getJSONArray("choices").getJSONObject(0)
				.getJSONObject("message").getStr("content");
		if (content == null) return result;
		// 全角标点归一化成半角，避免 JSON 解析失败
		content = content.replace('，', ',').replace('“', '"').replace('”', '"').replace('［', '[').replace('］', ']');
		int l = content.indexOf('['), r = content.lastIndexOf(']');
		if (l >= 0 && r > l) {
			JSONArray arr = JSONUtil.parseArray(content.substring(l, r + 1));
			for (Object o : arr) {
				String w = String.valueOf(o).trim();
				// 过滤掉包含原词或被原词包含的复合词
				if (!w.isEmpty() && !w.equals(keyword) && !w.contains(keyword) && !keyword.contains(w) && !result.contains(w)) result.add(w);
			}
		}
		// Embedding 近重复去重：与已保留词余弦相似度 >= 0.9 的扩展词丢弃（需语义服务开启；失败不影响原结果）
		if (result.size() > 1 && semanticService.isEnabled()) {
			try {
				List<List<Float>> vecs = semanticService.embedBatch(result);
				if (vecs != null && vecs.size() == result.size()) {
					List<String> kept = new ArrayList<>();
					List<List<Float>> keptVec = new ArrayList<>();
					for (int i = 0; i < result.size(); i++) {
						boolean dup = false;
						for (List<Float> kv : keptVec) {
							if (SemanticService.cosine(vecs.get(i), kv) >= 0.9) { dup = true; break; }
						}
						if (!dup) { kept.add(result.get(i)); keptVec.add(vecs.get(i)); }
					}
					if (kept.size() != result.size()) log.info("扩词语义去重: {} -> {}", result, kept);
					result = kept;
				}
			} catch (Exception e) {
				log.warn("扩词语义去重失败，跳过: {}", e.getMessage());
			}
		}
		return result;
	}

	/** 错别字纠正：模型判断检索词是否有错别字，有则返回纠正词，无/不确定返回空串。仅在无结果时调用 */
	private String correctTypo(String keyword) {
		if (StringUtils.isEmpty(keyword)) return "";
		// 1) 词典编辑距离优先：与专名/同义词表中最相近的词（如 算电写同 -> 算电协同）
		try {
			String near = dictService.nearestTerm(keyword);
			if (StringUtils.isNotEmpty(near) && !near.equals(keyword)) {
				log.info("纠错(词典最近词): {} -> {}", keyword, near);
				return near;
			}
		} catch (Exception ignore) {}
		// 2) 模型兜底（词典找不到相近词时）
		if (StringUtils.isEmpty(aiExpandUrl)) return "";
		try {
			String sys = "你是中文检索纠错助手。用户的检索词可能有错别字、形近字或拼音误打。" +
					"若存在明显错别字，只输出纠正后的正确检索词（一个词，长度和含义与原词保持接近，不要解释、不要标点、不要引号）；" +
					"若没有错别字或无法确定，只输出一个字：无。";
			JSONObject body = new JSONObject();
			body.put("model", aiExpandModel);
			JSONArray messages = new JSONArray();
			messages.add(new JSONObject().set("role", "system").set("content", sys));
			messages.add(new JSONObject().set("role", "user").set("content", keyword));
			body.put("messages", messages);
			body.put("temperature", 0);
			body.put("max_tokens", 32);
			body.put("chat_template_kwargs", new JSONObject().set("enable_thinking", false));
			String resp = HttpUtil.createPost(aiExpandUrl).header("Content-Type", "application/json")
					.body(body.toString()).timeout(aiExpandTimeout).execute().body();
			JSONObject obj = JSONUtil.parseObj(resp);
			String content = obj.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getStr("content");
			if (content == null) return "";
			content = content.trim().replaceAll("[\\s\"'，。、：:]", "");
			if (content.isEmpty() || "无".equals(content) || content.equals(keyword)) return "";
			if (Math.abs(content.length() - keyword.length()) > 2) return ""; // 长度差太多，疑似乱答
			return content;
		} catch (Exception ex) {
			log.warn("纠错失败: {}", ex.getMessage());
			return "";
		}
	}

	@Log(title = "全文检索", businessType = BusinessType.OTHER)
	@PostMapping("/querys")
	public TableDataInfo querys(@RequestBody Map<String, String> map) {
		List<OaPublishContent> list = new ArrayList<>();
		int total = 0;
		List<String> aiWords = null;
		List<String> allExpand = null;
		List<String> deptList = null;
		boolean poolRerank = false;
		int realPageNum = 1, realPageSize = 10;
		int originalPageNum = 1, originalPageSize = 10;
		int realTotal = 0;
		// 提前声明，避免后续 try 外引用作用域问题；缺省 false=纯精确
		boolean requestAiExpand = false;

		// 结果缓存：相同关键词+角色+筛选条件，5 分钟内直接返回
		// ⚠️ 必须包含权限三元组（userId/deptId/tenantId）：roles 相同不代表数据权限相同
		// （两个分公司领导 roles 一样但 tenantId 不同），否则 B 命中 A 的缓存 = 跨单位越权
		String cacheKey = "es:search:" + cn.hutool.crypto.digest.DigestUtil.md5Hex(
				String.valueOf(map.get("keyword")) + "|" +
				String.valueOf(map.get("roles")) + "|" +
				String.valueOf(map.get("userId")) + "|" +
				String.valueOf(map.get("deptId")) + "|" +
				String.valueOf(map.get("tenantId")) + "|" +
				String.valueOf(map.get("type")) + "|" +
				String.valueOf(map.get("method")) + "|" +
				String.valueOf(map.get("pageNum")) + "|" +
				String.valueOf(map.get("pageSize")) + "|" +
				String.valueOf(map.get("sort")) + "|" +
				String.valueOf(map.get("docType")) + "|" +
				String.valueOf(map.get("startTime")) + "|" +
				String.valueOf(map.get("endTime")) + "|" +
				String.valueOf(map.get("deptName")) + "|" +
				String.valueOf(map.get("aiExpand"))
		);
		try {
			String cached = redisTemplate.opsForValue().get(cacheKey);
			if (StringUtils.isNotEmpty(cached)) {
				log.info("[全文检索] 命中缓存: {}", cacheKey);
				return com.alibaba.fastjson2.JSON.parseObject(cached, TableDataInfo.class);
			}
		} catch (Exception e) {
			log.warn("[全文检索] 读取缓存失败: {}", e.getMessage());
		}

		try {
			String indexName = "oa_publish_content,official_doc,oa_information_content";
			String type = map.get("type");
			if(StringUtils.isNotEmpty(type)){
				indexName = switch (type) {
					// 公文数据同时落在 official_doc（公文系统）和 oa_publish_content（OA 发布）两个索引
					case "99" -> "official_doc,oa_publish_content";
					case "5" -> "oa_information_content";
					default -> "oa_publish_content";
				};
			}


			map.put("indexName", indexName);

			// 按请求控制扩展：aiExpand=false 时纯关键词精确检索（不做本地词典扩词/AI扩词/语义召回）
			requestAiExpand = "true".equalsIgnoreCase(String.valueOf(map.getOrDefault("aiExpand", "false")).trim());
			map.put("requestAiExpand", String.valueOf(requestAiExpand));
			log.info("[全文检索] kw={} aiExpand参数={} aiExpandEnabled={} -> requestAiExpand={}（false=纯精确，不做扩词/语义召回）", map.get("keyword"), map.get("aiExpand"), aiExpandEnabled, requestAiExpand);
		System.out.println("[DEBUG-ES] requestAiExpand=" + requestAiExpand + ", keyword=" + map.get("keyword") + ", method=" + map.get("method") + ", type=" + map.get("type"));

			// 本地词典：专名分词改写关键词 + 同义词扩展，AI 扩词并入同一通道
			String kw = map.get("keyword");
			// 去空格版本用于同义词/模板扩展，避免"算电 协同"因空格无法命中"算电协同"同义词组
			String kwNoSpace = kw == null ? null : kw.replaceAll("[\\s\\u3000]+", "");
			List<String> expand = new ArrayList<>();
			// 最终参与 ES 查询的"原词"：默认用用户输入；如果输入含空格且去空格版本能命中本地同义词，
			// 则把去空格版本作为原词，避免"算电 协同"被 ES 拆成"算电"+"协同"两个必须同时命中的词而漏召或错召
			String queryKw = kw;
			// 人名/机构名不扩词、不拆分
			boolean isPersonOrOrg = dictService.isPersonOrOrg(kwNoSpace != null ? kwNoSpace : kw);
			if (requestAiExpand && StringUtils.isNotEmpty(kw) && !isPersonOrOrg) {
				try {
					String segKw = dictService.segment(kw);
					// 专名改写结果只作为扩展词，不再替换原 keyword，避免"弱口令"被改写成"弱 口令"后 must 过宽
					if (StringUtils.isNotEmpty(segKw) && !segKw.equals(kw) && !expand.contains(segKw)) {
						expand.add(segKw);
						log.info("专名分词扩展: {} -> {}", kw, segKw);
					}
					// 同义词/模板扩展：优先用"去空格版本"（保证"算电 协同"命中"算电协同"组），
					// 只有去空格版本无结果时，才用"专名分词结果"兜底，避免"协同"被拆词后污染扩展词
					List<String> synList = new ArrayList<>();
					List<String> expandSources = new ArrayList<>();
					if (StringUtils.isNotEmpty(kwNoSpace)) expandSources.add(kwNoSpace);
					if (StringUtils.isNotEmpty(segKw) && !segKw.equals(kwNoSpace)) expandSources.add(segKw);
					boolean kwNoSpaceHit = false;
					for (int i = 0; i < expandSources.size(); i++) {
						String expandSource = expandSources.get(i);
						List<String> syns = dictService.synonyms(expandSource);
						List<String> patterns = dictService.patternExpansions(expandSource);
						if (i == 0 && (!syns.isEmpty() || !patterns.isEmpty())) {
							kwNoSpaceHit = true;
						}
						if (i > 0 && kwNoSpaceHit) {
							// 去空格版本已命中，不再用分词结果，避免"算电 协同"被拆成"算电"+"协同"后扩展出"协同办公"等无关词
							break;
						}
						for (String w : syns) {
							if (!expand.contains(w)) {
								expand.add(w);
								synList.add(w);
							}
						}
						for (String w : patterns) {
							if (!expand.contains(w)) {
								expand.add(w);
							}
						}
					}
					// 只要去空格版本能触发本地同义词/模板扩展，就用去空格版本作为原词查询，
					// 保证"算电 协同"按完整短语"算电协同"召回，而不是被拆成 must AND
					if (!synList.isEmpty() && StringUtils.isNotEmpty(kwNoSpace) && !kwNoSpace.equals(kw)) {
						queryKw = kwNoSpace;
					}
					System.out.println("[DEBUG-CTRL] keyword=" + kw + ", expandSources=" + expandSources + ", expand=" + expand);
				} catch (Exception ex) {
					log.warn("本地词典处理失败: {}", ex.getMessage());
				}
			}
			// 写入最终参与查询的 keyword（不再被拆词/改写污染）
			map.put("keyword", queryKw);
			// 多词组合查询：为每个词构建"原词+同义词"分组（termGroups=组1|组2，组内逗号分隔），
			// 让多词 AND 变为"词A分组 AND 词B分组"，组内同义词 OR——
			// 否则扩展词平铺成 OR 会导致"借调 中纪委"被大量只含"纪委"不含"借调"的文档淹没
			if (StringUtils.isNotEmpty(queryKw)) {
				String[] tgTerms = queryKw.split("[\\s\\u3000,，、;；]+");
				List<String> cleanTerms = new ArrayList<>();
				for (String t : tgTerms) { if (StringUtils.isNotEmpty(t.trim())) cleanTerms.add(t.trim()); }
				if (cleanTerms.size() > 1) {
					try {
						// 合并词优先：去分隔符后的整词若命中同义词表（如"网络 安全"→"网络安全"），
						// 视为单一概念只出一组，不拆成逐词 AND——避免"网络 AND 安全"泛词组合洪水
						String merged = String.join("", cleanTerms);
						List<String> mergedSyns = dictService.synonyms(merged);
						List<String> groups = new ArrayList<>();
						if (mergedSyns != null && !mergedSyns.isEmpty()) {
							List<String> g = new ArrayList<>();
							g.add(merged);
							for (String w : mergedSyns) {
								if (!g.contains(w)) g.add(w);
							}
							groups.add(String.join(",", g));
						} else {
							for (String t : cleanTerms) {
								List<String> g = new ArrayList<>();
								g.add(t);
								for (String w : dictService.synonyms(t)) {
									if (!g.contains(w)) g.add(w);
								}
								groups.add(String.join(",", g));
							}
						}
						map.put("termGroups", String.join("|", groups));
					} catch (Exception ex) {
						log.warn("多词分组构建失败: {}", ex.getMessage());
					}
				}
			}
			// 拼音/首字母通道：纯字母输入时查拼音词典补充候选中文词（aqsc→安全生产），优先于 AI 模型兜底
			if (requestAiExpand && StringUtils.isNotEmpty(kwNoSpace) && kwNoSpace.matches("[a-zA-Z]{2,}")) {
				try {
					List<String> pyWords = dictService.pinyinWords(kwNoSpace);
					if (!pyWords.isEmpty()) {
						for (String w : pyWords) {
							if (!expand.contains(w)) expand.add(w);
						}
						log.info("拼音扩词: {} -> {}", kw, pyWords);
					}
				} catch (Exception ex) {
					log.warn("拼音扩词失败: {}", ex.getMessage());
				}
			}
			// 本地词典优先：仅当本地同义词/模板无结果时，才调 AI 模型兜底（避免模型随机联想污染）；
			// "不扩词"标记词（zero_words 采集词/单词自成组词）跳过兜底——已知没有好的扩展
			if (requestAiExpand && expand.isEmpty() && aiExpandEnabled && StringUtils.isNotEmpty(kw) && StringUtils.isNotEmpty(aiExpandUrl)
					&& !dictService.isNoExpand(kwNoSpace)) {
				try {
					String aiKw = StringUtils.isNotEmpty(kwNoSpace) ? kwNoSpace : kw;
					List<String> ext = expandKeywords(aiKw);
					if (ext != null && !ext.isEmpty()) {
						aiWords = ext;
						for (String w : ext) {
							if (!expand.contains(w)) expand.add(w);
						}
						log.info("AI扩词(本地无结果兜底): {} -> {}", kw, ext);
					} else {
						log.info("AI扩词兜底无结果: {}", kw);
					}
				} catch (Exception ex) {
					log.error("[AI扩词调试] AI扩词失败，按原词检索", ex);
				}
			}
			// 前端×掉的联想词（excludeWords）：从扩展词中剔除，本次检索不再参与查询/联想/高亮
			String excludeWords = String.valueOf(map.getOrDefault("excludeWords", "")).trim();
			if (StringUtils.isNotEmpty(excludeWords)) {
				List<String> excludes = new ArrayList<>();
				for (String w : excludeWords.split("[,，]")) {
					if (StringUtils.isNotEmpty(w.trim())) excludes.add(w.trim());
				}
				if (!excludes.isEmpty()) {
					expand.removeIf(excludes::contains);
					log.info("剔除用户移除的联想词: {} -> 剩余扩展词: {}", excludes, expand);
					// 多词分组（termGroups=组1|组2，组内逗号分隔）同步剔除；剔空的组整个丢弃
					String termGroups = map.get("termGroups");
					if (StringUtils.isNotEmpty(termGroups)) {
						List<String> keptGroups = new ArrayList<>();
						for (String g : termGroups.split("\\|")) {
							List<String> kept = new ArrayList<>();
							for (String w : g.split(",")) {
								if (StringUtils.isNotEmpty(w.trim()) && !excludes.contains(w.trim())) kept.add(w.trim());
							}
							if (!kept.isEmpty()) keptGroups.add(String.join(",", kept));
						}
						map.put("termGroups", String.join("|", keptGroups));
					}
				}
			}
			if (!expand.isEmpty()) {
				map.put("expandWords", String.join(",", expand));
				aiWords = new ArrayList<>(expand); // 本地同义词/模板扩展也作为 AI 扩词结果返回给前端展示
			}
			allExpand = new ArrayList<>(expand);

			// 语义/向量召回：作为"兜底通道"开启——Milvus 命中先经 minScore(0.5) 阈值过滤，
			// 且 terms(id) 子句 boost(0.5) 低于原词(5/10)，语义结果天然排在字面命中之后：
			// 字面结果充足时沉底不可见，字面结果不足时补位，不与字面结果混排争序。
			boolean SEMANTIC_RECALL = true;
			if (SEMANTIC_RECALL && requestAiExpand && semanticService.isEnabled() && StringUtils.isNotEmpty(kw) && kw.trim().length() >= semanticMinLen) {
				try {
					List<Long> sids = semanticService.searchIds(kw, recallTopK);
					if (!sids.isEmpty()) {
						map.put("semanticIds", sids.stream().map(String::valueOf).collect(Collectors.joining(",")));
						log.info("语义召回 {} 条", sids.size());
					}
				} catch (Exception ex) {
					log.warn("语义召回失败，降级: {}", ex.getMessage());
				}
			}

			// 广召回，临时改大 pageSize 拉候选池，顺序交给 Reranker；精确模式不精排，直接按 ES 相关度返回
			boolean sortByTime = "time".equals(map.get("sort"));
			// 暂时禁用 poolRerank，避免精排逻辑过滤掉有效结果导致 AI 扩词后结果变少
			poolRerank = false;
			if (poolRerank) {
				try { realPageNum = Integer.parseInt(map.getOrDefault("pageNum", "1")); } catch (Exception e) {}
				try { realPageSize = Integer.parseInt(map.getOrDefault("pageSize", "10")); } catch (Exception e) {}
				map.put("pageNum", "1");
				map.put("pageSize", String.valueOf(rerankPoolSize));
			}

			// AI 扩词模式下拉大候选池，避免 finalFilter 后无结果；同时记录真实分页参数
			originalPageNum = 1;
			originalPageSize = 10;
			try { originalPageNum = Integer.parseInt(map.getOrDefault("pageNum", "1")); } catch (Exception e) {}
			try { originalPageSize = Integer.parseInt(map.getOrDefault("pageSize", "10")); } catch (Exception e) {}
			if (requestAiExpand) {
				// 候选池按翻页深度动态放大：用户翻到第 N 页就拉够 N 页的量（封顶 200 条），
				// 不再固定 20 条导致"共128条只能看20条"；ES 查询现在百毫秒级，拉 200 条也撑得住
				int dynamicPool = Math.min(200, Math.max(20, originalPageNum * originalPageSize));
				map.put("pageNum", "1");
				map.put("pageSize", String.valueOf(dynamicPool));
			}

			// 执行 ES 查询
			// 裸名部门筛选时带上补全别名（如 办公室/党委办公室 → 公司本部>办公室/党委办公室），
			// 让全路径格式存储的文档也能命中（ElasticServiceImpl 里做 OR）
			String selDept = map.get("deptName");
			if (StringUtils.isNotEmpty(selDept)) {
				Map<String, String> aliasMap = bareToFullLabelMap();
				if (aliasMap.containsKey(selDept)) {
					map.put("deptNameAlias", aliasMap.get(selDept));
				}
			}
			long esStart = System.currentTimeMillis();
			Map<String, Object> searchQueryMap = elasticService.queryEs(map);
			System.out.println("[DEBUG-CTRL] queryEs耗时=" + (System.currentTimeMillis() - esStart) + "ms");

			// 获取总数
			total = Optional.ofNullable(searchQueryMap.get("total"))
					.map(String::valueOf)
					.map(Integer::parseInt)
					.orElse(0);
			realTotal = total; // 精排池覆盖前留住真实命中总数
			System.out.println("[DEBUG-CTRL] esTotal=" + total + ", dataListSize=" + (searchQueryMap.get("data") == null ? 0 : ((List)searchQueryMap.get("data")).size()));

			// 仅展示真实命中的扩展词：根据 ES 返回的 matched_queries 过滤，未命中当前结果集的扩词不展示
			List<String> matchedExpand = (List<String>) searchQueryMap.get("matchedExpandTerms");
			if (matchedExpand != null && !matchedExpand.isEmpty() && allExpand != null) {
				allExpand.retainAll(matchedExpand);
				if (aiWords != null) aiWords.retainAll(matchedExpand);
			} else if (requestAiExpand && allExpand != null) {
				allExpand.clear();
				if (aiWords != null) aiWords.clear();
			}
			// 部门聚合列表，用于高级筛选
			deptList = (List<String>) searchQueryMap.get("deptList");

			// 处理数据列表
			long convertStart = System.currentTimeMillis();
			List<Map<String, Object>> dataList = (List<Map<String, Object>>) searchQueryMap.get("data");
			if (dataList != null && !dataList.isEmpty()) {
				ObjectMapper mapper = new ObjectMapper();
				// 配置 mapper 忽略未知字段
				mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
				// 配置下划线转驼峰
				mapper.setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE);

				// 创建一个自定义的日期格式器
				SimpleDateFormat[] dateFormats = new SimpleDateFormat[]{
						new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"),
						new SimpleDateFormat("yyyy-MM-dd")
				};

				// 配置 ObjectMapper
				mapper.setDateFormat(new CustomDateFormat(dateFormats));

				String finalIndexName = indexName;
				list = dataList.stream()
						.map(item -> {
							try {
								// 使用 mapper 转换，同时手动设置特殊字段
//								OaPublishContent content = mapper.convertValue(item, OaPublishContent.class);
								OaPublishContent content = BeanUtil.toBean(item, OaPublishContent.class);
								content.setEsIndex(finalIndexName);
								content.setHisTasks(String.valueOf(item.get("hisTasks")));
								// 透传语义命中标记（实体无此字段，走 params 带给前端）
								try {
									Object so = item.get("semanticOnly");
									content.getParams().put("semanticOnly", Boolean.TRUE.equals(so));
								} catch (Exception ignore) {}
								// 兼容 official_doc 等索引正文存的是 fullContent/full_content 的场景
								if (StringUtils.isEmpty(content.getContent())) {
									Object fullContent = item.get("fullContent");
									if (fullContent == null) fullContent = item.get("full_content");
									if (fullContent != null) content.setContent(String.valueOf(fullContent));
								}
								return content;
							} catch (Exception e) {
								// 转换失败时记录日志并跳过该条记录
								log.error("转换 发布内容实体  失败: {}", item, e);
								return null;
							}
						})
						.filter(Objects::nonNull) // 过滤掉转换失败的 null 值
						.collect(Collectors.toList());
				System.out.println("[DEBUG-CTRL] 结果转换耗时=" + (System.currentTimeMillis() - convertStart) + "ms");

				// 补全部门完整路径：publishDeptName 不含 ">" 时，根据 publishDeptId 查询完整路径
				try {
					List<Long> deptIds = list.stream()
							.filter(c -> c.getPublishDeptId() != null && (c.getPublishDeptName() == null || !c.getPublishDeptName().contains(">")))
							.map(OaPublishContent::getPublishDeptId)
							.distinct()
							.collect(Collectors.toList());
					if (!deptIds.isEmpty()) {
						Map<Long, String> deptNameMap = new HashMap<>();
						for (Long deptId : deptIds) {
							try {
								com.example.system.core.domain.entity.SysDept dept = sysDeptService.selectDeptById(deptId);
								if (dept != null && StringUtils.isNotEmpty(dept.getFullName())) {
									deptNameMap.put(deptId, dept.getFullName());
								}
							} catch (Exception ignore) {}
						}
						for (OaPublishContent c : list) {
							if (c.getPublishDeptId() != null && (c.getPublishDeptName() == null || !c.getPublishDeptName().contains(">"))) {
								String fullName = deptNameMap.get(c.getPublishDeptId());
								if (StringUtils.isNotEmpty(fullName)) {
									c.setPublishDeptName(fullName);
								}
							}
						}
					}
				} catch (Exception ignore) {}
			}
		} catch (Exception e) {
			log.error("查询 ES 数据失败", e);
			list = Collections.emptyList();
		}

		// Reranker 整池打分：输入为「标题 + 正文前500字」，得分保留，后续分桶后桶内按得分降序；失败保持原序
		String rerankKw = map.get("keyword");
		double[] rerankScores = null;
		if (poolRerank && list.size() > 1) {
			try {
				List<String> docs = new ArrayList<>();
				for (OaPublishContent c : list) {
					String t = c.getTitle() == null ? "" : c.getTitle();
					String ct = c.getContent() == null ? "" : c.getContent().replaceAll("<[^>]+>", "");
					String doc = (t + " " + ct).trim();
					docs.add(doc.length() > 500 ? doc.substring(0, 500) : doc);
				}
				rerankScores = semanticService.rerankScores(rerankKw, docs);
				if (rerankScores != null) log.info("Reranker 整池打分 {} 条", list.size());
			} catch (Exception ex) {
				log.warn("精排失败: {}", ex.getMessage());
			}
		}
		// 应用内分页，总数为候选池大小
		System.out.println("[DEBUG-CTRL] before poolRerank listSize=" + list.size() + ", poolRerank=" + poolRerank);
		if (poolRerank) {
			// 精确优先，含全部关键词的结果排前，相关结果排后
			if (StringUtils.isNotEmpty(rerankKw) && list.size() > 1) {
				// 分桶时同时检查原词和扩展词，过滤掉不包含任何关键词的无关结果
				List<String> checkTerms = new ArrayList<>();
				checkTerms.add(rerankKw);
				if (allExpand != null) checkTerms.addAll(allExpand);
				List<OaPublishContent> exact = new ArrayList<>();
				for (OaPublishContent c : list) {
					String text = ((c.getTitle() == null ? "" : c.getTitle()) + " " + (c.getContent() == null ? "" : c.getContent())).replaceAll("<[^>]+>", "");
					boolean hit = false;
					for (String term : checkTerms) {
						if (StringUtils.isNotEmpty(term) && text.contains(term)) { hit = true; break; }
					}
					if (hit) { exact.add(c); }
				}
				// 桶内按 Reranker 得分降序（无得分时保持 ES 原序）
				if (rerankScores != null) {
					Map<OaPublishContent, Double> scoreMap = new IdentityHashMap<>();
					for (int i = 0; i < list.size(); i++) scoreMap.put(list.get(i), rerankScores[i]);
					exact.sort((a, b) -> Double.compare(
							scoreMap.getOrDefault(b, Double.NEGATIVE_INFINITY),
							scoreMap.getOrDefault(a, Double.NEGATIVE_INFINITY)));
				}
				list = exact;
			}
			total = list.size();
			// 精排池分页后，realTotal 必须同步为精排后的总条数，否则前端显示"共 X 个结果"与实际数据不符
			realTotal = total;
			int from = Math.max(0, (realPageNum - 1) * realPageSize);
			int to = Math.min(from + realPageSize, list.size());
			list = (from < list.size()) ? new ArrayList<>(list.subList(from, to)) : new ArrayList<>();
		}

		// 通用兜底：仅在非 AI 扩词模式下过滤掉标题+正文不包含任何关键词的结果（原词或扩展词）。
		// AI 扩词模式下直接返回 ES 召回结果，避免 finalFilter 因片段/字段原因误杀有效结果导致数量骤降。
		System.out.println("[DEBUG-CTRL] before finalFilter listSize=" + list.size() + ", requestAiExpand=" + requestAiExpand);
		if (!requestAiExpand && StringUtils.isNotEmpty(rerankKw) && list != null) {
			// 同时检查原词和扩展词
			List<String> checkTerms = new ArrayList<>();
			checkTerms.add(rerankKw);
			if (allExpand != null) checkTerms.addAll(allExpand);
			list = list.stream().filter(c -> {
				String text = ((c.getTitle() == null ? "" : c.getTitle()) + " " + (c.getContent() == null ? "" : c.getContent())).replaceAll("<[^>]+>", "");
				for (String term : checkTerms) {
					if (StringUtils.isNotEmpty(term) && text.contains(term)) return true;
				}
				return false;
			}).collect(Collectors.toList());
			total = list.size();
			realTotal = total; // 同步更新真实总数，避免前端显示"共 X 个结果"与实际数据不符
			System.out.println("[DEBUG-CTRL] after finalFilter listSize=" + list.size());
		}

		// AI 扩词候选池拉回 200 条后，在这里按原始分页参数截断返回
		if (requestAiExpand) {
			int from = Math.max(0, (originalPageNum - 1) * originalPageSize);
			int to = Math.min(from + originalPageSize, list.size());
			list = (from < list.size()) ? new ArrayList<>(list.subList(from, to)) : new ArrayList<>();
			System.out.println("[DEBUG-CTRL] after paging listSize=" + list.size() + ", total=" + total);
		}

		TableDataInfo<OaPublishContent> dataTable = TableDataInfo.getDataTable(list);
		dataTable.setTotal(total);
		// 把 AI 扩词通过 msg 字段返回给前端
		String correction = "";
			try {
				if (realTotal == 0 && total == 0 && StringUtils.isNotEmpty(rerankKw) && StringUtils.isNotEmpty(aiExpandUrl)) {
					String fixed = correctTypo(rerankKw);
					if (StringUtils.isNotEmpty(fixed) && !fixed.equals(rerankKw)) correction = fixed;
				}
			} catch (Exception ignore) {}
			// msg 五段：真实总数 || AI扩词(chip) || 全部扩词(高亮) || 纠错建议(您是不是想搜) || 部门列表
			// 裸部门名（无路径）补归口标注："展示名#原值"，仅影响展示不影响筛选
			deptList = enrichBareDeptNames(deptList);
			// 部门下拉排序：本部部门在前、分公司在后，按组织机构 order_num 排
			deptList = sortDeptByOrgOrder(deptList);
			dataTable.setMsg(realTotal + "||" + (allExpand != null ? String.join(",", allExpand) : "") + "||" + (allExpand != null ? String.join(",", allExpand) : "") + "||" + correction + "||" + (deptList != null ? String.join(",", deptList) : ""));
		// 记录搜索使用日志，失败不影响检索
		try {
			String uname = "";
			try { uname = com.example.system.utils.SecurityUtils.getUsername(); } catch (Exception ignore) {}
			searchLogService.record(rerankKw, map.get("type"), map.get("method"),
					parseLong(map.get("userId")), uname, parseLong(map.get("deptId")), parseLong(map.get("tenantId")), realTotal);
		} catch (Exception ignore) {}
		// 写入缓存，5 分钟过期
		try {
			redisTemplate.opsForValue().set(cacheKey, com.alibaba.fastjson2.JSON.toJSONString(dataTable), 5, java.util.concurrent.TimeUnit.MINUTES);
		} catch (Exception e) {
			log.warn("[全文检索] 写入缓存失败: {}", e.getMessage());
		}
		return dataTable;
	}

	@GetMapping("/queryReadTest")
	public TableDataInfo queryReadTest(@Parameter String searchTerm) {
		List<OaPublishContent> list = new ArrayList<>();
		Long total = 0L;
		try {
//			Map<String, Object> map = elasticService.buildChunkFuzzyQuery(searchTerm);
//			total = (Long) map.get("total");
//			list = (List<OaPublishContent>) map.get("data");
		} catch (Exception e) {
			e.printStackTrace();
		}
		TableDataInfo<OaPublishContent> dataTable = TableDataInfo.getDataTable(list);
		dataTable.setTotal(total);
		return dataTable;
	}

//	@GetMapping("/queryReadTest")
//	public AjaxResult queryReadTest(@Parameter String searchTerm) {
//
//		try {
//			Map<String, Object> map = elasticService.buildChunkFuzzyQuery(searchTerm);
//
//
//
//			return AjaxResult.success(map);
//		} catch (Exception e) {
//			e.printStackTrace();
//			return AjaxResult.error();
//		}
//	}


	@PostMapping("/test")
	public AjaxResult test() {

//		OaPublishContent oaPublishContent = oaPublishContentService.queryByPrimaryKey("${RECORD_ID}");
//		OaPublishContent oaPublishContent1 = oaPublishContentService.queryByPrimaryKey("0");
//		List<OaPublishContent> oaPublishContents = oaPublishContentService.queryAll();
		try {
//			elasticService.insertDoc()
//			elasticService.insertDoc("oapublishcontent", oaPublishContent1);
			OaPublishContent oaPublishContent = new OaPublishContent();
			elasticService.createIndex("oa_publish_content",oaPublishContent);
			return AjaxResult.success();
		} catch (Exception e) {
			e.printStackTrace();
			return AjaxResult.error();
		}
	}

	@PostMapping("/testQuery")
	public AjaxResult testQuery() {

//		OaPublishContent oaPublishContent = oaPublishContentService.queryByPrimaryKey("${RECORD_ID}");
//		OaPublishContent oaPublishContent1 = oaPublishContentService.queryByPrimaryKey("0");
		List<OaPublishContent> oaPublishContents = oaPublishContentService.queryAll();
		try {
//			elasticService.insertDoc()
			elasticService.insertDocBulk("oa_publish_content", oaPublishContents);
//			OaPublishContent oaPublishContent = new OaPublishContent();
//			elasticService.createIndex("oa_publish_content",oaPublishContent);
			return AjaxResult.success();
		} catch (Exception e) {
			e.printStackTrace();
			return AjaxResult.error();
		}
	}

	@PostMapping("/query")
	public AjaxResult query(@RequestBody Map<String,String> map) {

//		LoginUser loginUser = tokenService.getLoginUser(ServletUtils.getRequest());
//		Long userId = loginUser.getUser().getUserId();

		try {
			return AjaxResult.success(elasticService.queryEs(map));
		} catch (Exception e) {
			e.printStackTrace();
			return AjaxResult.error();
		}
	}


	@GetMapping("/deleteIndex")
	public AjaxResult deleteIndex(@Parameter String indexName) {

//		LoginUser loginUser = tokenService.getLoginUser(ServletUtils.getRequest());
//		Long userId = loginUser.getUser().getUserId();

		try {
			elasticService.deleteIndex(indexName);
			return AjaxResult.success();
		} catch (Exception e) {
			e.printStackTrace();
			return AjaxResult.error();
		}
	}


	@GetMapping("/queryRead")
	public AjaxResult queryRead(@Parameter String path,@Parameter(required = false) String filePath,String fileId) {

		try {
			// 1. 读取Word内容
//			String content = TextSegmenterUtils.readFilePathContent(path);
//			String text = TextSegmenterUtils.filterContent(content);
//			String regEx="[\n`~@#$%^&*()+=|{}':;',\\[\\].<>/~@#￥%……&*（）——+|{}‘；：”“’ 、]";
//			Pattern p = Pattern.compile(regEx);
//			Matcher m = p.matcher(content.replaceAll("[\\\\\"]", "").replace("\\", "").replaceAll("\"", ""));
//			String text = m.replaceAll("").trim();

			// 2. 分割内容
//			List<String> chunks = TextSegmenterUtils.splitByLength(text,50000);

			Map<String, Object> jsonMap = new HashMap<>();
			//${RECORD_ID} read.docx
			//${RECORD_ID} 某内部通知文件.doc
			//${RECORD_ID} 某内部通知文件.pdf
			//${RECORD_ID} 某指导手册.docx
			//${RECORD_ID} 某科技公司某指导手册通知〔2025〕号.docx
			jsonMap.put("file_id",fileId);
			jsonMap.put("create_user","admin");
			jsonMap.put("create_time","2025-04-18 02:27:33");
			jsonMap.put("tenant_id","100");
			jsonMap.put("publish_dept_id",100);
			jsonMap.put("publish_dept_name","北京集团");
			jsonMap.put("publish_user_id","1");
			jsonMap.put("publish_user","admin");


			jsonMap.put("types","4");
			jsonMap.put("id", SnowFlake.getId());
			jsonMap.put("file_path",filePath);

			//来文部门
			jsonMap.put("received_unit","公文read");
			//文种（字典类型：doc_type）
			jsonMap.put("doc_type_name","汇报");
			//目前标题title为 来源：文件名，后续更改？
			String fileName = filePath.substring(filePath.lastIndexOf("/") + 1);
			jsonMap.put("title",jsonMap.get("received_unit")+"关于做好2025年专业技术职务任职资格评定工作的通知〔2025〕号");
			jsonMap.put("file_name",fileName);
			jsonMap.put("publish_time","2025-04-28");

//			elasticService.indexDocument("official_doc", text, chunks,jsonMap);

			return AjaxResult.success();
		} catch (Exception e) {
			e.printStackTrace();
			return AjaxResult.error();
		}
	}



	public String readWordContent(String filePath) throws Exception {
		FileInputStream fis = new FileInputStream(filePath);
		XWPFDocument document = new XWPFDocument(fis);
		XWPFWordExtractor extractor = new XWPFWordExtractor(document);
		String content = extractor.getText();
		extractor.close();
		document.close();
		fis.close();
		return content;
	}

	//按段落分割
	public List<String> splitByParagraph(String content) {
		return Arrays.asList(content.split("\\r?\\n"));
	}

	//固定长度分割
	public List<String> splitByLength(String content, int chunkSize) {
		List<String> chunks = new ArrayList<>();
		for (int i = 0; i < content.length(); i += chunkSize) {
			chunks.add(content.substring(i, Math.min(content.length(), i + chunkSize)));
		}
		return chunks;
	}

//
//	/**
//	 * 高级检索
//	 * @param map
//	 * @return
//	 */
//	@PostMapping("/advancedSearch")
//	public AjaxResult advancedSearch(@RequestBody Map<String,String> map) {
//		try {
//			return AjaxResult.success(elasticService.advancedSearch(map));
//		} catch (Exception e) {
//			e.printStackTrace();
//			return AjaxResult.error();
//		}
//	}



//
//	@PostMapping("/querysentence")
//	public AjaxResult querysentence(@RequestBody List<SearchBean> list) {
//		try {
//			if(null == list || list.size()==0) {
//				return AjaxResult.error("没有查询条件!");
//			}
//			return AjaxResult.success(elasticService.querysentence(list));
//		} catch (Exception e) {
//			e.printStackTrace();
//			return AjaxResult.error();
//		}
//	}

	// ===================== 部门下拉排序：顶层单位序（本部→研究院→分公司）× 部门在父级内序号 =====================
	/** 去根前缀全路径 -> order_num（该部门在其父级下的序号），缓存，组织机构调整需重启应用刷新 */
	private volatile Map<String, Integer> deptPathOrderCache;
	/** 顶层单位（parentId=1：公司本部/财务共享中心/研究院/各分公司）名称 -> order_num 缓存 */
	private volatile List<Map.Entry<String, Integer>> topUnitOrderCache;

	private static Integer parseOrder(String orderNum) {
		if (StringUtils.isEmpty(orderNum)) return null;
		try { return Integer.valueOf(orderNum.trim()); } catch (Exception e) { return null; }
	}

	private Map<String, Integer> deptPathOrderMap() {
		Map<String, Integer> c = deptPathOrderCache;
		if (c == null) {
			synchronized (this) {
				if (deptPathOrderCache == null) {
					c = new HashMap<>();
					List<Map.Entry<String, Integer>> tops = new ArrayList<>();
					try {
						for (com.example.system.core.domain.entity.SysDept d : sysDeptService.selectDeptList(new com.example.system.core.domain.entity.SysDept())) {
							Integer on = parseOrder(d.getOrderNum());
							if (on == null) continue;
							String fn = d.getFullName();
							if (StringUtils.isNotEmpty(fn)) {
								// 去根公司前缀的全路径作为键，如"公司本部>办公室/党委办公室"、"华东分公司>综合管理部"
								c.putIfAbsent(stripRootCompany(fn), on);
							}
							// 顶层单位（parentId=1）：公司本部、财务共享中心、研究院、各分公司
							if ("1".equals(String.valueOf(d.getParentId())) && StringUtils.isNotEmpty(d.getDeptName())) {
								tops.add(new java.util.AbstractMap.SimpleEntry<>(d.getDeptName(), on));
							}
						}
					} catch (Exception ignore) {}
					deptPathOrderCache = c;
					topUnitOrderCache = tops;
				}
			}
		}
		return deptPathOrderCache;
	}

	private List<Map.Entry<String, Integer>> topUnitOrderList() {
		if (topUnitOrderCache == null) deptPathOrderMap();
		return topUnitOrderCache != null ? topUnitOrderCache : java.util.Collections.emptyList();
	}

	/** 裸部门名 -> sys_dept 全路径列表（同名部门可能多个），缓存，组织机构调整需重启刷新 */
	private volatile Map<String, List<String>> bareDeptFullCache;
	/** 裸部门名 -> 唯一全路径展示名（仅同名唯一时有值），用于展示补全和筛选别名 */
	private volatile Map<String, String> bareToFullLabelCache;

	/** 去根公司前缀：full_name 里根公司名可能出现多次（分隔/粘连混杂），循环剥离 */
	private static String stripRootCompany(String fn) {
		if (fn == null) return null;
		String r = fn;
		while (r.startsWith("某新能源集团")) {
			r = r.substring("某新能源集团".length());
			if (r.startsWith(">")) r = r.substring(1);
		}
		return r;
	}

	private Map<String, List<String>> bareDeptFullMap() {
		Map<String, List<String>> c = bareDeptFullCache;
		if (c == null) {
			synchronized (this) {
				if (bareDeptFullCache == null) {
					c = new HashMap<>();
					try {
						for (com.example.system.core.domain.entity.SysDept d : sysDeptService.selectDeptList(new com.example.system.core.domain.entity.SysDept())) {
							if (StringUtils.isEmpty(d.getDeptName()) || StringUtils.isEmpty(d.getFullName())) continue;
							c.computeIfAbsent(d.getDeptName(), k -> new ArrayList<>()).add(d.getFullName());
						}
					} catch (Exception ignore) {}
					bareDeptFullCache = c;
					// 同名唯一的裸名 -> 去根前缀的全路径展示名
					Map<String, String> alias = new HashMap<>();
					for (Map.Entry<String, List<String>> en : c.entrySet()) {
						if (en.getValue().size() == 1) {
							alias.put(en.getKey(), stripRootCompany(en.getValue().get(0)));
						}
					}
					bareToFullLabelCache = alias;
				}
			}
		}
		return bareDeptFullCache;
	}

	private Map<String, String> bareToFullLabelMap() {
		if (bareToFullLabelCache == null) bareDeptFullMap();
		return bareToFullLabelCache != null ? bareToFullLabelCache : java.util.Collections.emptyMap();
	}

	/**
	 * 裸部门名（无">"路径）补充归口标注，仅影响下拉展示，筛选值保持原值。
	 * 条目格式变为"展示名#原值"，前端拆开展示/提交：
	 * 1) 同名部门在全集团唯一 → 展示全路径（去根公司前缀），如 办公室/党委办公室 → 公司本部>办公室/党委办公室
	 * 2) 同名部门只存在于分公司（如 综合管理部 有 15 个同名）→ 展示加"（分公司）"后缀
	 * 3) 本部与分公司都有同名 → 原样返回
	 */
	private List<String> enrichBareDeptNames(List<String> deptList) {
		if (deptList == null || deptList.isEmpty()) return deptList;
		try {
			Map<String, List<String>> bareMap = bareDeptFullMap();
			Map<String, String> aliasMap = bareToFullLabelMap();
			List<String> enriched = new ArrayList<>(deptList.size());
			for (String e : deptList) {
				if (e == null || e.contains("#")) { enriched.add(e); continue; }
				// 带根公司前缀的全路径条目：展示名去掉前缀（统一短格式），筛选值保持原样
				if (e.startsWith("某新能源集团")) {
					enriched.add(stripRootCompany(e) + "#" + e);
					continue;
				}
				if (e.contains(">")) { enriched.add(e); continue; }
				List<String> fulls = bareMap.get(e);
				if (fulls == null || fulls.isEmpty()) { enriched.add(e); continue; }
				String alias = aliasMap.get(e);
				if (alias != null) {
					// 同名唯一：展示全路径，如 办公室/党委办公室 → 公司本部>办公室/党委办公室
					enriched.add(alias + "#" + e);
				} else {
					// 同名多个：只存在于分公司的加"（分公司）"后缀，本部也有的保持原样
					boolean anyBenbu = fulls.stream().anyMatch(f -> f.contains(">公司本部>"));
					enriched.add(anyBenbu ? e : e + "（分公司）#" + e);
				}
			}
			// 按展示名去重：补全条目与原生全路径条目可能同名（如两种"公司本部>办公室/党委办公室"），
			// 保留带"#"的（其原值命中裸名文档，全路径文档由筛选别名兜底）
			java.util.LinkedHashMap<String, String> byLabel = new java.util.LinkedHashMap<>();
			for (String e : enriched) {
				String label = e.contains("#") ? e.substring(0, e.indexOf('#')) : e;
				String prev = byLabel.get(label);
				if (prev == null || (!prev.contains("#") && e.contains("#"))) {
					byLabel.put(label, e);
				}
			}
			return new ArrayList<>(byLabel.values());
		} catch (Exception ex) {
			log.warn("部门裸名归口标注失败，按原样返回: {}", ex.getMessage());
			return deptList;
		}
	}

	/**
	 * 部门下拉排序（两级）：先按顶层单位序（公司本部/财务共享中心/研究院/各分公司，取 parentId=1 的 order_num），
	 * 同一单位内再按部门在其父级下的 order_num。deptList 条目形如"公司本部>人力资源部"或"宁夏分公司>党群工作部"，
	 * 顶层单位多为简称，用包含关系匹配 sys_dept 全称；未匹配的组织保持原相对顺序垫底。
	 */
	private List<String> sortDeptByOrgOrder(List<String> deptList) {
		if (deptList == null || deptList.size() < 2) return deptList;
		try {
			Map<String, Integer> pathOrders = deptPathOrderMap();
			List<Map.Entry<String, Integer>> topUnits = topUnitOrderList();
			Map<String, Integer> keyOf = new HashMap<>();
			for (int i = 0; i < deptList.size(); i++) {
				String e = deptList.get(i);
				// 标注过的条目格式为"展示名#原值"，匹配用展示名；再去根公司前缀（兼容粘连格式）
				String label = e.contains("#") ? e.substring(0, e.indexOf('#')) : e;
				String norm = stripRootCompany(label);
				Integer key = null;
				if (label.endsWith("（分公司）")) {
					// 仅分公司存在的裸名（如 综合管理部）：排在本部/研究院之后、具体分公司之前
					key = 999_999;
				} else {
					// 顶层单位序号（first=首段，如"公司本部"/"宁夏分公司"）
					String first = norm.contains(">") ? norm.substring(0, norm.indexOf('>')) : norm;
					Integer unitOrder = null;
					for (Map.Entry<String, Integer> u : topUnits) {
						if (u.getKey().contains(first) || first.contains(u.getKey())) {
							unitOrder = u.getValue();
							break;
						}
					}
					// 部门在其父级下的序号（全路径精确匹配）
					Integer deptOrder = pathOrders.get(norm);
					if (unitOrder != null) {
						key = unitOrder * 10000 + (deptOrder != null ? deptOrder : 5000);
					} else if (deptOrder != null) {
						key = deptOrder;
					}
				}
				keyOf.put(e, key != null ? key : Integer.MAX_VALUE - 100000 + i);
			}
			List<String> sorted = new ArrayList<>(deptList);
			sorted.sort(java.util.Comparator.comparingInt(keyOf::get));
			return sorted;
		} catch (Exception ex) {
			log.warn("部门排序失败，按原顺序返回: {}", ex.getMessage());
			return deptList;
		}
	}
}

