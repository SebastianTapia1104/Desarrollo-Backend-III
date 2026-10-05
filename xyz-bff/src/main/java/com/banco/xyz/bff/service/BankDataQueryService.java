package com.banco.xyz.bff.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

@Service
public class BankDataQueryService {

	private static final Logger log = LoggerFactory.getLogger(BankDataQueryService.class);

	private final RestClient backend;
	private final String baseUrl;
	private final JsonParser json = JsonParserFactory.getJsonParser();

	public BankDataQueryService(RestClient backend, @Value("${bank.backend.base-url}") String baseUrl) {
		this.backend = backend;
		this.baseUrl = baseUrl;
	}

	public Summary summary() {
		return get("/api/resumen", Summary.class);
	}

	public List<WebTransaction> transactions() {
		return getList("/api/transacciones", new ParameterizedTypeReference<List<WebTransaction>>() {
		});
	}

	public List<MobileTransaction> recentTransactions(int limit) {
		log.info("Integracion HTTP GET {}/api/transacciones/recientes?limit={}", baseUrl, limit);
		return backend.get().uri("/api/transacciones/recientes?limit={limit}", limit).retrieve()
				.body(new ParameterizedTypeReference<List<MobileTransaction>>() {
				});
	}

	public List<Account> accounts() {
		return getList("/api/cuentas", new ParameterizedTypeReference<List<Account>>() {
		});
	}

	public Optional<Account> accountById(long id) {
		log.info("Integracion HTTP GET {}/api/cuentas/{}", baseUrl, id);
		try {
			Account account = backend.get().uri("/api/cuentas/{id}", id).retrieve().body(Account.class);
			return Optional.ofNullable(account);
		}
		catch (HttpStatusCodeException ex) {
			if (ex.getStatusCode().value() == 404) {
				return Optional.empty();
			}
			throw ex;
		}
	}

	public List<Movement> yearlyMovements(long id) {
		log.info("Integracion HTTP GET {}/api/cuentas/{}/movimientos", baseUrl, id);
		return backend.get().uri("/api/cuentas/{id}/movimientos", id).retrieve()
				.body(new ParameterizedTypeReference<List<Movement>>() {
				});
	}

	public Withdrawal withdraw(long id, BigDecimal amount) {
		log.info("Integracion HTTP POST {}/api/retiros id={}", baseUrl, id);
		try {
			return backend.post().uri("/api/retiros").body(Map.of("id", id, "monto", amount)).retrieve().body(Withdrawal.class);
		}
		catch (HttpStatusCodeException ex) {
			String mensaje = mensaje(ex);
			if (ex.getStatusCode().value() == 409) {
				throw new IllegalStateException(mensaje);
			}
			if (ex.getStatusCode().is4xxClientError()) {
				throw new IllegalArgumentException(mensaje);
			}
			throw ex;
		}
	}

	private <T> T get(String path, Class<T> type) {
		log.info("Integracion HTTP GET {}{}", baseUrl, path);
		return backend.get().uri(path).retrieve().body(type);
	}

	private <T> T getList(String path, ParameterizedTypeReference<T> type) {
		log.info("Integracion HTTP GET {}{}", baseUrl, path);
		return backend.get().uri(path).retrieve().body(type);
	}

	private String mensaje(HttpStatusCodeException ex) {
		try {
			Map<String, Object> body = json.parseMap(ex.getResponseBodyAsString());
			Object error = body.get("error");
			if (error != null) {
				return error.toString();
			}
		}
		catch (Exception ignored) {
			HttpStatusCode status = ex.getStatusCode();
			return status.toString();
		}
		return ex.getStatusCode().toString();
	}

	public record Summary(int totalTransacciones, int transaccionesAnomalas, int cuentasConInteres, int movimientosAnuales) {
	}

	public record WebTransaction(long id, LocalDate fecha, BigDecimal monto, String tipo, boolean anomalia, String observacion) {
	}

	public record MobileTransaction(long id, LocalDate fecha, BigDecimal monto, String tipo) {
	}

	public record Account(long id, long cuentaId, String nombre, BigDecimal saldoInicial, int edad, String tipo,
			BigDecimal tasaInteres, BigDecimal interesCalculado, BigDecimal saldoFinal) {
	}

	public record Movement(LocalDate fecha, String transaccion, BigDecimal monto, String descripcion, String clasificacion) {
	}

	public record Withdrawal(long id, long cuentaId, BigDecimal montoRetirado, BigDecimal saldoAnterior,
			BigDecimal saldoActual) {
	}
}
