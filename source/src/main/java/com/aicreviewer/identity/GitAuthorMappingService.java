package com.aicreviewer.identity;

import com.aicreviewer.git.RepositoryOrigin;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GitAuthorMappingService {
    private final JdbcTemplate jdbc;
    private final UserAccountService users;
    private final AuditEventWriter audit;
    private final Validator validator;
    private final Set<String> allowedHosts;

    public GitAuthorMappingService(JdbcTemplate jdbc, UserAccountService users, AuditEventWriter audit,
                                   Validator validator, @Value("${app.git.allowed-hosts:github.com}") String allowedHosts) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
        this.validator = validator;
        this.allowedHosts = Arrays.stream(allowedHosts.split(",")).map(String::strip)
                .filter(host -> !host.isEmpty()).map(host -> host.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public List<GitAuthorMapping> list(String actorName, int page) {
        users.requireAdmin(actorName);
        if (page < 0 || page > 10000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return jdbc.query("""
                SELECT m.id, m.user_id, u.username, u.enabled, m.repository_origin, m.author_email, m.created_at
                FROM git_author_mapping m JOIN app_user u ON u.id = m.user_id
                ORDER BY m.id DESC LIMIT 51 OFFSET ?
                """, (rs, row) -> new GitAuthorMapping(rs.getLong("id"), rs.getLong("user_id"), rs.getString("username"),
                        rs.getBoolean("enabled"), rs.getString("repository_origin"), rs.getString("author_email"),
                        rs.getTimestamp("created_at").toInstant()), page * 50);
    }

    /** The extra result signals that the administrator should narrow the username search. */
    public List<UserOption> userOptions(String actorName, String query) {
        users.requireAdmin(actorName);
        String search = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (search.length() > 80 || search.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String pattern = "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return jdbc.query("""
                SELECT id, username FROM app_user WHERE enabled = TRUE AND username LIKE ? ESCAPE '!'
                ORDER BY username LIMIT 51
                """, (rs, row) -> new UserOption(rs.getLong("id"), rs.getString("username")), pattern);
    }

    @Transactional
    public long create(String actorName, long userId, String repositoryOrigin, String authorEmail) {
        UserAccount actor = users.requireAdmin(actorName);
        if (repositoryOrigin == null || repositoryOrigin.length() > 512) {
            throw new IllegalArgumentException("저장소 서버 주소는 512자 이하로 입력해 주세요.");
        }
        String origin = RepositoryOrigin.normalize(repositoryOrigin, allowedHosts);
        String email = normalizedEmail(authorEmail);
        if (userId < 1 || !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM app_user WHERE id = ? AND enabled = TRUE)", Boolean.class, userId))) {
            throw new IllegalArgumentException("매핑할 활성 사용자 계정을 선택해 주세요.");
        }
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO git_author_mapping(user_id, repository_origin, author_email)
                        VALUES (?, ?, ?)
                        """, new String[] { "id" });
                statement.setLong(1, userId);
                statement.setString(2, origin);
                statement.setString(3, email);
                return statement;
            }, keys);
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("해당 저장소 서버와 이메일의 매핑이 이미 있습니다. 기존 매핑을 삭제한 뒤 다시 등록해 주세요.");
        }
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("Git 작성자 매핑 생성 결과를 확인할 수 없습니다.");
        long id = key.longValue();
        audit.write(actor.id(), "GIT_AUTHOR_MAPPING_CREATED", "GIT_AUTHOR_MAPPING", id, "user_id=" + userId);
        return id;
    }

    @Transactional
    public void delete(String actorName, long id) {
        UserAccount actor = users.requireAdmin(actorName);
        List<Long> mappedUsers = jdbc.queryForList("SELECT user_id FROM git_author_mapping WHERE id = ? FOR UPDATE", Long.class, id);
        if (mappedUsers.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        jdbc.update("DELETE FROM git_author_mapping WHERE id = ?", id);
        audit.write(actor.id(), "GIT_AUTHOR_MAPPING_DELETED", "GIT_AUTHOR_MAPPING", id, "user_id=" + mappedUsers.getFirst());
    }

    private String normalizedEmail(String value) {
        String result = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (result.isEmpty() || result.length() > 320
                || result.codePoints().anyMatch(character -> Character.isISOControl(character)
                    || Character.isWhitespace(character) || Character.isSpaceChar(character))
                || !validator.validate(new EmailValue(result)).isEmpty()) {
            throw new IllegalArgumentException("Git 커밋에 기록된 전체 이메일 주소를 공백이나 제어문자 없이 320자 이하로 입력해 주세요.");
        }
        return result;
    }

    public record UserOption(long id, String username) { }
    private record EmailValue(@Email String value) { }
}
