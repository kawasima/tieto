package net.unit8.tieto.spring;

import net.unit8.tieto.core.TietoClient;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Spring {@link FactoryBean} that creates a tieto Repository proxy for
 * a given interface.
 */
public class TietoRepositoryFactoryBean<T> implements FactoryBean<T> {

    private final Class<T> repositoryInterface;
    private TietoClient tietoClient;

    public TietoRepositoryFactoryBean(Class<T> repositoryInterface) {
        this.repositoryInterface = repositoryInterface;
    }

    @Autowired
    public void setTietoClient(TietoClient tietoClient) {
        this.tietoClient = tietoClient;
    }

    @Override
    public T getObject() {
        return tietoClient.createRepository(repositoryInterface);
    }

    @Override
    public Class<?> getObjectType() {
        return repositoryInterface;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }
}
