package net.unit8.tieto.spring;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.orm.jpa.JpaTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The linker sets the DataSource on a {@link JpaTransactionManager} that has none, so tieto's
 * calls join the JPA transaction — but only when the DataSource is unambiguous and the manager
 * has not been configured with one already.
 */
class TietoJpaTransactionManagerLinkerTest {

    @Test
    void setsTheUniqueDataSourceOnAJpaManagerThatHasNone() {
        DataSource dataSource = new NoOpDataSource();
        JpaTransactionManager jpa = new JpaTransactionManager();

        linkerFor(dataSource).postProcessBeforeInitialization(jpa, "transactionManager");

        assertThat(jpa.getDataSource()).as("linked to the application DataSource").isSameAs(dataSource);
    }

    @Test
    void leavesAnExplicitlyConfiguredDataSourceUntouched() {
        DataSource configured = new NoOpDataSource();
        DataSource other = new NoOpDataSource();
        JpaTransactionManager jpa = new JpaTransactionManager();
        jpa.setDataSource(configured);

        linkerFor(other).postProcessBeforeInitialization(jpa, "transactionManager");

        assertThat(jpa.getDataSource()).as("an explicit dataSource is respected").isSameAs(configured);
    }

    @Test
    void doesNothingWhenTheDataSourceIsNotUnique() {
        JpaTransactionManager jpa = new JpaTransactionManager();

        linkerFor(new NoOpDataSource(), new NoOpDataSource())
                .postProcessBeforeInitialization(jpa, "transactionManager");

        assertThat(jpa.getDataSource()).as("does not guess among several DataSources").isNull();
    }

    @Test
    void ignoresBeansThatAreNotJpaTransactionManagers() {
        Object other = new Object();
        assertThat(linkerFor(new NoOpDataSource()).postProcessBeforeInitialization(other, "x"))
                .isSameAs(other);
    }

    private static TietoJpaTransactionManagerLinker linkerFor(DataSource... dataSources) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        for (int i = 0; i < dataSources.length; i++) {
            beanFactory.registerSingleton("dataSource" + i, dataSources[i]);
        }
        ObjectProvider<DataSource> provider = beanFactory.getBeanProvider(DataSource.class);
        return new TietoJpaTransactionManagerLinker(provider);
    }
}
