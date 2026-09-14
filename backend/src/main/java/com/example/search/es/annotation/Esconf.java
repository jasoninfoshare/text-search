package com.example.search.es.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Esconf {
	
	//字符串类型按照keyword存储（不分词），或者text存储（分词）
	String stringType() default "text";
	
	//like:模糊查询,term:精确查询,range:范围查询
	String searchType() default "like";
	
	boolean isHighLight() default false;
	
	boolean isSort() default false;
	
	String sortBy() default "Asc";
	
	int order() default 1;
}
