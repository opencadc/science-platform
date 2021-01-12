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
#  $Revision: 4 $
#
# ***********************************************************************
#

import sys
from io import StringIO
from mock import patch
from os import path, unlink

# because I do not yet know what the deployment/installation environment
# for this code will look like, take the easy way out:
sys.path.insert(0, path.dirname(path.dirname(path.abspath(__file__))))

import carta_rewrite as cr
import desktop_rewrite as dr
import notebook_rewrite as nbr
import skaha_rewrite as sr


@patch('subprocess.check_output')
def test_carta_rewrite(check_output_mock, monkeypatch):
    # nominal execution
    monkeypatch.setattr('sys.stdin',
                        StringIO(u'/carta/z6xrplsh/socket/,http\n'))
    check_output_mock.side_effect = _subprocess_mock
    test_subject = cr.CartaRewrite('/logs/carta-rewrite.log')
    test_subject.listen()
    assert check_output_mock.called, 'mock should be called'
    args, kwargs = check_output_mock.call_args
    assert 'stderr' in kwargs, 'expect stderr in kwargs'
    assert args[0] == ['kubectl', '-n', 'skaha-workload',
                       '--kubeconfig=/root/kube/k8s-config', 'get', 'pod',
                       '--selector=canfar-net-sessionID=z6xrplsh',
                       '--no-headers=true',
                       '-o',
                       'custom-columns=IPADDR:.status.podIP,'
                       'DT:.metadata.deletionTimestamp'], 'wrong command line'


@patch('subprocess.check_output')
def test_desktop_rewrite(check_output_mock, monkeypatch):
    # nominal execution
    monkeypatch.setattr('sys.stdin',
                        StringIO(u'/desktop/wjulbff1/websockify,http\n'))
    check_output_mock.side_effect = _subprocess_mock
    test_subject = dr.DesktopRewrite('/logs/desktop-rewrite.log')
    test_subject.listen()
    assert check_output_mock.called, 'mock should be called'
    args, kwargs = check_output_mock.call_args
    assert 'stderr' in kwargs, 'expect stderr in kwargs'
    assert args[0] == ['kubectl', '-n', 'skaha-workload',
                       '--kubeconfig=/root/kube/k8s-config', 'get', 'pod',
                       '--selector=canfar-net-sessionID=wjulbff1',
                       '--no-headers=true',
                       '-o',
                       'custom-columns=IPADDR:.status.podIP,'
                       'DT:.metadata.deletionTimestamp'], 'wrong command line'


@patch('subprocess.check_output')
def test_notebook_rewrite(check_output_mock, monkeypatch):
    # nominal execution
    monkeypatch.setattr('sys.stdin',
                        StringIO(u'/notebook/v5tmy4b2/terminals/websocket/'
                                 u'1,http,token=v5tmy4b2\n'))
    check_output_mock.side_effect = _subprocess_mock
    test_subject = nbr.NotebookRewrite('/logs/notebook-rewrite.log')
    test_subject.listen()
    assert check_output_mock.called, 'mock should be called'
    args, kwargs = check_output_mock.call_args
    assert 'stderr' in kwargs, 'expect stderr in kwargs'
    assert args[0] == ['kubectl', '-n', 'skaha-workload',
                       '--kubeconfig=/root/kube/k8s-config', 'get', 'pod',
                       '--selector=canfar-net-sessionID=v5tmy4b2',
                       '--no-headers=true',
                       '-o',
                       'custom-columns=IPADDR:.status.podIP,'
                       'DT:.metadata.deletionTimestamp'], 'wrong command line'


