// 테스트 도구(테스트 자체는 아님): 로그인 토큰(JWT)을 가짜로 만들어 준다. 실제 서비스의 역할을 흉내 낸다.
//   - AGENT: 고객 노트북의 진단 에이전트가 6자리 페어링 코드로 받은 토큰(어느 렌탈·어느 시점·어느 기기 시리얼인지 담김)
//   - ADMIN: 관리자, CUSTOMER: 고객(자기 렌탈 목록을 담음), BOOTH: 촬영 부스
//   - capture(): QR로 받은 촬영 전용 토큰(진단 조회에는 쓸 수 없어야 함)
// RENTAL·OUTBOUND(출고 시점)·RETURN(반납 시점) 같은 상수는 테스트용 ID다.

package com.gakdo.trace.diagnostics.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/** MockMvc용 JWT. auth-server AU1 클레임(role·scope·rentals·rentalId)을 흉내 낸다. */
public final class TestJwts {

    // 테스트용 기본 렌탈 ID(21자)
    public static final String RENTAL = "Ws0HLv6QRbDILPWOcz5I2";
    // 권한 밖 시험에 쓸 다른 렌탈 ID
    public static final String OTHER_RENTAL = "Z4U03wInx137yk13jDsmn";
    // 테스트용 출고 시점 ID
    public static final String OUTBOUND = "IPv6mcQOlTkKj6mOecAQt";
    // 테스트용 반납 시점 ID
    public static final String RETURN = "h40b9AN9xMQhXRHrOyJWB";
    /** 계약 예시의 기기 시리얼: macos.json, windows.json·ssd-skipped.json */
    public static final String MAC_SERIAL = "C02TEST00001";
    public static final String PC_SERIAL = "PF3TEST01";

    private TestJwts() {
    }

    /** 페어링 코드 교환으로 받은 AGENT 토큰(sub = scope = checkpointId, rentalId, deviceSerial). */
    public static JwtRequestPostProcessor agent(String checkpointId, String rentalId, String deviceSerial) {
        // (도구) jwt() = 진짜 로그인 없이 "이 토큰을 가진 사용자"로 요청을 보내게 해 주는 Spring Security 테스트 도구
        // 토큰 내용을 채운다
        return jwt().jwt(j -> {
            // 주인=시점 ID, 역할 AGENT, 권한 범위=시점 ID, 렌탈 ID를 넣는다
            j.subject(checkpointId).claim("role", "AGENT").claim("scope", checkpointId).claim("rentalId", rentalId);
            // 기기 시리얼이 있으면
            if (deviceSerial != null) {
                // 토큰에 기기 시리얼도 넣는다
                j.claim("deviceSerial", deviceSerial);
            }
        // 서버가 권한 검사에 쓰는 ROLE_AGENT 권한을 붙인다
        }).authorities(new SimpleGrantedAuthority("ROLE_AGENT"));
    }

    /** macos.json 기기(MAC_SERIAL)를 빌린 렌탈의 AGENT 토큰. */
    public static JwtRequestPostProcessor agent(String checkpointId, String rentalId) {
        // 맥 예시 기기 시리얼로 위 AGENT 토큰을 만든다
        return agent(checkpointId, rentalId, MAC_SERIAL);
    }

    /** windows.json·ssd-skipped.json 기기(PC_SERIAL)를 빌린 렌탈의 AGENT 토큰. */
    public static JwtRequestPostProcessor pcAgent() {
        // 기본 렌탈·출고 시점·윈도우 예시 기기 시리얼로 AGENT 토큰을 만든다
        return agent(OUTBOUND, RENTAL, PC_SERIAL);
    }

    // 테스트 도구: 기본 렌탈·출고 시점·맥 기기의 AGENT 토큰
    public static JwtRequestPostProcessor agent() {
        // 기본 값으로 위 AGENT 토큰을 만든다
        return agent(OUTBOUND, RENTAL);
    }

    // 테스트 도구: 관리자 토큰
    public static JwtRequestPostProcessor admin() {
        // 주인=관리자 ID, 역할 ADMIN을 넣고
        return jwt().jwt(j -> j.subject("admin0000000000000001").claim("role", "ADMIN"))
                // ROLE_ADMIN 권한을 붙인다
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    // 테스트 도구: 받은 렌탈 목록을 가진 고객 토큰
    public static JwtRequestPostProcessor customer(String... rentals) {
        // 주인=고객 ID, 역할 CUSTOMER, 볼 수 있는 렌탈 목록을 넣고
        return jwt().jwt(j -> j.subject("cust00000000000000001").claim("role", "CUSTOMER")
                .claim("rentals", List.of(rentals)))
                // ROLE_CUSTOMER 권한을 붙인다
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    /** QR 교환으로 받은 촬영 JWT(CUSTOMER + scope). 진단 조회에는 쓰지 못한다. */
    public static JwtRequestPostProcessor capture() {
        // 고객 ID, 역할 CUSTOMER에 더해 권한 범위=반납 시점, 렌탈 ID를 넣고(이 시점만 찍는 토큰)
        return jwt().jwt(j -> j.subject("cust00000000000000001").claim("role", "CUSTOMER")
                .claim("scope", RETURN).claim("rentalId", RENTAL))
                // ROLE_CUSTOMER 권한을 붙인다
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    // 테스트 도구: 부스 토큰
    public static JwtRequestPostProcessor booth() {
        // 주인=부스 ID, 역할 BOOTH, 권한 범위=부스 ID를 넣고
        return jwt().jwt(j -> j.subject("booth-01").claim("role", "BOOTH").claim("scope", "booth-01"))
                // ROLE_BOOTH 권한을 붙인다
                .authorities(new SimpleGrantedAuthority("ROLE_BOOTH"));
    }
}
