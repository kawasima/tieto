package net.unit8.tieto.spring;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.unit8.tieto.core.TietoClient;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The startup guard warns when a {@link TietoClient} bean is built over a DataSource
 * that is not transaction-aware, and stays silent when the wrapping is in place.
 */
class TietoClientTransactionAwarenessGuardTest {

    private final TietoClientTransactionAwarenessGuard guard = new TietoClientTransactionAwarenessGuard();

    @Test
    void rawDataSource_isNotTransactionAware() {
        assertThat(TietoClientTransactionAwarenessGuard.isTransactionAware(new NoOpDataSource())).isFalse();
    }

    @Test
    void transactionAwareProxy_isTransactionAware() {
        assertThat(TietoClientTransactionAwarenessGuard.isTransactionAware(
                new TransactionAwareDataSourceProxy(new NoOpDataSource()))).isTrue();
    }

    @Test
    void proxyNestedInsideADelegatingDataSource_isTransactionAware() {
        DataSource nested = new DelegatingDataSource(new TransactionAwareDataSourceProxy(new NoOpDataSource()));
        assertThat(TietoClientTransactionAwarenessGuard.isTransactionAware(nested)).isTrue();
    }

    @Test
    void warnsForAClientOverARawDataSource() {
        ListAppender<ILoggingEvent> log = capture();
        TietoClient client = TietoClient.builder(new NoOpDataSource()).build();

        guard.postProcessAfterInitialization(client, "customClient");

        assertThat(log.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains("customClient")
                    .contains("@Transactional")
                    .contains("TietoClientCustomizer");
        });
    }

    @Test
    void silentForAClientOverATransactionAwareDataSource() {
        ListAppender<ILoggingEvent> log = capture();
        TietoClient client = TietoClient.builder(
                new TransactionAwareDataSourceProxy(new NoOpDataSource())).build();

        guard.postProcessAfterInitialization(client, "tietoClient");

        assertThat(log.list).noneSatisfy(event ->
                assertThat(event.getLevel()).isEqualTo(Level.WARN));
    }

    private static ListAppender<ILoggingEvent> capture() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(TietoClientTransactionAwarenessGuard.class);
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
