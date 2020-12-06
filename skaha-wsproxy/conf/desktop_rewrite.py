#!/usr/bin/env python

import os
import skaha_rewrite


class DesktopRewrite(skaha_rewrite.Rewrite):
    def __init__(self, log_file_fqn):
        super(DesktopRewrite, self).__init__(log_file_fqn)

    def _build_url(self, segs, path, session_id, ip_address, params):
        port = '6901'
        ret = 'http://{}:{}/?password={}/'.format(ip_address, port, session_id)
        self.log('DEBUG: Segs[3]: {}'.format(segs[3]))
        if segs[3] == 'websockify':
            ret = 'ws://{}:{}/websockify'.format(ip_address, port)
        elif segs[3] != 'connect':
            idx = path.find(session_id)
            end_of_path = path[(idx+8):]
            ret = 'http://{}:{}{}'.format(ip_address, port, end_of_path)
        return ret


if __name__ == '__main__':
    sr = DesktopRewrite('/logs/desktop-rewrite.log')
    sr.log('INFO: desktop_rewrite.py listening to stdin')
    os.environ['HOME'] = '/root'
    sr.log('INFO: entering listen loop')
    while True:
        sr.listen()
