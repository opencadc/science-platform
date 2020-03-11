#!/usr/bin/python

import sys
import subprocess
import time
import traceback
import os
from urlparse import urlparse

def getRedirect(input):

  log("DEBUG: proxying k8s session")
  log("DEBUG: input=" + input)
  if input is None:
    log("WARN: no input")
    return None
  
  params = input.split(",")

  url = urlparse(params[0])
  path = url.path
  segs = path.split("/")

  ipAddress = segs[2]
  log("DEBUG: ipAddress=" + ipAddress)
  sessionID = segs[3]
  log("DEBUG: sessionID=" + sessionID)

  if ipAddress is None:
    log("WARN: IP Address not found")
    return None

  port = "6901"

  ret = "http://" + ipAddress + ":" + port + "/?password=" + sessionID + "/"
  log("DEBUG: Segs[4]: " + segs[4])
  if (segs[4] == "websockify"):
    ret = "ws://" + ipAddress + ":" + port + "/websockify"
  elif (segs[4] != "connect"):
    idx = path.find(sessionID)
    endOfPath = path[(idx+8):]
    ret = "http://" + ipAddress + ":" + port + endOfPath
  return ret

def log(message):
  logfile.write(time.ctime() + " - " + message + "\n")
  logfile.flush()

logfile = open("/logs/rewrite.log", "a")
log("INFO: session_rewrite.py listening to stdin")
log("INFO: entering listen loop")

while True:
  log("INFO: getting hostname")
  #hostname = os.environ['HOME', 'proto.canfar.net']
  # above line produces KeyError when run with kubernetes... environment
  # is missing.
  hostname = 'proto.canfar.net'
  log("INFO: hostname: " + hostname)
  try:
    request = sys.stdin.readline().strip()
    log("INFO: Start request: " + request)
    response = getRedirect(request)
    if response:
      log("INFO: End response: " + response)
      sys.stdout.write(response + '\n')
    else:
      log("INFO: End response: None")
      sys.stdout.write('http://' + hostname + '/notfound.html\n')
  except Exception as e:
    tb = traceback.format_exc()
    log("ERROR: unexpected: " + str(e) + ":" + tb) 
    sys.stdout.write('http://' + hostname + '/notfound.html\n')
  except:
    log("ERROR: unclassified error")
    sys.stdout.write('http://' + hostname + '/notfound.html\n')
  sys.stdout.flush()
