package com.banco.xyz.cuentas;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import jakarta.annotation.PostConstruct;

@RestController
public class CuentaController {

	private final JdbcTemplate jdbc;
	private final CircuitBreaker circuito;
	private final RestClient peer;
	private final String configSource;

	public CuentaController(JdbcTemplate jdbc, CircuitBreaker circuito, RestClient peer,
			@Value("${bank.config-source}") String configSource) {
		this.jdbc = jdbc;
		this.circuito = circuito;
		this.peer = peer;
		this.configSource = configSource;
	}

	@GetMapping("/cuentas/estados")
	public List<EstadoView> estados() {
		return jdbc.query("""
				SELECT id, cuenta_id, fecha, transaccion, monto, descripcion
				FROM estados_cuenta_anuales
				ORDER BY id
				LIMIT 10
				""", (rs, row) -> new EstadoView(rs.getLong("id"), rs.getLong("cuenta_id"),
				rs.getDate("fecha").toLocalDate().toString(), rs.getString("transaccion"), rs.getBigDecimal("monto"),
				rs.getString("descripcion")));
	}

	@GetMapping("/cuentas/origen-config")
	public Map<String, String> origen() {
		return Map.of("servicio", "ms-cuentas", "configSource", configSource);
	}

	@GetMapping("/interno/ping")
	public Map<String, String> ping() {
		return Map.of("servicio", "ms-cuentas", "estado", "UP");
	}

	@GetMapping("/cuentas/salud-dependencia")
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

	public record EstadoView(long id, long cuentaId, String fecha, String transaccion, BigDecimal monto,
			String descripcion) {
	}
}

@Service
class SagaCuentasListener {

	private static final Logger log = LoggerFactory.getLogger(SagaCuentasListener.class);

	private final JdbcTemplate jdbc;
	private final JmsTemplate jms;

	SagaCuentasListener(JdbcTemplate jdbc, JmsTemplate jms) {
		this.jdbc = jdbc;
		this.jms = jms;
	}

	@PostConstruct
	void schema() {
		jdbc.execute("""
				CREATE TABLE IF NOT EXISTS eventos_procesados (
				    event_id VARCHAR(80) PRIMARY KEY,
				    cola VARCHAR(80) NOT NULL
				)
				""");
	}

	@JmsListener(destination = "cuentas.debitar")
	public void debitar(String raw) {
		MensajeSaga msg = MensajeSaga.decode(raw);
		String resultado = aplicar(msg.eventId(), "cuentas.debitar", msg.cuentaId(), msg.monto().negate());
		if ("DUPLICADO".equals(resultado)) {
			log.info("SAGA cola=cuentas.debitar eventId={} resultado=DUPLICADO", msg.eventId());
			return;
		}
		jms.convertAndSend("cuentas.debitar.respuesta", resultado);
		log.info("SAGA cola=cuentas.debitar eventId={} resultado={}", msg.eventId(), resultado);
	}

	@JmsListener(destination = "cuentas.compensar")
	public void compensar(String raw) {
		MensajeSaga msg = MensajeSaga.decode(raw);
		String resultado = aplicar(msg.eventId() + ":COMPENSATE", "cuentas.compensar", msg.cuentaId(), msg.monto());
		if ("DUPLICADO".equals(resultado)) {
			log.info("SAGA cola=cuentas.compensar eventId={} resultado=DUPLICADO", msg.eventId());
			return;
		}
		jms.convertAndSend("cuentas.debitar.respuesta", resultado);
		log.info("SAGA cola=cuentas.compensar eventId={} resultado={}", msg.eventId(), resultado);
	}

	@Transactional
	String aplicar(String clave, String cola, long cuentaId, BigDecimal delta) {
		try {
			jdbc.update("INSERT INTO eventos_procesados(event_id, cola) VALUES (?, ?)", clave, cola);
		}
		catch (DuplicateKeyException ex) {
			return "DUPLICADO";
		}
		Integer existe = jdbc.queryForObject("SELECT COUNT(*) FROM cuentas_con_interes WHERE id = ?", Integer.class,
				cuentaId);
		if (existe == null || existe == 0) {
			return MensajeSaga.result(eventIdDe(clave), BigDecimal.ZERO, "REJECT:cuenta inexistente").encode();
		}
		if (delta.signum() < 0) {
			int updated = jdbc.update(
					"UPDATE cuentas_con_interes SET saldo_final = saldo_final + ? WHERE id = ? AND saldo_final >= ?",
					delta, cuentaId, delta.abs());
			if (updated == 0) {
				return MensajeSaga.result(eventIdDe(clave), BigDecimal.ZERO, "REJECT:saldo insuficiente").encode();
			}
		}
		else {
			jdbc.update("UPDATE cuentas_con_interes SET saldo_final = saldo_final + ? WHERE id = ?", delta, cuentaId);
		}
		BigDecimal saldo = jdbc.queryForObject("SELECT saldo_final FROM cuentas_con_interes WHERE id = ?",
				BigDecimal.class, cuentaId);
		String detalle = delta.signum() < 0 ? "OK" : "COMPENSATED";
		return MensajeSaga.result(eventIdDe(clave), saldo, detalle).encode();
	}

	private static String eventIdDe(String clave) {
		return clave.endsWith(":COMPENSATE") ? clave.substring(0, clave.length() - ":COMPENSATE".length()) : clave;
	}
}
