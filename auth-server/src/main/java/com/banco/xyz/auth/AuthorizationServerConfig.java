package com.banco.xyz.auth;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

@Configuration
@EnableWebSecurity
public class AuthorizationServerConfig {

	@Bean
	@Order(1)
	SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
		OAuth2AuthorizationServerConfigurer authorizationServer = new OAuth2AuthorizationServerConfigurer();
		http.securityMatcher(authorizationServer.getEndpointsMatcher())
				.with(authorizationServer, server -> server.oidc(org.springframework.security.config.Customizer.withDefaults()))
				.authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
		return http.build();
	}

	@Bean
	@Order(2)
	SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
		http.securityMatcher("/actuator/**")
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
		return http.build();
	}

	@Bean
	RegisteredClientRepository registeredClientRepository(PasswordEncoder encoder) {
		TokenSettings jwt = TokenSettings.builder().build();
		RegisteredClient web = cliente("web", "web123", encoder, jwt, "cuentas.read");
		RegisteredClient atm = cliente("atm", "atm123", encoder, jwt, "cuentas.read", "retiros.write");
		RegisteredClient servicio = cliente("servicio", "servicio123", encoder, jwt, "interno");
		return new InMemoryRegisteredClientRepository(web, atm, servicio);
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	JWKSource<SecurityContext> jwkSource() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		KeyPair keyPair = generator.generateKeyPair();
		RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
				.privateKey((RSAPrivateKey) keyPair.getPrivate())
				.keyID(UUID.randomUUID().toString())
				.build();
		return new ImmutableJWKSet<>(new JWKSet(rsaKey));
	}

	@Bean
	OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer() {
		return context -> {
			if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
				var scopes = context.getAuthorizedScopes();
				if (scopes == null || scopes.isEmpty()) {
					scopes = context.getRegisteredClient().getScopes();
				}
				context.getClaims().claim("scope", scopes);
			}
		};
	}

	@Bean
	JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
		return org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration
				.jwtDecoder(jwkSource);
	}

	@Bean
	AuthorizationServerSettings authorizationServerSettings(@Value("${bank.oauth.issuer}") String issuer) {
		return AuthorizationServerSettings.builder().issuer(issuer).build();
	}

	private static RegisteredClient cliente(String id, String secret, PasswordEncoder encoder, TokenSettings jwt,
			String... scopes) {
		RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
				.clientId(id)
				.clientSecret(encoder.encode(secret))
				.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
				.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
				.tokenSettings(jwt);
		for (String scope : scopes) {
			builder.scope(scope);
		}
		return builder.build();
	}
}
