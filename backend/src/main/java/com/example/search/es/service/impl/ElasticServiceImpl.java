package com.example.search.es.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.*;
import co.elastic.clients.elasticsearch._types.analysis.Analyzer;
import co.elastic.clients.elasticsearch._types.analysis.TokenChar;
import co.elastic.clients.elasticsearch._types.analysis.Tokenizer;
import co.elastic.clients.elasticsearch._types.mapping.*;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery.Builder;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.ExistsRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.search.*;
import co.elastic.clients.elasticsearch.indices.*;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.FieldSort;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.json.JsonData;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.TypeReference;
import com.example.common.utils.SnowFlake;
import com.example.common.utils.StringUtils;
import com.example.search.es.annotation.Esconf;
import com.example.search.es.entity.SearchBean;
import com.example.search.es.entity.SortBean;
import com.example.search.es.service.ElasticService;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.reflect.Field;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ElasticServiceImpl<T> implements ElasticService<T> {

	@Autowired
	private ElasticsearchClient client;
	/*	管理员、本部公司领导、本部文书： (general_system_admin,  admin , general_company_leadership , general_company_manager, general_company_partysecretary ,officialdoc_company_outgoingclerk  ,officialdoc_company_incomingclerk )
        分公司领导、分公司文书：(general_subcompany_leadership,  general_subcompany_partysecretary, general_subcompany_manager, officialdoc_subcompany_outgoingclerk , officialdoc_subcompany_incomingclerk )
        本部部门主任+分公司部门主任:  (general_company_departleadmanager,  general_company_departmanager,   general_subcompany_departleadmanager,  general_subcompany_departmanager)*/
	private final static String[] leaderRoles = "general_system_admin,admin,general_company_leadership,general_company_manager,general_company_partysecretary,officialdoc_company_outgoingclerk,officialdoc_company_incomingclerk".split(",");

	private final static String[] fenGsRoles = "general_subcompany_leadership,general_subcompany_partysecretary,general_subcompany_manager,officialdoc_subcompany_outgoingclerk,officialdoc_subcompany_incomingclerk".split(",");

	// 注意：逗号后不得带空格，否则 split 出的元素含前导空格，equalsIgnoreCase 永不命中（部门主任角色曾因此失效）
	private final static String[] benbuRoles = "general_company_departleadmanager,general_company_departmanager,general_subcompany_departleadmanager,general_subcompany_departmanager".split(",");
	private TermsQueryField.Builder f;

	private Map<String, Object> checkAppendCondition(T t) {
		Map<String, Object> params = new HashMap<String, Object>();
		Field[] fields = t.getClass().getDeclaredFields();
		List<String> highLightFields = new ArrayList<String>();
		List<SortBean> sortFields = new ArrayList<SortBean>();

		SortBean sb = null;
		for (int i = 0; i < fields.length; i++) {
			Field field = fields[i];
			String fieldName = field.getName();
			Esconf esconf = field.getAnnotation(Esconf.class);
			if (null != esconf) {
				if (esconf.isHighLight()) {
					highLightFields.add(fieldName);
				}
				if (esconf.isSort()) {
					sb = new SortBean();
					sb.setFieldName(fieldName);
					sb.setSortBy(esconf.sortBy());
					sb.setOrder(esconf.order());
					sortFields.add(sb);
				}
			}
		}
		sortFields.sort((a, b) -> a.getOrder() > b.getOrder() ? 1 : 0);
		params.put("hfield", highLightFields);
		params.put("sfield", sortFields);
		return params;
	}

	public List<SearchBean> toSearchParams(T t) throws Exception {
		Field[] fields = t.getClass().getDeclaredFields();
		Map<String, String> t_map = new HashMap<String, String>();
		for (int i = 0; i < fields.length; i++) {
			Field field = fields[i];
			Esconf esconf = field.getAnnotation(Esconf.class);
			String type = "like";
			if (null != esconf) {
				type = esconf.searchType();
			}
			t_map.put(field.getName(), type);
		}
		List<SearchBean> list = new ArrayList<SearchBean>();

		Map<String, Object> map = BeanUtil.beanToMap(t);
		Set<String> keySet = map.keySet();
		SearchBean bean = null;
		for (String key : keySet) {
			if (null != map.get(key) && !"".equals(String.valueOf(map.get(key)))
					&& !"{}".equals(String.valueOf(map.get(key)))) {
				bean = new SearchBean();
				bean.setType(t_map.get(key));
				bean.setRelation("and");
				if ("params".equals(key)) {
					String jsonStr = map.get(key).toString();
					jsonStr = jsonStr.replace("{", "{\"");
					jsonStr = jsonStr.replace("=", "\":\"");
					jsonStr = jsonStr.replace(", ", "\",\"");
					jsonStr = jsonStr.replace("}", "\"}");
					JSONObject jsonObject = JSONObject.parseObject(jsonStr);
					String beginTime = jsonObject.get("beginTime").toString();
					String endTime = jsonObject.get("endTime").toString();
					String field = jsonObject.get("field").toString();
					bean.setFieldName(field);
					bean.setType("range");
					if (null == beginTime || "".equals(beginTime) || beginTime.length() == 0
							|| "null".equals(beginTime)) {
						bean.setKeyword(null);
					} else {
						bean.setKeyword(beginTime);
					}
					if (null == endTime || "".equals(endTime) || endTime.length() == 0 || "null".equals(endTime)) {
						bean.setOther(null);
					} else {
						bean.setOther(endTime);
					}
				} else {
					bean.setFieldName(key);
					bean.setKeyword(String.valueOf(map.get(key)));
				}
				list.add(bean);
			}
		}
		return list;
	}

	private Map<String, Property> createPropertyMap(T t) {
		Field[] fields = t.getClass().getDeclaredFields();
		if (fields != null) {
			Map<String, Property> propertyMap = new HashMap<String, Property>();
			for (Field field : fields) {
				String fieldName = field.getName();
				Esconf esconf = field.getAnnotation(Esconf.class);
				if (field.getType() == String.class) {
					String stringType = "text";
					if (null != esconf) {
						stringType = esconf.stringType();
					}
					if ("keyword".equals(stringType)) {
						propertyMap.put(fieldName, new Property(new KeywordProperty.Builder().index(true).build()));
					} else {
						propertyMap.put(fieldName,
								new Property(new TextProperty.Builder().index(true).analyzer("my_tokenizer").build()));
					}
				} else if (field.getType() == Long.class || field.getType() == Integer.class) {
					propertyMap.put(fieldName, new Property(new KeywordProperty.Builder().build()));
				} else if (field.getType() == Date.class) {
					propertyMap.put(fieldName, new Property(new DateProperty.Builder().build()));
				} else {
					propertyMap.put(fieldName, new Property(new KeywordProperty.Builder().build()));
				}
			}
			return propertyMap;
		} else {
			// System.out.println("实体类：" + c.getName() + "不存在成员变量");
			return null;
		}
	}

	@Override
	public Boolean isExists(String indexName) throws Exception {
		// TODO Auto-generated method stub
		Boolean isExists = false;
		try {
			isExists = client.indices().exists(b -> b.index(indexName)).value();
		} catch (ElasticsearchException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return isExists;
	}

	@Override
	public Boolean isExistsDoc(String indexName, String docId) throws Exception {
		try {
			ExistsRequest request = ExistsRequest.of(b -> b
					.index(indexName)
					.id(docId));
			return client.exists(request).value();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return false;
	}

	@Override
	public void createIndex(String indexName, T t) throws Exception {
		if (isExists(indexName)) {
			System.out.println("索引:" + indexName + " exists........");
			return;
		}
		Map<String, Property> propertyMap = this.createPropertyMap(t);
		TypeMapping mapping = new TypeMapping.Builder().properties(propertyMap).build();
		IndexSettingsAnalysis.Builder value = new IndexSettingsAnalysis.Builder();
		value.analyzer("my_tokenizer", Analyzer.of(f -> f.custom(c -> c.tokenizer("my_tokenizer"))));
		value.tokenizer("my_tokenizer", Tokenizer.of(f -> f
				.definition(d -> d.ngram(n -> n.maxGram(5).minGram(2).tokenChars(TokenChar.Letter, TokenChar.Digit)))));
		IndexSettings indexSettings = new IndexSettings.Builder().numberOfShards(String.valueOf(1))// 分片
				.numberOfReplicas(String.valueOf(1))// 备份
				.analysis(value.build()).maxNgramDiff(5).build();
		CreateIndexRequest createIndexRequest = new CreateIndexRequest.Builder().index(indexName.toLowerCase())
				.mappings(mapping).settings(indexSettings).build();
		// 执行创建索引操作并返回结果
		client.indices().create(createIndexRequest);
	}

	@Override
	public Integer insertDoc(String indexName,String id, T t) throws Exception {
		int i = 0;
		Map<String,Object> temp = new HashMap<>();
		Field[] fields = t.getClass().getDeclaredFields();
		for (Field field : fields) {
			field.setAccessible(true);
			String fieldName = field.getName();
			Object v = field.get(t);
			if (v instanceof Collection || v instanceof Map){
				String value = v.toString();
				temp.put(fieldName, JSON.toJSONString(value));
			}else{
				temp.put(fieldName,v);
			}

		}
		IndexRequest<Map> request = IndexRequest.of(r -> r.index(indexName).id(id).document(temp));
		IndexResponse response = client.index(request);
//			System.out.println(response.result());
		i = "Created".equals(response.result().toString()) ? 1 : 0;
		return i;
	}

	@Override
	public void insertDocBulk(String idnexName, List<T> list) throws Exception {
		List<BulkOperation> ops = new ArrayList<BulkOperation>();
		list.forEach(item -> {
			BulkOperation.Builder bo = new BulkOperation.Builder();
			Class<? extends Object> clazz = item.getClass();
			Field field = null;
			try {
				field = clazz.getDeclaredField("id");
				field.setAccessible(true);
				String id = field.get(item).toString();
				bo.index(idx -> idx.index(idnexName).id(id).document(item));
				ops.add(bo.build());
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
		BulkRequest.Builder br = new BulkRequest.Builder();
		br.operations(ops);
		client.bulk(br.build());
	}

	@SuppressWarnings("serial")
	@Override
	public void deleteIndex(String indexName) throws Exception {
		if (isExists(indexName)) {
			System.out.println("product exists........delete");
			DeleteIndexRequest request = new DeleteIndexRequest.Builder().index(new ArrayList<String>() {
				{
					add(indexName);
				}
			}).build();
			client.indices().delete(request);
		}

	}

	@Override
	public void deleteIndexBulk(List<String> indexs) throws Exception {
		List<String> collect = indexs.stream().filter(f -> {
			try {
				return isExists(f);
			} catch (Exception e1) {
				e1.printStackTrace();
				return false;
			}
		}).collect(Collectors.toList());
		DeleteIndexRequest.Builder db = new DeleteIndexRequest.Builder().index(collect).allowNoIndices(true);
		client.indices().delete(db.build());

	}

	@Override
	public void deleteDocById(String indexName, String id) throws Exception {
		DeleteRequest deleteRequest = new DeleteRequest.Builder().index(indexName).id(id).build();
		DeleteResponse delete = client.delete(deleteRequest);
		return;
	}

	@Override
	public Long deleteBulkByIds(String indexName, List<String> ids) throws Exception {
		BoolQuery.Builder builder = new BoolQuery.Builder();
		if (null != ids && ids.size() > 0) {
			ids.forEach(id -> {
				Query query = new TermQuery.Builder().field("id").value(FieldValue.of(id)).build()._toQuery();
				builder.should(query);
			});
		}
		DeleteByQueryRequest deleteByQueryRequest = DeleteByQueryRequest
				.of(f -> f.index(indexName).query(q -> q.bool(builder.build())));
		DeleteByQueryResponse deleteByQuery = client.deleteByQuery(deleteByQueryRequest);
		return deleteByQuery.deleted();
	}

	@Override
	public void deleteByQuery(String indexName, List<SearchBean> params) throws Exception {
		BoolQuery.Builder builder = new BoolQuery.Builder();
		if (null != params && params.size() > 0) {
			Query query = null;
			for (SearchBean searchBean : params) {
				if (null == searchBean.getKeyword()) {
					continue;
				}
				if ("term".equals(searchBean.getType())) {
					query = new TermQuery.Builder().field(searchBean.getFieldName())
							.value(FieldValue.of(searchBean.getKeyword())).build()._toQuery();
				} else if ("range".equals(searchBean.getType())) {
					Builder fb = new RangeQuery.Builder().field(searchBean.getFieldName() + ".keyword");
					if (null != searchBean.getKeyword()) {
						fb.from(searchBean.getKeyword());
					}
					if (null != searchBean.getOther()) {
						fb.to(searchBean.getOther());
					}
					query = fb.build()._toQuery();
				} else {
					query = new WildcardQuery.Builder().field(searchBean.getFieldName() + ".keyword")
							.wildcard("*" + searchBean.getKeyword() + "*").build()._toQuery();
				}

				if ("and".equals(searchBean.getRelation())) {
					builder.must(query);
				} else {
					builder.should(query);
				}
			}
		}
//		SearchRequest of = SearchRequest.of(f->f.index(indexName).query(q->q.bool(builder.build())));
//		SearchResponse<Map> response = client.search(of, Map.class);
//		System.out.println(response);
		DeleteByQueryRequest request = DeleteByQueryRequest
				.of(f -> f.index(indexName).query(q -> q.bool(builder.build())));
		DeleteByQueryResponse deleteByQuery = client.deleteByQuery(request);
		Long deleted = deleteByQuery.deleted();
		System.out.println("deleted:" + deleted);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Override
	public void updateDoc(String indexName, T t) throws Exception {
		Field field = t.getClass().getDeclaredField("id");
		field.setAccessible(true);
		String id = field.get(t).toString();
		UpdateRequest request = new UpdateRequest.Builder().index(indexName).id(id).doc(t).build();
		client.update(request, Map.class);
	}

	private BoolQuery buildQuery(List<SearchBean> list) {
		BoolQuery.Builder bb = new BoolQuery.Builder();

		Query query = null;
		for (SearchBean searchBean : list) {
			if (null == searchBean.getKeyword()) {
				continue;
			}
			if ("term".equals(searchBean.getType())) {
				query = new TermQuery.Builder().field(searchBean.getFieldName())
						.value(FieldValue.of(searchBean.getKeyword())).build()._toQuery();
			} else if ("range".equals(searchBean.getType())) {
				Builder fb = new RangeQuery.Builder().field(searchBean.getFieldName() + ".keyword");
				if (null != searchBean.getKeyword()) {
					fb.from(searchBean.getKeyword()+" 00:00:00");
				}
				if (null != searchBean.getOther()) {
					fb.to(searchBean.getOther()+" 23:59:59");
				}
				query = fb.build()._toQuery();
			} else {
				query = new WildcardQuery.Builder().field(searchBean.getFieldName() + ".keyword")
						.wildcard("*" + searchBean.getKeyword() + "*").build()._toQuery();
			}

			if ("and".equals(searchBean.getRelation())) {
				bb.must(query);
			} else {
				bb.should(query);
			}
		}
		return bb.build();
	}

	private List<SortOptions> buildSorts(List<SortBean> params) {
		List<SortOptions> list = new ArrayList<SortOptions>();
		for (SortBean bean : params) {
			FieldSort of = FieldSort.of(f -> f.field(bean.getFieldName()).order(SortOrder.valueOf(bean.getSortBy())));
			SortOptions options = SortOptions.of(f -> f.field(of));
			list.add(options);
		}
		return list;
	}

	/** 取数值字符串，为空或非数字返回 "-1" */
	private static String numOrNeg(Object o) {
		if (o == null) return "-1";
		String s = String.valueOf(o).trim();
		return s.matches("\\d+") ? s : "-1";
	}

	/**
	 * AI 扩词查询：原词查询完全复用精确模式（match_phrase + wildcard + 低权重 fuzzy），
	 * 扩展词作为低权重 should 子句叠加。
	 * 结果 = 精确模式结果 ∪ 扩展词命中结果，minimumShouldMatch=1 保证至少命中其一。
	 * 原词子句统一标记 _name=__ORIGIN_KEYWORD__，供 searchResponse 识别原词命中来源。
	 */
	private static final String ORIGIN_KEYWORD_NAME = "__ORIGIN_KEYWORD__";

	private Query buildKeywordOrQuery(String keyword, List<String> expandWords, String termGroups, String method, String... phraseFields) {
		if (keyword == null || keyword.trim().isEmpty()) return null;

		List<Query> shoulds = new ArrayList<>();
		String kw = keyword.trim();

		// 多词组合查询（逗号/空格/顿号/分号分隔）：每个词一组独立 OR 子句，组间 AND 约束（全部命中才召回）。
		// 语义："燃机，项目经理" = 同时包含"燃机"和"项目经理"的文档，而不是把组合当整体短语去相邻匹配
		List<String> multiTerms = splitMultiTerms(kw);
		if (multiTerms.size() > 1) {
			// 逐词分组：优先用 controller 传入的 termGroups（每组=原词+同义词），
			// 使"借调 中纪委"变为 借调 AND(中纪委|中央纪委|纪委|纪检|纪检监察) 的组间 AND，
			// 而不是平铺扩展把只含"纪委"不含"借调"的文档也放进来
			List<List<String>> groups = new ArrayList<>();
			if (termGroups != null && !termGroups.trim().isEmpty()) {
				for (String g : termGroups.split("\\|")) {
					List<String> grp = new ArrayList<>();
					for (String w : g.split(",")) {
						String x = w.trim();
						if (!x.isEmpty() && !grp.contains(x)) grp.add(x);
					}
					if (!grp.isEmpty()) groups.add(grp);
				}
			}
			// groups.size()==1 为合法情形：controller 判定合并词命中同义词表（如"网络 安全"→"网络安全"），
			// 整词作为单一概念一组下发；此时不拆回逐词 AND，否则泛词组合（网络 AND 安全）会召回半个语料库
			if (groups.size() != 1 && groups.size() != multiTerms.size()) {
				groups = new ArrayList<>();
				for (String t : multiTerms) groups.add(new ArrayList<>(java.util.Collections.singletonList(t)));
			}
			List<Query> ands = new ArrayList<>();
			for (List<String> group : groups) {
				List<Query> per = new ArrayList<>();
				for (int gi = 0; gi < group.size(); gi++) {
					final String term = group.get(gi);
					final boolean isOrigin = (gi == 0);
					final float tBoost = isOrigin ? 10.0f : 2.0f;
					final float bBoost = isOrigin ? 5.0f : 1.0f;
					final String qName = isOrigin ? ORIGIN_KEYWORD_NAME : term;
					per.add(Query.of(q -> q.matchPhrase(mp -> mp.field("title").query(term).boost(tBoost).queryName(qName))));
					if (isOrigin) {
						per.add(Query.of(q -> q.wildcard(w -> w.field("title.keyword").value("*" + term + "*").boost(8.0f).queryName(qName))));
						// 标题命中常量提权：多词组合同样保证标题命中排前
						per.add(Query.of(q -> q.constantScore(cs -> cs
								.filter(f -> f.matchPhrase(mp -> mp.field("title").query(term)))
								.boost(50.0f).queryName(qName))));
					}
					if (!"2".equals(method)) {
						for (String f : phraseFields) {
							final String field = f;
							per.add(Query.of(q -> q.matchPhrase(mp -> mp.field(field).query(term).boost(bBoost).queryName(qName))));
						}
					}
				}
				ands.add(Query.of(q -> q.bool(bb -> bb.should(per).minimumShouldMatch("1"))));
			}
			// 近邻约束改为 should 提权（不做硬性 must）：
			// 组间 AND 负责"每个词（或其同义词）都出现"，原词近邻只用来把"两词同段出现"的文档排到前面，
			// 避免 slop 写死原词后把"借调+纪委(同义命中)"的正当组合错杀
			List<Query> nearShoulds = new ArrayList<>();
			for (String f : phraseFields) {
				final String field = f;
				nearShoulds.add(Query.of(q -> q.matchPhrase(mp -> mp.field(field).query(kw).slop(80).boost(15.0f))));
			}
			// termGroups 命中时（每词已带同义词组）：扩展已组内消化，不再叠加平铺扩展 OR，
			// 否则"只含纪委不含借调"的几千篇文档会经平铺扩展淹没组合查询
			final boolean usedTermGroups = termGroups != null && !termGroups.trim().isEmpty();
			final List<Query> finalNearShoulds = nearShoulds;
			Query originAnd = Query.of(q -> q.bool(bb -> bb.must(ands).should(finalNearShoulds)));
			// 扩展词仍按 OR 并列兜底：与多词 AND 结果平级，命中扩展词也算召回
			if (!usedTermGroups && expandWords != null && !expandWords.isEmpty()) {
				List<Query> expandShoulds = new ArrayList<>();
				for (String t : expandWords) {
					if (t == null || t.trim().isEmpty()) continue;
					String term = t.trim();
					expandShoulds.add(Query.of(q -> q.matchPhrase(mp -> mp.field("title").query(term).boost(2.0f).queryName(term))));
					if ("2".equals(method)) {
						expandShoulds.add(Query.of(q -> q.wildcard(w -> w.field("title.keyword").value("*" + term + "*").boost(1.0f).queryName(term))));
					} else {
						for (String f : phraseFields) {
							final String field = f;
							expandShoulds.add(Query.of(q -> q.matchPhrase(mp -> mp.field(field).query(term).boost(1.0f).queryName(term))));
						}
					}
				}
				if (!expandShoulds.isEmpty()) {
					Query expandOr = Query.of(q -> q.bool(bb -> bb.should(expandShoulds).minimumShouldMatch("1")));
					return Query.of(q -> q.bool(bb -> bb.should(originAnd, expandOr).minimumShouldMatch("1")));
				}
			}
			return originAnd;
		}

		// 原词子句：只用 match_phrase + title.keyword 子串匹配，不用 fuzzy。
		// 避免 "弱口令" 被 fuzzy 拆成单字 "弱" 后召回 "两高一弱" 等无关结果。
		// constant_score 标题命中大额提权（50）：不受正文字数/词频影响，
		// 保证标题命中的文档排在仅正文命中的前面（长文档正文词频累积会压过普通 boost）。
		if ("2".equals(method)) {
			shoulds.add(Query.of(q -> q.matchPhrase(mp -> mp.field("title").query(kw).boost(10.0f).queryName(ORIGIN_KEYWORD_NAME))));
			shoulds.add(Query.of(q -> q.wildcard(w -> w.field("title.keyword").value("*" + kw + "*").boost(8.0f).queryName(ORIGIN_KEYWORD_NAME))));
			shoulds.add(Query.of(q -> q.constantScore(cs -> cs
					.filter(f -> f.matchPhrase(mp -> mp.field("title").query(kw)))
					.boost(50.0f).queryName(ORIGIN_KEYWORD_NAME))));
		} else {
			shoulds.add(Query.of(q -> q.matchPhrase(mp -> mp.field("title").query(kw).boost(10.0f).queryName(ORIGIN_KEYWORD_NAME))));
			shoulds.add(Query.of(q -> q.wildcard(w -> w.field("title.keyword").value("*" + kw + "*").boost(8.0f).queryName(ORIGIN_KEYWORD_NAME))));
			shoulds.add(Query.of(q -> q.constantScore(cs -> cs
					.filter(f -> f.matchPhrase(mp -> mp.field("title").query(kw)))
					.boost(50.0f).queryName(ORIGIN_KEYWORD_NAME))));
			for (String f : phraseFields) {
				final String field = f;
				shoulds.add(Query.of(q -> q.matchPhrase(mp -> mp.field(field).query(kw).boost(5.0f).queryName(ORIGIN_KEYWORD_NAME))));
			}
		}

		// 扩展词查询：每个扩展词在 title 和正文做 match_phrase（标题模式只查 title），低权重
		// 注意：正文不要用 match+AND 替代 match_phrase——扩展词被 IK 拆成常见字后（如"弱密码"->"弱/密码"），
		// match+AND 会召回大量只沾边单字的文档，且多子句得分叠加会把精确命中挤下去（实测 20 条里仅 4 条含原词）
		// 给每个扩展词子查询加 _name，以便从 ES 返回的 matched_queries 中反推哪些扩展词真实命中
		if (expandWords != null && !expandWords.isEmpty()) {
			for (String t : expandWords) {
				if (t == null || t.trim().isEmpty()) continue;
				String term = t.trim();
				if ("2".equals(method)) {
					shoulds.add(Query.of(q -> q.matchPhrase(mp -> mp.field("title").query(term).boost(2.0f).queryName(term))));
					shoulds.add(Query.of(q -> q.wildcard(w -> w.field("title.keyword").value("*" + term + "*").boost(1.0f).queryName(term))));
				} else {
					shoulds.add(Query.of(q -> q.matchPhrase(mp -> mp.field("title").query(term).boost(2.0f).queryName(term))));
					for (String f : phraseFields) {
						final String field = f;
						shoulds.add(Query.of(q -> q.matchPhrase(mp -> mp.field(field).query(term).boost(1.0f).queryName(term))));
					}
				}
			}
		}

		if (shoulds.isEmpty()) return null;
		return Query.of(q -> q.bool(bb -> bb.should(shoulds).minimumShouldMatch("1")));
	}

	/** 精确模式：短语/子串匹配为主，fuzzy 容错兜底为辅，不做分词 AND 兜底召回 */
	private Query buildExactKeywordQuery(String keyword, String method, String... phraseFields) {
		return buildExactKeywordQuery(keyword, method, null, phraseFields);
	}

	/**
	 * 精确模式（可统一标记 _name）。
	 * 当 queryName 不为空时，所有生成的子句都会带上 _name，供调用方从 matched_queries 中识别命中来源。
	 */
	private Query buildExactKeywordQuery(String keyword, String method, String queryName, String... phraseFields) {
		if (keyword == null || keyword.trim().isEmpty()) return null;
		String kw = keyword.trim();

		// 多词组合（逗号/空格/顿号/分号分隔）：逐词短语/子串匹配，组间 AND（全部命中才召回）；不做 fuzzy（多词 fuzzy 噪声太大）
		List<String> multiTerms = splitMultiTerms(kw);
		if (multiTerms.size() > 1) {
			List<Query> ands = new ArrayList<>();
			for (String t : multiTerms) {
				final String term = t;
				List<Query> per = new ArrayList<>();
				per.add(Query.of(q -> q.matchPhrase(mp -> mp.field("title").query(term).boost(10.0f).queryName(queryName))));
				per.add(Query.of(q -> q.wildcard(w -> w.field("title.keyword").value("*" + term + "*").boost(8.0f).queryName(queryName))));
				if (!"2".equals(method)) {
					for (String f : phraseFields) {
						final String field = f;
						per.add(Query.of(q -> q.matchPhrase(mp -> mp.field(field).query(term).boost(5.0f).queryName(queryName))));
					}
				}
				ands.add(Query.of(q -> q.bool(bb -> bb.should(per).minimumShouldMatch("1"))));
			}
			// 近邻约束（同 AI 扩词模式）：slop=80，防大汇编文档稀释精准度
			for (String f : phraseFields) {
				final String field = f;
				ands.add(Query.of(q -> q.matchPhrase(mp -> mp.field(field).query(kw).slop(80).boost(2.0f).queryName(queryName))));
			}
			return Query.of(q -> q.bool(bb -> bb.must(ands)));
		}

		List<Query> shoulds = new ArrayList<>();
		if ("2".equals(method)) {
			// 标题精确：同时用 match_phrase + title.keyword 子串匹配，避免缺少 keyword 子字段时 0 命中
			shoulds.add(Query.of(q -> q.matchPhrase(mp -> mp.field("title").query(kw).boost(10.0f).queryName(queryName))));
			shoulds.add(Query.of(q -> q.wildcard(w -> w.field("title.keyword").value("*" + kw + "*").boost(8.0f).queryName(queryName))));
			// fuzzy 容错：允许 1-2 字编辑距离/顺序错误，权重较低，不影响精确结果排序
			if (kw.length() <= 20) {
				shoulds.add(Query.of(q -> q.match(m -> m.field("title").query(kw).fuzziness("AUTO").boost(2.0f).queryName(queryName))));
			}
		} else {
			// 标题短语/子串 + 正文 match_phrase（要求分词后相邻，对收录词即精确匹配）
			shoulds.add(Query.of(q -> q.matchPhrase(mp -> mp.field("title").query(kw).boost(10.0f).queryName(queryName))));
			shoulds.add(Query.of(q -> q.wildcard(w -> w.field("title.keyword").value("*" + kw + "*").boost(8.0f).queryName(queryName))));
			for (String f : phraseFields) {
				final String field = f;
				shoulds.add(Query.of(q -> q.matchPhrase(mp -> mp.field(field).query(kw).boost(5.0f).queryName(queryName))));
			}
			// fuzzy 容错兜底：标题和正文都允许少量容错
			if (kw.length() <= 20) {
				shoulds.add(Query.of(q -> q.match(m -> m.field("title").query(kw).fuzziness("AUTO").boost(2.0f).queryName(queryName))));
				for (String f : phraseFields) {
					final String field = f;
					shoulds.add(Query.of(q -> q.match(m -> m.field(field).query(kw).fuzziness("AUTO").boost(1.0f).queryName(queryName))));
				}
			}
		}
		if (shoulds.isEmpty()) return null;
		return Query.of(q -> q.bool(b -> b.should(shoulds).minimumShouldMatch("1")));
	}

	/**
	 * 多词组合拆分：按空格/全角空格/逗号/顿号/分号拆分关键词，去空去重。
	 * 用于"燃机，项目经理"这类多词查询的 AND 召回。
	 */
	private static List<String> splitMultiTerms(String kw) {
		List<String> terms = new ArrayList<>();
		if (kw == null) return terms;
		for (String t : kw.split("[\\s\\u3000,，、;；]+")) {
			String x = t.trim();
			if (!x.isEmpty() && !terms.contains(x)) terms.add(x);
		}
		return terms;
	}

	/**
	 * 单个词的命中子句：短语匹配优先（权重与原逻辑一致），原词另加两层兜底，
	 * 解决 IK 词典未收录专名时 match_phrase 因分词边界错位而 0 命中的问题：
	 * 兜底1 标题按原文子串匹配（wildcard，不受分词影响）；
	 * 兜底2 正文 match 且 operator=AND（词项需全部出现，允许不相邻）。
	 */
	private List<Query> tokenClauses(String tk0, boolean original, String... phraseFields) {
		final String tk = tk0;
		List<Query> per = new ArrayList<>();
		final float tBoost = original ? 10.0f : 1.0f;
		final float cBoost = original ? 5.0f : 1.0f;
		per.add(Query.of(q -> q.matchPhrase(mp -> mp.field("title").query(tk).boost(tBoost))));
		for (String f : phraseFields) {
			final String field = f;
			per.add(Query.of(q -> q.matchPhrase(mp -> mp.field(field).query(tk).boost(cBoost))));
		}
		if (original) {
			per.add(Query.of(q -> q.wildcard(w -> w.field("title.keyword").value("*" + tk + "*").boost(8.0f))));
			for (String f : phraseFields) {
				final String field = f;
				per.add(Query.of(q -> q.match(m -> m.field(field).query(tk).operator(Operator.And).boost(2.0f))));
			}
		}
		return per;
	}

	private Map<String, HighlightField> buildHighLight(List<String> params) {
		Map<String, HighlightField> map = new HashMap<String, HighlightField>();
		HighlightField of = HighlightField.of(f -> f.preTags("<span style='color:red'>").postTags("</span>")
				.fragmentSize(100).requireFieldMatch(true)); // true=只高亮真正命中的字段，不匹配的不白算
		for (String field : params) {
			map.put(field, of);
		}
		return map;
	}

	@SuppressWarnings("unchecked")
	@Override
	public T queryById(String indexName, T t) throws Exception {
		Class<? extends Object> clazz = t.getClass();
		Field field = clazz.getDeclaredField("id");
		field.setAccessible(true);
		String id = field.get(t).toString();
		GetRequest getRequest = new GetRequest.Builder().index(indexName).id(id).build();
		GetResponse<T> response = (GetResponse<T>) client.get(getRequest, t.getClass());
		if (response.found())
			return response.source();
		return null;
	}

	@SuppressWarnings({ "unchecked" })
	public Map<String, Object> query(String indexName, T t, int... pages) throws Exception {
		Map<String, Object> result = new HashMap<String, Object>();
		if (null == indexName)
			return result;

		SearchRequest.Builder builder = new SearchRequest.Builder().trackTotalHits(TrackHits.of(f -> f.enabled(true)));
		Map<String, Object> params = this.checkAppendCondition(t);
		List<SearchBean> searchParams = this.toSearchParams(t);
		List<String> hfields = (List<String>) params.get("hfield");
		List<SortBean> sfields = (List<SortBean>) params.get("sfield");
		Query query = null;
		if (null == searchParams || searchParams.size() == 0) {
			MatchAllQuery v = new MatchAllQuery.Builder().build();
			query = new Query.Builder().matchAll(v).build();
		} else {
			query = this.buildQuery(searchParams)._toQuery();
			if (null != sfields && sfields.size() > 0) {
				List<SortOptions> buildSorts = this.buildSorts(sfields);
				builder.sort(buildSorts);
			}
			if (null != hfields && hfields.size() > 0) {
				Map<String, HighlightField> highLight = this.buildHighLight(hfields);
				builder.highlight(f -> f.fields(highLight));
			}

		}

		if (null != pages) {
			if (pages.length > 1) {
				int pageNum = pages[0];
				int pageSize = pages[1];
				builder.from((pageNum - 1) * pageSize);
				builder.size(pageSize);
			}
		}
		builder.index(indexName).query(query);
		SearchResponse<T> response = (SearchResponse<T>) client.search(builder.build(), t.getClass());
		HitsMetadata<T> hitsMetadata = response.hits();
		List<Hit<T>> hits = hitsMetadata.hits();
		List<T> data = hits.stream().map(hit -> hit.source()).collect(Collectors.toList());
		result.put("total", hitsMetadata.total().value());
		result.put("data", data);
		return result;
	}

	@SuppressWarnings("rawtypes")
	public Map<String, Object> aggregationsBycondition(String indexName, List<SearchBean> params, String aggregFiled)
			throws Exception {
//		Map<String, Object> map = new HashMap<String, Object>();
//
//		BoolQuery.Builder bb = new BoolQuery.Builder();
//		if (null == params || params.size() == 0) {
//			MatchAllQuery v = new MatchAllQuery.Builder().build();
//			Query query = new Query.Builder().matchAll(v).build();
//			bb.must(query);
//		} else {
//			Query query = null;
//			for (SearchBean searchBean : params) {
//				if (StringUtils.isNotEmpty(searchBean.getKeyword())) {
//					if ("term".equals(searchBean.getType())) {
//						query = new TermQuery.Builder().field(searchBean.getFieldName())
//								.value(FieldValue.of(searchBean.getKeyword())).build()._toQuery();
//					} else if ("range".equals(searchBean.getType())) {
//						Builder fb = new RangeQuery.Builder().field(searchBean.getFieldName() + ".keyword");
//						if (null != searchBean.getKeyword()) {
//							fb.from(searchBean.getKeyword());
//						}
//						if (null != searchBean.getOther()) {
//							fb.to(searchBean.getOther());
//						}
//						query = fb.build()._toQuery();
//					} else {
//						query = new WildcardQuery.Builder().field(searchBean.getFieldName() + ".keyword")
//								.wildcard("*" + searchBean.getKeyword() + "*").build()._toQuery();
//					}
//
//					if ("and".equals(searchBean.getRelation())) {
//						bb.must(query);
//					} else {
//						bb.should(query);
//					}
//				}
//			}
//		}
//		SearchRequest.Builder builder = new SearchRequest.Builder().trackTotalHits(TrackHits.of(f -> f.enabled(true)));
//		builder.index(indexName).query(q -> q.bool(bb.build())).aggregations(aggregFiled,
//				a -> a.terms(v -> v.field(aggregFiled + ".keyword")));
//		SearchResponse<Map> response = client.search(builder.build(), Map.class);
//		Map<String, Aggregate> aggregations = response.aggregations();
//		if (!aggregations.isEmpty()) {
//			Aggregate aggregate = aggregations.get(aggregFiled);
//			StringTermsAggregate sterms = aggregate.sterms();
//			Buckets<StringTermsBucket> buckets = sterms.buckets();
//			for (StringTermsBucket b : buckets.array()) {
//				map.put(String.valueOf(b.key()), b.docCount());
//			}
//		}
//		return map;
		return null;
	}


	public SearchResponse<Map>  combinedQuery(String indexName, String multiValueField, List<String> values, Map<String, String> andConditions,int... pages) throws IOException {

		//构建多值查询(terms)
//		Query multiValueQuery = Query.of(q -> q
//				.terms(t -> t
//						.field(multiValueField)
//						.terms(ts -> ts.value(values.stream()
//								.map(FieldValue::of)
//								.toList())
//						)
//				));
		String rolesStr = String.valueOf(andConditions.get("roles"));
		String[] rolesArr = rolesStr.split(",");
		String tenantId = numOrNeg(andConditions.get("tenantId"));
		String deptId = numOrNeg(andConditions.get("deptId"));
		String userId = numOrNeg(andConditions.get("userId"));
		String _docTypeRaw = andConditions.get("docType");
		final String docType = "null".equals(_docTypeRaw) ? null : _docTypeRaw;
		// 构建布尔查询
		BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
//		boolBuilder.must(multiValueQuery);

		// 1新闻，2,子公司动态,3.通知公告,4.公文公告,5.信息公开,99.公文
		String type = andConditions.get("type");
		//	1全文，2标题
		String method = andConditions.get("method");
		//搜索内容
		String keyword = andConditions.get("keyword");
		if(keyword == null){
			keyword = "";
		}
		//时间
		String startTime = andConditions.get("startTime");
		String endTime = andConditions.get("endTime");
		// 发文部门筛选：oa 系用 publishDeptName，公文系用 draftUnit。
		// 走 post_filter 而非 query 上下文：命中结果与总数照常过滤，但部门分面聚合（deptAgg/deptAgg2）不受影响——
		// 否则选中某部门后下拉里就只剩该部门，无法改选其他部门
		String deptName = andConditions.get("deptName");
		String deptNameAlias = andConditions.get("deptNameAlias");
		Query deptFilterQuery = null;
		if (StringUtils.isNotEmpty(deptName)) {
			BoolQuery.Builder db = new BoolQuery.Builder()
					.should(s -> s.term(t -> t.field("publishDeptName.keyword").value(deptName)))
					.should(s -> s.term(t -> t.field("draftUnit.keyword").value(deptName)));
			// 裸名补全归口后的别名（如 办公室/党委办公室 → 公司本部>办公室/党委办公室）：
			// 同一种部门在 ES 里有裸名和全路径两种存储格式，都要命中
			if (StringUtils.isNotEmpty(deptNameAlias)) {
				db.should(s -> s.term(t -> t.field("publishDeptName.keyword").value(deptNameAlias)))
						.should(s -> s.term(t -> t.field("draftUnit.keyword").value(deptNameAlias)));
			}
			db.minimumShouldMatch("1");
			deptFilterQuery = Query.of(q -> q.bool(db.build()));
		}

		//发布时间范围查询
		if(startTime != null && !startTime.trim().isEmpty() && endTime != null && !endTime.trim().isEmpty()) {
			if(type.equals("1")||type.equals("2")||type.equals("3")||type.equals("4")||type.equals("5")){
				// startTime = Convert.ToDateTime(startTime);
				// endTime = Convert.ToDateTime(endTime);
				// 1. 解析成 Date
                try {

                    boolBuilder.must(q4 -> q4.range(r -> r.field("publishTime").from(startTime).to(endTime)));

					/*Query query = Query.of(q -> q
							.bool(q1 -> q1
									.should(
											Query.of(qr -> qr
													.range(r -> r
															.field("publish_time")
															.gte(JsonData.of(startTime))  // 替代from
															.lte(JsonData.of(endTime))  // 替代to
													)
											)
									)
									.minimumShouldMatch("1") // 至少匹配一个should条件
							)
					);
					boolBuilder.must(query);*/
                } catch (Exception e) {
					log.error("publishTime 转换失败: {}", e.getMessage());
                }
            }else{
				boolBuilder.must(q4 -> q4.range(r -> r.field("publishTime").from(startTime).to(endTime)));
			}

		}
		boolean a = false;
		boolean b = false;
		boolean c = false;
		// 1新闻，2,子公司动态,3.通知公告,4.公文,5.信息公开
		if(type != null && !type.trim().isEmpty()) {
			if(type.equals("99")){
				// 公文：official_doc（公文系统）+ oa_publish_content 中 types=4 的公文公告
				BoolQuery.Builder docIndexFilter = new BoolQuery.Builder();
				docIndexFilter.should(q -> q.term(t -> t.field("_index").value("official_doc")));
				docIndexFilter.should(q -> q.bool(bb -> bb
						.must(m -> m.term(t -> t.field("_index").value("oa_publish_content")))
						.must(m -> m.match(t -> t.field("types").query("4")))
				));
				docIndexFilter.minimumShouldMatch("1");
				boolBuilder.must(Query.of(q -> q.bool(docIndexFilter.build())));

				// 角色权限过滤（与原逻辑一致）
				if(rolesArr!=null && rolesArr.length > 0){
					for(String role : rolesArr){
						//超管  领导角色看所有
						if(Arrays.stream(leaderRoles).anyMatch(role::equalsIgnoreCase)){
							a = true;
							break;
						}
						//分公司领导、分公司文书：看本公司
						if(Arrays.stream(fenGsRoles).anyMatch(role::equalsIgnoreCase)){
							b = true;
							break;
						}
						//本部部门主任+分公司部门主任：看本部门
						if(Arrays.stream(benbuRoles).anyMatch(role::equalsIgnoreCase)){
							c = true;
							break;
						}
					}
					if(b){
						boolBuilder.must(q2 -> q2.match(m -> m.field("tenantId").query(tenantId)));
					}
					if(c){
						boolBuilder.must(q2 -> q2.match(m -> m.field("deptId").query(deptId)));
					}
					if(!a && !b && !c){
						List<FieldValue> userIds = Arrays.asList(FieldValue.of(Long.parseLong(userId)));
						Query query = Query.of(q -> q
								.terms(t -> t
										.field("userIds")
										.terms(ts -> ts.value(userIds)
										)
								));
						boolBuilder.must(query);
					}
					if (StringUtils.isNotEmpty(docType) && !"null".equals(docType)){
						boolBuilder.must(q->q.term(t->t.field("docType").value(docType)));
					}
				}else{
					List<FieldValue> userIds = Arrays.asList(FieldValue.of(Long.parseLong(userId)));
					Query query = Query.of(q -> q
							.terms(t -> t
									.field("userIds")
									.terms(ts -> ts.value(userIds)
									)
							));
					boolBuilder.must(query);
				}
			}else{
				if(!"5".equals(type)){
					boolBuilder.must(q2 -> q2.match(m -> m.field("types").query(type)));
				}
			}

		}else {
			// 全部：公开类内容（oa_publish_content 中非公文公告 + oa_information_content 信息公开）对所有人可见；
			// 公文（official_doc + oa_publish_content 中 types=4 的公文公告）按角色权限过滤。
			// 不能对整个查询套 userIds 兜底——公开公告没有 userIds 字段，会被误排除（普通员工"全部"搜不到公告）
			BoolQuery.Builder vis = new BoolQuery.Builder();
			// 公开：新闻/子公司动态/通知公告（types 非 4 或为空，都按公开发布处理）
			vis.should(q -> q.bool(bb -> bb
					.must(m -> m.term(t -> t.field("_index").value("oa_publish_content")))
					.mustNot(m -> m.term(t -> t.field("types.keyword").value("4")))
			));
			// 公开：信息公开/资讯索引
			vis.should(q -> q.term(t -> t.field("_index").value("oa_information_content")));
			// 公文范围：official_doc + oa_publish_content 中 types=4 的公文公告
			BoolQuery.Builder docScope = new BoolQuery.Builder();
			docScope.should(q -> q.term(t -> t.field("_index").value("official_doc")));
			docScope.should(q -> q.bool(bb -> bb
					.must(m -> m.term(t -> t.field("_index").value("oa_publish_content")))
					.must(m -> m.term(t -> t.field("types.keyword").value("4")))
			));
			docScope.minimumShouldMatch("1");
			// 角色权限：a 超管/公司领导全看；b 分公司领导/文书看本公司(tenantId)；c 部门主任看本部门(deptId)；
			// 都无→只看共享给自己的(userIds)
			if(rolesArr!=null && rolesArr.length > 0){
				for(String role : rolesArr){
					//超管  领导角色看所有
					if(Arrays.stream(leaderRoles).anyMatch(role::equalsIgnoreCase)){
						a = true;
						break;
					}
					//分公司领导、分公司文书：看本公司
					if(Arrays.stream(fenGsRoles).anyMatch(role::equalsIgnoreCase)){
						b = true;
						break;
					}
					//本部部门主任+分公司部门主任：看本部门
					if(Arrays.stream(benbuRoles).anyMatch(role::equalsIgnoreCase)){
						c = true;
						break;
					}
				}
			}
			BoolQuery.Builder docPerm = new BoolQuery.Builder();
			docPerm.must(Query.of(q -> q.bool(docScope.build())));
			if(b){
				docPerm.must(q2 -> q2.match(m -> m.field("tenantId").query(tenantId)));
			}
			if(c){
				docPerm.must(q2 -> q2.match(m -> m.field("deptId").query(deptId)));
			}
			if(!a && !b && !c){
				List<FieldValue> userIds = Arrays.asList(FieldValue.of(Long.parseLong(userId)));
				docPerm.must(Query.of(q -> q
						.terms(t -> t
								.field("userIds")
								.terms(ts -> ts.value(userIds)
								)
						)));
			}
			vis.should(Query.of(q -> q.bool(docPerm.build())));
			vis.minimumShouldMatch("1");
			boolBuilder.must(Query.of(q -> q.bool(vis.build())));
			// docType 二级过滤（公文页签才用，全部页签前端传空）
			if (StringUtils.isNotEmpty(docType) && !"null".equals(docType)){
				boolBuilder.must(q->q.term(t->t.field("docType").value(docType)));
			}
		}
		// aiExpand=false 时进入精确模式：不做分词 AND 兜底，只按完整关键词短语/子串匹配
		boolean exactMode = !"true".equalsIgnoreCase(andConditions.get("requestAiExpand"));
		// AI扩展检索词，逗号分隔
		List<String> expandWords = new ArrayList<>();
		String expandStr = andConditions.get("expandWords");
		if (StringUtils.isNotEmpty(expandStr)) {
			for (String ew : expandStr.split(",")) {
				if (ew != null && !ew.trim().isEmpty()) expandWords.add(ew.trim());
			}
		}
		// 语义召回的id，Milvus返回，逗号分隔；精确模式下忽略语义召回
		List<Long> semanticIds = new ArrayList<>();
		String semStr = andConditions.get("semanticIds");
		if (!exactMode && StringUtils.isNotEmpty(semStr)) {
			for (String sid : semStr.split(",")) {
				try { semanticIds.add(Long.parseLong(sid.trim())); } catch (Exception ignore) {}
			}
		}
		String finalKeyword = keyword;
		// 时间排序+扩词：给"原词精确命中"的文档置 _score=1、其余置 0，
		// 实现"精确结果在前、相关结果在后，各自按时间倒序"，避免扩展词命中把原词结果挤出列表
		// 暂时关闭两层 function_score，避免影响命中数
		boolean twoStreamTime = false;
		if (method != null && !method.trim().isEmpty()) {
			// 原词加扩展词，按type选择检索字段
			Query kwQuery;
			String[] phraseFields;
			// 正文统一走 searchText（copy_to 汇聚 fullContent/content，三个索引字段一致），
			// 不再按索引分字段重复查询，子句数减半
			if (StringUtils.isEmpty(type)) {
				phraseFields = new String[]{"searchText"};
			} else if (type.equals("99")) {
				// 公文同时跨 official_doc（fullContent）和 oa_publish_content（content）两个索引，统一走 searchText
				phraseFields = new String[]{"searchText"};
			} else {
				phraseFields = new String[]{"searchText"};
			}
			if (exactMode) {
				// 精确模式：短语/子串匹配，标题用 wildcard 兜底，正文用 match_phrase
				if ("2".equals(method)) {
					kwQuery = Query.of(q -> q.wildcard(w -> w.field("title.keyword").value("*" + finalKeyword + "*")));
				} else {
					if (StringUtils.isEmpty(type)) {
						kwQuery = Query.of(q -> q.bool(bb -> bb
								.should(Query.of(sq -> sq.wildcard(w -> w.field("title.keyword").value("*" + finalKeyword + "*").boost(10.0f))))
								.should(Query.of(sq -> sq.matchPhrase(mp -> mp.field("fullContent").query(finalKeyword))))
								.should(Query.of(sq -> sq.matchPhrase(mp -> mp.field("content").query(finalKeyword))))
								.minimumShouldMatch("1")));
					} else if ("99".equals(type)) {
						kwQuery = Query.of(q -> q.bool(bb -> bb
								.should(Query.of(sq -> sq.wildcard(w -> w.field("title.keyword").value("*" + finalKeyword + "*").boost(10.0f))))
								.should(Query.of(sq -> sq.matchPhrase(mp -> mp.field("fullContent").query(finalKeyword))))
								.minimumShouldMatch("1")));
					} else {
						kwQuery = Query.of(q -> q.bool(bb -> bb
								.should(Query.of(sq -> sq.wildcard(w -> w.field("title.keyword").value("*" + finalKeyword + "*").boost(10.0f))))
								.should(Query.of(sq -> sq.matchPhrase(mp -> mp.field("content").query(finalKeyword))))
								.minimumShouldMatch("1")));
					}
				}
			} else {
				kwQuery = buildKeywordOrQuery(finalKeyword, expandWords, andConditions.get("termGroups"), method, phraseFields);
				if (twoStreamTime && kwQuery != null) {
					// 精确命中原词的文档 _score 置 1，其余置 0（boostMode=Replace 屏蔽内部权重/BM25）
					Query exactQ = buildExactKeywordQuery(finalKeyword, method, phraseFields);
					if (exactQ != null) {
						final Query allQ = kwQuery, fq = exactQ;
						kwQuery = Query.of(q -> q.functionScore(fs -> fs
								.query(allQ)
								.functions(FunctionScore.of(f -> f.filter(fq).weight(1.0)))
								.scoreMode(FunctionScoreMode.Sum)
								.boostMode(FunctionBoostMode.Replace)));
					}
				}
			}
			// 语义召回id子句：低权重(0.5)，保证语义命中排在所有字面/扩词命中之后，
			// 仅当字面结果不足时才在结果列表中显露（兜底通道，不混排）
			Query semQuery = null;
			if (!semanticIds.isEmpty()) {
				List<FieldValue> idVals = new ArrayList<>();
				for (Long sid : semanticIds) idVals.add(FieldValue.of(sid.longValue()));
				semQuery = Query.of(q -> q.terms(t -> t.field("id").terms(ts -> ts.value(idVals)).boost(0.5f)));
			}
			final Query kq = kwQuery, sq = semQuery;
			if (kq != null && sq != null) {
				boolBuilder.must(Query.of(q -> q.bool(bb -> bb.should(kq).should(sq).minimumShouldMatch("1"))));
			} else if (kq != null) {
				boolBuilder.must(kq);
			} else if (sq != null) {
				boolBuilder.must(sq);
			}
		}
		
		Map<String, HighlightField> highLight = new HashMap<String, HighlightField>();
		List<String> hfields = new ArrayList<>();
		hfields.add("title");
		hfields.add("content");
		if (null != hfields && hfields.size() > 0) {
			highLight = this.buildHighLight(hfields);
		}
		// 添加其他AND条件
//		andConditions.forEach((field, value) ->
//				boolBuilder.must(Query.of(q -> q
//						.term(t -> t
//								.field(field)
//								.value(value)
//						)
//				))
//		);
		int pageNum = 0;
		int pageSize;
		if (null != pages) {
			if (pages.length > 1) {
				pageNum = pages[0];
				pageSize = pages[1];
				pageNum = ((pageNum - 1) * pageSize);
			} else {
				pageSize = 0;
			}
		} else {
			pageSize = 0;
		}
		// 执行查询
		int finalPageNum = pageNum;
		Map<String, HighlightField> finalHighLight = highLight;
		BoolQuery query = boolBuilder.build();
			// 有关键词按相关度排序，无关键词按时间；时间排序+扩词时 _score 是精确命中标记(1/0)，
			// 同样作为第一排序键保证精确结果在前，publishTime 作为第二键保证各自按时间倒序
			List<SortOptions> sortOptions = new ArrayList<>();
			if ((!"time".equals(andConditions.get("sort")) || twoStreamTime) && finalKeyword != null && !finalKeyword.trim().isEmpty()) {
				sortOptions.add(SortOptions.of(so -> so.score(sc -> sc.order(SortOrder.Desc))));
			}
			sortOptions.add(SortOptions.of(so -> so.field(f -> f.field("publishTime").order(SortOrder.Desc))));
			sortOptions.add(SortOptions.of(so -> so.field(f -> f.field("id").order(SortOrder.Desc))));
		System.out.println("[DEBUG-ES] exactMode=" + exactMode + ", method=" + method + ", type=" + type + ", keyword=" + finalKeyword);
		System.out.println("[DEBUG-ES] queryDSL=" + query._toQuery().toString());
		System.out.println(query.toString());
		// 支持逗号分隔的多索引查询
		List<String> indexNames = Arrays.asList(indexName.split(","));
		final Query deptFilterQueryFinal = deptFilterQuery;
		try {
			long searchStart = System.currentTimeMillis();
			SearchResponse<Map> searchResponse = client.search(s -> {
						s.index(indexNames)
							.from(finalPageNum)
							.size(pageSize)
							.trackTotalHits(t -> t.count(100))
							// fullContentChunks/content_chunks 在结果处理中从未使用，且占 _source 体积约 1/5，排除以减小传输
							.source(src -> src.filter(sf -> sf.excludes("fullContentChunks", "content_chunks")))
							.highlight(f -> f.fields(finalHighLight))
								.sort(sortOptions)
								.collapse(cl -> cl.field("title.keyword"))
								.aggregations("deptAgg", ag -> ag.terms(t -> t.field("publishDeptName.keyword").size(20)))
								.aggregations("deptAgg2", ag -> ag.terms(t -> t.field("draftUnit.keyword").size(20)))
								.query(q -> q.bool(query));
						if (deptFilterQueryFinal != null) {
							// 部门筛选在 post_filter：聚合不看它，命中与总数看它
							s.postFilter(deptFilterQueryFinal);
							// 总数也要反映部门筛选：cardinality 包在 filter agg 里重新施加部门条件
							s.aggregations("uniqueCount", ag -> ag.filter(deptFilterQueryFinal)
									.aggregations("c", cc -> cc.cardinality(cd -> cd.field("title.keyword"))));
						} else {
							s.aggregations("uniqueCount", ag -> ag.cardinality(cd -> cd.field("title.keyword")));
						}
						return s;
					},
					Map.class
			);
			System.out.println("[DEBUG-ES] clientSearch耗时=" + (System.currentTimeMillis() - searchStart) + "ms");
			System.out.println("[DEBUG-ES] responseTotal=" + (searchResponse.hits() != null && searchResponse.hits().total() != null ? searchResponse.hits().total().value() : 0));
			return searchResponse;
		}catch(ElasticsearchException e){
			// 打印完整的错误信息
			log.error("Elasticsearch error: " + e.getMessage());
			log.error("Error reason: " + e.error().reason());

			// 如果有更多详情
			if (e.error().rootCause() != null && !e.error().rootCause().isEmpty()) {
				for (ErrorCause cause : e.error().rootCause()) {
					log.error("Root cause: " + cause.reason());
				}
			}
			throw e;
		}
	}

	@SuppressWarnings({ "unchecked" })
	public Map<String, Object> queryEs(Map<String,String> map) throws Exception {
		Map<String, Object> result = new HashMap<String, Object>();
		String indexName = map.get("indexName");

		if (null == indexName || "".equals(indexName)) {
			return result;
		}

		String finalIndexName = indexName;

		//分页信息
		int pageNum = Integer.parseInt(map.get("pageNum"));
		String pageSizeMaps = map.get("pageSize");
		if(StringUtils.isEmpty(pageSizeMaps)){
			pageSizeMaps = "10";
		}
		int pageSize = Integer.parseInt(pageSizeMaps);

		//
		List<String> values = new ArrayList<>();

		System.out.println("es_querys====="+map.get("indexName"));
		long cqStart = System.currentTimeMillis();
		SearchResponse<Map> response = this.combinedQuery(finalIndexName, "publish_dept_id", values, map,pageNum,pageSize);
		System.out.println("[DEBUG-ES] combinedQuery耗时=" + (System.currentTimeMillis() - cqStart) + "ms");
		// 1新闻，2公文
		String type = map.get("type");
		//	1全文，2标题
		String method = map.get("method");

		// 扩展词（本地同义词/模板/AI扩词，逗号分隔），用于片段提取和可见命中过滤
		List<String> expandTerms = new ArrayList<>();
		String expandStr = map.get("expandWords");
		if (StringUtils.isNotEmpty(expandStr)) {
			for (String ew : expandStr.split(",")) {
				if (ew != null && !ew.trim().isEmpty()) expandTerms.add(ew.trim());
			}
		}
		long srStart = System.currentTimeMillis();
		Map<String, Object> srResult = searchResponse(response, map.get("keyword"), type, method, expandTerms);
		System.out.println("[DEBUG-ES] searchResponse处理耗时=" + (System.currentTimeMillis() - srStart) + "ms");
		return srResult;
	}

	/**
	 *	indexName 字段索引
	 *  fullContent  原文
	 *  chunks 切割后文本
	 *  T 对象
	 * */
	@Override
	public void indexDocument(String indexName, String fullContent, List<String> chunks,Map<String, Object> jsonMap) {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		String createTime = sdf.format(new Date());
		jsonMap.put("create_time", createTime);
		jsonMap.put("index_name", indexName);
		jsonMap.put("full_content", fullContent);

		List<Map<String, Object>> chunkList = new ArrayList<>();
		for (int i = 0; i < chunks.size(); i++) {
			Map<String, Object> chunkMap = new HashMap<>();
			chunkMap.put("text", chunks.get(i));
			chunkMap.put("chunk_number", i);
			chunkList.add(chunkMap);
		}
		jsonMap.put("content_chunks", chunkList);
		IndexRequest<T> request = IndexRequest.of(r -> r.index("official_doc").id(String.valueOf(SnowFlake.getId())).document((T) jsonMap));
		try {
			client.index(request);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 公文拼参
	 * 分段查询 后续该文传入字段、传入索引名
	 *
	 * */
	@Override
	public Map<String, Object> buildChunkFuzzyQuery(Map<String,String> map) throws Exception {
		//公文索引t_doc_received
		map.put("indexName","official_doc");

		return this.queryEs(map);

//		//	1全文，2标题
//		String method = map.get("method");
//		String keyword = map.get("keyword");
//		//时间
//		String startTime = map.get("startTime");
//		String endTime = map.get("endTime");
//		Query query = Query.of(q -> q
//						.bool(b -> {
//							// 必须条件
//							b.must(m -> m
//									.term(t -> t
//											.field("types")
//											.value(keyword)
//									)
//							);
//
//
//							//发布时间范围查询
//							if(startTime != null && !startTime.trim().isEmpty() && endTime != null && !endTime.trim().isEmpty()) {
//								b.must(q4 -> q4.range(r -> r.field("publish_time").from(startTime).to(endTime)));
//							}
//
//							// 动态添加第一个OR条件（如果启用）
//							if (method != null && !method.trim().isEmpty()) {
//								if (method.equals("1")) {
//									b.must(bm -> bm.match(m -> m
//													.field("content_chunks.text")
//													.query(keyword)
//													.fuzziness("AUTO")
//											)
//									);
//								}else{
//									b.must(bm -> bm.match(m -> m
//													.field("title")
//													.query(keyword)
//													.fuzziness("AUTO")
//											)
//									);
//								}
//							}
//
//							// 动态添加第二个OR条件（如果启用）
////					if (enableCondition2) {
////						b.should(s -> s
////								.match(m -> m
////										.field("optional_field2")
////										.query(condition2Value)
////								)
////						);
////					}
//
//							return b;
//						})
//		);


//		Query finalQuery = query;
//		SearchRequest request = SearchRequest.of(s -> s
//				.index("t_doc_received")
//				.query(finalQuery)
//		);
//		SearchResponse<Map> response = client.search(request, Map.class);

//		return searchResponse(response,keyword,"0");
	}

	/**
	 * response es查询数据
	 * searchTerm 搜索关键字
	 * type 是否分段查询内容 0是 1不是
	 * */
	private Map<String, Object> searchResponse(SearchResponse<Map> response, String searchTerm, String type, String method, List<String> expandTerms){
		Map<String, Object> result = new HashMap<String, Object>();
		List<Map> mapList = new ArrayList<>();
		List<Hit<Map>> hits = response.hits().hits();
		// 收集 ES 返回的 matched_queries，反推出真实命中的扩展词（用于前端只展示有命中结果的扩词）
		Set<String> matchedExpandTerms = new LinkedHashSet<>();
		for (Hit<Map> hit : hits) {
			List<String> mq = hit.matchedQueries();
			boolean originMatched = false;
			Set<String> hitExpandTerms = new LinkedHashSet<>();
			if (mq != null && !mq.isEmpty()) {
				for (String name : mq) {
					if (name == null) continue;
					if (ORIGIN_KEYWORD_NAME.equals(name)) {
						originMatched = true;
					} else if (expandTerms != null && expandTerms.contains(name)) {
						hitExpandTerms.add(name);
						matchedExpandTerms.add(name);
					}
				}
			}

			boolean hasContent = false;
			boolean hasTitle = false;
			StringBuffer stringBuffer = new StringBuffer();
			Map map = new HashMap();
			Map source = hit.source();
			// 兜底：ES matched_queries 不可靠时，按原文是否包含关键词/扩展词判断
			if (StringUtils.isNotEmpty(searchTerm)) {
				String rawText = String.valueOf(source.get("title")) + " " +
						String.valueOf(source.get("content")) + " " +
						String.valueOf(source.get("fullContent"));
				String cleanText = rawText.replaceAll("<[^>]+>", "");
				if (!originMatched) {
					// 多词组合（逗号/空格分隔）：整串不会出现在原文里，要求每个词都出现才算原词命中
					List<String> parts = splitMultiTerms(searchTerm);
					if (parts.size() > 1) {
						originMatched = true;
						for (String p : parts) {
							if (!cleanText.contains(p)) { originMatched = false; break; }
						}
					} else {
						originMatched = cleanText.contains(searchTerm);
					}
				}
				if (expandTerms != null) {
					for (String term : expandTerms) {
						if (StringUtils.isNotEmpty(term) && cleanText.contains(term)) {
							hitExpandTerms.add(term);
							matchedExpandTerms.add(term);
						}
					}
				}
			}
			boolean titleMatched = false;
			if (StringUtils.isNotEmpty(searchTerm)) {
				String titleText = String.valueOf(source.get("title")).replaceAll("<[^>]+>", "");
				titleMatched = titleText.contains(searchTerm);
			}
			map.put("titleMatched", titleMatched);
			map.put("originMatched", originMatched);
			map.put("expandMatched", !hitExpandTerms.isEmpty());
			// 纯语义命中标记：原词和扩展词都没命中、仅靠语义召回 id 子句召回的结果，前端显示"语义相关"徽标
			map.put("semanticOnly", !originMatched && hitExpandTerms.isEmpty());
			// hit.index() 返回真实索引名；使用别名检索时公文会是 official_doc_v2 等带后缀的名字，
			// 用 startsWith 兼容别名+版本后缀，避免公文被误判为非公文而读空 content 字段导致结果被过滤
			// 注意：必须每条命中独立判断、就地用局部变量——不能改写入参 type，
			// 否则一条公文命中后，其后的新闻命中都会被当成公文、读不到 fullContent 而被误过滤
			String hitDocType;
			if(hit.index() != null && hit.index().startsWith("official_doc")){
				hitDocType = "0";
			}else if(StringUtils.isEmpty(type)){
				hitDocType = "1";
			}else{
				hitDocType = type;
			}
			// 公文
			if("0".equals(hitDocType)){
				String text = String.valueOf(source.get("fullContent"));
				text = text.equals("null")?"":text;
				if(StringUtils.isNotEmpty(text)){
					hasContent = true;

				}
				stringBuffer.append(text);
			}else{
				String content = (String) source.get("content");
				content = content == null || content.equals("null")?"":content;
				if(StringUtils.isNotEmpty(content)){
					hasContent = true;
				}
				stringBuffer.append(content);
			}

			String fullMap = String.valueOf(source.get("fullMap"));
			// 超大文本截断，避免 extractParagraphsWithKeyword 处理几 MB 的漏洞通报/附件清单导致接口超时
			String fullText = stringBuffer.toString();
			if (fullText.length() > 200 * 1024) {
				// 超大文本截断，避免 extractParagraphsWithKeyword 处理几 MB 的漏洞通报/附件清单导致接口超时；
				// 但若首个命中词（原词/扩展词）在截断范围之外，片段里就看不到高亮词（看起来像没匹配）——
				// 此时把窗口挪到命中点附近（前 50KB 上下文 + 后 150KB），保证锚点落在窗口内
				int hitPos = -1;
				if (StringUtils.isNotEmpty(searchTerm)) hitPos = fullText.indexOf(searchTerm.replace(" ", ""));
				if (hitPos < 0 && expandTerms != null) {
					for (String t : expandTerms) {
						if (StringUtils.isEmpty(t)) continue;
						int p = fullText.indexOf(t.replace(" ", ""));
						if (p >= 0 && (hitPos < 0 || p < hitPos)) hitPos = p;
					}
				}
				if (hitPos > 150 * 1024) {
					int from = Math.max(0, hitPos - 50 * 1024);
					fullText = fullText.substring(from, Math.min(fullText.length(), from + 200 * 1024));
				} else {
					fullText = fullText.substring(0, 200 * 1024);
				}
			}
			//以句号为切割，关键字前后五行；原词找不到时按扩展词找
			List<Map<String, String>> contextFileList = extractParagraphsWithKeyword(fullMap, fullText, searchTerm, expandTerms, 1);
			// 如果没有找到高亮关键词 使用全文返回
			if (CollUtil.isNotEmpty(contextFileList)){
				map.put("content", JSONObject.toJSONString(contextFileList));
			} else {
				String collect = stringBuffer.toString();
				try {
					List<Map<String, String>> maps = JSONObject.parseObject(collect, new TypeReference<List<Map<String, String>>>() {
					});
					collect = maps.stream().map(re -> re.values().stream().collect(Collectors.toList()).get(0))
							.filter(str -> StringUtils.isNotEmpty(str))
							.map(html -> Jsoup.parse(html).text())
							.collect(Collectors.joining(";"));
				} catch (Exception e) {
					log.info("分析失败!");
					collect = Jsoup.parse(collect).text();
				}
				// 对扩展词也做高亮：黄色字体，无背景
				if (expandTerms != null) {
					for (String term : expandTerms) {
						if (StringUtils.isNotEmpty(term)) {
							collect = collect.replace(term, "<span style=\"color:#f0ad4e;font-weight:bold\">" + term + "</span>");
						}
					}
				}
				// 重新构建
				map.put("content", JSONObject.toJSONString(List.of(Map.of("title","","content",collect,"type","content","url","","fileId",""))));
			}
			//标题，文件名
			String title = (String) source.get("title");
			if(StringUtils.isNotEmpty(title)){
				if(StringUtils.isNotEmpty(searchTerm)){
					title = title.replaceAll(searchTerm, "<span style=\"color:red;font-weight:bold\">" + searchTerm + "</span>");
				}
				// 扩展词也做高亮：黄色字体，无背景
				if (expandTerms != null) {
					for (String term : expandTerms) {
						if (StringUtils.isNotEmpty(term)) {
							title = title.replace(term, "<span style=\"color:#f0ad4e;font-weight:bold\">" + term + "</span>");
						}
					}
				}
				hasTitle = true;
			}
			//	1全文，2标题
			if(method.equals("1")){
				if(!hasContent){
					continue;
				}
			}else {
				if(!hasTitle){
					continue;
				}
			}
			map.put("title", title);
			//公文文件上传地址，给前端返回显示
			Object filePath = source.get("file_path");
			map.put("filePath", filePath);
			//时间
			String createTime = StringUtils.isNotNull(source.get("createTime"))?source.get("createTime").toString():(StringUtils.isNotNull(source.get("publishTime"))?source.get("publishTime").toString():null);

			map.put("createTime", createTime);
			map.put("esIndex", hit.index());
			map.put("createUser",source.get("create_user"));
			map.put("tenantId",source.get("tenant_id"));
			map.put("publishDeptId",source.get("publish_dept_id"));
			// 公文没有 publish_dept_name 时，用 draftUnit 作为部门名
			Object pubDeptName = source.get("publish_dept_name");
			if (pubDeptName == null || StringUtils.isEmpty(String.valueOf(pubDeptName))) {
				pubDeptName = source.get("draftUnit");
			}
			map.put("publishDeptName", pubDeptName);
			map.put("draftUnit",source.get("draftUnit"));
			map.put("publishUserId",source.get("publish_user_id"));
			map.put("publishUser",source.get("publish_user"));
			map.put("types",source.get("types"));
			map.put("id",source.get("id").toString());
			map.put("fileId",source.get("file_id"));
			map.put("taskId",source.get("taskId"));
			map.put("receivedUnit",source.get("received_unit"));
			map.put("fileName",source.get("file_name"));
			//公文检索添加
			// hisTasks 瘦身：原始流程任务数据每条几 KB，前端只用 userId+taskId 两个字段，
			// 裁剪后 20 条结果省约 90KB 传输；解析失败时保留原值兜底
			Object hisTasksRaw = source.get("hisTasks");
			map.put("hisTasks", slimHisTasks(hisTasksRaw));
			map.put("flwInstId",source.get("flwInstId"));
			map.put("userIds",source.get("userIds"));
			map.put("procState",source.get("procState"));
			map.put("deptId",source.get("deptId"));
			map.put("tenantId",source.get("tenantId"));
			map.put("publishTime",createTime);
			if(map.get("content")==null){
				continue;
			}
			mapList.add(map);
		}
		long __uniqTotal = response.hits().total().value();
		try {
			if (response.aggregations() != null && response.aggregations().get("uniqueCount") != null) {
				co.elastic.clients.elasticsearch._types.aggregations.Aggregate uc = response.aggregations().get("uniqueCount");
				if (uc.isFilter()) {
					// 部门筛选生效时 uniqueCount 是 filter agg，真实基数在子聚合 c 里
					co.elastic.clients.elasticsearch._types.aggregations.Aggregate c = uc.filter().aggregations().get("c");
					if (c != null && c.isCardinality()) __uniqTotal = c.cardinality().value();
				} else if (uc.isCardinality()) {
					__uniqTotal = uc.cardinality().value();
				}
			}
		} catch (Exception __ignore) {}
		// 不再本地重排：标题命中/扩展词命中已收敛，直接按 ES 返回的时间/相关度顺序返回
		// 调试日志：观察命中分布
		long titleCount = mapList.stream().filter(m -> Boolean.TRUE.equals(m.get("titleMatched"))).count();
		long originCount = mapList.stream().filter(m -> Boolean.TRUE.equals(m.get("originMatched"))).count();
		long expandCount = mapList.stream().filter(m -> Boolean.TRUE.equals(m.get("expandMatched"))).count();
		System.out.println("[DEBUG-ES-SORT] hits=" + hits.size() + ", mapList=" + mapList.size()
				+ ", titleMatched=" + titleCount + ", originMatched=" + originCount
				+ ", expandMatched=" + expandCount + ", matchedExpand=" + matchedExpandTerms);
		// total 用真实唯一命中数（按 title 去重后的 cardinality），不要用 mapList.size()——
		// 那只是候选池大小（AI扩词固定拉 20 条），会让前端误以为总共只有 20 个结果
		result.put("total", __uniqTotal);
		result.put("data", mapList);
		result.put("matchedExpandTerms", new ArrayList<>(matchedExpandTerms));
		// 部门聚合，用于高级筛选：oa 系用 publishDeptName，公文系用 draftUnit，合并去重
		try {
			Set<String> deptSet = new LinkedHashSet<>();
			if (response.aggregations() != null) {
				if (response.aggregations().get("deptAgg") != null) {
					response.aggregations().get("deptAgg").sterms().buckets().array().forEach(b -> deptSet.add(b.key().stringValue()));
				}
				if (response.aggregations().get("deptAgg2") != null) {
					response.aggregations().get("deptAgg2").sterms().buckets().array().forEach(b -> deptSet.add(b.key().stringValue()));
				}
			}
			// 聚合为空时，从结果集直接提取
			if (deptSet.isEmpty()) {
				for (Map map : mapList) {
					Object dept = map.get("publishDeptName");
					if (dept == null) dept = map.get("draftUnit");
					if (dept != null && StringUtils.isNotEmpty(String.valueOf(dept))) {
						deptSet.add(String.valueOf(dept));
					}
				}
			}
			result.put("deptList", new ArrayList<>(deptSet));
		} catch (Exception __ignore) {}

		return result;
	}

	/**
	 * hisTasks 裁剪：把流程任务 JSON 数组精简为只含 userId/taskId 的数组字符串。
	 * 前端仅使用这两个字段；解析失败返回原值，保证兼容。
	 */
	private static Object slimHisTasks(Object hisTasksRaw) {
		if (hisTasksRaw == null) return null;
		String raw = String.valueOf(hisTasksRaw);
		if (raw.isEmpty() || "null".equals(raw)) return hisTasksRaw;
		try {
			List<Map> tasks = JSONObject.parseObject(raw, new TypeReference<List<Map>>() {});
			List<Map<String, Object>> slim = new ArrayList<>();
			for (Map t : tasks) {
				Map<String, Object> m = new HashMap<>();
				m.put("userId", t.get("userId"));
				m.put("taskId", t.get("taskId"));
				slim.add(m);
			}
			return JSONObject.toJSONString(slim);
		} catch (Exception e) {
			return hisTasksRaw;
		}
	}

	/**
	 * 可见命中判定：返回给前端的标题+片段文本里，必须包含完整连续的原词
	 * （去空格后），或包含完整连续的任一扩展词，才视为可见命中并保留该条结果。
	 */
	private static boolean hasVisibleMatch(String title, String contentJson, String keyword, List<String> expandTerms) {
		String kw = keyword == null ? "" : keyword.trim();
		if (kw.isEmpty() && (expandTerms == null || expandTerms.isEmpty())) {
			return true; // 无关键词检索不过滤
		}
		String visible = ((title == null ? "" : title) + " " + (contentJson == null ? "" : contentJson))
				.replaceAll("<[^>]+>", "").replaceAll("[\\s　]+", "");
		// 原词：必须完整连续出现（去空格后）
		if (!kw.isEmpty()) {
			String k = kw.replaceAll("[\\s　]+", "");
			if (!k.isEmpty() && visible.contains(k)) return true;
		}
		// 扩展词：任一完整连续出现
		if (expandTerms != null) {
			for (String w : expandTerms) {
				if (w != null && !w.trim().isEmpty()) {
					String t = w.trim().replaceAll("[\\s　]+", "");
					if (!t.isEmpty() && visible.contains(t)) return true;
				}
			}
		}
		return false;
	}

	/**
	 * 提取包含关键词的段落（保留上下文）；原词找不到时按扩展词顺序找第一个可见命中词
	 * @param fullText 完整文本
	 * @param keyword 原关键词
	 * @param expandTerms 扩展词列表（本地同义词/模板/AI扩词），无则传空列表
	 * @param contextLines 保留前后段落数（可选）
	 * @return 包含关键词的段落列表
	 */
	public static List<Map<String,String>> extractParagraphsWithKeyword(String fullMap, String fullText, String keyword, List<String> expandTerms, int... contextLines) {
		if (StringUtils.isEmpty(fullText)){
			return null;
		}
		List<Map<String, String>> result = new ArrayList<>();
		int reserved = contextLines.length > 0 ? contextLines[0] : 0; // 默认不保留上下文

		List<Map<String, String>> fullMaps = new ArrayList<>();
		try {
			fullMaps = JSONObject.parseObject(fullMap, new TypeReference<List<Map<String, String>>>() {
			});
		} catch (Exception e) {
			log.error("错误");
		}
		try {
			List<Map<String,String>> resultList = JSONObject.parseObject(fullText, new TypeReference<List<Map<String,String>>>() {
			});
			for (Map<String, String> resultMap : resultList) {
				for (String key : resultMap.keySet()) {
					Map<String, String> stringStringMap = fullMaps.stream().filter(item -> key.equals(item.get("fileId"))).findFirst().orElse(new HashMap<>());

					String title = stringStringMap.getOrDefault("fileName","");
					String type = stringStringMap.getOrDefault("fileType","");
					String fileId = stringStringMap.getOrDefault("fileId","");
					String url = stringStringMap.getOrDefault("url","");
					List<String> list = resultContentHandle(resultMap.get(key), keyword, expandTerms, reserved);
					contentBuild(result, list, title, type,fileId, url);
				}
			}


		} catch (Exception e) {
			//无法解析JSON
			List<String> list = resultContentHandle(fullText, keyword, expandTerms, reserved);
			if (CollUtil.isEmpty(list)) {
				// 没命中关键词，截正文开头当摘要
				String plain = Jsoup.parse(fullText).text().replaceAll("\\s", "");
				// fullContent 为附件 JSON 数组（[{"fileId":"正文..."}]）时，超 200KB 截断后 JSON 失效，
				// 兜底摘要会漏出 [{"fileId":" 前缀——剥掉这层结构，只留正文文本
				if (plain.startsWith("[{\"")) {
					int sep = plain.indexOf("\":\"");
					if (sep > 0 && sep < 40) {
						plain = plain.substring(sep + 3);
					}
				}
				if (StringUtils.isNotEmpty(plain)) {
					list = new ArrayList<>();
					list.add(plain.length() > 200 ? plain.substring(0, 200) : plain);
				}
			}
			contentBuild(result, list, "", "content", "","");
		}
		return result;
	}

	/**
	 *
	 * @param result 结果集引用 <--
	 * @param contents 命中的关键词段落
	 * @param title 附件标题
	 * @param type 文档类型 content:文章内容, document:正文文档附件,attribute:附件
	 * @param fileId 附件ID
	 * @param url 附件地址
	 */
	private static void contentBuild(List<Map<String,String>> result ,List<String> contents , String title,String type,String fileId,String url){
		if(CollUtil.isNotEmpty(contents)){
			result.add(Map.of("title",title,"content",contents.get(0),"type",type,"url",url,"fileId",fileId));
		}
	}


	private static List<String> resultContentHandle(String fullText, String keyword, List<String> expandTerms, int reserved){
		List<String> result = new ArrayList<>();
		if (StringUtils.isEmpty(fullText)){
			return result;
		}
		fullText = Jsoup.parse(fullText).text();
		fullText = fullText.replaceAll(" ", "");
		// 锚点词列表：原词在前，扩展词按传入顺序补充（去空格、去重）
		List<String> terms = new ArrayList<>();
		if (StringUtils.isNotEmpty(keyword)) {
			String k = keyword.replaceAll(" ", "");
			if (!k.isEmpty()) terms.add(k);
			// 多词组合：整串（含分隔符）不会出现在正文里，把拆分后的单词也加入锚点候选
			for (String t : splitMultiTerms(k)) {
				if (!terms.contains(t)) terms.add(t);
			}
		}
		if (expandTerms != null) {
			for (String w : expandTerms) {
				if (w == null) continue;
				String t = w.replaceAll(" ", "").trim();
				if (!t.isEmpty() && !terms.contains(t)) terms.add(t);
			}
		}
		if (terms.isEmpty()) {
			return result;
		}
		// 原词优先；原词不在文本中时，按扩展词顺序找第一个可见命中词
		String anchor = null;
		for (String t : terms) {
			if (fullText.contains(t)) { anchor = t; break; }
		}
		if (anchor == null) {
			return result;
		}
		// 构建包含上下文的段落
		String paragraphsString = safeSubstring(fullText, fullText.indexOf(anchor),300);
		// 原词标红，扩展词标黄（黄色字体，无背景）
		String originalKw = terms.isEmpty() ? null : terms.get(0);
		// 多词组合拆分出的各词（燃气、项目经理）是用户自己输入的原词成分，按原词标红，不当扩展词标黄
		java.util.Set<String> originSet = new java.util.HashSet<>();
		if (originalKw != null) {
			originSet.add(originalKw);
			originSet.addAll(splitMultiTerms(originalKw));
		}
		for (String t : terms) {
			if (paragraphsString.contains(t)) {
				boolean isOriginal = originSet.contains(t);
				String span = isOriginal
					? "<span style=\"color:red;font-weight:bold\">" + t + "</span>"
					: "<span style=\"color:#f0ad4e;font-weight:bold\">" + t + "</span>";
				paragraphsString = paragraphsString.replace(t, span);
			}
		}
		result.add(paragraphsString);
		return result;
	}

	public static String safeSubstring(String input,int firstIndex, int maxLength) {
		if (input == null) return null;
		if (input.length() <= maxLength) return input;
		int offset  = 0;
		int endIndex = input.length();
		if (firstIndex>maxLength/2){
			int tempOffset;
			// 关键词前部分
			String frontStr = input.substring(0, firstIndex);
			String[] split = frontStr.split("[,;，；]");
			// 按照标点符号进行分割, 尽可能保留两个以上段落
			if (split.length>0){
				if (split.length>=2){
					tempOffset = input.indexOf(split[split.length-2]);
				} else {
					tempOffset = input.indexOf(split[0]);
				}
			} else {
				tempOffset = firstIndex - maxLength/2;
			}
			offset = tempOffset;
			// 锚点前最多保留 120 字符：前端片段只显示两行，
			// 锚点太靠后会被 -webkit-line-clamp 截掉，看起来像"没匹配也显示"
			if (firstIndex - offset > 120) {
				offset = firstIndex - 120;
			}
			if ((maxLength+tempOffset+1) <= input.length()){
				// 切割偏移后依旧满足最大长度
				endIndex = input.offsetByCodePoints(offset, maxLength);
			}
		}
		return input.substring(offset, endIndex);

	}


	// 更新索引映射，添加id字段
	public void updateIndexMapping() {
		try {
			PutMappingRequest request = PutMappingRequest.of(p -> p
					.index("*")
					.properties("id", prop -> prop.long_(l -> l))  // 根据你的id类型调整
			);

			client.indices().putMapping(request);
			log.info("索引映射更新成功");
		} catch (Exception e) {
			log.error("更新映射失败", e);
		}
	}
}
