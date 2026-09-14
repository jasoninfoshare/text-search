package com.example.search.es.service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import co.elastic.clients.elasticsearch.core.search.Hit;
import com.example.search.es.entity.SearchBean;

public interface ElasticService<T> { 
	
	
	/**
	 *  创建查询添加
	 * @param map
	 * @param t
	 * @return
	 * @throws Exception
	 */
	public List<SearchBean> toSearchParams(T t) throws Exception;
	/**
	 *  判断索引是否存在
	 * @param indexName
	 * @return
	 * @throws Exception
	 */
	public Boolean isExists(String indexName) throws Exception;
	public Boolean isExistsDoc(String indexName,String docId) throws Exception;
	
	/**
	 *  创建索引
	 * @param indexName
	 * @param propertyMap
	 * @throws Exception
	 */
	public void createIndex(String indexName,T t) throws Exception;
	
	/**
	 *  添加数据
	 * @param indexName
	 * @param t
	 * @throws Exception
	 */
	public Integer insertDoc(String indexName,String id,T t) throws Exception;
	
	/**
	 *  批量添加
	 * @param idnexName
	 * @param list
	 * @throws Exception
	 */
	public void insertDocBulk(String idnexName, List<T> list) throws Exception;
	/**
	 *  删除索引
	 * @param indexName
	 * @throws Exception
	 */
	public void deleteIndex(String indexName) throws Exception;
	
	/**
	 *    批量删除索引
	 * @param indexName
	 * @throws Exception
	 */
	public void deleteIndexBulk(List<String> indexs)throws Exception;
	
	/**
	 *  删除数据
	 * @param indexName
	 * @param id
	 * @throws Exception
	 */
	public void deleteDocById(String indexName,String id) throws Exception;
	
	/**
	 *   根据ID 批量删除
	 * @param indexName
	 * @param ids
	 * @throws Exception
	 */
	public Long deleteBulkByIds(String indexName,List<String> ids) throws Exception;
	
	/**
	 *  根据条件删除
	 * @param indexName
	 * @param params
	 * @throws Exception
	 */
	public void deleteByQuery(String indexName,List<SearchBean> params) throws Exception;
	
	/**
	 *  修改数据
	 * @param indexName
	 * @param t
	 * @throws Exception
	 */
	public void updateDoc(String indexName,T t) throws Exception;
	
	/**
	 *  根据ID查询
	 * @param indexName
	 * @param id
	 * @return
	 * @throws Exception
	 */
	public T queryById(String indexName,T t) throws Exception;
	
	/**
	 *   单表查询,自动组成条件
	 * @param bean
	 * @return
	 * @throws Exception
	 */
	public Map<String, Object> query(String indexName,T t, int... pages) throws Exception;
	
	/**
	 *  根据条件查询，并聚合
	 * @param indexName
	 * @param params 条件
  	 * @param aggregFiled  聚合的字段
	 * @return
	 * @throws Exception
	 */
	public Map<String,Object> aggregationsBycondition(String indexName, List<SearchBean> params,String aggregFiled) throws Exception;

	Map<String, Object> queryEs(Map<String,String> map) throws Exception;

	void indexDocument(String indexName, String fullContent, List<String> chunks,Map<String, Object> jsonMap);

	Map<String, Object> buildChunkFuzzyQuery(Map<String,String> map) throws Exception;

	void updateIndexMapping();
}
