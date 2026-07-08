package org.bsl.sales.security;

public record JwtPayload(String email, String role, long tokenVersion) {
}
