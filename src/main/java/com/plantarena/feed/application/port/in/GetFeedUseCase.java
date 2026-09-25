package com.plantarena.feed.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Лента (раздел 9): смешанные карточки для голосования, keyset-пагинация
 * без total. Субъект: пользователь (X-Demo-User-Id/JWT) или гость
 * (X-Guest-Token).
 */
public interface GetFeedUseCase {

    /**
     * @param guestToken сырое значение X-Guest-Token (игнорируется для
     *                   идентифицированного пользователя — раздел 9)
     * @param limit      1–50, по умолчанию 20
     * @param cursor     подписанный курсор предыдущей страницы или null
     */
    FeedPage get(CurrentActor actor, String guestToken, Integer limit, String cursor);

    record FeedPage(List<FeedItem> items, String nextCursor, boolean hasNext) {
    }

    record FeedItem(UUID windowId, String scope, UUID tournamentId, UUID entryId,
                    UUID plantId, String title, String imageUrl, UUID ownerId,
                    String ownerDisplayName, Instant closesAt) {
    }
}
