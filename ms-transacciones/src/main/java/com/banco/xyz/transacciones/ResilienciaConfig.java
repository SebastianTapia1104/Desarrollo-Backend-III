package com.banco.xyz.transacciones;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

@Configuration
public class ResilienciaConfig {

	@Bean
	CircuitBreaker dependencia(CircuitBreakerRegistry registry,
			@Value("${resilience4j.circuitbreaker.instances.dependencia.sliding-window-size:2}") int window,
			@Value("${resilience4j.circuitbreaker.instances.dependencia.minimum-number-of-calls:1}") int minimum,
			@Value("${resilience4j.circuitbreaker.instances.dependencia.failure-rate-threshold:50}") float threshold) {
		CircuitBreakerConfig config = CircuitBreakerConfig.custom()
				.slidingWindowSize(window)
				.minimumNumberOfCalls(minimum)
				.failureRateThreshold(threshold)
				.waitDurationInOpenState(Duration.ofSeconds(15))
				.build();
		return registry.circuitBreaker("dependencia", config);
	}
}
