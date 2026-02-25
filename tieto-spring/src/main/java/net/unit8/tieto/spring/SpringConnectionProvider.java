package net.unit8.tieto.spring;

import net.unit8.tieto.core.connection.ConnectionProvider;
import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * A {@link ConnectionProvider} that uses Spring's {@link DataSourceUtils}
 * to participate in Spring-managed transactions ({@code @Transactional}).
 */
public class SpringConnectionProvider implements ConnectionProvider {

    private final DataSource dataSource;

    public SpringConnectionProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return DataSourceUtils.getConnection(dataSource);
    }
}
