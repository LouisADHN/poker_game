package fr.louis.poker.server;

import org.springframework.boot.SpringApplication;

public class TestPokerServerApplication {

	public static void main(String[] args) {
		SpringApplication.from(PokerServerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
