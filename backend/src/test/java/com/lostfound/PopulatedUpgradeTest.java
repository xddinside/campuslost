package com.lostfound;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import org.springframework.dao.DataIntegrityViolationException;

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
