package com.angatusistemas.whatsapp.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Parses the bridge process's stdout - one pino JSON record per line - and
 * re-emits each record through SLF4J at the matching level, so bridge
 * activity shows up in whatever logging setup the host application already
 * has, instead of the core SDK ever calling {@code System.out.println}.
 *
 * <p>The bridge itself redacts the auth token and session credentials
 * before a line is ever written (see {@code bridge/src/util/logger.ts}), so
 * nothing further needs to be stripped here.
 */
final class BridgeLogForwarder {

    private static final Logger fallbackLog = LoggerFactory.getLogger("com.angatusistemas.whatsapp.bridge");
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final Set<String> STANDARD_FIELDS = Set.of("level", "time", "pid", "hostname", "msg", "v", "component");

    private BridgeLogForwarder() {
    }

    /**
     * Forwards one raw stdout line. Returns the parsed record so callers can
     * inspect it further (e.g. {@code BridgeProcessManager} watching for the
     * {@code bridge_ready} sentinel), or {@code null} if the line was not a
     * parseable JSON object - which is forwarded verbatim at INFO instead of
     * being dropped, since an unstructured line (e.g. a dependency writing
     * directly to stdout, or a raw stack trace during a catastrophic startup
     * failure) may be the only clue to a startup bug.
     */
    static JsonNode forward(String rawLine) {
        if (rawLine == null || rawLine.isBlank()) {
            return null;
        }

        JsonNode node;
        try {
            node = mapper.readTree(rawLine);
        } catch (JsonProcessingException e) {
            fallbackLog.info("[bridge] {}", rawLine);
            return null;
        }
        if (node == null || !node.isObject()) {
            fallbackLog.info("[bridge] {}", rawLine);
            return null;
        }

        Logger logger = loggerFor(node);
        String message = buildMessage(node);
        emit(logger, node.path("level").asInt(30), message);
        return node;
    }

    private static void emit(Logger logger, int pinoLevel, String message) {
        if (pinoLevel >= 50) {
            logger.error(message);
        } else if (pinoLevel >= 40) {
            logger.warn(message);
        } else if (pinoLevel >= 30) {
            logger.info(message);
        } else if (pinoLevel >= 20) {
            logger.debug(message);
        } else {
            logger.trace(message);
        }
    }

    private static Logger loggerFor(JsonNode node) {
        String component = node.path("component").asText(null);
        String name = component != null && !component.isBlank()
                ? "com.angatusistemas.whatsapp.bridge." + component
                : "com.angatusistemas.whatsapp.bridge";
        return LoggerFactory.getLogger(name);
    }

    private static String buildMessage(JsonNode node) {
        StringBuilder message = new StringBuilder(node.path("msg").asText(""));

        StringBuilder extras = new StringBuilder();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (STANDARD_FIELDS.contains(entry.getKey())) {
                continue;
            }
            if (!extras.isEmpty()) {
                extras.append(", ");
            }
            JsonNode value = entry.getValue();
            extras.append(entry.getKey()).append('=').append(value.isTextual() ? value.asText() : value.toString());
        }
        if (!extras.isEmpty()) {
            message.append(" [").append(extras).append(']');
        }
        return message.toString();
    }
}
