package com.example.search.es.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig.Builder;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class EsConfig {
	@Value("${spring.elasticsearch.rest.uris}")
	private String[] uris;

	@Bean
	public ElasticsearchClient elasticsearchClient() {
		
		HttpHost[] hosts = Arrays.stream(uris).map(HttpHost::create).toArray(HttpHost[]::new);
		JacksonJsonpMapper mapper = new JacksonJsonpMapper();
		mapper.objectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
		//	RequestConfig requestConfig = RequestConfig.custom().setConnectTimeout(5000).setSocketTimeout(6000).build();
		RestClient restClient = RestClient.builder(hosts).setRequestConfigCallback(new RestClientBuilder.RequestConfigCallback() {

			@Override
			public Builder customizeRequestConfig(Builder requestConfigBuilder) {
				requestConfigBuilder.setConnectTimeout(5000);
                requestConfigBuilder.setSocketTimeout(40000);
                requestConfigBuilder.setConnectionRequestTimeout(5000);
                return requestConfigBuilder;
			}

		}).setCompressionEnabled(true) // 开启 gzip：检索响应含大文本，跨网传输可压缩 3-6 倍，显著降低耗时
				.build();
		ElasticsearchTransport transport = new RestClientTransport(restClient, mapper);
		return new ElasticsearchClient(transport);
	}
}
