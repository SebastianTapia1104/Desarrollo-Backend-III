package com.banco.xyz.bff.mobile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.banco.xyz.core.service.BankDataService;

/**
 * BFF Móvil: payloads livianos (solo campos esenciales) para ahorrar ancho de banda.
 */
@RestController
@RequestMapping("/bff/mobile")
public class MobileBffController {

	private final BankDataService bankDataService;

	public MobileBffController(BankDataService bankDataService) {
		this.bankDataService = bankDataService;
	}

	@GetMapping("/home")
	public Map<String, Object> home(@RequestParam(defaultValue = "5") int limit) {
		int safeLimit = Math.min(Math.max(limit, 1), 10);
		Map<String, Object> resumen = bankDataService.resumenGlobal();
		List<Map<String, Object>> recientes = bankDataService.listarTransaccionesRecientes(safeLimit);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("canal", "MOBILE");
		body.put("cuentasActivas", resumen.get("cuentasConInteres"));
		body.put("txRecientes", recientes.size());
		body.put("items", recientes);
		return body;
	}

	@GetMapping("/cuentas/{id}/resumen")
	public ResponseEntity<Map<String, Object>> resumenCuenta(@PathVariable long id) {
		return bankDataService.buscarCuentaPorId(id)
				.map(cuenta -> {
					Map<String, Object> light = new LinkedHashMap<>();
					light.put("canal", "MOBILE");
					light.put("id", cuenta.get("id"));
					light.put("cuentaId", cuenta.get("cuenta_id"));
					light.put("nombre", cuenta.get("nombre"));
					light.put("saldo", cuenta.get("saldo_final"));
					light.put("tipo", cuenta.get("tipo"));
					return ResponseEntity.ok(light);
				})
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	@GetMapping("/cuentas")
	public Map<String, Object> cuentasLivianas() {
		List<Map<String, Object>> light = bankDataService.listarCuentasConInteres().stream()
				.map(c -> {
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("id", c.get("id"));
					row.put("saldo", c.get("saldo_final"));
					row.put("tipo", c.get("tipo"));
					return row;
				})
				.collect(Collectors.toList());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("canal", "MOBILE");
		body.put("total", light.size());
		body.put("campos", List.of("id", "saldo", "tipo"));
		body.put("data", light);
		return body;
	}
}
