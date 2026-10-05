package com.banco.xyz.backend;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatosService {

	private final JdbcTemplate jdbc;

	public DatosService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Resumen resumen() {
		return new Resumen(
				count("SELECT COUNT(*) FROM transacciones_diarias"),
				count("SELECT COUNT(*) FROM transacciones_diarias WHERE anomalia = TRUE"),
				count("SELECT COUNT(*) FROM cuentas_con_interes"),
				count("SELECT COUNT(*) FROM estados_cuenta_anuales"));
	}

	public List<Transaccion> transacciones() {
		return jdbc.query("""
				SELECT id, fecha, monto, tipo, anomalia, observacion
				FROM transacciones_diarias
				ORDER BY fecha DESC, id DESC
				""", (rs, rowNum) -> new Transaccion(
				rs.getLong("id"),
				rs.getDate("fecha").toLocalDate(),
				rs.getBigDecimal("monto"),
				rs.getString("tipo"),
				rs.getBoolean("anomalia"),
				rs.getString("observacion")));
	}

	public List<TransaccionReciente> transaccionesRecientes(int limit) {
		return jdbc.query("""
				SELECT id, fecha, monto, tipo
				FROM transacciones_diarias
				ORDER BY fecha DESC, id DESC
				LIMIT ?
				""", (rs, rowNum) -> new TransaccionReciente(
				rs.getLong("id"),
				rs.getDate("fecha").toLocalDate(),
				rs.getBigDecimal("monto"),
				rs.getString("tipo")), limit);
	}

	public List<Cuenta> cuentas() {
		return jdbc.query("""
				SELECT id, cuenta_id, nombre, saldo_inicial, edad, tipo,
				       tasa_interes, interes_calculado, saldo_final
				FROM cuentas_con_interes
				ORDER BY id
				""", (rs, rowNum) -> mapCuenta(rs));
	}

	public Optional<Cuenta> cuenta(long id) {
		List<Cuenta> found = jdbc.query("""
				SELECT id, cuenta_id, nombre, saldo_inicial, edad, tipo,
				       tasa_interes, interes_calculado, saldo_final
				FROM cuentas_con_interes
				WHERE id = ?
				""", (rs, rowNum) -> mapCuenta(rs), id);
		return found.stream().findFirst();
	}

	public List<Movimiento> movimientos(long cuentaTecnicaId) {
		Cuenta cuenta = cuenta(cuentaTecnicaId).orElseThrow(() -> new IllegalArgumentException("Cuenta no encontrada"));
		return jdbc.query("""
				SELECT fecha, transaccion, monto, descripcion, clasificacion
				FROM estados_cuenta_anuales
				WHERE cuenta_id = ?
				ORDER BY fecha
				""", (rs, rowNum) -> new Movimiento(
				rs.getDate("fecha").toLocalDate(),
				rs.getString("transaccion"),
				rs.getBigDecimal("monto"),
				rs.getString("descripcion"),
				rs.getString("clasificacion")), cuenta.cuentaId());
	}

	@Transactional
	public Retiro retirar(long id, BigDecimal amount) {
		if (amount == null || amount.signum() <= 0) {
			throw new IllegalArgumentException("Monto de retiro debe ser positivo");
		}
		Cuenta cuenta = cuenta(id).orElseThrow(() -> new IllegalArgumentException("Cuenta no encontrada"));
		if (cuenta.saldoFinal().compareTo(amount) < 0) {
			throw new IllegalStateException("Saldo insuficiente");
		}
		BigDecimal nuevoSaldo = cuenta.saldoFinal().subtract(amount);
		jdbc.update("UPDATE cuentas_con_interes SET saldo_final = ? WHERE id = ?", nuevoSaldo, id);
		jdbc.update("""
				INSERT INTO estados_cuenta_anuales (cuenta_id, fecha, transaccion, monto, descripcion, clasificacion)
				VALUES (?, ?, 'retiro', ?, 'Retiro ATM', 'EGRESO')
				""", cuenta.cuentaId(), LocalDate.now(), amount);
		return new Retiro(id, cuenta.cuentaId(), amount, cuenta.saldoFinal(), nuevoSaldo);
	}

	private Cuenta mapCuenta(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new Cuenta(
				rs.getLong("id"),
				rs.getLong("cuenta_id"),
				rs.getString("nombre"),
				rs.getBigDecimal("saldo_inicial"),
				rs.getInt("edad"),
				rs.getString("tipo"),
				rs.getBigDecimal("tasa_interes"),
				rs.getBigDecimal("interes_calculado"),
				rs.getBigDecimal("saldo_final"));
	}

	private int count(String sql) {
		Integer count = jdbc.queryForObject(sql, Integer.class);
		return count == null ? 0 : count;
	}

	public record Resumen(int totalTransacciones, int transaccionesAnomalas, int cuentasConInteres, int movimientosAnuales) {
	}

	public record Transaccion(long id, LocalDate fecha, BigDecimal monto, String tipo, boolean anomalia, String observacion) {
	}

	public record TransaccionReciente(long id, LocalDate fecha, BigDecimal monto, String tipo) {
	}

	public record Cuenta(long id, long cuentaId, String nombre, BigDecimal saldoInicial, int edad, String tipo,
			BigDecimal tasaInteres, BigDecimal interesCalculado, BigDecimal saldoFinal) {
	}

	public record Movimiento(LocalDate fecha, String transaccion, BigDecimal monto, String descripcion, String clasificacion) {
	}

	public record Retiro(long id, long cuentaId, BigDecimal montoRetirado, BigDecimal saldoAnterior, BigDecimal saldoActual) {
	}
}
