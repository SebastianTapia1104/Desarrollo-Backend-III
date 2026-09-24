package com.banco.xyz.transacciones;

import org.apache.activemq.broker.BrokerService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MsTransaccionesApplication {

	public static void main(String[] args) throws Exception {
		BrokerService broker = new BrokerService();
		broker.setPersistent(false);
		broker.setUseJmx(false);
		broker.setUseShutdownHook(false);
		broker.addConnector("tcp://127.0.0.1:61616");
		broker.start();
		SpringApplication.run(MsTransaccionesApplication.class, args);
	}
}
