package com.banco.xyz.backend;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import com.banco.xyz.backend.DatosService.Cuenta;
import com.banco.xyz.backend.DatosService.Movimiento;
import com.banco.xyz.backend.DatosService.Resumen;
import com.banco.xyz.backend.DatosService.Retiro;
import com.banco.xyz.backend.DatosService.Transaccion;
import com.banco.xyz.backend.DatosService.TransaccionReciente;

@RestController
public class DatosController {

	private final DatosService datos;

	public DatosController(DatosService datos) {
		this.datos = datos;
	}

	@GetMapping("/api/resumen")
	public Resumen resumen() {
		return datos.resumen();
	}

	@GetMapping("/api/transacciones")
	public List<Transaccion> transacciones() {
		return datos.transacciones();
	}

	@GetMapping("/api/transacciones/recientes")
	public List<TransaccionReciente> recientes(@RequestParam(defaultValue = "5") int limit) {
		int safeLimit = Math.min(Math.max(limit, 1), 10);
		return datos.transaccionesRecientes(safeLimit);
	}

	@GetMapping("/api/cuentas")
	public List<Cuenta> cuentas() {
		return datos.cuentas();
	}

	@GetMapping("/api/cuentas/{id}")
	public Cuenta cuenta(@PathVariable long id) {
		return datos.cuenta(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cuenta no encontrada"));
	}

	@GetMapping("/api/cuentas/{id}/movimientos")
	public List<Movimiento> movimientos(@PathVariable long id) {
		try {
			return datos.movimientos(id);
		}
		catch (IllegalArgumentException ex) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
		}
	}

	@PostMapping("/api/retiros")
	public Retiro retirar(@RequestBody RetiroRequest request) {
		if (request == null || request.id() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id y monto son obligatorios");
		}
		try {
			return datos.retirar(request.id(), request.monto());
		}
		catch (IllegalArgumentException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
		}
		catch (IllegalStateException ex) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
		}
	}

	public record RetiroRequest(Long id, BigDecimal monto) {
	}
}

@RestControllerAdvice
class DatosErrors {

	@ExceptionHandler(ResponseStatusException.class)
	org.springframework.http.ResponseEntity<Map<String, String>> estado(ResponseStatusException ex) {
		String mensaje = ex.getReason() == null ? ex.getStatusCode().toString() : ex.getReason();
		return org.springframework.http.ResponseEntity.status(ex.getStatusCode()).body(Map.of("error", mensaje));
	}
}
