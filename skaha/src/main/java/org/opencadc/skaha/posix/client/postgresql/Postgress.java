package org.opencadc.skaha.posix.client.postgresql;
import ca.nrc.cadc.ac.User;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import org.apache.log4j.Logger;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.query.SelectionQuery;
import org.opencadc.skaha.posix.client.postgresql.enitities.CommonGroup;
import org.opencadc.skaha.posix.client.postgresql.enitities.Groups;
import org.opencadc.skaha.posix.client.postgresql.enitities.Users;

import java.util.List;
import java.util.Properties;
public class Postgress {
    private Session session;
    private static final Logger log = Logger.getLogger(Postgress.class);

    private Properties properties() {
        Properties properties = new Properties();
        properties.put("hibernate.connection.driver_class", "org.postgresql.Driver");
        properties.put("hibernate.connection.url", getEnvOrElse("POSIX_DATABASE_URL", "jdbc:postgresql://localhost:5432/postgres"));
        properties.put("hibernate.connection.username", getEnvOrElse("POSIX_DATABASE_USERNAME", "postgres"));
        properties.put("hibernate.connection.password", getEnvOrElse("POSIX_DATABASE_PASSWORD", "mysecretpassword"));
        properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        properties.put("hibernate.show_sql", "false");
        properties.put("hibernate.format_sql", "false");
        properties.put("hibernate.hbm2ddl.auto", "validate");
        properties.put("hibernate.current_session_context_class", "org.hibernate.context.internal.ThreadLocalSessionContext");
        return properties;
    }

    private SessionFactory sessionFactory(Properties properties, Class< ? >... entityClasses) {
        Configuration configuration = new Configuration();
        configuration.addProperties(properties);
        for(Class< ? > entityClass : entityClasses)
            configuration.addAnnotatedClass(entityClass);
        return configuration.buildSessionFactory();
    }

    public Postgress build(Class< ? >... entityClasses) {
        session = sessionFactory(properties(), entityClasses).openSession();
        return this;
    }

    public String getFromEnv(String key) {
        String val = System.getenv(key);
        if(null == val) throw new RuntimeException("unable to fetch " + key + " from environment");
        return val;
    }

    public String getEnvOrElse(String key, String defaultVal) {
        try {
            return getFromEnv(key);
        } catch(Throwable th) {
            log.error("Unable to fetch from env for key " + key);
            return defaultVal;
        }
    }

    public Postgress() {
    }

    public Postgress(Class< ? >... entityClasses) {
        build(entityClasses);
    }

    public Users getUsers(String userid) {
        Users user = null;
        try {
            session.beginTransaction();
            String jpql = "SELECT u FROM Users u WHERE u.userid = :userId";
            TypedQuery< Users > query = session.createQuery(jpql, Users.class);
            query.setParameter("userId", userid);
            // Execute the query and get the result (a single User entity)
            user = query.getSingleResult();
            session.getTransaction().commit();
        } catch(NoResultException e) {
            session.getTransaction().commit();
            return null;
        } catch(Exception e) {
            // Handle other exceptions if needed
            session.getTransaction().rollback(); // Rollback the transaction in case of an error
            return null; // Or throw an exception or handle it as appropriate
        }
        return user;
    }

    public Groups getGroups(String groupname) {
        Groups groups = null;
        try {
            session.beginTransaction();
            String jpql = "SELECT g FROM Groups g WHERE g.groupName = :groupname";
            TypedQuery< Groups > query = session.createQuery(jpql, Groups.class);
            query.setParameter("groupname", groupname);
            groups = query.getSingleResult();
            session.getTransaction().commit();
        } catch(NoResultException e) {
            session.getTransaction().commit();
            return null;
        } catch(Exception e) {
            // Handle other exceptions if needed
            session.getTransaction().rollback(); // Rollback the transaction in case of an error
            return null; // Or throw an exception or handle it as appropriate
        }
        return groups;
    }

    public void saveUser(Users users) {
        session.beginTransaction();
        session.merge(users);
        session.getTransaction().commit();
    }

    public void saveGroups(Groups groups) {
        session.beginTransaction();
        session.merge(groups);
        session.getTransaction().commit();
    }

    public void removeUser(Users users) {
        session.beginTransaction();
        session.remove(users);
        session.getTransaction().commit();
    }

    public void saveCommonGroup(CommonGroup commonGroup) {
        session.beginTransaction();
        session.merge(commonGroup);
        session.getTransaction().commit();
    }

    public List< Users > getUserFromGroup(int groupId) {
        List< Users > users = null;
        try {
            session.beginTransaction();
            String hql = "SELECT u FROM Users u JOIN Groups g WHERE g.groupId = :groupId";
            TypedQuery< Users > query = session.createQuery(hql, Users.class);
            query.setParameter("groupId", groupId);
            users = query.getResultList();
            session.getTransaction().commit();
        } catch(NoResultException e) {
            log.error("getUserFromGroup(int groupId) ", e);
            session.getTransaction().commit();
            return null;
        } catch(Exception e) {
            log.error("getUserFromGroup(int groupId) ", e);
            // Handle other exceptions if needed
            session.getTransaction().rollback(); // Rollback the transaction in case of an error
            return null; // Or throw an exception or handle it as appropriate
        }
        return users;
    }

    // Add a method to close the session when you're done with the Postgress instance
    public void closeSession() {
        if(session != null && session.isOpen()) {
            session.close();
        }
    }
}
