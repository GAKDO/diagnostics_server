// 테스트 도구(테스트 자체는 아님): contracts 레포에 있는 진단 결과 예시 파일(맥·윈도우·SSD 생략)을 읽어 오고,
// 필요하면 일부 값만 바꾼 변형(예: 시리얼만 다른 기기)을 만들어 준다. 테스트마다 같은 실제 형태의 데이터를 쓰기 위함.

package com.gakdo.trace.diagnostics.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** contracts examples/diagnostics 사본(scripts/sync-contracts.sh)을 읽는다. */
public final class Examples {

    // JSON 읽기·쓰기 도구(여러 테스트가 같이 쓴다)
    public static final ObjectMapper JSON = new ObjectMapper();

    private Examples() {
    }

    // 테스트 도구: 예시 파일 하나를 바이트로 읽는다
    public static byte[] bytes(String name) {
        // 테스트 폴더의 contracts 예시 사본을 연다(다 읽으면 자동으로 닫힘)
        try (InputStream in = Examples.class.getResourceAsStream("/contracts/examples/diagnostics/" + name)) {
            // 파일이 없으면
            if (in == null) {
                // 이름이 틀렸다고 바로 알리고 멈춘다
                throw new IllegalArgumentException("예시가 없습니다: " + name);
            }
            // 파일 내용을 모두 읽어 돌려준다
            return in.readAllBytes();
        } catch (IOException e) {
            // 읽기 오류는 테스트를 멈추는 예외로 바꿔 던진다
            throw new UncheckedIOException(e);
        }
    }

    // 테스트 도구: 예시 파일을 고칠 수 있는 JSON 객체로 읽는다
    public static ObjectNode node(String name) {
        try {
            // 바이트를 JSON 객체로 바꿔 돌려준다
            return (ObjectNode) JSON.readTree(bytes(name));
        } catch (IOException e) {
            // 읽기 오류는 예외로 바꿔 던진다
            throw new UncheckedIOException(e);
        }
    }

    /** 예시를 고쳐서 바이트로(예: 시리얼만 바꾼 다른 기기). */
    public static byte[] modified(String name, Consumer<ObjectNode> change) {
        // 예시를 JSON 객체로 읽고
        ObjectNode node = node(name);
        // 부른 쪽이 준 고치기(예: 시리얼 바꾸기)를 적용한 뒤
        change.accept(node);
        try {
            // 다시 바이트로 바꿔 돌려준다
            return JSON.writeValueAsBytes(node);
        } catch (IOException e) {
            // 쓰기 오류는 예외로 바꿔 던진다
            throw new UncheckedIOException(e);
        }
    }

    // 맥 진단 예시 파일 이름
    public static final String MACOS = "valid/macos.json";
    // 윈도우 진단 예시 파일 이름
    public static final String WINDOWS = "valid/windows.json";
    // SSD 검사를 건너뛴 진단 예시 파일 이름
    public static final String SSD_SKIPPED = "valid/ssd-skipped.json";
}
