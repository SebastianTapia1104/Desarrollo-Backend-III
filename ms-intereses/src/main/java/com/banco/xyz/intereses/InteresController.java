package com.banco.xyz.intereses;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

@RestController
public class InteresController {

	private final JdbcTemplate jdbc;
	private final CircuitBreaker circuito;
	private final RestClient peer;
	private final String configSource;

	public InteresController(JdbcTemplate jdbc, CircuitBreaker circuito, RestClient peer,
			@Value("${bank.config-source}") String configSource) {
		this.jdbc = jdbc;
		this.circuito = circuito;
		this.peer = peer;
		this.configSource = configSource;
	}

	@GetMapping("/intereses/cuentas")
	public List<CuentaView> cuentas() {
		return jdbc.query("""
				SELECT id, cuenta_id, nombre, tipo, saldo_final
				FROM cuentas_con_interes
				ORDER BY id
				LIMIT 10
				""", (rs, row) -> new CuentaView(rs.getLong("id"), rs.getLong("cuenta_id"), rs.getString("nombre"),
				rs.getString("tipo"), rs.getBigDecimal("saldo_final")));
	}

	@GetMapping("/intereses/origen-config")
	public Map<String, String> origen() {
		return Map.of("servicio", "ms-intereses", "configSource", configSource);
	}

	@GetMapping("/interno/ping")
	public Map<String, String> ping() {
		return Map.of("servicio", "ms-intereses", "estado", "UP");
	}

	@GetMapping("/intereses/salud-dependencia")
	public Map<String, String> salud(@RequestParam(defaultValue = "false") boolean forzarFallo) {
		try {
			String respuesta = circuito.executeSupplier(() -> {
				if (forzarFallo) {
					RestClient.create().get().uri("http://127.0.0.1:9/no").retrieve().toBodilessEntity();
				}
				return peer.get().uri("/interno/ping").retrieve().body(String.class);
			});
			return Map.of("estado", "OK", "circuito", circuito.getState().name(), "respuesta",
					respuesta == null ? "" : respuesta);
		}
		catch (Exception ex) {
			return Map.of("estado", "FALLBACK", "circuito", circuito.getState().name(), "detalle",
					ex.getClass().getSimpleName());
		}
	}

	public record CuentaView(long id, long cuentaId, String nombre, String tipo, BigDecimal saldoFinal) {
	}
}
