package com.lostfound;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Permanent populated-upgrade regression: a database at the last shared-fixes
 * migration with real rows must upgrade to the current schema losing nothing,
 * and the narrowed reservation slots must keep their guarantees — historical
 * completions no longer block a reopened report, while two live reservations
 * still cannot coexist.
 */
class PopulatedUpgradeTest {

    private static final String URL =
            "jdbc:mariadb://localhost:3306/laf_migration_test?useUnicode=true&characterEncoding=utf8";

    /**
     * The pre-Flyway prototype schema, populated: deliberate baseline adoption
     * must keep every row and apply the shared-fixes conversions — an approved
     * claim on a resolved report becomes COMPLETED, and a duplicate approval
     * is declined rather than blocking the new unique keys.
     */
    @Test
    void populatedLegacySchemaIsAdoptedWithConversionsIntact() {
        DriverManagerDataSource ds = new DriverManagerDataSource(URL, "lostfound", "lostfound");
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // Build the legacy schema by hand — the shape the pre-Flyway app had.
        // items and photos reference each other, so the drops need one
        // connection with FK checks off.
        try (java.sql.Connection conn = ds.getConnection();
             java.sql.Statement st = conn.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (String table : List.of("flyway_schema_history", "notifications",
                    "match_candidates", "handover_challenges", "claims",
                    "photo_uploads", "items", "photos", "users")) {
                st.execute("DROP TABLE IF EXISTS " + table);
            }
            st.execute("SET FOREIGN_KEY_CHECKS = 1");
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException("could not reset the migration database", ex);
        }
        jdbc.execute("""
                CREATE TABLE users (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    created_at DATETIME(6) NOT NULL,
                    email VARCHAR(150) NOT NULL,
                    name VARCHAR(100) NOT NULL,
                    password_hash VARCHAR(255) NOT NULL,
                    phone VARCHAR(30) NULL,
                    role VARCHAR(20) NOT NULL,
                    PRIMARY KEY (id), UNIQUE KEY uk_users_email (email)
                ) ENGINE = InnoDB""");
        jdbc.execute("""
                CREATE TABLE items (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    category VARCHAR(60) NOT NULL,
                    created_at DATETIME(6) NOT NULL,
                    date_occurred DATETIME(6) NOT NULL,
                    description TEXT NOT NULL,
                    location VARCHAR(120) NOT NULL,
                    resolved_at DATETIME(6) NULL,
                    status VARCHAR(32) NOT NULL,
                    title VARCHAR(120) NOT NULL,
                    type VARCHAR(10) NOT NULL,
                    reporter_id BIGINT NOT NULL,
                    PRIMARY KEY (id), KEY idx_items_reporter (reporter_id),
                    CONSTRAINT fk_items_reporter FOREIGN KEY (reporter_id) REFERENCES users (id)
                ) ENGINE = InnoDB""");
        jdbc.execute("""
                CREATE TABLE claims (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    created_at DATETIME(6) NOT NULL,
                    message TEXT NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    claimant_id BIGINT NOT NULL,
                    item_id BIGINT NOT NULL,
                    PRIMARY KEY (id),
                    CONSTRAINT fk_claims_claimant FOREIGN KEY (claimant_id) REFERENCES users (id),
                    CONSTRAINT fk_claims_item FOREIGN KEY (item_id) REFERENCES items (id)
                ) ENGINE = InnoDB""");

        jdbc.update("""
                INSERT INTO users (created_at, email, name, password_hash, role)
                VALUES (UTC_TIMESTAMP(6), 'alice@legacy.test', 'Alice', 'x', 'USER'),
                       (UTC_TIMESTAMP(6), 'bob@legacy.test', 'Bob', 'x', 'USER')
                """);
        Long alice = jdbc.queryForObject("select id from users where email = 'alice@legacy.test'", Long.class);
        Long bob = jdbc.queryForObject("select id from users where email = 'bob@legacy.test'", Long.class);

        // A resolved report carrying an approved claim: V2 must convert it to
        // COMPLETED so the slot rules hold.
        jdbc.update("""
                INSERT INTO items (category, created_at, date_occurred, description, location,
                                   status, title, type, reporter_id, resolved_at)
                VALUES ('Other', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 'Resolved ring.',
                        'Quad', 'RESOLVED', 'Silver ring', 'LOST', ?, UTC_TIMESTAMP(6))
                """, alice);
        Long resolvedItem = jdbc.queryForObject(
                "select id from items where title = 'Silver ring'", Long.class);
        jdbc.update("""
                INSERT INTO claims (created_at, message, status, claimant_id, item_id)
                VALUES (UTC_TIMESTAMP(6), 'It is mine.', 'APPROVED', ?, ?)
                """, bob, resolvedItem);
        Long legacyClaim = jdbc.queryForObject(
                "select id from claims where item_id = ?", Long.class, resolvedItem);

        // Two approved claims on one open report: the prototype could leave
        // both live; the conversion keeps the oldest and declines the rest.
        jdbc.update("""
                INSERT INTO items (category, created_at, date_occurred, description, location,
                                   status, title, type, reporter_id)
                VALUES ('Other', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 'Open ring.',
                        'Quad', 'OPEN', 'Gold ring', 'FOUND', ?)
                """, alice);
        Long openItem = jdbc.queryForObject(
                "select id from items where title = 'Gold ring'", Long.class);
        jdbc.update("""
                INSERT INTO claims (created_at, message, status, claimant_id, item_id)
                VALUES (UTC_TIMESTAMP(6), 'First responder.', 'APPROVED', ?, ?),
                       (UTC_TIMESTAMP(6), 'Second responder.', 'APPROVED', ?, ?)
                """, alice, openItem, bob, openItem);
        Long firstApproved = jdbc.queryForObject(
                "select min(id) from claims where item_id = ?", Long.class, openItem);
        Long secondApproved = jdbc.queryForObject(
                "select max(id) from claims where item_id = ?", Long.class, openItem);

        // Deliberate adoption: baseline at V1, then apply everything after it.
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .cleanDisabled(false)
                .load()
                .migrate();

        // Every row survived with its id, the schema is current, and the
        // conversions produced the required statuses.
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from items", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from claims", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "select max(version) from flyway_schema_history", String.class)).isEqualTo("60");
        assertThat(jdbc.queryForObject(
                "select status from claims where id = ?", String.class, legacyClaim))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                "select status from claims where id = ?", String.class, firstApproved))
                .isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject(
                "select status from claims where id = ?", String.class, secondApproved))
                .isEqualTo("REJECTED");
    }

    @Test
    void populatedSchemaMigratesForwardAndKeepsItsRowsAndRules() {
        DriverManagerDataSource ds = new DriverManagerDataSource(URL, "lostfound", "lostfound");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Flyway toSharedFixes = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .cleanDisabled(false)
                .target(MigrationVersion.fromVersion("40"))
                .load();
        toSharedFixes.clean();
        toSharedFixes.migrate();

        // A response-resolved LOST report: the exact legacy state that broke
        // the old reservation unique keys on reopen.
        jdbc.update("""
                INSERT INTO users (created_at, email, name, password_hash, role)
                VALUES (UTC_TIMESTAMP(6), 'alice@upgrade.test', 'Alice', 'x', 'USER'),
                       (UTC_TIMESTAMP(6), 'bob@upgrade.test', 'Bob', 'x', 'USER')
                """);
        Long alice = jdbc.queryForObject("select id from users where email = 'alice@upgrade.test'", Long.class);
        Long bob = jdbc.queryForObject("select id from users where email = 'bob@upgrade.test'", Long.class);
        jdbc.update("""
                INSERT INTO items (category, created_at, date_occurred, description, location,
                                   status, title, type, reporter_id, updated_at, resolved_at)
                VALUES ('Other', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 'A lost umbrella.',
                        'Library', 'RESOLVED', 'Lost umbrella', 'LOST', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, alice);
        Long lost = jdbc.queryForObject("select id from items where title = 'Lost umbrella'", Long.class);
        jdbc.update("""
                INSERT INTO claims (created_at, message, status, claimant_id, item_id, completed_at)
                VALUES (UTC_TIMESTAMP(6), 'I have it.', 'COMPLETED', ?, ?, UTC_TIMESTAMP(6))
                """, bob, lost);
        Long firstClaim = jdbc.queryForObject("select id from claims where item_id = ?", Long.class, lost);

        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .cleanDisabled(false)
                .load()
                .migrate();

        // Every row survives, IDs included, and the schema is current.
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select id from claims where id = ?", Long.class, firstClaim))
                .isEqualTo(firstClaim);
        assertThat(jdbc.queryForObject(
                "select max(version) from flyway_schema_history", String.class)).isEqualTo("60");

        // Reopen + re-resolve: a later COMPLETED claim on the same report now
        // fits the narrowed slots instead of hitting a duplicate-key wall.
        jdbc.update("update items set status = 'OPEN', resolved_at = null where id = ?", lost);
        jdbc.update("""
                INSERT INTO claims (created_at, message, status, claimant_id, item_id, completed_at)
                VALUES (UTC_TIMESTAMP(6), 'Found it again.', 'COMPLETED', ?, ?, UTC_TIMESTAMP(6))
                """, bob, lost);

        // Two live reservations on one report are still impossible.
        jdbc.update("update items set status = 'OPEN', resolved_at = null where id = ?", lost);
        jdbc.update("""
                INSERT INTO claims (created_at, message, status, claimant_id, item_id)
                VALUES (UTC_TIMESTAMP(6), 'Reserve me.', 'APPROVED', ?, ?)
                """, bob, lost);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO claims (created_at, message, status, claimant_id, item_id)
                VALUES (UTC_TIMESTAMP(6), 'Reserve me too.', 'APPROVED', ?, ?)
                """, bob, lost))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
