package org.bsl.cartonloading.common.socket;

import java.time.LocalDateTime;

public record AppSocketEvent(
        String module,
        String action,
        String id,
        LocalDateTime at
) {
}