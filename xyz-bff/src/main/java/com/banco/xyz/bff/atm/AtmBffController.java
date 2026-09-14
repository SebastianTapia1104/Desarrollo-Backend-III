package com.banco.xyz.bff.atm;

import java.math.BigDecimal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.banco.xyz.bff.security.AtmPinService;
import com.banco.xyz.bff.service.BankDataQueryService;
import com.banco.xyz.bff.service.BankDataQueryService.Withdrawal;

@RestController
@RequestMapping("/bff/atm")
public class AtmBffController {

	private final BankDataQueryService service;
	private final AtmPinService pinService;

	public AtmBffController(BankDataQueryService service, AtmPinService pinService) {
		this.service = service;
		this.pinService = pinService;
	}

	@GetMapping("/ping")
	public AtmPingResponse ping() {
		return new AtmPingResponse("ATM", "OK", new String[] { "consulta-saldo", "retiro" });
	}

	@GetMapping("/saldo/{id}")
	public ResponseEntity<?> saldo(@PathVariable long id,
			@RequestHeader(value = "X-ATM-PIN", required = false) String pin) {
		if (!pinService.isValidPin(id, pin)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
					.body(new AtmErrorResponse("ATM", "PIN invalido para la cuenta"));
		}
		return service.accountById(id)
				.<ResponseEntity<?>>map(account -> ResponseEntity
						.ok(new AtmBalanceResponse("ATM", account.id(), account.cuentaId(), account.saldoFinal(), "CLP")))
				.orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
						.body(new AtmErrorResponse("ATM", "Cuenta no encontrada")));
	}

	@PostMapping("/retiro")
	public ResponseEntity<?> retiro(@RequestBody AtmWithdrawalRequest request,
			@RequestHeader(value = "X-ATM-PIN", required = false) String pin) {
		if (request == null || request.id() == null || request.monto() == null) {
			return ResponseEntity.badRequest().body(new AtmErrorResponse("ATM", "id y monto son obligatorios"));
		}
		if (!pinService.isValidPin(request.id(), pin)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
					.body(new AtmErrorResponse("ATM", "PIN invalido para la cuenta"));
		}
		try {
			Withdrawal result = service.withdraw(request.id(), request.monto());
			return ResponseEntity.ok(new AtmWithdrawalResponse("ATM", "RETIRO_OK", result));
		} catch (IllegalArgumentException ex) {
			return ResponseEntity.badRequest().body(new AtmErrorResponse("ATM", ex.getMessage()));
		} catch (IllegalStateException ex) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(new AtmErrorResponse("ATM", ex.getMessage()));
		}
	}

	public record AtmPingResponse(String canal, String estado, String[] operaciones) {
	}

	public record AtmBalanceResponse(String canal, long id, long cuentaId, BigDecimal saldoDisponible, String moneda) {
	}

	public record AtmWithdrawalRequest(Long id, BigDecimal monto) {
	}

	public record AtmWithdrawalResponse(String canal, String operacion, Withdrawal data) {
	}

	public record AtmErrorResponse(String canal, String error) {
	}
}
