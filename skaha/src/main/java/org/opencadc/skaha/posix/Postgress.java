package org.opencadc.skaha.posix;

import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import org.apache.log4j.Logger;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

import java.util.*;
import java.util.function.Function;

public class Postgress {
    private final List<Class<?>> entityClasses = new ArrayList<>();
    private SessionFactory sessionFactory;

    private static final Logger log = Logger.getLogger(Postgress.class);

    public String getFromEnv(String key) {
        String val = System.getenv(key);
        if (null == val) throw new RuntimeException("unable to fetch " + key + " from environment");
        return val;
    }

    public String getEnvOrElse(String key, String defaultVal) {
        try {
            return getFromEnv(key);
        } catch (Exception th) {
            log.error("Unable to fetch from env for key " + key);
            return defaultVal;
        }
    }

    private Properties properties() {
        Properties properties = new Properties();
        properties.put("hibernate.connection.driver_class", "org.postgresql.Driver");
        properties.put("hibernate.connection.url", getFromEnv("POSIX_DATABASE_URL"));
        properties.put("hibernate.connection.username", getFromEnv("POSIX_DATABASE_USERNAME"));
        properties.put("hibernate.connection.password", getFromEnv("POSIX_DATABASE_PASSWORD"));
        properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        properties.put("hibernate.show_sql", "false");
        properties.put("hibernate.format_sql", "false");
//        properties.put("hibernate.hbm2ddl.auto", "create-drop");
        properties.put("hibernate.hbm2ddl.auto", "validate");
        properties.put("hibernate.current_session_context_class", "org.hibernate.context.internal.ThreadLocalSessionContext");
        return properties;
    }

    private Configuration configuration(Properties properties, List<Class<?>> entityClasses) {
        Configuration configuration = new Configuration();
        configuration.addProperties(properties);
        for (Class<?> entityClass : entityClasses)
            configuration.addAnnotatedClass(entityClass);
        return configuration;
    }

    private Postgress() {
    }

    public Postgress entityClass(Class<?>... entityClasses) {
        this.entityClasses.addAll(Arrays.asList(entityClasses));
        return this;
    }

    public static Postgress instance() {
        return new Postgress();
    }

    public Postgress build() {
        Configuration configuration = configuration(properties(), this.entityClasses);
        sessionFactory = configuration.buildSessionFactory();
        return this;
    }

    public Session open() {
        return this.sessionFactory.openSession();
    }

    public void close(Session session) {
        if (session != null && session.isOpen()) {
            session.close();
        }
    }

    public <R> R inSession(Function<Session, R> function) throws Exception {
        Session session = null;
        try {
            session = open();
            return function.apply(session);
        } catch (RuntimeException e) {
            log.error(e);
            throw e;
        } finally {
            close(session);
        }
    }

    public <R> R inTransaction(Function<Session, R> function) throws Exception {
        try {
            return inSession(session -> {
                session.beginTransaction();
                R val = null;
                try {
                    val = function.apply(session);
                } catch (Exception e) {
                    session.getTransaction().rollback();
                    throw new RuntimeException(e);
                }
                session.getTransaction().commit();
                return val;
            });
        } catch (RuntimeException e) {
            log.error(e);
            throw e;
        }
    }

    public <T> T save(T entity) throws Exception {
        return inTransaction(session -> {
            session.persist(entity);
            return entity;
        });
    }

    public <T> T update(T entity) throws Exception {
        return inTransaction(session -> {
            session.merge(entity);
            return entity;
        });
    }


    public <T> void remove(T entity) throws Exception {
        inTransaction(session -> {
            session.remove(entity);
            return null;
        });
    }

    public <T> T findById(Class<T> type, Object id) throws Exception {
        return inTransaction(session -> session.find(type, id));
    }

    public <T> T find(Class<T> type, String queryName, Map<String, Object> criteria) throws Exception {
        return inTransaction(session -> find(type, queryName, criteria, session));
    }

    private <T> T find(Class<T> type, String queryName, Map<String, Object> criteria, Session session) {
        TypedQuery<T> query = session.createNamedQuery(queryName, type);
        if (null != criteria && !criteria.isEmpty())
            for (Map.Entry<String, Object> entry : criteria.entrySet())
                query.setParameter(entry.getKey(), entry.getValue());
        try {
            return query.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    public <T> List<T> findAll(Class<T> type, String queryName, Map<String, Object> criteria) throws Exception {
        return inTransaction(session -> findAll(type, queryName, criteria, session));
    }

    private <T> List<T> findAll(Class<T> type, String queryName, Map<String, Object> criteria, Session session) {
        TypedQuery<T> query = session.createNamedQuery(queryName, type);
        if (null != criteria && !criteria.isEmpty())
            for (Map.Entry<String, Object> entry : criteria.entrySet())
                query.setParameter(entry.getKey(), entry.getValue());
        try {
            return query.getResultList();
        } catch (NoResultException e) {
            return new ArrayList<>();
        }
    }
}
