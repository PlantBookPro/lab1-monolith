package com.plantarena.shared.web;

/**
 * Параметры пагинации списков (раздел 13): page от 0, size 1–50, по умолчанию 20.
 */
public record PaginationParams(int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 50;

    public static PaginationParams of(Integer page, Integer size) {
        int resolvedPage = page == null ? 0 : page;
        int resolvedSize = size == null ? DEFAULT_SIZE : size;
        if (resolvedPage < 0 || resolvedSize < 1 || resolvedSize > MAX_SIZE) {
            throw new InvalidPaginationException(
                "Параметры пагинации вне диапазона: page=" + resolvedPage + ", size=" + resolvedSize
                    + " (допустимо: page >= 0, size 1–" + MAX_SIZE + ")");
        }
        return new PaginationParams(resolvedPage, resolvedSize);
    }

    public int offset() {
        return page * size;
    }
}
