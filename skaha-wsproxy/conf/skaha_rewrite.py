# -*- coding: utf-8 -*-
# ***********************************************************************
# ******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
# *************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
#
#  (c) 2020.                            (c) 2020.
#  Government of Canada                 Gouvernement du Canada
#  National Research Council            Conseil national de recherches
#  Ottawa, Canada, K1A 0R6              Ottawa, Canada, K1A 0R6
#  All rights reserved                  Tous droits réservés
#
#  NRC disclaims any warranties,        Le CNRC dénie toute garantie
#  expressed, implied, or               énoncée, implicite ou légale,
#  statutory, of any kind with          de quelque nature que ce
#  respect to the software,             soit, concernant le logiciel,
#  including without limitation         y compris sans restriction
#  any warranty of merchantability      toute garantie de valeur
#  or fitness for a particular          marchande ou de pertinence
#  purpose. NRC shall not be            pour un usage particulier.
#  liable in any event for any          Le CNRC ne pourra en aucun cas
#  damages, whether direct or           être tenu responsable de tout
#  indirect, special or general,        dommage, direct ou indirect,
#  consequential or incidental,         particulier ou général,
#  arising from the use of the          accessoire ou fortuit, résultant
#  software.  Neither the name          de l'utilisation du logiciel. Ni
#  of the National Research             le nom du Conseil National de
#  Council of Canada nor the            Recherches du Canada ni les noms
#  names of its contributors may        de ses  participants ne peuvent
#  be used to endorse or promote        être utilisés pour approuver ou
#  products derived from this           promouvoir les produits dérivés
#  software without specific prior      de ce logiciel sans autorisation
#  written permission.                  préalable et particulière
#                                       par écrit.
#
#  This file is part of the             Ce fichier fait partie du projet
#  OpenCADC project.                    OpenCADC.
#
#  OpenCADC is free software:           OpenCADC est un logiciel libre ;
#  you can redistribute it and/or       vous pouvez le redistribuer ou le
#  modify it under the terms of         modifier suivant les termes de
#  the GNU Affero General Public        la “GNU Affero General Public
#  License as published by the          License” telle que publiée
#  Free Software Foundation,            par la Free Software Foundation
#  either version 3 of the              : soit la version 3 de cette
#  License, or (at your option)         licence, soit (à votre gré)
#  any later version.                   toute version ultérieure.
#
#  OpenCADC is distributed in the       OpenCADC est distribué
#  hope that it will be useful,         dans l’espoir qu’il vous
#  but WITHOUT ANY WARRANTY;            sera utile, mais SANS AUCUNE
#  without even the implied             GARANTIE : sans même la garantie
#  warranty of MERCHANTABILITY          implicite de COMMERCIALISABILITÉ
#  or FITNESS FOR A PARTICULAR          ni d’ADÉQUATION À UN OBJECTIF
#  PURPOSE.  See the GNU Affero         PARTICULIER. Consultez la Licence
#  General Public License for           Générale Publique GNU Affero
#  more details.                        pour plus de détails.
#
#  You should have received             Vous devriez avoir reçu une
#  a copy of the GNU Affero             copie de la Licence Générale
#  General Public License along         Publique GNU Affero avec
#  with OpenCADC.  If not, see          OpenCADC ; si ce n’est
#  <http://www.gnu.org/licenses/>.      pas le cas, consultez :
#                                       <http://www.gnu.org/licenses/>.
#
#  : 4 $
#
# ***********************************************************************
#

import os
import subprocess
import sys
import traceback
import time

from cachetools import TTLCache
from urlparse import urlparse


class Rewrite(object):
    """
    Common functionality for any skaha re-write. Over-ride the _build_url
    method before using.
    """

    def __init__(self, log_fqn, max_size=100, ttl=120):
        """
        :param log_fqn: Fully-qualified name for the log file.
        :param max_size: maximum size of the cache
        :param ttl: time to live of cache entries
        """
        self._log_fqn = log_fqn
        if os.path.exists(log_fqn):
            self._log_file = open(log_fqn, 'a')
        else:
            dir_name = os.path.dirname(log_fqn)
            if not os.path.exists(dir_name):
                os.mkdir(dir_name)
            self._log_file = open(log_fqn, 'w')

        self._cache = TTLCache(maxsize=max_size, ttl=ttl)

    def _build_url(self, segs, path, session_id, ip_address, params):
        return None

    def _get_ip_for_session(self, session_id):
        session_ip_address = self._cache.get(session_id)
        if session_ip_address:
            return session_ip_address
        else:
            try:
                command = ['kubectl',
                           '-n',
                           'skaha-workload',
                           '--kubeconfig=/root/kube/k8s-config',
                           'get',
                           'pod',
                           '--selector=canfar-net-sessionID={}'.format(
                               session_id),
                           '--no-headers=true',
                           '-o',
                           'custom-columns=IPADDR:.status.podIP,'
                           'DT:.metadata.deletionTimestamp']
                command_string = ' '.join([str(elem) for elem in command])
                self.log('DEBUG: kubectl command: {}'.format(command_string))
                command_output = subprocess.check_output(
                    command, stderr=subprocess.STDOUT)
                lines = command_output.splitlines()
                for line in lines:
                    parts = line.split()
                    if len(parts) > 1 and parts[1].strip() == '<none>':
                        session_ip_address = parts[0].strip()
            except subprocess.CalledProcessError as exc:
                self.log('ERROR: error calling kubectl: {}'.format(exc.output))
                return None
            else:
                self.log('DEBUG: session_ip_address: {}'.format(
                    session_ip_address))
                if session_ip_address is not None:
                    self._cache[session_id] = session_ip_address
                return session_ip_address

    def _get_redirect(self, from_stdin):
        self.log('DEBUG: proxying carta session')
        self.log('DEBUG: from_stdin=' + from_stdin)
        if from_stdin is None:
            self.log('WARN: no from_stdin')
            return None

        params = from_stdin.split(',')

        url = urlparse(params[0])
        path = url.path
        segs = path.split('/')
        self.log('DEBUG: len(segs): {}'.format(str(len(segs))))

        session_id = segs[2]
        self.log('DEBUG: session_id={}'.format(session_id))

        ip_address = self._get_ip_for_session(session_id)

        if ip_address is None:
            self.log('WARN: IP Address not found')
            return None

        return self._build_url(segs, path, session_id, ip_address, params)

    def listen(self):
        try:
            request = sys.stdin.readline().strip()
            self.log('INFO: Start request: {}'.format(request))
            response = self._get_redirect(request)
            if response:
                self.log('INFO: End response: {}'.format(response))
                sys.stdout.write(response + '\n')
            else:
                self.log('INFO: End response: None')
                sys.stdout.write('https://www.canfar.net/notfound.html\n')
        except Exception as e:
            tb = traceback.format_exc()
            self.log('ERROR: unexpected: {}:{}'.format(str(e), tb))
            sys.stdout.write('https://www.canfar.net/notfound.html\n')
        except:
            self.log('ERROR: unclassified error')
            sys.stdout.write('https://www.canfar.net/notfound.html\n')
        sys.stdout.flush()

    def log(self, message):
        self._log_file.write(time.ctime() + u' - ' + message.encode() + u'\n')
        self._log_file.flush()
