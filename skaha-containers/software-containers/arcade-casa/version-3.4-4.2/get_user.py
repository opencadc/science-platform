import errno
import os
import pwd

def get_user():
    # Seen in
    # http://cr.opensolaris.org/~migi/24_09_os_unix_using_osgetlogin_3595/test1.py
    def __get_username():
        # Cron jobs, at least in Ubuntu, are more likely to have LOGNAME set
        # than USER.
        #
        # Note that environment variables are easily forged...
        #
        user = os.getenv('USER') or os.getenv('LOGNAME') or \
               os.getenv('USERNAME')

        if not user:
            # ...but when su is being used it's hard to tell in advance
            # whether the uid or euid is wanted.
            return pwd.getpwuid(os.getuid()).pw_name

        return user

    try:
        # os.getlogin() is limited to whatever POSIX getlogin() does.
        return os.getlogin()
    except AttributeError:
        # os.getlogin() not available on this platform.
        return __get_username()
    except OSError, e:
        if e.errno == errno.ENOTTY:
            # Known failure case for gksu.
            return __get_username()
        else:
            # In most cases, just do an os.getlogin() yourself if you want to see the error.
            #print "os.getlogin() raised exception", e
            #print "get_user() is using __get_username() instead of os.getlogin()."
            return __get_username()
        #raise
