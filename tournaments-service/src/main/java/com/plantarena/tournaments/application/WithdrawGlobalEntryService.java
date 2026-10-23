package com.plantarena.tournaments.application;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.port.in.WithdrawGlobalEntryUseCase;
import com.plantarena.tournaments.application.port.out.PlantEligibilityGateway;
import com.plantarena.tournaments.domain.EntryStatus;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;


@Service
@RequiredArgsConstructor
public class WithdrawGlobalEntryService implements WithdrawGlobalEntryUseCase {

    private final TournamentEntryRepository entries;
    private final PlantEligibilityGateway eligibility;
    private final TournamentsAccessPolicy accessPolicy;
    private final TransactionTemplate transactionTemplate;

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
