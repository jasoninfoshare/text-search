package com.example.search.es.entity;

import java.io.Serializable;


public class AttachmentBean implements Serializable{
	
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	private String id;
	
	/**
	 * 	主表ID
	 */
	private String mainId;
	
	private String title;
	
	private String createTime;
	
	private String content;
	
	private String authorId;
	
	private String authorName;
	
	private String sourceIndex;
	
	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getCreateTime() {
		return createTime;
	}

	public void setCreateTime(String createTime) {
		this.createTime = createTime;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public String getAuthorId() {
		return authorId;
	}

	public void setAuthorId(String authorId) {
		this.authorId = authorId;
	}

	public String getAuthorName() {
		return authorName;
	}

	public void setAuthorName(String authorName) {
		this.authorName = authorName;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getMainId() {
		return mainId;
	}

	public void setMainId(String mainId) {
		this.mainId = mainId;
	}

	public String getSourceIndex() {
		return sourceIndex;
	}

	public void setSourceIndex(String sourceIndex) {
		this.sourceIndex = sourceIndex;
	}

	@Override
	public String toString() {
		return "AttachmentBean [id=" + id + ", mainId=" + mainId + ", title=" + title + ", createTime=" + createTime + ", content=" + content + ", authorId=" + authorId + ", authorName=" + authorName + ", sourceIndex=" + sourceIndex + "]";
	}
	
}
