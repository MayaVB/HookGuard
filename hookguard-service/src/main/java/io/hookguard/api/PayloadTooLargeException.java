package io.hookguard.api;

public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException(int actualBytes, int maxBytes) {
        super("Payload is " + actualBytes + " bytes; the maximum is " + maxBytes + " bytes");
    }
}
