package com.banco.xyz.batch.policy;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.stereotype.Component;

/**
 * Política de reintento personalizada (RetryPolicy).
 * Escenarios de fallo transitorio cubiertos (hasta {@code bank.batch.retry-limit} intentos):
 * <ul>
 *   <li>{@link TransientDataAccessException} — fallos transitorios genéricos de acceso a datos</li>
 *   <li>{@link CannotGetJdbcConnectionException} — pool/conexión temporalmente no disponible</li>
 *   <li>{@link QueryTimeoutException} — timeout de consulta</li>
 *   <li>{@link DeadlockLoserDataAccessException} — deadlock en BD</li>
 *   <li>{@link CannotAcquireLockException} — no se pudo adquirir lock</li>
 *   <li>{@link OptimisticLockingFailureException} — conflicto de versión concurrente</li>
 *   <li>{@link RecoverableDataAccessException} — error recuperable del driver/BD</li>
 *   <li>{@link DataAccessResourceFailureException} — recurso de datos temporalmente caído</li>
 * </ul>
 * Los errores de datos inválidos NO se reintentan: los gestiona {@link FileVerificationSkipper}.
 */
@Component
public class CustomRetryPolicy implements RetryPolicy {

	private static final Logger log = LoggerFactory.getLogger(CustomRetryPolicy.class);

	private final SimpleRetryPolicy delegate;
	private final int retryLimit;

	public CustomRetryPolicy(@Value("${bank.batch.retry-limit:3}") int retryLimit) {
		this.retryLimit = retryLimit;
		Map<Class<? extends Throwable>, Boolean> retryables = new HashMap<>();
		retryables.put(TransientDataAccessException.class, true);
		retryables.put(CannotGetJdbcConnectionException.class, true);
		retryables.put(QueryTimeoutException.class, true);
		retryables.put(DeadlockLoserDataAccessException.class, true);
		retryables.put(CannotAcquireLockException.class, true);
		retryables.put(OptimisticLockingFailureException.class, true);
		retryables.put(RecoverableDataAccessException.class, true);
		retryables.put(DataAccessResourceFailureException.class, true);
		this.delegate = new SimpleRetryPolicy(retryLimit, retryables, true);
		log.info("CustomRetryPolicy inicializada: limit={}, escenarios={}", retryLimit, retryables.keySet());
	}

	@Override
	public boolean canRetry(RetryContext context) {
		boolean retry = delegate.canRetry(context);
		if (retry && esReintentable(context.getLastThrowable())) {
			log.warn("CustomRetryPolicy - reintento {}/{} causa={}", context.getRetryCount(), retryLimit,
					String.valueOf(context.getLastThrowable()));
		}
		return retry;
	}

	@Override
	public RetryContext open(RetryContext parent) {
		return delegate.open(parent);
	}

	@Override
	public void close(RetryContext context) {
		delegate.close(context);
	}

	@Override
	public void registerThrowable(RetryContext context, Throwable throwable) {
		if (esReintentable(throwable)) {
			log.warn("CustomRetryPolicy - registrando fallo transitorio ({}/{}): {}",
					context.getRetryCount() + 1, retryLimit, throwable.toString());
		} else if (throwable != null) {
			log.debug("CustomRetryPolicy - fallo no reintentable (irá a SkipPolicy si aplica): {}",
					throwable.getClass().getSimpleName());
		}
		delegate.registerThrowable(context, throwable);
	}

	private boolean esReintentable(Throwable t) {
		Throwable actual = t;
		while (actual != null) {
			if (actual instanceof TransientDataAccessException
					|| actual instanceof CannotGetJdbcConnectionException
					|| actual instanceof QueryTimeoutException
					|| actual instanceof DeadlockLoserDataAccessException
					|| actual instanceof CannotAcquireLockException
					|| actual instanceof OptimisticLockingFailureException
					|| actual instanceof RecoverableDataAccessException
					|| actual instanceof DataAccessResourceFailureException) {
				return true;
			}
			actual = actual.getCause();
		}
		return false;
	}
}
