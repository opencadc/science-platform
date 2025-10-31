/*
 ************************************************************************
 *******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
 **************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
 *
 *  (c) 2023.                            (c) 2023.
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

import ca.nrc.cadc.util.FileUtil;
import ca.nrc.cadc.util.Log4jInit;
import java.io.File;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.log4j.Level;
import org.jetbrains.annotations.NotNull;
import org.junit.Assert;
import org.junit.Test;
import org.opencadc.skaha.utils.TestUtils;

public class UserStorageClientTest {
    static {
        Log4jInit.setLevel("org.opencadc.skaha", Level.DEBUG);
    }

    @Test
    public void extractDesktopSetupFiles() throws Exception {
        final File testTARFile = FileUtil.getFileFromResource("test-file.tar", UserStorageClientTest.class);
        final Path outputPath = UserStorageClient.extractDesktopSetupFiles(testTARFile);

        final Path[] expectedFiles = {
            outputPath.resolve("a/b/.ab"),
            outputPath.resolve("a/b2/b2.file"),
            outputPath.resolve("x/y/.xyfile"),
            outputPath.resolve("x/zz/8/file.txt"),
            outputPath.resolve("x/zz/9/file.txt"),
            outputPath.resolve("x/.xhidden"),
            outputPath.resolve("x2/anotherfile.txt"),
        };

        final List<Path> files = new ArrayList<>();
        Files.walkFileTree(outputPath, new SimpleFileVisitor<>() {
            /** Invoked for a file in a directory. */
            @NotNull @Override
            public FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        Arrays.sort(expectedFiles);
        files.sort(Path::compareTo);

        Assert.assertArrayEquals("Wrong paths.", expectedFiles, files.toArray(new Path[0]));
    }

    @Test
    public void testCheckExistingSessions() {
        final PostAction testSubject = new PostAction() {
            @Override
            protected String getUsername() {
                return "owner";
            }
        };

        // Should pass
        testSubject.checkExistingSessions(SessionType.HEADLESS, Collections.emptyList());

        // Should pass
        final List<Session> sessions = new ArrayList<>();
        testSubject.checkExistingSessions(SessionType.NOTEBOOK, sessions);

        // Should pass
        sessions.add(TestUtils.createSession("id1", SessionType.NOTEBOOK, Session.STATUS_FAILED));
        testSubject.checkExistingSessions(SessionType.NOTEBOOK, sessions);

        sessions.clear();

        // Should pass
        sessions.add(TestUtils.createSession("id1", SessionType.NOTEBOOK, Session.STATUS_TERMINATING));
        testSubject.checkExistingSessions(SessionType.DESKTOP, sessions);

        sessions.clear();

        // Should fail (max 1 session by default)
        sessions.add(TestUtils.createSession("id1", SessionType.NOTEBOOK, Session.STATUS_PENDING));
        try {
            testSubject.checkExistingSessions(SessionType.NOTEBOOK, sessions);
            Assert.fail("Should throw IllegalArgumentException");
        } catch (IllegalArgumentException exception) {
            // Good.
        }
    }
}
