package com.gakdo.trace.diagnostics.common.infra;

import java.util.List;

import org.springframework.security.oauth2.jwt.Jwt;

import com.gakdo.trace.diagnostics.common.domain.DomainException;
import com.gakdo.trace.diagnostics.common.domain.ErrorCode;

/**
 * 요청한 토큰(auth-server AU1 클레임). 범위 판단은 여기 한 곳에서 한다.
 * <ul>
 * <li>AGENT: scope = checkpointId(= sub), rentalId = 렌탈 publicId (페어링 코드 교환으로 받음)</li>
 * <li>CUSTOMER: rentals = 본인 렌탈 publicId 목록. 촬영 JWT(scope 있음)는 진단 조회에 쓰지 못한다</li>
 * <li>ADMIN: 제한 없음(조회만)</li>
 * </ul>
 */
public record Caller(String role, String scope, String rentalId, List<String> rentals) {

    public static Caller from(Jwt jwt) {
        List<String> rentals = jwt.hasClaim("rentals") ? jwt.getClaimAsStringList("rentals") : List.of();
        return new Caller(jwt.getClaimAsString("role"), jwt.getClaimAsString("scope"),
                jwt.getClaimAsString("rentalId"), rentals == null ? List.of() : List.copyOf(rentals));
    }

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    /** 고객 폰 촬영 JWT(QR 교환으로 받은 토큰, scope = checkpointId). 일반 고객 토큰에는 scope가 없다. */
    public boolean isCaptureToken() {
        return "CUSTOMER".equals(role) && PublicIds.isValid(scope);
    }

    /** 진단 업로드는 시점·렌탈이 모두 NanoID인 AGENT 토큰만. 모자라면 403 FORBIDDEN. */
    public void requireAgent() {
        if (!"AGENT".equals(role) || !PublicIds.isValid(scope) || !PublicIds.isValid(rentalId)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "진단 에이전트 페어링 토큰만 올릴 수 있습니다.");
        }
    }

    /** 조회: ADMIN은 모두, 일반 CUSTOMER 토큰은 rentals에 있는 렌탈만. 촬영 JWT(scope 있음)는 403 FORBIDDEN. */
    public void requireCanRead(String runRentalId) {
        if (isAdmin()) {
            return;
        }
        if (!"CUSTOMER".equals(role) || isCaptureToken()) {
            throw new DomainException(ErrorCode.FORBIDDEN, null);
        }
        if (!rentals.contains(runRentalId)) {
            throw new DomainException(ErrorCode.OUT_OF_SCOPE, null);
        }
    }
}
