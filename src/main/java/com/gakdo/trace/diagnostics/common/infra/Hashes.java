package com.gakdo.trace.diagnostics.common.infra;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 소문자 hex 64자(계약 Sha256). */
public final class Hashes {

    private Hashes() {
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e); // 모든 JDK에 있으므로 일어나지 않는다
        }
    }
}
