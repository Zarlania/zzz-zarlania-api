package com.zarlania.api.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.jdbc.JdbcTestUtils;

/**
 * Clears every application table after each test method so committed rows never leak across the
 * shared in-memory H2 instance (which persists for the JVM lifetime via {@code DB_CLOSE_DELAY=-1}).
 *
 * <p>Tables are discovered dynamically from {@code INFORMATION_SCHEMA} rather than hard-coded, so a
 * new table is cleaned automatically the moment it exists — no test-support edit required. Flyway's
 * own history table is left untouched.
 *
 * <p>Cleanup runs after Spring's transaction management has settled the test method's transaction
 * (order below {@code TransactionalTestExecutionListener}'s 4000), so a {@code @DataJpaTest}'s
 * rollback completes first.
 */
public class CleanDatabaseTestExecutionListener extends AbstractTestExecutionListener {

  // UPPER(...) because DATABASE_TO_LOWER=TRUE (see pom.xml Surefire config) makes H2 report the
  // schema name as lowercase 'public'; comparing case-insensitively keeps this robust either way.
  private static final String SELECT_APPLICATION_TABLES =
      "SELECT table_name FROM information_schema.tables "
          + "WHERE UPPER(table_schema) = 'PUBLIC' AND table_type = 'BASE TABLE' "
          + "AND UPPER(table_name) <> 'FLYWAY_SCHEMA_HISTORY'";

  @Override
  public int getOrder() {
    // Just below TransactionalTestExecutionListener (4000): in the reverse-ordered "after" phase
    // that listener ends the test transaction first, then this one clears the tables.
    return 3900;
  }

  @Override
  public void afterTestMethod(TestContext testContext) throws SQLException {
    DataSource dataSource = testContext.getApplicationContext().getBean(DataSource.class);
    try (Connection connection = dataSource.getConnection()) {
      // Pin the whole reset to one connection so disabling referential integrity (session-scoped in
      // H2) actually spans the deletes that follow.
      JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
      try {
        List<String> tables = jdbc.queryForList(SELECT_APPLICATION_TABLES, String.class);
        JdbcTestUtils.deleteFromTables(jdbc, tables.toArray(new String[0]));
      } finally {
        // Always re-enable, even if discovery/deletion throws: integrity is session-scoped and this
        // connection returns to the pool, so leaving it FALSE would silently disable FK checks for
        // whichever test reuses the connection next.
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
      }
    }
  }
}
