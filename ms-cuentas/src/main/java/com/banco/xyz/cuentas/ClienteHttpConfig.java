package com.banco.xyz.cuentas;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
	RestClient peer(@LoadBalanced RestClient.Builder balanceado, @Value("${bank.peer-service}") String peer) {
		return balanceado.baseUrl("http://" + peer)
				.requestInterceptor((request, body, execution) -> {
					request.getHeaders().setBasicAuth("servicio", "servicio123");
					return execution.execute(request, body);
				})
				.build();
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