@patch('subprocess.check_output')
def test_rewrite_class_nones(check_output_mock):
    # the test_session_id value is None for the cases here
    check_output_mock.side_effect = _subprocess_mock_none

    test_log_fqn = '/logs/test_rewrite_none.log'
    if path.exists(test_log_fqn):
        unlink(test_log_fqn)

    test_subject = sr.Rewrite(test_log_fqn)
    test_subject.log('test content')
    assert test_subject is not None, 'expect a subject'
    for test_session_id in ['', 'not_found', None]:
        test_subject.log('TEST CASE SESSION ID: \"{}\"'.format(
            test_session_id))
        test_ip_address = test_subject._cache.get(test_session_id)
        assert test_ip_address is None, 'un-cached session {}'.format(
            test_session_id)
        test_ip_address = test_subject._get_ip_for_session(test_session_id)
        assert test_ip_address is None, \
            'k8s should not know the answer for \"{}\"'.format(test_session_id)
        args, kwargs = check_output_mock.call_args
        assert 'stderr' in kwargs, 'expect stderr in kwargs'
        assert args[0] == ['kubectl', '-n', 'skaha-workload',
                           '--kubeconfig=/root/kube/k8s-config', 'get', 'pod',
                           '--selector=canfar-net-sessionID={}'.format(
                               test_session_id),
                           '--no-headers=true',
                           '-o',
                           'custom-columns=IPADDR:.status.podIP,'
                           'DT:.metadata.deletionTimestamp'], \
            'wrong command line for {}'.format(test_session_id)

    assert path.exists(test_log_fqn), 'expect log file creation'
    with open(test_log_fqn, 'r') as f:
        content = f.readlines()
        assert len(content) == 10, 'wrong number of lines'
        assert content[0].endswith(' - test content\n'), 'wrong content'


def test_rewrite_class_get_redirect():
    # nominal cases for get_redirect
    test_log_fqn = '/logs/test_rewrite_nominal.log'
    if path.exists(test_log_fqn):
        unlink(test_log_fqn)

    test_ip_address = '8.8.8.8'
    test_session_id = 'z6xrplsh'

    cr_test_data = {
        '/carta/{}/socket/,http'.format(test_session_id):
        'ws://{}:5901/'.format(test_ip_address),
        '/carta/{}/manifest.json,http'.format(test_session_id):
        'http://{}:6901/manifest.json'.format(test_ip_address)}

    nbr_test_data = {
        '/notebook/{}/terminals/websocket/1,http,token={}'.format(
            test_session_id, test_session_id):
        'http://{}:8888/notebook/{}/terminals/websocket/1?token={}'
        '&token={}'.format(test_ip_address, test_session_id, test_session_id,
                           test_session_id)
    }

    dr_test_data = {
        '/desktop/{}/websockify,http'.format(test_session_id):
        'ws://{}:6901/websockify'.format(test_ip_address),
        '/desktop/{}/connect,http'.format(test_session_id):
        'http://{}:6901/?password={}/'.format(
            test_ip_address, test_session_id)
    }

    test_subjects = [cr.CartaRewrite(test_log_fqn),
                     dr.DesktopRewrite(test_log_fqn),
                     nbr.NotebookRewrite(test_log_fqn)]
    test_data = [cr_test_data, dr_test_data, nbr_test_data]
    for index, test_subject in enumerate(test_subjects):
        test_subject._cache[test_session_id] = test_ip_address
        for key, value in test_data[index].items():
            test_result = test_subject._get_redirect(key)
            assert test_result == value, 'expected desktop result {}'.format(
                value)


@patch('subprocess.check_output')
def test_rewrite_class(check_output_mock, monkeypatch):
    # nominal success case
    check_output_mock.side_effect = _subprocess_mock_none
    monkeypatch.setattr('sys.stdin',
                        StringIO(u'/notebook/v5tmy4b2/terminals/websocket/'
                                 u'1,http,token=v5tmy4b2\n'))

    test_log_fqn = '/logs/test_rewrite_nominal.log'
    if path.exists(test_log_fqn):
        unlink(test_log_fqn)

    test_subject = sr.Rewrite(test_log_fqn)
    test_subject.listen()
    assert check_output_mock.called, 'mock should be called'
    args, kwargs = check_output_mock.call_args
    assert 'stderr' in kwargs, 'expect stderr in kwargs'
    assert args[0] == ['kubectl', '-n', 'skaha-workload',
                       '--kubeconfig=/root/kube/k8s-config', 'get', 'pod',
                       '--selector=canfar-net-sessionID=v5tmy4b2',
                       '--no-headers=true',
                       '-o',
                       'custom-columns=IPADDR:.status.podIP,'
                       'DT:.metadata.deletionTimestamp'], 'wrong command line'


def _subprocess_mock(command_line, **kwargs):
    return u'10.233.68.53   <none>\n'


def _subprocess_mock_none(command_line, **kwargs):
    return '\n'
