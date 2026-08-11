package com.angatusistemas.whatsapp.cli;

/** A required {@code --flag} was missing or a command was invoked with an argument it does not understand. */
final class CliArgumentException extends RuntimeException {

    CliArgumentException(String message) {
        super(message);
    }
}
