package io.hookguard.api;

/** A request field is syntactically valid but semantically unacceptable. Mapped to 400. */
public class InvalidFieldException extends RuntimeException {

    private final String field;

    public InvalidFieldException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
