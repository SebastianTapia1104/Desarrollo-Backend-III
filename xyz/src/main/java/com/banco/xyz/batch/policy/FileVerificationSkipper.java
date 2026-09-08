package com.banco.xyz.batch.policy;

import java.time.format.DateTimeParseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.batch.core.step.skip.SkipPolicy;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.banco.xyz.batch.exception.InvalidDataException;

/**
 * Política de omisión personalizada (SkipPolicy) — tolerancia a fallos de datos.
 * Escenarios omitibles (hasta {@code bank.batch.skip-limit}):
 * <ul>
 *   <li>{@link InvalidDataException} — validaciones de negocio del ItemProcessor</li>
 *   <li>{@link FlatFileParseException} — línea CSV mal formada / mapper</li>
 *   <li>{@link DateTimeParseException} — fecha ilegible en lectura</li>
 *   <li>{@link NumberFormatException} — id/monto/edad no numéricos</li>
 *   <li>{@link IllegalArgumentException} — argumentos inconsistentes en parseo</li>
 * </ul>
 * Si se supera el límite, el Step falla (no se silencia el problema).
 */
@Component
public class FileVerificationSkipper implements SkipPolicy {

	private static final Logger log = LoggerFactory.getLogger(FileVerificationSkipper.class);

	private final int skipLimit;

	public FileVerificationSkipper(@Value("${bank.batch.skip-limit:50}") int skipLimit) {
		this.skipLimit = skipLimit;
		log.info("FileVerificationSkipper inicializado: skipLimit={}", skipLimit);
	}

	@Override
	public boolean shouldSkip(Throwable t, long skipCount) {
		if (skipCount >= skipLimit) {
			log.error("CustomSkipPolicy - se alcanzo el limite de omisiones ({})", skipLimit);
			throw new SkipLimitExceededException(skipLimit, t);
		}

		if (esOmisible(t)) {
			log.warn("CustomSkipPolicy - Excepcion omitida ({}/{}): {} - {}",
					skipCount + 1, skipLimit, t.getClass().getSimpleName(), mensaje(t));
			return true;
		}

		log.error("CustomSkipPolicy - excepcion no omitible (puede reintentarse si es transitoria): {}", t.toString());
		return false;
	}

	private boolean esOmisible(Throwable t) {
		Throwable actual = t;
		while (actual != null) {
			if (actual instanceof InvalidDataException
					|| actual instanceof FlatFileParseException
					|| actual instanceof DateTimeParseException
					|| actual instanceof NumberFormatException
					|| actual instanceof IllegalArgumentException) {
				return true;
			}
			actual = actual.getCause();
		}
		return false;
	}

	private String mensaje(Throwable t) {
		if (t instanceof FlatFileParseException parseEx) {
			return parseEx.getMessage() + " | input=[" + parseEx.getInput() + "]";
		}
		return t.getMessage();
	}
}
