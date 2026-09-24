package com.banco.xyz.transacciones;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RetiroService {

	private static final Logger log = LoggerFactory.getLogger(RetiroService.class);

	private final JdbcTemplate jdbc;
	private final JmsTemplate jms;

	public RetiroService(JdbcTemplate jdbc, JmsTemplate jms) {
		this.jdbc = jdbc;
		this.jms = jms;
	}

	public List<TransaccionView> listar() {
		return jdbc.query("""
				SELECT id, fecha, monto, tipo, anomalia, observacion
				FROM transacciones_diarias
				ORDER BY fecha DESC, id DESC
				LIMIT 10
				""", (rs, row) -> new TransaccionView(rs.getLong("id"), rs.getDate("fecha").toLocalDate().toString(),
				rs.getBigDecimal("monto"), rs.getString("tipo"), rs.getBoolean("anomalia"), rs.getString("observacion")));
	}

	public RetiroView solicitar(long cuentaId, BigDecimal monto, boolean simularFallo, boolean simularDuplicado) {
		String eventId = UUID.randomUUID().toString();
		GeneratedKeyHolder keys = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			PreparedStatement ps = connection.prepareStatement("""
					INSERT INTO retiros_saga(event_id, cuenta_id, monto, estado, detalle, simular_fallo, simular_duplicado)
					VALUES (?,?,?,?,?,?,?)
					""", Statement.RETURN_GENERATED_KEYS);
			ps.setString(1, eventId);
			ps.setLong(2, cuentaId);
			ps.setBigDecimal(3, monto);
			ps.setString(4, "PENDIENTE");
			ps.setString(5, "publicado en cuentas.debitar");
			ps.setBoolean(6, simularFallo);
			ps.setBoolean(7, simularDuplicado);
			return ps;
		}, keys);
		String mensaje = MensajeSaga.debit(eventId, cuentaId, monto).encode();
		jms.convertAndSend("cuentas.debitar", mensaje);
		log.info("SAGA cola=cuentas.debitar eventId={} cuentaId={} monto={}", eventId, cuentaId, monto);
		if (simularDuplicado) {
			jms.convertAndSend("cuentas.debitar", mensaje);
			log.info("SAGA cola=cuentas.debitar eventId={} duplicado=true", eventId);
		}
		return buscar(keys.getKey().longValue());
	}

	public RetiroView buscar(long id) {
		List<RetiroView> rows = jdbc.query("""
				SELECT id, event_id, cuenta_id, monto, estado, detalle
				FROM retiros_saga WHERE id = ?
				""", (rs, row) -> new RetiroView(rs.getLong("id"), rs.getString("event_id"), rs.getLong("cuenta_id"),
				rs.getBigDecimal("monto"), rs.getString("estado"), rs.getString("detalle")), id);
		if (rows.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Retiro no encontrado");
		}
		return rows.get(0);
	}

	@JmsListener(destination = "cuentas.debitar.respuesta")
	public void onRespuesta(String raw) {
		MensajeSaga msg = MensajeSaga.decode(raw);
		Boolean simularFallo = jdbc.queryForObject(
				"SELECT simular_fallo FROM retiros_saga WHERE event_id = ?", Boolean.class, msg.eventId());
		if (simularFallo == null) {
			log.warn("SAGA cola=cuentas.debitar.respuesta eventId={} sin retiro local", msg.eventId());
			return;
		}
		if ("OK".equals(msg.detalle()) && simularFallo) {
			jdbc.update("UPDATE retiros_saga SET estado = ?, detalle = ? WHERE event_id = ?",
					"COMPENSANDO", "fallo posterior al debito; se publica cuentas.compensar", msg.eventId());
			var retiro = jdbc.queryForMap("SELECT cuenta_id, monto FROM retiros_saga WHERE event_id = ?", msg.eventId());
			String compensacion = MensajeSaga.compensate(msg.eventId(), ((Number) retiro.get("cuenta_id")).longValue(),
					(BigDecimal) retiro.get("monto")).encode();
			jms.convertAndSend("cuentas.compensar", compensacion);
			log.info("SAGA cola=cuentas.compensar eventId={} estado=COMPENSANDO", msg.eventId());
			return;
		}
		String estado = switch (msg.detalle()) {
			case "OK" -> "CONFIRMADO";
			case "COMPENSATED" -> "COMPENSADO";
			default -> "RECHAZADO";
		};
		jdbc.update("UPDATE retiros_saga SET estado = ?, detalle = ? WHERE event_id = ?", estado, msg.detalle(),
				msg.eventId());
		log.info("SAGA cola=cuentas.debitar.respuesta eventId={} estado={}", msg.eventId(), estado);
	}

	public record TransaccionView(long id, String fecha, BigDecimal monto, String tipo, boolean anomalia,
			String observacion) {
	}

	public record RetiroView(long id, String eventId, long cuentaId, BigDecimal monto, String estado, String detalle) {
	}
}
