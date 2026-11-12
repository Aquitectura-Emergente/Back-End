package com.emsafe.aiforecasting.domain.ports;

import java.util.List;

/** Página de resultados independiente de Spring Data; {@code page} empieza en 0. */
public record PageResult<T>(List<T> content, int page, int size, long totalElements) {

    public PageResult {
        content = List.copyOf(content);
    }
}
