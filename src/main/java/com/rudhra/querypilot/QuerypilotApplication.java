package com.rudhra.querypilot;

import java.util.TimeZone;

import com.rudhra.querypilot.config.SandboxProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(SandboxProperties.class)
public class QuerypilotApplication {

	public static void main(String[] args) {
		// Windows maps "India Standard Time" to the legacy id "Asia/Calcutta",
		// which the Postgres server's tzdata no longer recognises. Force a
		// canonical zone before any JDBC connection is opened.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(QuerypilotApplication.class, args);
	}

}
