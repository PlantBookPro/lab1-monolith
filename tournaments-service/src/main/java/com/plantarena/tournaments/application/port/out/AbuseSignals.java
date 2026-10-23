package com.plantarena.tournaments.application.port.out;


public interface AbuseSignals {

    void signal(String action, String details);
}
