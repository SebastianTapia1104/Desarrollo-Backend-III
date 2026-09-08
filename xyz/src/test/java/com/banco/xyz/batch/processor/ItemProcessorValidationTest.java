package com.banco.xyz.batch.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.banco.xyz.batch.exception.InvalidDataException;
import com.banco.xyz.domain.CuentaInteres;
import com.banco.xyz.domain.MovimientoAnual;
import com.banco.xyz.domain.Transaccion;

/**
 * Cobertura de todos los casos de error / transformación de los ItemProcessor
 * (criterio Semana 3: validaciones completas).
 */
class ItemProcessorValidationTest {

	@Nested
	@DisplayName("TransaccionItemProcessor")
	class Transacciones {

		private TransaccionItemProcessor processor;

		@BeforeEach
		void setUp() {
			processor = new TransaccionItemProcessor();
		}

		@Test
		void aceptaDebitoYCreditoValidos() {
			Transaccion ok = base(1L, "100.00", "credito");
			Transaccion out = processor.process(ok);
			assertFalse(out.isAnomalia());
			assertEquals("OK", out.getObservacion());
			assertEquals("credito", out.getTipo());
		}

		@Test
		void omiteIncompletaSinMonto() {
			Transaccion t = base(4L, null, "debito");
			t.setMonto(null);
			assertThrows(InvalidDataException.class, () -> processor.process(t));
		}

		@Test
		void omiteTipoInvalid() {
			assertThrows(InvalidDataException.class, () -> processor.process(base(3L, "800", "invalid")));
		}

		@Test
		void omiteTipoDesconocido() {
			assertThrows(InvalidDataException.class, () -> processor.process(base(671L, "800", "desconocido")));
		}

		@Test
		void omiteIdNoPositivo() {
			assertThrows(InvalidDataException.class, () -> processor.process(base(0L, "100", "debito")));
		}

		@Test
		void omiteDuplicadoLogico() {
			processor.process(base(1L, "1500", "credito"));
			Transaccion dup = base(2L, "1500", "credito");
			assertThrows(InvalidDataException.class, () -> processor.process(dup));
		}

		@Test
		void marcaAnomaliaMontoNegativoSinOmitir() {
			Transaccion t = processor.process(base(10L, "-200", "debito"));
			assertTrue(t.isAnomalia());
			assertEquals("Monto negativo detectado", t.getObservacion());
		}

		@Test
		void marcaAnomaliaMontoCeroSinOmitir() {
			Transaccion t = processor.process(base(11L, "0", "credito"));
			assertTrue(t.isAnomalia());
			assertEquals("Monto cero detectado", t.getObservacion());
		}

		private Transaccion base(Long id, String monto, String tipo) {
			Transaccion t = new Transaccion();
			t.setId(id);
			t.setFecha(LocalDate.of(2024, 6, 1));
			if (monto != null) {
				t.setMonto(new BigDecimal(monto));
			}
			t.setTipo(tipo);
			return t;
		}
	}

	@Nested
	@DisplayName("InteresItemProcessor")
	class Intereses {

		private InteresItemProcessor processor;

		@BeforeEach
		void setUp() {
			processor = new InteresItemProcessor();
		}

		@Test
		void calculaInteresAhorro() {
			CuentaInteres out = processor.process(base(100L, "Alice", "1000", 30, "ahorro"));
			assertEquals(new BigDecimal("0.0100"), out.getTasaInteres());
			assertEquals(new BigDecimal("10.00"), out.getInteresCalculado());
			assertEquals(new BigDecimal("1010.00"), out.getSaldoFinal());
		}

		@Test
		void calculaInteresPrestamo() {
			CuentaInteres out = processor.process(base(101L, "Bob", "2000", 40, "prestamo"));
			assertEquals(new BigDecimal("0.0150"), out.getTasaInteres());
			assertEquals(new BigDecimal("30.00"), out.getInteresCalculado());
		}

