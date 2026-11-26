// 테스트 도구(테스트 자체는 아님): 화면 요청(API) 시험이 실제 운영과 같은 보안 규칙(누가 무엇을 부를 수 있나)과
// 같은 오류 응답 형식(code·title·detail)을 쓰도록 묶어 주는 바탕 클래스.

package com.gakdo.trace.diagnostics.support;

import org.springframework.context.annotation.Import;

import com.gakdo.trace.diagnostics.common.api.ProblemAdvice;
import com.gakdo.trace.diagnostics.common.infra.SecurityConfig;

/** WebMvc 시험이 운영과 같은 보안 규칙·오류 형식을 쓰게 한다. */
// (도구) @Import = 함께 띄울 부품을 고른다. 운영 보안 규칙(SecurityConfig)과 오류 응답 형식(ProblemAdvice)을 싣는다
@Import({SecurityConfig.class, ProblemAdvice.class})
public abstract class WebTestBase {
}
