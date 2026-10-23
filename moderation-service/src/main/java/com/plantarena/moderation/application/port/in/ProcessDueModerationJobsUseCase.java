package com.plantarena.moderation.application.port.in;


public interface ProcessDueModerationJobsUseCase {

    int processDue(int limit);
}
