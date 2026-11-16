package com.angatusistemas.whatsapp.cli;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Parses {@code --name value} pairs, the only syntax the command line accepts.
 *
 * @author Angatu Sistemas
 */
final class CliArguments {

    private final Map<String, String> options;

    private CliArguments(Map<String, String> options) {
        this.options = options;
    }

    static CliArguments parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            String token = args[i];
            if (!token.startsWith("--") || token.length() == 2) {
                throw new IllegalArgumentException("argumento inesperado: " + token);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("falta o valor de " + token);
            }
            options.put(token.substring(2), args[i + 1]);
        }
        return new CliArguments(options);
    }

    String require(String name) {
        return optional(name).orElseThrow(() -> new IllegalArgumentException("--" + name + " é obrigatório"));
    }

    Optional<String> optional(String name) {
        String value = options.get(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
