package com.banco.xyz.batch.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.retry.RetryContext;

import com.banco.xyz.batch.exception.InvalidDataException;

/**
 * Cobertura de escenarios de omisión y reintento (criterio Semana 3: tolerancia a fallos).
 */
class FaultTolerancePolicyTest {

	@Test
	@DisplayName("SkipPolicy omite todos los escenarios de datos inválidos")
	void skipPolicyCubreEscenariosDeDatos() {
		FileVerificationSkipper skipper = new FileVerificationSkipper(10);
		assertTrue(skipper.shouldSkip(new InvalidDataException("dato malo"), 0));
		assertTrue(skipper.shouldSkip(new FlatFileParseException("parse", "1,bad,line"), 1));
		assertTrue(skipper.shouldSkip(new NumberFormatException("abc"), 2));
		assertTrue(skipper.shouldSkip(new IllegalArgumentException("arg"), 3));
		assertFalse(skipper.shouldSkip(new RuntimeException("no omisible"), 4));
	}

	@Test
	@DisplayName("SkipPolicy falla al superar el límite")
	void skipPolicyRespetaLimite() {
		FileVerificationSkipper skipper = new FileVerificationSkipper(2);
		assertTrue(skipper.shouldSkip(new InvalidDataException("1"), 0));
		assertTrue(skipper.shouldSkip(new InvalidDataException("2"), 1));
		assertThrows(SkipLimitExceededException.class,
				() -> skipper.shouldSkip(new InvalidDataException("3"), 2));
	}

	@Test
	@DisplayName("RetryPolicy reintenta todos los escenarios transitorios de BD")
	void retryPolicyCubreEscenariosTransitorios() {
		CustomRetryPolicy policy = new CustomRetryPolicy(3);
		assertCanRetry(policy, new TransientDataAccessResourceException("transient"));
		assertCanRetry(policy, new CannotGetJdbcConnectionException("conn"));
		assertCanRetry(policy, new QueryTimeoutException("timeout"));
		assertCanRetry(policy, new DeadlockLoserDataAccessException("deadlock", null));
		assertCanRetry(policy, new CannotAcquireLockException("lock"));
		assertCanRetry(policy, new OptimisticLockingFailureException("optimistic"));
		assertCanRetry(policy, new DataAccessResourceFailureException("resource"));

		RetryContext ctx = policy.open(null);
		policy.registerThrowable(ctx, new InvalidDataException("no reintentar"));
		assertFalse(policy.canRetry(ctx), "Los errores de datos no deben reintentarse");
		policy.close(ctx);
	}

	private void assertCanRetry(CustomRetryPolicy policy, Throwable error) {
		RetryContext ctx = policy.open(null);
		policy.registerThrowable(ctx, error);
		assertTrue(policy.canRetry(ctx), "Debe reintentar: " + error.getClass().getSimpleName());
		policy.close(ctx);
	}
}
