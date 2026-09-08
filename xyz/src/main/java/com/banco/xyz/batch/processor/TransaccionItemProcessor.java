package com.banco.xyz.batch.processor;

import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ItemProcessor;

import com.banco.xyz.batch.exception.InvalidDataException;
import com.banco.xyz.batch.support.TextNormalizer;
import com.banco.xyz.domain.Transaccion;

/**
 * Transformaciones y validaciones del reporte de transacciones diarias.
 * Casos de error cubiertos (lanzan {@link InvalidDataException} → SkipPolicy):
 * <ul>
 *   <li>Registro incompleto (id/fecha/monto/tipo nulos o vacíos)</li>
 *   <li>Id no positivo</li>
 *   <li>Tipo inválido ({@code invalid}, {@code desconocido}, u otros fuera de debito/credito)</li>
 *   <li>Duplicados lógicos (misma fecha+monto+tipo con distinto id)</li>
 * </ul>
 * Anomalías de negocio (NO se omiten; se marcan y persisten):
 * <ul>
 *   <li>Monto negativo</li>
 *   <li>Monto cero</li>
 * </ul>
 */
public class TransaccionItemProcessor implements ItemProcessor<Transaccion, Transaccion> {

	private static final Logger log = LoggerFactory.getLogger(TransaccionItemProcessor.class);
	private static final Set<String> TIPOS_VALIDOS = Set.of("debito", "credito");
	private static final Set<String> TIPOS_INVALIDOS_CONOCIDOS = Set.of("invalid", "desconocido");

	private final ConcurrentMap<String, Long> vistos = new ConcurrentHashMap<>();

	public void reset() {
		vistos.clear();
	}

	@Override
	public Transaccion process(Transaccion item) {
		validarCompletitud(item);
		validarIdentificador(item);

		String tipo = TextNormalizer.normalize(item.getTipo());
		validarTipo(item, tipo);
		item.setTipo(tipo);

		validarDuplicado(item, tipo);
		marcarAnomaliaMonto(item);

		log.info("[{}] Transaccion procesada: id={}, tipo={}, monto={}, anomalia={}",
				Thread.currentThread().getName(), item.getId(), item.getTipo(), item.getMonto(), item.isAnomalia());
		return item;
	}

	private void validarCompletitud(Transaccion item) {
		if (item.getId() == null || item.getFecha() == null || item.getMonto() == null
				|| item.getTipo() == null || item.getTipo().isBlank()) {
			throw new InvalidDataException("Transaccion incompleta: " + item);
		}
	}

	private void validarIdentificador(Transaccion item) {
		if (item.getId() <= 0) {
			throw new InvalidDataException("Transaccion con id no positivo: " + item.getId());
		}
	}

	private void validarTipo(Transaccion item, String tipo) {
		if (TIPOS_INVALIDOS_CONOCIDOS.contains(tipo) || !TIPOS_VALIDOS.contains(tipo)) {
			throw new InvalidDataException("Transaccion " + item.getId() + " con tipo no valido: " + item.getTipo());
		}
	}

	private void validarDuplicado(Transaccion item, String tipo) {
		String claveDuplicado = item.getFecha() + "|" + item.getMonto() + "|" + tipo;
		Long existente = vistos.putIfAbsent(claveDuplicado, item.getId());
		if (existente != null && !existente.equals(item.getId())) {
			throw new InvalidDataException("Transaccion duplicada omitida: " + item);
		}
	}

	private void marcarAnomaliaMonto(Transaccion item) {
		if (item.getMonto().compareTo(BigDecimal.ZERO) < 0) {
			item.setAnomalia(true);
			item.setObservacion("Monto negativo detectado");
			log.warn("Anomalia marcada (no se omite): {}", item);
		} else if (item.getMonto().compareTo(BigDecimal.ZERO) == 0) {
			item.setAnomalia(true);
			item.setObservacion("Monto cero detectado");
			log.warn("Anomalia marcada (no se omite): {}", item);
		} else {
			item.setAnomalia(false);
			item.setObservacion("OK");
		}
	}
}
