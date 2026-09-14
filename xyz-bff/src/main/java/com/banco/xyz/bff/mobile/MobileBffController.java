package com.banco.xyz.bff.mobile;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.banco.xyz.bff.service.BankDataQueryService;

@RestController
@RequestMapping("/bff/mobile")
public class MobileBffController {

	private final BankDataQueryService service;

	public MobileBffController(BankDataQueryService service) {
		this.service = service;
	}

	@GetMapping("/home")
	public MobileHomeResponse home(@RequestParam(defaultValue = "5") int limit) {
		int safeLimit = Math.min(Math.max(limit, 1), 10);
		BankDataQueryService.Summary summary = service.summary();
		List<BankDataQueryService.MobileTransaction> items = service.recentTransactions(safeLimit);
		return new MobileHomeResponse("MOBILE", summary.cuentasConInteres(), items.size(), items);
	}

	@GetMapping("/cuentas/{id}/resumen")
	public ResponseEntity<MobileAccountSummaryResponse> resumenCuenta(@PathVariable long id) {
		return service.accountById(id).map(account -> ResponseEntity.ok(
				new MobileAccountSummaryResponse("MOBILE", account.id(), account.cuentaId(), account.nombre(),
						account.saldoFinal(), account.tipo())))
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	@GetMapping("/cuentas")
	public MobileAccountsResponse cuentasLivianas() {
		List<MobileAccountLight> data = service.accounts().stream()
				.map(account -> new MobileAccountLight(account.id(), account.saldoFinal(), account.tipo()))
				.toList();
		return new MobileAccountsResponse("MOBILE", data.size(), List.of("id", "saldo", "tipo"), data);
	}

	public record MobileHomeResponse(String canal, int cuentasActivas, int txRecientes,
			List<BankDataQueryService.MobileTransaction> items) {
	}

	public record MobileAccountSummaryResponse(String canal, long id, long cuentaId, String nombre,
			java.math.BigDecimal saldo, String tipo) {
	}

	public record MobileAccountsResponse(String canal, int total, List<String> campos, List<MobileAccountLight> data) {
	}

	public record MobileAccountLight(long id, java.math.BigDecimal saldo, String tipo) {
	}
}
