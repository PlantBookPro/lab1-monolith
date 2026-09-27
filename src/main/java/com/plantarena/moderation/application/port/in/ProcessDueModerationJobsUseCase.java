package com.plantarena.moderation.application.port.in;

/** Обработать пачку due-заданий (вызывает @Scheduled-poller). */
public interface ProcessDueModerationJobsUseCase {

    int processDue(int limit);
}
