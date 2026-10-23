package com.plantarena.shared.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Параметры пагинации списков (раздел 13)")
class PaginationParamsTest {

    @Test
    void значения_по_умолчанию_page_0_size_20() {
        PaginationParams params = PaginationParams.of(null, null);

        assertThat(params.page()).isZero();
        assertThat(params.size()).isEqualTo(20);
        assertThat(params.offset()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "0,0", "0,51", "0,-1"})
    void параметры_вне_диапазона_отклоняются(int page, int size) {
        assertThatThrownBy(() -> PaginationParams.of(page, size))
            .isInstanceOf(InvalidPaginationException.class);
    }

    @Test
    void верхняя_граница_размера_допустима() {
        assertThat(PaginationParams.of(3, 50).offset()).isEqualTo(150);
    }
}
