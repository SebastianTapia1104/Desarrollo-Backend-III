package com.banco.xyz.h2;

import org.h2.tools.Server;

public class H2ServerApplication {

	public static void main(String[] args) throws Exception {
		Server tcp = Server.createTcpServer("-tcp", "-tcpAllowOthers", "-tcpPort", "9092", "-ifNotExists", "-baseDir",
				"/data").start();
		Runtime.getRuntime().addShutdownHook(new Thread(tcp::stop));
		Thread.currentThread().join();
	}
}