		@Test
		void omiteIncompletaSinEdad() {
			CuentaInteres c = base(137L, "Bob Johnson", "7000", null, "prestamo");
			assertThrows(InvalidDataException.class, () -> processor.process(c));
		}

		@Test
		void omiteNombreUnknown() {
			assertThrows(InvalidDataException.class,
					() -> processor.process(base(114L, "Unknown", "1000", 30, "ahorro")));
		}

		@Test
		void omiteEdadFueraDeRango() {
			assertThrows(InvalidDataException.class,
					() -> processor.process(base(1L, "Ana", "1000", 17, "ahorro")));
			assertThrows(InvalidDataException.class,
					() -> processor.process(base(2L, "Ana", "1000", 101, "ahorro")));
		}

		@Test
		void omiteSaldoNoPositivo() {
			assertThrows(InvalidDataException.class,
					() -> processor.process(base(1L, "Ana", "0", 30, "ahorro")));
		}

		@Test
		void omiteTipoMalformadoNumerico() {
			assertThrows(InvalidDataException.class,
					() -> processor.process(base(1L, "Ana", "1000", 30, "-1")));
		}

		@Test
		void filtraHipotecaYUnknownSinError() {
			assertNull(processor.process(base(133L, "Alice Brown", "12000", 100, "hipoteca")));
			assertNull(processor.process(base(200L, "Carl", "5000", 40, "unknown")));
		}

		@Test
		void omiteDuplicado() {
			processor.process(base(1L, "Jane Smith", "12000", 40, "prestamo"));
			assertThrows(InvalidDataException.class,
					() -> processor.process(base(2L, "Jane Smith", "12000", 40, "prestamo")));
		}

		private CuentaInteres base(Long id, String nombre, String saldo, Integer edad, String tipo) {
			CuentaInteres c = new CuentaInteres();
			c.setCuentaId(id);
			c.setNombre(nombre);
			if (saldo != null) {
				c.setSaldo(new BigDecimal(saldo));
			}
			c.setEdad(edad);
			c.setTipo(tipo);
			return c;
		}
	}

	@Nested
	@DisplayName("EstadoCuentaItemProcessor")
	class Estados {

		private final EstadoCuentaItemProcessor processor = new EstadoCuentaItemProcessor();

		@Test
		void normalizaDepositoConTildeYClasificaIngreso() {
			MovimientoAnual m = base(103L, "depósito", "3000", "Retiro parcial");
			MovimientoAnual out = processor.process(m);
			assertEquals("deposito", out.getTransaccion());
			assertEquals("INGRESO", out.getClasificacion());
		}

		@Test
		void clasificaRetiroYCompraComoEgreso() {
			assertEquals("EGRESO", processor.process(base(1L, "retiro", "100", "x")).getClasificacion());
			assertEquals("EGRESO", processor.process(base(2L, "compra", "100", "x")).getClasificacion());
		}

		@Test
		void completaDescripcionVacia() {
			MovimientoAnual out = processor.process(base(110L, "retiro", "1500", null));
			assertEquals("Sin descripcion", out.getDescripcion());
		}

		@Test
		void omiteTipoPago() {
			assertThrows(InvalidDataException.class, () -> processor.process(base(1L, "pago", "100", "x")));
		}

		@Test
		void omiteMontoNoPositivo() {
			assertThrows(InvalidDataException.class, () -> processor.process(base(1L, "compra", "0", "x")));
		}

		@Test
		void omiteIncompleto() {
			MovimientoAnual m = base(120L, "compra", null, "Ingreso mensual");
			m.setMonto(null);
			assertThrows(InvalidDataException.class, () -> processor.process(m));
		}

		private MovimientoAnual base(Long cuentaId, String tipo, String monto, String desc) {
			MovimientoAnual m = new MovimientoAnual();
			m.setCuentaId(cuentaId);
			m.setFecha(LocalDate.of(2024, 3, 8));
			m.setTransaccion(tipo);
			if (monto != null) {
				m.setMonto(new BigDecimal(monto));
			}
			m.setDescripcion(desc);
			return m;
		}
	}
}
