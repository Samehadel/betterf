package com.theeverthread;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
public class TheeverthreadApplication {

	public static void main(String[] args) {
		SpringApplication.run(TheeverthreadApplication.class, args);
	}

}
