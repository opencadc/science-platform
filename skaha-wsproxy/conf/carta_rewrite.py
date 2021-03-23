#!/usr/bin/env python

import os
import skaha_rewrite


class CartaRewrite(skaha_rewrite.Rewrite):
    def __init__(self, log_file_fqn):
        super(CartaRewrite, self).__init__(log_file_fqn)

    def _build_url(self, segs, path, scheme, session_id, ip_address, params):
        port = '6901'
        bport = '5901'

        idx = path.find(session_id)
        end_of_path = path[(idx+8):]

        self.log('DEBUG: Segs[3]: {}'.format(segs[3]))
        if segs[3] == 'socket':
            ret = 'ws://{}:{}/'.format(ip_address, bport)
        else:
            ret = 'http://{}:{}{}'.format(ip_address, port, end_of_path)
        return ret


if __name__ == '__main__':
    sr = CartaRewrite('/etc/httpd/logs/carta-rewrite.log')
    sr.log('INFO: carta_rewrite.py listening to stdin')
    os.environ['HOME'] = '/root'
    sr.log('INFO: entering listen loop')
    while True:
        sr.listen()
