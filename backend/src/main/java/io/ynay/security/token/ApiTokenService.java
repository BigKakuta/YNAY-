package io.ynay.security.token;

import io.ynay.action.ActionException;
import io.ynay.security.YnayUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Personal access tokens ({@code ynay_pat_<secret>}) for MCP clients. Only the SHA-256 hash of the
 * full token is stored; the plaintext is shown once on creation.
 */
@Service
public class ApiTokenService {

    public static final String TOKEN_PREFIX = "ynay_pat_";
    static final int MAX_NAME_LENGTH = 100;
    static final int MAX_EXPIRY_DAYS = 3650;
    /** {@code last_used_at} is refreshed at most this often per token. */
    static final Duration LAST_USED_GRANULARITY = Duration.ofMinutes(1);

    private static final int SECRET_BYTES = 32;
    private static final int DISPLAY_PREFIX_LENGTH = TOKEN_PREFIX.length() + 6;

    private final JdbcClient jdbc;
    private final SecureRandom random = new SecureRandom();

    public ApiTokenService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public CreatedApiToken create(UUID userId, String name, Integer expiresInDays) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty()) {
            throw ActionException.invalid("name must not be blank");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw ActionException.invalid("name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        if (expiresInDays != null && (expiresInDays < 1 || expiresInDays > MAX_EXPIRY_DAYS)) {
            throw ActionException.invalid("expiresInDays must be between 1 and " + MAX_EXPIRY_DAYS);
        }

        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        String token = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String prefix = token.substring(0, DISPLAY_PREFIX_LENGTH);

        // Truncate to what Postgres stores so the returned values equal later reads.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = expiresInDays == null ? null : now.plus(Duration.ofDays(expiresInDays));
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into api_token (id, user_id, name, token_hash, token_prefix, created_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?)""")
                .params(id, userId, trimmed, hash(token), prefix, toOffset(now), toOffset(expiresAt))
                .update();
        return new CreatedApiToken(id, trimmed, prefix, now, null, expiresAt, token);
    }

    /** Active (non-revoked) tokens of the user, newest first. Expired tokens are included. */
    public List<ApiToken> list(UUID userId) {
        return jdbc.sql("""
                select id, name, token_prefix, created_at, last_used_at, expires_at
                  from api_token
                 where user_id = ? and revoked_at is null
                 order by created_at desc, id""")
                .param(userId)
                .query(ApiTokenService::mapToken)
                .list();
    }

    public void revoke(UUID userId, UUID tokenId) {
        if (tokenId == null) {
            throw ActionException.invalid("tokenId is required");
        }
        int updated = jdbc.sql("""
                update api_token set revoked_at = now()
                 where id = ? and user_id = ? and revoked_at is null""")
                .params(tokenId, userId)
                .update();
        if (updated == 0) {
            throw ActionException.notFound("API token " + tokenId);
        }
    }

    /**
     * Resolves a bearer token to its user. Rejects unknown, revoked and expired tokens and records
     * the use (at most once per {@link #LAST_USED_GRANULARITY}).
     */
    public Optional<YnayUser> authenticate(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        record Match(UUID tokenId, YnayUser user) {
        }
        Optional<Match> match = jdbc.sql("""
                select t.id as token_id, u.id as user_id, u.username
                  from api_token t
                  join app_user u on u.id = t.user_id
                 where t.token_hash = ?
                   and t.revoked_at is null
                   and (t.expires_at is null or t.expires_at > now())""")
                .param(hash(token))
                .query((rs, n) -> new Match(
                        rs.getObject("token_id", UUID.class),
                        new YnayUser(rs.getObject("user_id", UUID.class), rs.getString("username"))))
                .optional();
        match.ifPresent(m -> jdbc.sql("""
                update api_token set last_used_at = now()
                 where id = ? and (last_used_at is null or last_used_at < now() - make_interval(secs => ?))""")
                .params(m.tokenId(), (double) LAST_USED_GRANULARITY.toSeconds())
                .update());
        return match.map(Match::user);
    }

    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiToken mapToken(ResultSet rs, int rowNum) throws SQLException {
        return new ApiToken(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("token_prefix"),
                toInstant(rs.getObject("created_at", OffsetDateTime.class)),
                toInstant(rs.getObject("last_used_at", OffsetDateTime.class)),
                toInstant(rs.getObject("expires_at", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime toOffset(Instant t) {
        return t == null ? null : t.atOffset(java.time.ZoneOffset.UTC);
    }
}
