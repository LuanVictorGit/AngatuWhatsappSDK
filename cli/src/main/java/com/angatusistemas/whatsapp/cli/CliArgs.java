package com.angatusistemas.whatsapp.cli;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal {@code --flag value} argument parser. Deliberately hand-rolled
 * instead of pulling in a full CLI framework dependency (e.g. picocli) -
 * this module's own pom.xml describes it as existing purely for command-line
 * convenience over the SDK, and four flags per command does not justify an
 * extra dependency.
 */
final class CliArgs {

    private final Map<String, String> options = new HashMap<>();

    private CliArgs() {
    }

    static CliArgs parse(String[] args) {
        CliArgs parsed = new CliArgs();
        int i = 0;
        while (i < args.length) {
            String token = args[i];
            if (!token.startsWith("--")) {
                throw new CliArgumentException("argumento inesperado: " + token);
            }
            String key = token.substring(2);
            if (key.isEmpty()) {
                throw new CliArgumentException("nome de flag vazio em: " + token);
            }
            if (i + 1 >= args.length) {
                throw new CliArgumentException("faltando valor para --" + key);
            }
            parsed.options.put(key, args[i + 1]);
            i += 2;
        }
        return parsed;
    }

    String require(String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new CliArgumentException("--" + key + " e obrigatorio");
        }
        return value;
    }

    String optional(String key, String defaultValue) {
        return options.getOrDefault(key, defaultValue);
    }
}
