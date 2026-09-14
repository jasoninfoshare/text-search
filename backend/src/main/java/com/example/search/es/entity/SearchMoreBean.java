package com.example.search.es.entity;

import java.io.Serializable;
/**
 *   查询条件
 * @author 28790
 *
 */
public class SearchMoreBean implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	
	private String indexName;
	
	private String fieldName;
	
	private String keyword;
	
	private String other;
	
	// or 、 and
	private String relation = "or";
	
	//like:模糊查询,term:精确查询,range:范围查询
	private String type;
	
	private Boolean sort = false;
	
	private String sortBy = "ASC";
	
	private Boolean highlight = true;
	
	public String getFieldName() {
		return fieldName;
	}

	public void setFieldName(String fieldName) {
		this.fieldName = fieldName;
	}

	public String getKeyword() {
		return keyword;
	}

	public void setKeyword(String keyword) {
		this.keyword = keyword;
	}

	public String getRelation() {
		return relation;
	}

	public void setRelation(String relation) {
		this.relation = relation;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public String getOther() {
		return other;
	}

	public void setOther(String other) {
		this.other = other;
	}

	public Boolean getSort() {
		return sort;
	}

	public void setSort(Boolean sort) {
		this.sort = sort;
	}

	public String getSortBy() {
		return sortBy;
	}

	public void setSortBy(String sortBy) {
		this.sortBy = sortBy;
	}

	public Boolean getHighlight() {
		return highlight;
	}

	public void setHighlight(Boolean highlight) {
		this.highlight = highlight;
	}

	public String getIndexName() {
		return indexName;
	}

	public void setIndexName(String indexName) {
		this.indexName = indexName;
	}

	@Override
	public String toString() {
		return "SearchMoreBean [indexName=" + indexName + ", fieldName=" + fieldName + ", keyword=" + keyword
				+ ", other=" + other + ", relation=" + relation + ", type=" + type + ", sort=" + sort + ", sortBy="
				+ sortBy + ", highlight=" + highlight + "]";
	}
	
}
