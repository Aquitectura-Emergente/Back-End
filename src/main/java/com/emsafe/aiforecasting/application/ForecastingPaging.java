package com.emsafe.aiforecasting.application;

import com.emsafe.shared.exception.BadRequestException;

import java.util.List;

/** Reglas de paginación de la API: page desde 0 y size entre 1 y 200. */
final class ForecastingPaging {

    static final int MAX_SIZE = 200;

    private ForecastingPaging() {
    }

    static void validate(int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page must be >= 0");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new BadRequestException("size must be between 1 and " + MAX_SIZE);
        }
    }

    static <T> List<T> slice(List<T> all, int page, int size) {
        long from = Math.min((long) page * size, all.size());
        long to = Math.min(from + size, all.size());
        return List.copyOf(all.subList((int) from, (int) to));
    }
}
