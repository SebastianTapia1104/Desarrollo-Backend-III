package com.banco.xyz.cuentas;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

@Configuration
public class ClienteHttpConfig {

	@Bean
	@org.springframework.context.annotation.Primary
	RestClient.Builder restClientBuilder() {
		return RestClient.builder().requestFactory(timeouts());
	}

	@Bean
	@LoadBalanced
	RestClient.Builder balanceado() {
		return RestClient.builder().requestFactory(timeouts());
	}

	@Bean
	RestClient peer(@LoadBalanced RestClient.Builder balanceado, RestClient.Builder restClientBuilder,
			@Value("${bank.peer-service}") String peer, @Value("${bank.oauth.token-uri}") String tokenUri) {
		RestClient tokens = restClientBuilder.build();
		AtomicReference<String> token = new AtomicReference<>();
		AtomicLong expira = new AtomicLong(0);
		return balanceado.baseUrl("http://" + peer).requestInterceptor((request, body, execution) -> {
			request.getHeaders().setBearerAuth(tokenDeServicio(tokens, tokenUri, token, expira));
			return execution.execute(request, body);
		}).build();
	}

	@SuppressWarnings("unchecked")
	private static String tokenDeServicio(RestClient tokens, String tokenUri, AtomicReference<String> token,
			AtomicLong expira) {
		long ahora = System.currentTimeMillis();
		if (token.get() != null && ahora < expira.get()) {
			return token.get();
		}
		Map<String, Object> respuesta = tokens.post().uri(tokenUri)
				.headers(headers -> headers.setBasicAuth("servicio", "servicio123"))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body("grant_type=client_credentials")
				.retrieve()
				.body(Map.class);
		token.set(String.valueOf(respuesta.get("access_token")));
		Number segundos = (Number) respuesta.get("expires_in");
		expira.set(ahora + Math.max(30, segundos.intValue() - 30) * 1000L);
		return token.get();
	}

	@Bean
	CircuitBreaker dependencia(CircuitBreakerRegistry registry,
			@Value("${resilience4j.circuitbreaker.instances.dependencia.sliding-window-size:2}") int window,
			@Value("${resilience4j.circuitbreaker.instances.dependencia.minimum-number-of-calls:1}") int minimum,
			@Value("${resilience4j.circuitbreaker.instances.dependencia.failure-rate-threshold:50}") float threshold) {
		return registry.circuitBreaker("dependencia", CircuitBreakerConfig.custom()
				.slidingWindowSize(window)
				.minimumNumberOfCalls(minimum)
				.failureRateThreshold(threshold)
				.waitDurationInOpenState(Duration.ofSeconds(15))
				.build());
	}

	private static SimpleClientHttpRequestFactory timeouts() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(2));
		factory.setReadTimeout(Duration.ofSeconds(2));
		return factory;
	}
}
