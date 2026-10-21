package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.domain.Tag;
import com.plantarena.tournaments.domain.TagRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;


@Repository
@Transactional
public class JdbcTagRepository implements TagRepository {

    private static final String SELECT_TAG = """
            select id, name, created_at
            from tournaments.tag
            """;

    private final JdbcClient jdbcClient;

    public JdbcTagRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Tag save(Tag tag) {
        int updated = jdbcClient.sql("update tournaments.tag set name = :name where id = :id")
            .param("id", tag.id())
            .param("name", tag.name())
            .update();
        if (updated == 0) {
            jdbcClient.sql("""
                    insert into tournaments.tag (id, name, created_at)
                    values (:id, :name, :createdAt)
                    """)
                .param("id", tag.id())
                .param("name", tag.name())
                .param("createdAt", Timestamp.from(tag.createdAt()))
                .update();
        }
        return Tag.restore(tag.id(), tag.name(), tag.createdAt());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Tag> findById(UUID id) {
        return jdbcClient.sql(SELECT_TAG + " where id = :id")
            .param("id", id)
            .query(this::mapTag)
            .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Tag> findByName(String name) {
        return jdbcClient.sql(SELECT_TAG + " where name = :name")
            .param("name", name)
            .query(this::mapTag)
            .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Tag> findAll(int offset, int size) {
        return jdbcClient.sql(SELECT_TAG
                + " order by name asc, id asc"
                + " limit :limit offset :offset")
            .param("limit", size)
            .param("offset", (long) (offset / size) * size)
            .query(this::mapTag)
            .list();
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        return jdbcClient.sql("select count(*) from tournaments.tag")
            .query((rs, rowNum) -> rs.getLong(1))
            .single();
    }

    @Override
    public void delete(UUID id) {
        jdbcClient.sql("delete from tournaments.tournament_tag where tag_id = :id")
            .param("id", id)
            .update();
        jdbcClient.sql("delete from tournaments.tag where id = :id")
            .param("id", id)
            .update();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isUsedByTournament(UUID tagId) {
        return jdbcClient.sql("select exists(select 1 from tournaments.tournament_tag"
                + " where tag_id = :tagId)")
            .param("tagId", tagId)
            .query((rs, rowNum) -> rs.getBoolean(1))
            .single();
    }

    private Tag mapTag(ResultSet rs, int rowNum) throws SQLException {
        return Tag.restore(rs.getObject("id", UUID.class), rs.getString("name"),
            rs.getTimestamp("created_at").toInstant());
    }
}
