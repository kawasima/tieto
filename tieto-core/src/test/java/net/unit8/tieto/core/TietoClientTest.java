package net.unit8.tieto.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TietoClientTest {

    @Test
    void createRepository_rejectsNonInterface() {
        assertThatThrownBy(() -> {
            // Use a dummy DataSource just to create a client
            var client = TietoClient.builder((javax.sql.DataSource) null);
        }).isInstanceOf(NullPointerException.class);
    }

    @Test
    void createRepository_createsProxy() {
        // Use a minimal DataSource stub
        javax.sql.DataSource ds = new StubDataSource();
        TietoClient client = TietoClient.builder(ds).build();

        SampleRepository repo = client.createRepository(SampleRepository.class);
        assertThat(repo).isNotNull();
        assertThat(repo.toString()).contains("SampleRepository");
    }

    @Test
    void createRepository_rejectsClass() {
        javax.sql.DataSource ds = new StubDataSource();
        TietoClient client = TietoClient.builder(ds).build();

        assertThatThrownBy(() -> client.createRepository(String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not an interface");
    }

    interface SampleRepository {
        void doSomething();
    }

    // Minimal DataSource stub that throws on getConnection
    static class StubDataSource implements javax.sql.DataSource {
        public java.sql.Connection getConnection() { throw new UnsupportedOperationException(); }
        public java.sql.Connection getConnection(String u, String p) { throw new UnsupportedOperationException(); }
        public java.io.PrintWriter getLogWriter() { return null; }
        public void setLogWriter(java.io.PrintWriter out) {}
        public void setLoginTimeout(int seconds) {}
        public int getLoginTimeout() { return 0; }
        public java.util.logging.Logger getParentLogger() { return null; }
        public <T> T unwrap(Class<T> iface) { return null; }
        public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
