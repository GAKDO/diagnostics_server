// 이 파일이 확인하는 것: 서버가 살아 있는지 확인하는 주소(ping). 배포 뒤 상태 확인용.

package com.gakdo.trace.diagnostics.ping;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import com.gakdo.trace.diagnostics.support.WebTestBase;

/**
 * diagnostics-server의 ping(점검) 주소 시험. 배포 뒤 "서버가 떴는지, 어떤 버전·커밋이 돌고 있는지" 확인할 때 부르는 주소다.
 * 컨트롤러만 띄우는 가벼운 시험(DB·메시지 큐 없음)이며, 커밋 값은 test-sha로 고정해 응답에 그대로 나오는지 본다.
 */
// (도구) @WebMvcTest = 서버 전체 대신 지정한 컨트롤러(PingController)만 띄우는 가벼운 API 테스트. properties로 커밋 값을 test-sha로 고정한다
@WebMvcTest(controllers = PingController.class, properties = "app.commit=test-sha")
class PingControllerTest extends WebTestBase {

    // (도구) MockMvc = 진짜 네트워크 없이 서버에 HTTP 요청을 보낸 것처럼 흉내 내는 도구. @Autowired = 스프링이 만들어 넣어 준다
    @Autowired
    private MockMvc mockMvc;

    // [상황] 로그인 없이 /api/v1/diagnostics/ping 을 부른다.
    // [기대] 200과 함께 서비스 이름(diagnostics-server), 버전, 상태 ok, 배포된 커밋 값이 온다.
    //        ※ 응답 코드: 200 = 정상 처리됨
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("/api/v1/diagnostics/ping은 토큰 없이 200, 계약 PingResponse(service·version·commit)")
    void ping() throws Exception {
        // (도구) perform(get(주소)) = GET 요청을 보낸다. andExpect(...) = 응답이 이래야 한다는 검사
        // 실행: 토큰 없이 점검 주소를 부른다
        mockMvc.perform(get("/api/v1/diagnostics/ping"))
                // (도구) status().isOk() = 응답 코드가 200(정상)인지 본다
                // 확인: 200이어야 한다
                .andExpect(status().isOk())
                // (도구) jsonPath("$.칸") = 응답 JSON에서 그 칸 값을 꺼낸다. value(값) = 그 값과 같은지 본다
                // 확인: 서비스 이름이 diagnostics-server여야 한다
                .andExpect(jsonPath("$.service").value("diagnostics-server"))
                // (도구) isNotEmpty() = 비어 있지 않아야 통과
                // 확인: 버전 값이 있어야 한다
                .andExpect(jsonPath("$.version").isNotEmpty())
                // 확인: 상태가 ok여야 한다
                .andExpect(jsonPath("$.status").value("ok"))
                // 확인: 배포 커밋이 고정해 둔 test-sha여야 한다
                .andExpect(jsonPath("$.commit").value("test-sha"));
    }
}
