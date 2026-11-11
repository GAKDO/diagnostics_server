package com.gakdo.trace.diagnostics.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** contracts examples/diagnostics 사본(scripts/sync-contracts.sh)을 읽는다. */
public final class Examples {

    public static final ObjectMapper JSON = new ObjectMapper();

    private Examples() {
    }

    public static byte[] bytes(String name) {
        try (InputStream in = Examples.class.getResourceAsStream("/contracts/examples/diagnostics/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("예시가 없습니다: " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ObjectNode node(String name) {
        try {
            return (ObjectNode) JSON.readTree(bytes(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 예시를 고쳐서 바이트로(예: 시리얼만 바꾼 다른 기기). */
    public static byte[] modified(String name, Consumer<ObjectNode> change) {
        ObjectNode node = node(name);
        change.accept(node);
        try {
            return JSON.writeValueAsBytes(node);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static final String MACOS = "valid/macos.json";
    public static final String WINDOWS = "valid/windows.json";
    public static final String SSD_SKIPPED = "valid/ssd-skipped.json";
}
