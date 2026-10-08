package com.plantarena.tournaments.application;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.port.in.WithdrawGlobalEntryUseCase;
import com.plantarena.tournaments.application.port.out.PlantEligibilityGateway;
import com.plantarena.tournaments.domain.EntryStatus;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;


@Service
public class WithdrawGlobalEntryService implements WithdrawGlobalEntryUseCase {

    private final TournamentEntryRepository entries;
    private final PlantEligibilityGateway eligibility;
    private final TournamentsAccessPolicy accessPolicy;
    private final TransactionTemplate transactionTemplate;

    public WithdrawGlobalEntryService(TournamentEntryRepository entries,
                                      PlantEligibilityGateway eligibility,
                                      TournamentsAccessPolicy accessPolicy,
                                      TransactionTemplate transactionTemplate) {
        this.entries = entries;
        this.eligibility = eligibility;
        this.accessPolicy = accessPolicy;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void withdraw(CurrentActor actor, UUID entryId) {
        accessPolicy.requireIdentified(actor);
        transactionTemplate.executeWithoutResult(status -> {
            TournamentEntry entry = entries.findById(entryId)
                .filter(found -> found.userId().equals(actor.userId()))
                .orElseThrow(() -> new GlobalEntryNotFoundException(
                    "Участие не найдено: " + entryId));
            if (entry.status() != EntryStatus.QUEUED) {
                throw new GlobalEntryNotWithdrawableException(
                    "Снять можно только заявку из очереди (QUEUED), текущий статус: "
                        + entry.status());
            }
            entry.withdraw();
            entries.save(entry);
            eligibility.release(entry.reservationId());
        });
    }
}
