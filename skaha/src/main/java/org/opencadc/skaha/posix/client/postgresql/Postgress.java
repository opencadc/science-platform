package org.opencadc.skaha.posix.client.postgresql;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.opencadc.skaha.posix.client.postgresql.enitities.Groups;
import org.opencadc.skaha.posix.client.postgresql.enitities.Users;
public class Postgress {
    private final SessionFactory sessionFactory;
    private Session session;

    public Postgress ( ) {

        Configuration configuration = new Configuration();
        configuration.configure("persistence.xml");
        configuration.addAnnotatedClass(Users.class);
        configuration.addAnnotatedClass(Groups.class);
        sessionFactory = configuration.buildSessionFactory();
        session = sessionFactory.openSession();
    }

    public Users getUsers ( String userid ) {

        Users user = null;
        try {
            session.beginTransaction();
            String jpql = "SELECT u FROM Users u WHERE u.userid = :userId";
            TypedQuery < Users > query = session.createQuery(jpql, Users.class);
            query.setParameter("userId", userid);
            // Execute the query and get the result (a single User entity)
            user = query.getSingleResult();
            session.getTransaction().commit();
        } catch (NoResultException e) {
            // Handle the case when no user is found
            user = null;
            session.getTransaction().commit();
        } catch (Exception e) {
            // Handle other exceptions if needed
            session.getTransaction().rollback(); // Rollback the transaction in case of an error
        } finally {
            return user;
        }
    }

    public Groups getGroups ( String groupname ) {

        Groups groups = null;
        try {
            session.beginTransaction();
            String jpql = "SELECT g FROM Groups g WHERE g.groupName = :groupname";
            TypedQuery < Groups > query = session.createQuery(jpql, Groups.class);
            query.setParameter("groupname", groupname);
            groups = query.getSingleResult();
            session.getTransaction().commit();
        } catch (NoResultException e) {
            session.getTransaction().commit();
            return null;
        } catch (Exception e) {
            // Handle other exceptions if needed
            session.getTransaction().rollback(); // Rollback the transaction in case of an error
            return null; // Or throw an exception or handle it as appropriate
        }
        return groups;
    }

    public void saveUser ( Users users ) {

        session.beginTransaction();
        session.merge(users);
        session.getTransaction().commit();
    }

    public void saveGroups ( Groups groups ) {

        session.beginTransaction();
        session.merge(groups);
        session.getTransaction().commit();
    }

    public void removeUser ( Users users ) {

        session.beginTransaction();
        session.remove(users);
        session.getTransaction().commit();
    }

    // Add a method to close the session when you're done with the Postgress instance
    public void closeSession ( ) {

        if (session != null && session.isOpen()) {
            session.close();
        }
    }
}
