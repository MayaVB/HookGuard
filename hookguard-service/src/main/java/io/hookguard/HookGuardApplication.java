package io.hookguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HookGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(HookGuardApplication.class, args);
    }
}
