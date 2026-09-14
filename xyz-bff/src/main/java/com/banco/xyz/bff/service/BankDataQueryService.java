package com.banco.xyz.bff.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BankDataQueryService {

	private final JdbcTemplate jdbc;

	public BankDataQueryService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Summary summary() {
		return new Summary(
				count("SELECT COUNT(*) FROM transacciones_diarias"),
				count("SELECT COUNT(*) FROM transacciones_diarias WHERE anomalia = TRUE"),
				count("SELECT COUNT(*) FROM cuentas_con_interes"),
				count("SELECT COUNT(*) FROM estados_cuenta_anuales"));
	}

	public List<WebTransaction> transactions() {
		return jdbc.query("""
				SELECT id, fecha, monto, tipo, anomalia, observacion
				FROM transacciones_diarias
				ORDER BY fecha DESC, id DESC
				""", (rs, rowNum) -> new WebTransaction(
				rs.getLong("id"),
				rs.getDate("fecha").toLocalDate(),
				rs.getBigDecimal("monto"),
				rs.getString("tipo"),
				rs.getBoolean("anomalia"),
				rs.getString("observacion")));
	}

	public List<MobileTransaction> recentTransactions(int limit) {
		return jdbc.query("""
				SELECT id, fecha, monto, tipo
				FROM transacciones_diarias
				ORDER BY fecha DESC, id DESC
				LIMIT ?
				""", (rs, rowNum) -> new MobileTransaction(
				rs.getLong("id"),
				rs.getDate("fecha").toLocalDate(),
				rs.getBigDecimal("monto"),
				rs.getString("tipo")), limit);
	}

	public List<Account> accounts() {
		return jdbc.query("""
				SELECT id, cuenta_id, nombre, saldo_inicial, edad, tipo,
				       tasa_interes, interes_calculado, saldo_final
				FROM cuentas_con_interes
				ORDER BY id
				""", (rs, rowNum) -> new Account(
				rs.getLong("id"),
				rs.getLong("cuenta_id"),
				rs.getString("nombre"),
				rs.getBigDecimal("saldo_inicial"),
				rs.getInt("edad"),
				rs.getString("tipo"),
				rs.getBigDecimal("tasa_interes"),
				rs.getBigDecimal("interes_calculado"),
				rs.getBigDecimal("saldo_final")));
	}

	public Optional<Account> accountById(long id) {
		return accounts().stream().filter(c -> c.id() == id).findFirst();
	}

	public List<Movement> yearlyMovements(long cuentaId) {
		return jdbc.query("""
				SELECT fecha, transaccion, monto, descripcion, clasificacion
				FROM estados_cuenta_anuales
				WHERE cuenta_id = ?
				ORDER BY fecha
				""", (rs, rowNum) -> new Movement(
				rs.getDate("fecha").toLocalDate(),
				rs.getString("transaccion"),
				rs.getBigDecimal("monto"),
				rs.getString("descripcion"),
				rs.getString("clasificacion")), cuentaId);
	}

	@Transactional
	public Withdrawal withdraw(long id, BigDecimal amount) {
		if (amount == null || amount.signum() <= 0) {
			throw new IllegalArgumentException("Monto de retiro debe ser positivo");
		}
		Account account = accountById(id).orElseThrow(() -> new IllegalArgumentException("Cuenta no encontrada"));
		if (account.saldoFinal().compareTo(amount) < 0) {
			throw new IllegalStateException("Saldo insuficiente");
		}
		BigDecimal newBalance = account.saldoFinal().subtract(amount);
		jdbc.update("UPDATE cuentas_con_interes SET saldo_final = ? WHERE id = ?", newBalance, id);
		jdbc.update("""
				INSERT INTO estados_cuenta_anuales (cuenta_id, fecha, transaccion, monto, descripcion, clasificacion)
				VALUES (?, ?, 'retiro', ?, 'Retiro ATM', 'EGRESO')
				""", account.cuentaId(), LocalDate.now(), amount);
		return new Withdrawal(id, account.cuentaId(), amount, account.saldoFinal(), newBalance);
	}

	private int count(String sql) {
		Integer count = jdbc.queryForObject(sql, Integer.class);
		return count == null ? 0 : count;
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
