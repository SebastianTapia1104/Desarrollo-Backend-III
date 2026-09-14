package com.banco.xyz.bff.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.banco.xyz.bff.service.BankDataQueryService;

@RestController
@RequestMapping("/bff/web")
public class WebBffController {

	private final BankDataQueryService service;

	public WebBffController(BankDataQueryService service) {
		this.service = service;
	}

	@GetMapping("/dashboard")
	public WebDashboardResponse dashboard() {
		return new WebDashboardResponse("WEB", "Panel completo para navegador", service.summary(), service.accounts());
	}

	@GetMapping("/transacciones")
	public WebTransactionsResponse transacciones() {
		List<BankDataQueryService.WebTransaction> items = service.transactions();
		return new WebTransactionsResponse("WEB", items.size(), true, true, items);
	}

	@GetMapping("/intereses")
	public WebInterestResponse intereses() {
		List<BankDataQueryService.Account> accounts = service.accounts();
		return new WebInterestResponse("WEB", accounts.size(), true, accounts);
	}

	@GetMapping("/cuentas/{id}/estado-anual")
	public ResponseEntity<WebAnnualStateResponse> estadoAnual(@PathVariable long id) {
		return service.accountById(id).map(account -> {
			List<BankDataQueryService.Movement> movements = service.yearlyMovements(account.cuentaId());
			return ResponseEntity.ok(new WebAnnualStateResponse("WEB", account, movements.size(), movements));
		}).orElseGet(() -> ResponseEntity.notFound().build());
	}

	public record WebDashboardResponse(String canal, String descripcion, BankDataQueryService.Summary resumen,
			List<BankDataQueryService.Account> cuentas) {
	}

	public record WebTransactionsResponse(String canal, int total, boolean incluyeAnomalias, boolean incluyeObservaciones,
			List<BankDataQueryService.WebTransaction> data) {
	}

	public record WebInterestResponse(String canal, int total, boolean detalleCompleto, List<BankDataQueryService.Account> data) {
	}

	public record WebAnnualStateResponse(String canal, BankDataQueryService.Account cuenta, int totalMovimientos,
			List<BankDataQueryService.Movement> movimientos) {
	}
}
