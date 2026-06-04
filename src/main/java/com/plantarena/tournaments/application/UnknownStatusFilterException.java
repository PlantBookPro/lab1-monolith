package com.plantarena.tournaments.application;

/**
 * Неизвестное значение фильтра статуса списка турниров (раздел 13: 400).
 * Фильтр приходит строкой опубликованного языка — разбор в application,
 * адаптер in.web не зависит от домена (LayerRules).
 */
public class UnknownStatusFilterException extends RuntimeException {

    public UnknownStatusFilterException(String message) {
        super(message);
    }
}
