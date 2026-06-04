package com.plantarena.tournaments.adapter.out.identity;

import com.plantarena.identity.api.UserDirectory;
import com.plantarena.tournaments.application.port.out.ParticipantDirectoryGateway;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ACL-адаптер identity → tournaments (раздел 4.3): проверка известного
 * активного пользователя при приглашении через OHS-контракт
 * identity.api.UserDirectory (первое api-пакет identity, дизайн итерации 5,
 * решение 6). В лабе №2 меняется на HTTP-клиент.
 */
@Component
public class InProcessParticipantDirectory implements ParticipantDirectoryGateway {

    private final UserDirectory userDirectory;

    public InProcessParticipantDirectory(UserDirectory userDirectory) {
        this.userDirectory = userDirectory;
    }

    @Override
    public boolean isKnownUser(UUID userId) {
        return userDirectory.findById(userId)
            .map(UserDirectory.UserData::active)
            .orElse(false);
    }
}
