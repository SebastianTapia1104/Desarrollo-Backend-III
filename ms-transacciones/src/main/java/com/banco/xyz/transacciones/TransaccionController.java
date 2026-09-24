package com.banco.xyz.transacciones;

import java.math.BigDecimal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class TransaccionController {

	private final RetiroService retiros;

	public TransaccionController(RetiroService retiros) {
		this.retiros = retiros;
	}

	@GetMapping("/transacciones")
	public Object listar() {
		return retiros.listar();
	}

	@PostMapping("/transacciones/retiros")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public RetiroService.RetiroView crear(@RequestBody RetiroRequest request) {
		if (request.cuentaId() == null || request.monto() == null || request.monto().signum() <= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cuentaId y monto positivo son obligatorios");
		}
		return retiros.solicitar(request.cuentaId(), request.monto(), Boolean.TRUE.equals(request.simularFallo()),
				Boolean.TRUE.equals(request.simularDuplicado()));
	}

	@GetMapping("/transacciones/retiros/{id}")
	public RetiroService.RetiroView ver(@PathVariable long id) {
		return retiros.buscar(id);
	}

	public record RetiroRequest(Long cuentaId, BigDecimal monto, Boolean simularFallo, Boolean simularDuplicado) {
	}
}
