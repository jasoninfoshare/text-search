package com.example.search.es.entity;

import java.io.Serializable;

public class SortBean implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = 856494468399853797L;

	private String fieldName;

	private String sortBy;

	private int order;

	public String getFieldName() {
		return fieldName;
	}

	public void setFieldName(String fieldName) {
		this.fieldName = fieldName;
	}

	public String getSortBy() {
		return sortBy;
	}

	public void setSortBy(String sortBy) {
		this.sortBy = sortBy;
	}

	public int getOrder() {
		return order;
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public String toString() {
		return "SortBean [fieldName=" + fieldName + ", sortBy=" + sortBy + ", order=" + order + "]";
	}

	
}
