package com.banco.xyz.batch.processor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ItemProcessor;

import com.banco.xyz.batch.exception.InvalidDataException;
import com.banco.xyz.batch.support.TextNormalizer;
import com.banco.xyz.domain.CuentaInteres;

/**
 * Transformaciones y validaciones del cálculo de intereses mensuales.
 * Casos de error (InvalidDataException → SkipPolicy):
 * <ul>
 *   <li>Cuenta incompleta (cuentaId/nombre/edad/tipo nulos o vacíos)</li>
 *   <li>Nombre placeholder {@code Unknown}</li>
 *   <li>Edad fuera de rango 18–100</li>
 *   <li>Saldo nulo o no positivo</li>
 *   <li>Tipo malformado (numérico / basura, p. ej. {@code -1})</li>
 *   <li>Duplicados lógicos</li>
 * </ul>
 * Filtrado de negocio (return null, no es error):
 * <ul>
 *   <li>Tipos que no aplican interés mensual: {@code hipoteca}, {@code unknown}</li>
 * </ul>
 */
public class InteresItemProcessor implements ItemProcessor<CuentaInteres, CuentaInteres> {

	private static final Logger log = LoggerFactory.getLogger(InteresItemProcessor.class);

	private static final Map<String, BigDecimal> TASAS = Map.of(
			"ahorro", new BigDecimal("0.0100"),
			"prestamo", new BigDecimal("0.0150"));

	private static final Set<String> TIPOS_SIN_INTERES = Set.of("hipoteca", "unknown");

	private final ConcurrentMap<String, Long> firmas = new ConcurrentHashMap<>();

	public void reset() {
		firmas.clear();
	}

	@Override
	public CuentaInteres process(CuentaInteres item) {
		validarCompletitud(item);
		validarIdentidad(item);
		validarEdad(item);
		validarSaldo(item);

		String tipo = TextNormalizer.normalize(item.getTipo());
		item.setTipo(tipo);
		validarTipo(item, tipo);

		if (TIPOS_SIN_INTERES.contains(tipo)) {
			log.warn("Tipo de cuenta no aplica interes mensual (filtrada): {}", item);
			return null;
		}
		if (!TASAS.containsKey(tipo)) {
			throw new InvalidDataException("Cuenta " + item.getCuentaId() + " con tipo no reconocido: " + item.getTipo());
		}

		validarDuplicado(item, tipo);
		calcularInteres(item, tipo);

		log.info("[{}] Interes calculado cuenta {}: tasa={}, interes={}, saldoFinal={}",
				Thread.currentThread().getName(), item.getCuentaId(), item.getTasaInteres(),
				item.getInteresCalculado(), item.getSaldoFinal());
		return item;
	}

	private void validarCompletitud(CuentaInteres item) {
		if (item.getCuentaId() == null || item.getNombre() == null || item.getNombre().isBlank()
				|| item.getEdad() == null || item.getTipo() == null || item.getTipo().isBlank()) {
			throw new InvalidDataException("Cuenta incompleta: " + item);
		}
	}

	private void validarIdentidad(CuentaInteres item) {
		if ("unknown".equalsIgnoreCase(item.getNombre().trim())) {
			throw new InvalidDataException("Cuenta " + item.getCuentaId() + " con nombre invalido (Unknown)");
		}
		if (item.getCuentaId() <= 0) {
			throw new InvalidDataException("Cuenta con id no positivo: " + item.getCuentaId());
		}
	}

	private void validarEdad(CuentaInteres item) {
		if (item.getEdad() < 18 || item.getEdad() > 100) {
			throw new InvalidDataException("Cuenta " + item.getCuentaId() + " con edad invalida: " + item.getEdad());
		}
	}

	private void validarSaldo(CuentaInteres item) {
		if (item.getSaldo() == null || item.getSaldo().compareTo(BigDecimal.ZERO) <= 0) {
			throw new InvalidDataException("Cuenta " + item.getCuentaId() + " con saldo no positivo");
		}
	}

	private void validarTipo(CuentaInteres item, String tipo) {
		if (tipo.matches("-?\\d+") || tipo.isBlank()) {
			throw new InvalidDataException("Cuenta " + item.getCuentaId() + " con tipo malformado: " + item.getTipo());
		}
	}

	private void validarDuplicado(CuentaInteres item, String tipo) {
		String firma = TextNormalizer.normalize(item.getNombre()) + "|" + item.getSaldo() + "|" + tipo + "|"
				+ item.getEdad();
		Long existente = firmas.putIfAbsent(firma, item.getCuentaId());
		if (existente != null && !existente.equals(item.getCuentaId())) {
			throw new InvalidDataException("Cuenta duplicada: " + item);
		}
	}

	private void calcularInteres(CuentaInteres item, String tipo) {
		BigDecimal tasa = TASAS.get(tipo);
		BigDecimal interes = item.getSaldo().multiply(tasa).setScale(2, RoundingMode.HALF_UP);
		BigDecimal saldoFinal = item.getSaldo().add(interes).setScale(2, RoundingMode.HALF_UP);
		item.setTasaInteres(tasa);
		item.setInteresCalculado(interes);
		item.setSaldoFinal(saldoFinal);
	}
}
