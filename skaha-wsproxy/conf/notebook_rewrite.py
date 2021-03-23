#!/usr/bin/env python

import os
import skaha_rewrite


class NotebookRewrite(skaha_rewrite.Rewrite):
    def __init__(self, log_file_fqn):
        super(NotebookRewrite, self).__init__(log_file_fqn)

    def _build_url(self, segs, path, scheme, session_id, ip_address, params):
        query_string = None
        if len(params) > 2:
            query_string = params[2].strip()
            self.log('DEBUG: query={}'.format(query_string))

        address = '://{}:8888/notebook/'.format(ip_address)

        idx = path.find(session_id)
        end_of_path = path[(idx+8):]
        self.log('DEBUG: end_of_path: {}'.format(end_of_path))

        if (len(segs) > 4 and segs[3] == 'api' and segs[4] == 'kernels') or ('websocket' in path):
            if query_string:
                ret = 'ws{}{}{}?{}'.format(
                  address, session_id, end_of_path, query_string)
            else:
                ret = 'ws{}{}{}'.format(address, session_id, end_of_path)
        else:
            if query_string:
                ret = 'http{}{}{}?{}&token={}'.format(
                  address, session_id, end_of_path, query_string, session_id)
            else:
                ret = 'http{}{}{}?token={}'.format(
                  address, session_id, end_of_path, session_id)
        return ret


if __name__ == '__main__':
    sr = NotebookRewrite('/etc/httpd/logs/notebook-rewrite.log')
    sr.log('INFO: notebook_rewrite.py listening to stdin')
    os.environ['HOME'] = '/root'
    sr.log('INFO: entering listen loop')
    while True:
        sr.listen()
