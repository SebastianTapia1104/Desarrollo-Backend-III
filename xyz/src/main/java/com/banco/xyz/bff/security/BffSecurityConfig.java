package com.banco.xyz.bff.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Autenticación/autorización específica por canal BFF.
 * <ul>
 *   <li>WEB → rol CHANNEL_WEB</li>
 *   <li>MOBILE → rol CHANNEL_MOBILE</li>
 *   <li>ATM → rol CHANNEL_ATM (+ PIN en header X-ATM-PIN para operaciones)</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class BffSecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http.csrf(csrf -> csrf.disable())
				.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/h2-console/**").permitAll()
						.requestMatchers("/actuator/health", "/error").permitAll()
						.requestMatchers("/bff/web/**").hasRole("CHANNEL_WEB")
						.requestMatchers("/bff/mobile/**").hasRole("CHANNEL_MOBILE")
						.requestMatchers(HttpMethod.GET, "/bff/atm/ping").hasRole("CHANNEL_ATM")
						.requestMatchers("/bff/atm/**").hasRole("CHANNEL_ATM")
						.anyRequest().authenticated())
				.httpBasic(Customizer.withDefaults())
				.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
		return http.build();
	}

	@Bean
	UserDetailsService userDetailsService(PasswordEncoder encoder) {
		return new InMemoryUserDetailsManager(
				User.withUsername("web").password(encoder.encode("web123")).roles("CHANNEL_WEB").build(),
				User.withUsername("mobile").password(encoder.encode("mobile123")).roles("CHANNEL_MOBILE").build(),
				User.withUsername("atm").password(encoder.encode("atm123")).roles("CHANNEL_ATM").build());
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
