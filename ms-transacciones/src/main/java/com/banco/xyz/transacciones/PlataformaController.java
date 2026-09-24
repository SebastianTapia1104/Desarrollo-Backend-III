package com.banco.xyz.transacciones;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

@RestController
public class PlataformaController {

	private final CircuitBreaker circuito;
	private final RestClient peer;
	private final String configSource;
	private final String servicio = "ms-transacciones";

	public PlataformaController(CircuitBreaker circuito, RestClient peer,
			@Value("${bank.config-source}") String configSource) {
		this.circuito = circuito;
		this.peer = peer;
		this.configSource = configSource;
	}

	@GetMapping("/transacciones/origen-config")
	public Map<String, String> origen() {
		return Map.of("servicio", servicio, "configSource", configSource);
	}

	@GetMapping("/interno/ping")
	public Map<String, String> ping() {
		return Map.of("servicio", servicio, "estado", "UP");
	}

	@GetMapping("/transacciones/salud-dependencia")
	public Map<String, String> salud(@RequestParam(defaultValue = "false") boolean forzarFallo) {
		try {
			String respuesta = circuito.executeSupplier(() -> {
				if (forzarFallo) {
					RestClient.create().get().uri("http://127.0.0.1:9/no").retrieve().toBodilessEntity();
				}
				return peer.get().uri("/interno/ping").retrieve().body(String.class);
			});
			return Map.of("estado", "OK", "circuito", circuito.getState().name(), "respuesta",
					respuesta == null ? "" : respuesta);
		}
		catch (Exception ex) {
			return Map.of("estado", "FALLBACK", "circuito", circuito.getState().name(), "detalle",
					ex.getClass().getSimpleName());
		}
	}
}
