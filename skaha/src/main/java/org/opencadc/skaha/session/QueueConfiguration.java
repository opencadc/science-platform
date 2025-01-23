/*
 ************************************************************************
 *******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
 **************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
 *
 *  (c) 2025.                            (c) 2025.
 *  Government of Canada                 Gouvernement du Canada
 *  National Research Council            Conseil national de recherches
 *  Ottawa, Canada, K1A 0R6              Ottawa, Canada, K1A 0R6
 *  All rights reserved                  Tous droits réservés
 *
 *  NRC disclaims any warranties,        Le CNRC dénie toute garantie
 *  expressed, implied, or               énoncée, implicite ou légale,
 *  statutory, of any kind with          de quelque nature que ce
 *  respect to the software,             soit, concernant le logiciel,
 *  including without limitation         y compris sans restriction
 *  any warranty of merchantability      toute garantie de valeur
 *  or fitness for a particular          marchande ou de pertinence
 *  purpose. NRC shall not be            pour un usage particulier.
 *  liable in any event for any          Le CNRC ne pourra en aucun cas
 *  damages, whether direct or           être tenu responsable de tout
 *  indirect, special or general,        dommage, direct ou indirect,
 *  consequential or incidental,         particulier ou général,
 *  arising from the use of the          accessoire ou fortuit, résultant
 *  software.  Neither the name          de l'utilisation du logiciel. Ni
 *  of the National Research             le nom du Conseil National de
 *  Council of Canada nor the            Recherches du Canada ni les noms
 *  names of its contributors may        de ses  participants ne peuvent
 *  be used to endorse or promote        être utilisés pour approuver ou
 *  products derived from this           promouvoir les produits dérivés
 *  software without specific prior      de ce logiciel sans autorisation
 *  written permission.                  préalable et particulière
 *                                       par écrit.
 *
 *  This file is part of the             Ce fichier fait partie du projet
 *  OpenCADC project.                    OpenCADC.
 *
 *  OpenCADC is free software:           OpenCADC est un logiciel libre ;
 *  you can redistribute it and/or       vous pouvez le redistribuer ou le
 *  modify it under the terms of         modifier suivant les termes de
 *  the GNU Affero General Public        la “GNU Affero General Public
 *  License as published by the          License” telle que publiée
 *  Free Software Foundation,            par la Free Software Foundation
 *  either version 3 of the              : soit la version 3 de cette
 *  License, or (at your option)         licence, soit (à votre gré)
 *  any later version.                   toute version ultérieure.
 *
 *  OpenCADC is distributed in the       OpenCADC est distribué
 *  hope that it will be useful,         dans l’espoir qu’il vous
 *  but WITHOUT ANY WARRANTY;            sera utile, mais SANS AUCUNE
 *  without even the implied             GARANTIE : sans même la garantie
 *  warranty of MERCHANTABILITY          implicite de COMMERCIALISABILITÉ
 *  or FITNESS FOR A PARTICULAR          ni d’ADÉQUATION À UN OBJECTIF
 *  PURPOSE.  See the GNU Affero         PARTICULIER. Consultez la Licence
 *  General Public License for           Générale Publique GNU Affero
 *  more details.                        pour plus de détails.
 *
 *  You should have received             Vous devriez avoir reçu une
 *  a copy of the GNU Affero             copie de la Licence Générale
 *  General Public License along         Publique GNU Affero avec
 *  with OpenCADC.  If not, see          OpenCADC ; si ce n’est
 *  <http://www.gnu.org/licenses/>.      pas le cas, consultez :
 *                                       <http://www.gnu.org/licenses/>.
 *
 *
 ************************************************************************
 */

package org.opencadc.skaha.session;

import ca.nrc.cadc.util.StringUtil;
import java.util.Map;
import java.util.Objects;

/**
 * Class to contain configuration of a queue. Specifics are pulled from the provided environment, which defaults to the
 * system environment.
 */
public class QueueConfiguration {
    private static final String QUEUE_CONFIG_VAR_NAME_PREFIX = "SKAHA_QUEUE_";
    private static final String QUEUE_CONFIG_VAR_NAME = "%s%s_NAME";
    private static final String QUEUE_CONFIG_VAR_PRIORITY_CLASS = "%s%s_PRIORITY_CLASS";
    private static final String QUEUE_CONFIG_VAR_DEFAULT_TYPE = "DEFAULT";

    final String sessionType;
    final String queueName;
    final String priorityClass;

    /**
     * Create a new QueueConfiguration. Used for testing.
     *
     * @param sessionType The session type.
     * @param priorityClass The priority class.
     * @param queueName The queue name.
     */
    QueueConfiguration(final String sessionType, final String priorityClass, final String queueName) {
        this.sessionType = sessionType;
        this.priorityClass = priorityClass;
        this.queueName = queueName;
    }

    /**
     * Obtain the configured QueueConfiguration for the given session type. This will look in the environment for the
     * configuration, and return null if none found.
     *
     * @param type The session type.
     * @return QueueConfiguration for the given session type, or null if none found.
     */
    public static QueueConfiguration fromType(final String type) {
        return QueueConfiguration.fromType(type, System.getenv());
    }

    /**
     * Obtain the configured QueueConfiguration for the given session type. This will look in the supplied environment.
     * Tests can use directly to avoid side environment setup. Default queue name is used if no specific queue is found,
     * if configured.
     *
     * @param type The session type.
     * @param env The environment to look in.
     * @return QueueConfiguration for the given session type, or null if none found.
     */
    static QueueConfiguration fromType(final String type, final Map<String, String> env) {
        final String expectedTypeCase = Objects.requireNonNull(type).toUpperCase();
        final Map<String, String> cleanEnv = Objects.requireNonNull(env);
        final String queueName = QueueConfiguration.getQueueNameForType(expectedTypeCase, cleanEnv);
        if (StringUtil.hasText(queueName)) {
            final String priorityClass = QueueConfiguration.getQueuePriorityClassForType(expectedTypeCase, cleanEnv);
            return new QueueConfiguration(expectedTypeCase, priorityClass, queueName);
        } else {
            final String defaultQueueName =
                    QueueConfiguration.getQueueNameForType(QueueConfiguration.QUEUE_CONFIG_VAR_DEFAULT_TYPE, cleanEnv);
            if (StringUtil.hasText(defaultQueueName)) {
                final String priorityClass = QueueConfiguration.getQueuePriorityClassForType(
                        QueueConfiguration.QUEUE_CONFIG_VAR_DEFAULT_TYPE, cleanEnv);
                return new QueueConfiguration(expectedTypeCase, priorityClass, defaultQueueName);
            } else {
                return null;
            }
        }
    }

    private static String getQueueNameForType(final String type, final Map<String, String> env) {
        final String expectedTypeCase = Objects.requireNonNull(type).toUpperCase();
        return env.get(String.format(
                QueueConfiguration.QUEUE_CONFIG_VAR_NAME,
                QueueConfiguration.QUEUE_CONFIG_VAR_NAME_PREFIX,
                expectedTypeCase));
    }

    private static String getQueuePriorityClassForType(final String type, final Map<String, String> env) {
        final String expectedTypeCase = Objects.requireNonNull(type).toUpperCase();
        return env.get(String.format(
                QueueConfiguration.QUEUE_CONFIG_VAR_PRIORITY_CLASS,
                QueueConfiguration.QUEUE_CONFIG_VAR_NAME_PREFIX,
                expectedTypeCase));
    }
}
