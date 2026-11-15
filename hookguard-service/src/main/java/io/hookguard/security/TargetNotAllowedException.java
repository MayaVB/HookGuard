package io.hookguard.security;

public class TargetNotAllowedException extends RuntimeException {

    public TargetNotAllowedException(String reason) {
        super(reason);
    }
}
