package com.plantarena.feed.application;

import com.plantarena.feed.domain.FeedCursor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;


public class FeedCursorCodec {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final Duration ttl;

    public FeedCursorCodec(String secret, Duration ttl) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
    }

    public String encode(FeedCursor cursor) {
        String canonical = canonical(cursor);
        byte[] payload = canonical.getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(hmac(canonical));
    }

    public FeedCursor decode(String encoded, Instant now) {
        if (encoded == null) {
            throw new FeedCursorInvalidException("Курсор отсутствует");
        }
        int dot = encoded.lastIndexOf('.');
        if (dot <= 0 || dot == encoded.length() - 1) {
            throw new FeedCursorInvalidException("Курсор повреждён");
        }
        byte[] payload;
        byte[] signature;
        try {
            payload = DECODER.decode(encoded.substring(0, dot));
            signature = DECODER.decode(encoded.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            throw new FeedCursorInvalidException("Курсор повреждён");
        }
        String canonical = new String(payload, StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(hmac(canonical), signature)) {
            throw new FeedCursorInvalidException("Подпись курсора не сходится");
        }
        FeedCursor cursor = parse(canonical);
        if (now.isAfter(cursor.snapshotCutoff().plus(ttl))) {
            throw new FeedCursorExpiredException(
                "Курсор истёк — начните новую ленту (запрос без cursor)");
        }
        return cursor;
    }

    private FeedCursor parse(String canonical) {
        String[] parts = canonical.split("\\|", -1);
        if (parts.length != 5) {
            throw new FeedCursorInvalidException("Курсор повреждён");
        }
        try {
            long seed = Long.parseLong(parts[0]);
            Instant cutoff = Instant.ofEpochMilli(Long.parseLong(parts[1]));
            Long lastSortKey = parts[2].isEmpty() ? null : Long.parseLong(parts[2]);
            UUID lastId = parts[3].isEmpty() ? null : UUID.fromString(parts[3]);
            return new FeedCursor(seed, cutoff, lastSortKey, lastId, parts[4]);
        } catch (RuntimeException e) {
            throw new FeedCursorInvalidException("Курсор повреждён");
        }
    }

    private String canonical(FeedCursor cursor) {
        return cursor.seed() + "|" + cursor.snapshotCutoff().toEpochMilli() + "|"
            + (cursor.lastSortKey() == null ? "" : cursor.lastSortKey()) + "|"
            + (cursor.lastId() == null ? "" : cursor.lastId()) + "|" + cursor.subjectKey();
    }

    private byte[] hmac(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 недоступен", e);
        }
    }
}
