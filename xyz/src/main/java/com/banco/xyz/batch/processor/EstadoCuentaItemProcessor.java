package com.banco.xyz.batch.processor;

import java.math.BigDecimal;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ItemProcessor;

import com.banco.xyz.batch.exception.InvalidDataException;
import com.banco.xyz.batch.support.TextNormalizer;
import com.banco.xyz.domain.MovimientoAnual;

/**
 * Transformaciones y validaciones de estados de cuenta anuales.
 * Casos de error (InvalidDataException → SkipPolicy):
 * <ul>
 *   <li>Movimiento incompleto (cuentaId/fecha/monto/transaccion)</li>
 *   <li>CuentaId no positivo</li>
 *   <li>Tipo no válido (p. ej. {@code pago}) — solo deposito/retiro/compra</li>
 *   <li>Monto nulo o no positivo</li>
 * </ul>
 * Transformaciones:
 * <ul>
 *   <li>Normaliza tildes: {@code depósito} → {@code deposito}</li>
 *   <li>Descripción vacía → {@code Sin descripcion}</li>
 *   <li>Clasificación INGRESO/EGRESO</li>
 * </ul>
 */
public class EstadoCuentaItemProcessor implements ItemProcessor<MovimientoAnual, MovimientoAnual> {

	private static final Logger log = LoggerFactory.getLogger(EstadoCuentaItemProcessor.class);
	private static final Set<String> TIPOS_VALIDOS = Set.of("deposito", "retiro", "compra");
	private static final Set<String> TIPOS_INVALIDOS_CONOCIDOS = Set.of("pago");

	@Override
	public MovimientoAnual process(MovimientoAnual item) {
		validarCompletitud(item);
		if (item.getCuentaId() <= 0) {
			throw new InvalidDataException("Movimiento anual con cuentaId no positivo: " + item.getCuentaId());
		}

		String tipo = TextNormalizer.normalize(item.getTransaccion());
		if (TIPOS_INVALIDOS_CONOCIDOS.contains(tipo) || !TIPOS_VALIDOS.contains(tipo)) {
			throw new InvalidDataException("Tipo de transaccion anual no valido: " + item.getTransaccion());
		}
		item.setTransaccion(tipo);

		if (item.getMonto().compareTo(BigDecimal.ZERO) <= 0) {
			throw new InvalidDataException(
					"Monto no positivo en estado de cuenta (cuenta " + item.getCuentaId() + "): " + item.getMonto());
		}

		if (item.getDescripcion() == null || item.getDescripcion().isBlank()) {
			item.setDescripcion("Sin descripcion");
		} else {
			item.setDescripcion(item.getDescripcion().trim());
		}

		item.setClasificacion(clasificar(tipo));
		log.info("[{}] Movimiento anual procesado: cuenta={}, tipo={}, monto={}, clasificacion={}",
				Thread.currentThread().getName(), item.getCuentaId(), tipo, item.getMonto(), item.getClasificacion());
		return item;
	}

	private void validarCompletitud(MovimientoAnual item) {
		if (item.getCuentaId() == null || item.getFecha() == null || item.getMonto() == null
				|| item.getTransaccion() == null || item.getTransaccion().isBlank()) {
			throw new InvalidDataException("Movimiento anual incompleto: " + item);
		}
	}

	private String clasificar(String tipo) {
		return switch (tipo) {
			case "deposito" -> "INGRESO";
			case "retiro", "compra" -> "EGRESO";
			default -> "OTRO";
		};
	}
}
