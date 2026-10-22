package com.betterf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
public class BetterfApplication {

	public static void main(String[] args) {
		SpringApplication.run(BetterfApplication.class, args);
	}

}
