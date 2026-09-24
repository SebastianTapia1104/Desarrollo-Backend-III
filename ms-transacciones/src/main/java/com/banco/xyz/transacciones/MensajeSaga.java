package com.banco.xyz.transacciones;

import java.math.BigDecimal;

public record MensajeSaga(String tipo, String eventId, long cuentaId, BigDecimal monto, String detalle) {

	public String encode() {
		return tipo + ";" + eventId + ";" + cuentaId + ";" + monto.toPlainString() + ";"
				+ (detalle == null ? "" : detalle);
	}

	public static MensajeSaga decode(String raw) {
		String[] parts = raw.split(";", 5);
		return new MensajeSaga(parts[0], parts[1], Long.parseLong(parts[2]), new BigDecimal(parts[3]),
				parts.length > 4 ? parts[4] : "");
	}

	public static MensajeSaga debit(String eventId, long cuentaId, BigDecimal monto) {
		return new MensajeSaga("DEBIT", eventId, cuentaId, monto, "");
	}

	public static MensajeSaga result(String eventId, BigDecimal saldo, String detalle) {
		return new MensajeSaga("RESULT", eventId, 0, saldo, detalle);
	}

	public static MensajeSaga compensate(String eventId, long cuentaId, BigDecimal monto) {
		return new MensajeSaga("COMPENSATE", eventId, cuentaId, monto, "");
	}
}
