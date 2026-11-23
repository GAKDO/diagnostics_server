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

    public static final String RENTAL = "Ws0HLv6QRbDILPWOcz5I2";
    public static final String OTHER_RENTAL = "Z4U03wInx137yk13jDsmn";
    public static final String OUTBOUND = "IPv6mcQOlTkKj6mOecAQt";
    public static final String RETURN = "h40b9AN9xMQhXRHrOyJWB";
    /** 계약 예시의 기기 시리얼: macos.json, windows.json·ssd-skipped.json */
    public static final String MAC_SERIAL = "C02TEST00001";
    public static final String PC_SERIAL = "PF3TEST01";

    private TestJwts() {
    }

    /** 페어링 코드 교환으로 받은 AGENT 토큰(sub = scope = checkpointId, rentalId, deviceSerial). */
    public static JwtRequestPostProcessor agent(String checkpointId, String rentalId, String deviceSerial) {
        return jwt().jwt(j -> {
            j.subject(checkpointId).claim("role", "AGENT").claim("scope", checkpointId).claim("rentalId", rentalId);
            if (deviceSerial != null) {
                j.claim("deviceSerial", deviceSerial);
            }
        }).authorities(new SimpleGrantedAuthority("ROLE_AGENT"));
    }

    /** macos.json 기기(MAC_SERIAL)를 빌린 렌탈의 AGENT 토큰. */
    public static JwtRequestPostProcessor agent(String checkpointId, String rentalId) {
        return agent(checkpointId, rentalId, MAC_SERIAL);
    }

    /** windows.json·ssd-skipped.json 기기(PC_SERIAL)를 빌린 렌탈의 AGENT 토큰. */
    public static JwtRequestPostProcessor pcAgent() {
        return agent(OUTBOUND, RENTAL, PC_SERIAL);
    }

    public static JwtRequestPostProcessor agent() {
        return agent(OUTBOUND, RENTAL);
    }

    public static JwtRequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("admin0000000000000001").claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    public static JwtRequestPostProcessor customer(String... rentals) {
        return jwt().jwt(j -> j.subject("cust00000000000000001").claim("role", "CUSTOMER")
                .claim("rentals", List.of(rentals)))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    /** QR 교환으로 받은 촬영 JWT(CUSTOMER + scope). 진단 조회에는 쓰지 못한다. */
    public static JwtRequestPostProcessor capture() {
        return jwt().jwt(j -> j.subject("cust00000000000000001").claim("role", "CUSTOMER")
                .claim("scope", RETURN).claim("rentalId", RENTAL))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    public static JwtRequestPostProcessor booth() {
        return jwt().jwt(j -> j.subject("booth-01").claim("role", "BOOTH").claim("scope", "booth-01"))
                .authorities(new SimpleGrantedAuthority("ROLE_BOOTH"));
    }
}
