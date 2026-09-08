package com.banco.xyz.core.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backend compartido (datos ya migrados por Spring Batch).
 * Los BFF personalizan/adaptan estas consultas a cada frontend.
 */
@Service
public class BankDataService {

	private final JdbcTemplate jdbc;

	public BankDataService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Map<String, Object> resumenGlobal() {
		Integer tx = jdbc.queryForObject("SELECT COUNT(*) FROM transacciones_diarias", Integer.class);
		Integer anomalias = jdbc.queryForObject(
				"SELECT COUNT(*) FROM transacciones_diarias WHERE anomalia = TRUE", Integer.class);
		Integer cuentas = jdbc.queryForObject("SELECT COUNT(*) FROM cuentas_con_interes", Integer.class);
		Integer movimientos = jdbc.queryForObject("SELECT COUNT(*) FROM estados_cuenta_anuales", Integer.class);
		return Map.of(
				"totalTransacciones", tx == null ? 0 : tx,
				"transaccionesAnomalas", anomalias == null ? 0 : anomalias,
				"cuentasConInteres", cuentas == null ? 0 : cuentas,
				"movimientosAnuales", movimientos == null ? 0 : movimientos);
	}

	public List<Map<String, Object>> listarTransacciones() {
		return normalizeAll(jdbc.queryForList("""
				SELECT id, fecha, monto, tipo, anomalia, observacion
				FROM transacciones_diarias
				ORDER BY fecha DESC, id DESC
				"""));
	}

	public List<Map<String, Object>> listarTransaccionesRecientes(int limit) {
		return normalizeAll(jdbc.queryForList("""
				SELECT id, fecha, monto, tipo
				FROM transacciones_diarias
				ORDER BY fecha DESC, id DESC
				LIMIT ?
				""", limit));
	}

	public List<Map<String, Object>> listarCuentasConInteres() {
		return normalizeAll(jdbc.queryForList("""
				SELECT id, cuenta_id, nombre, saldo_inicial, edad, tipo,
				       tasa_interes, interes_calculado, saldo_final
				FROM cuentas_con_interes
				ORDER BY id
				"""));
	}

	/** Busca por PK técnica {@code id} (única), no por cuenta_id legacy (puede repetirse). */
	public Optional<Map<String, Object>> buscarCuentaPorId(long id) {
		try {
			return Optional.of(normalize(jdbc.queryForMap("""
					SELECT id, cuenta_id, nombre, saldo_inicial, edad, tipo,
					       tasa_interes, interes_calculado, saldo_final
					FROM cuentas_con_interes
					WHERE id = ?
					""", id)));
		} catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		}
	}

	public List<Map<String, Object>> movimientosAnuales(long cuentaId) {
		return normalizeAll(jdbc.queryForList("""
				SELECT fecha, transaccion, monto, descripcion, clasificacion
				FROM estados_cuenta_anuales
				WHERE cuenta_id = ?
				ORDER BY fecha
				""", cuentaId));
	}

	public List<Map<String, Object>> resumenMovimientosPorCuenta() {
		return normalizeAll(jdbc.queryForList("""
				SELECT cuenta_id,
				       COUNT(*) AS total_movimientos,
				       SUM(CASE WHEN clasificacion = 'INGRESO' THEN monto ELSE 0 END) AS total_ingresos,
				       SUM(CASE WHEN clasificacion = 'EGRESO' THEN monto ELSE 0 END) AS total_egresos
				FROM estados_cuenta_anuales
				GROUP BY cuenta_id
				ORDER BY cuenta_id
				"""));
	}

	public Optional<BigDecimal> saldoCuenta(long id) {
		return buscarCuentaPorId(id).map(row -> toBigDecimal(row.get("saldo_final")));
	}

	@Transactional
	public Map<String, Object> retirar(long id, BigDecimal monto) {
		if (monto == null || monto.compareTo(BigDecimal.ZERO) <= 0) {
			throw new IllegalArgumentException("Monto de retiro debe ser positivo");
		}
		Map<String, Object> cuenta = buscarCuentaPorId(id)
				.orElseThrow(() -> new IllegalArgumentException("Cuenta no encontrada: " + id));
		BigDecimal saldo = toBigDecimal(cuenta.get("saldo_final"));
		long cuentaId = ((Number) cuenta.get("cuenta_id")).longValue();
		if (saldo.compareTo(monto) < 0) {
			throw new IllegalStateException("Saldo insuficiente");
		}
		BigDecimal nuevo = saldo.subtract(monto);
		jdbc.update("UPDATE cuentas_con_interes SET saldo_final = ? WHERE id = ?", nuevo, id);
		jdbc.update("""
				INSERT INTO estados_cuenta_anuales (cuenta_id, fecha, transaccion, monto, descripcion, clasificacion)
				VALUES (?, ?, 'retiro', ?, 'Retiro ATM', 'EGRESO')
				""", cuentaId, LocalDate.now(), monto);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", id);
		result.put("cuentaId", cuentaId);
		result.put("montoRetirado", monto);
		result.put("saldoAnterior", saldo);
		result.put("saldoActual", nuevo);
		return result;
	}

	private List<Map<String, Object>> normalizeAll(List<Map<String, Object>> rows) {
		return rows.stream().map(this::normalize).collect(Collectors.toList());
	}

	private Map<String, Object> normalize(Map<String, Object> row) {
		Map<String, Object> out = new LinkedHashMap<>();
		row.forEach((k, v) -> out.put(k.toLowerCase(Locale.ROOT), v));
		return out;
	}

	private BigDecimal toBigDecimal(Object value) {
		if (value instanceof BigDecimal bd) {
			return bd;
		}
		if (value instanceof Number n) {
			return BigDecimal.valueOf(n.doubleValue());
		}
		throw new IllegalStateException("Saldo no numerico: " + value);
	}
}
