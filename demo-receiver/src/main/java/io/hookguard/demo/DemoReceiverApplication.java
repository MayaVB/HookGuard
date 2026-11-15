package io.hookguard.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A stand-in for a third-party webhook receiver, used to demonstrate how HookGuard behaves when
 * the receiver is healthy, broken, slow, flaky or completely down. It shares no code with HookGuard.
 */
@SpringBootApplication
public class DemoReceiverApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoReceiverApplication.class, args);
    }
}
