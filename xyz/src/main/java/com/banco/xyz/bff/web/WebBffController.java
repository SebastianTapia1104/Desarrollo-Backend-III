package com.banco.xyz.bff.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.banco.xyz.core.service.BankDataService;

/**
 * BFF Web: respuestas completas para interfaces de escritorio/navegador.
 */
@RestController
@RequestMapping("/bff/web")
public class WebBffController {

	private final BankDataService bankDataService;

	public WebBffController(BankDataService bankDataService) {
		this.bankDataService = bankDataService;
	}

	@GetMapping("/dashboard")
	public Map<String, Object> dashboard() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("canal", "WEB");
		body.put("descripcion", "Panel completo con metricas, cuentas e historial detallado");
		body.put("resumen", bankDataService.resumenGlobal());
		body.put("cuentas", bankDataService.listarCuentasConInteres());
		body.put("auditoriaPorCuenta", bankDataService.resumenMovimientosPorCuenta());
		return body;
	}

	@GetMapping("/transacciones")
	public Map<String, Object> transacciones() {
		List<Map<String, Object>> data = bankDataService.listarTransacciones();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("canal", "WEB");
		body.put("total", data.size());
		body.put("incluyeAnomalias", true);
		body.put("incluyeObservaciones", true);
		body.put("data", data);
		return body;
	}

	@GetMapping("/intereses")
	public Map<String, Object> intereses() {
		List<Map<String, Object>> data = bankDataService.listarCuentasConInteres();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("canal", "WEB");
		body.put("total", data.size());
		body.put("detalleCompleto", true);
		body.put("data", data);
		return body;
	}

	@GetMapping("/cuentas/{id}/estado-anual")
	public ResponseEntity<Map<String, Object>> estadoAnual(@PathVariable long id) {
		return bankDataService.buscarCuentaPorId(id)
				.map(cuenta -> {
					long cuentaId = ((Number) cuenta.get("cuenta_id")).longValue();
					List<Map<String, Object>> movimientos = bankDataService.movimientosAnuales(cuentaId);
					Map<String, Object> body = new LinkedHashMap<>();
					body.put("canal", "WEB");
					body.put("cuenta", cuenta);
					body.put("movimientos", movimientos);
					body.put("totalMovimientos", movimientos.size());
					return ResponseEntity.ok(body);
				})
				.orElseGet(() -> ResponseEntity.notFound().build());
	}
}
