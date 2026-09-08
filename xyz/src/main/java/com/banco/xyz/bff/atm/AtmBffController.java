package com.banco.xyz.bff.atm;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.banco.xyz.core.service.BankDataService;

/**
 * BFF Cajeros: operaciones críticas acotadas (saldo / retiro) con PIN por canal.
 * El path usa el {@code id} técnico de {@code cuentas_con_interes} (único).
 */
@RestController
@RequestMapping("/bff/atm")
public class AtmBffController {

	/** PIN demo del canal ATM (actividad formativa). */
	private static final String ATM_PIN_DEMO = "1234";

	private final BankDataService bankDataService;

	public AtmBffController(BankDataService bankDataService) {
		this.bankDataService = bankDataService;
	}

	@GetMapping("/ping")
	public Map<String, Object> ping() {
		return Map.of(
				"canal", "ATM",
				"estado", "OK",
				"operaciones", new String[] { "consulta-saldo", "retiro" });
	}

	@GetMapping("/saldo/{id}")
	public ResponseEntity<?> saldo(@PathVariable long id,
			@RequestHeader(value = "X-ATM-PIN", required = false) String pin) {
		if (!pinValido(id, pin)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
					.body(Map.of("canal", "ATM", "error", "PIN invalido o cuenta no habilitada en cajero"));
		}
		return bankDataService.saldoCuenta(id)
				.<ResponseEntity<?>>map(saldo -> {
					Map<String, Object> body = new LinkedHashMap<>();
					body.put("canal", "ATM");
					body.put("id", id);
					body.put("saldoDisponible", saldo);
					body.put("moneda", "CLP");
					return ResponseEntity.ok(body);
				})
				.orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
						.body(Map.of("canal", "ATM", "error", "Cuenta no encontrada")));
	}

	@PostMapping("/retiro")
	public ResponseEntity<?> retiro(@RequestBody RetiroRequest request,
			@RequestHeader(value = "X-ATM-PIN", required = false) String pin) {
		if (request == null || request.id() == null || request.monto() == null) {
			return ResponseEntity.badRequest()
					.body(Map.of("canal", "ATM", "error", "id y monto son obligatorios"));
		}
		if (!pinValido(request.id(), pin)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
					.body(Map.of("canal", "ATM", "error", "PIN invalido o cuenta no habilitada en cajero"));
		}
		try {
			Map<String, Object> result = bankDataService.retirar(request.id(), request.monto());
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("canal", "ATM");
			body.put("operacion", "RETIRO_OK");
			body.putAll(result);
			return ResponseEntity.ok(body);
		} catch (IllegalArgumentException ex) {
			return ResponseEntity.badRequest().body(Map.of("canal", "ATM", "error", ex.getMessage()));
		} catch (IllegalStateException ex) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("canal", "ATM", "error", ex.getMessage()));
		}
	}

	private boolean pinValido(long id, String pin) {
		if (!ATM_PIN_DEMO.equals(pin)) {
			return false;
		}
		return bankDataService.buscarCuentaPorId(id).isPresent();
	}

	public record RetiroRequest(Long id, BigDecimal monto) {
	}
}
