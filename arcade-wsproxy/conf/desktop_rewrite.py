#!/usr/bin/python

import sys
import subprocess
import time
import traceback
import os
from urlparse import urlparse
from cachetools import TTLCache

def getRedirect(input):

  log("DEBUG: proxying desktop k8s session")
  log("DEBUG: input=" + input)
  if input is None:
    log("WARN: no input")
    return None
  
  params = input.split(",")

  url = urlparse(params[0])
  path = url.path
  segs = path.split("/")

  sessionID = segs[2]
  log("DEBUG: sessionID=" + sessionID)

  ipAddress = getIPForSession(sessionID)

  if ipAddress is None:
    log("WARN: IP Address not found")
    return None

  port = "6901"

  ret = "http://" + ipAddress + ":" + port + "/?password=" + sessionID + "/"
  log("DEBUG: Segs[3]: " + segs[3])
  if (segs[3] == "websockify"):
    ret = "ws://" + ipAddress + ":" + port + "/websockify"
  elif (segs[3] != "connect"):
    idx = path.find(sessionID)
    endOfPath = path[(idx+8):]
    ret = "http://" + ipAddress + ":" + port + endOfPath
  return ret

def getIPForSession(sessionID):
  sessionIPAddress = getIPFromCache(sessionID)
  if sessionIPAddress:
    return sessionIPAddress
  else:
    try:
      command = ["kubectl", "--kubeconfig=/root/kube/k8s-config", "get", "pod", "--selector=canfar-net-sessionID=" + sessionID, "--no-headers=true", "-o", "custom-columns=IPADDR:.status.podIP"]
      commandString = ' '.join([str(elem) for elem in command])
      log("DEBUG: kubectl command: " + commandString)
      sessionIPAddress = subprocess.check_output(command, stderr=subprocess.STDOUT)
    except subprocess.CalledProcessError as exc:
      log("ERROR: error calling kubectl: " + exc.output)
      return None
    else:
      log("DEBUG: sessionIPAddress: " + sessionIPAddress)
      cache[sessionID] = sessionIPAddress.strip()
      return sessionIPAddress.strip()

def getIPFromCache(sessionID):
  try:
    cv = cache[sessionID]
    return cv
  except KeyError:
    return None

def log(message):
  logfile.write(time.ctime() + " - " + message + "\n")
  logfile.flush()

logfile = open("/logs/desktop-rewrite.log", "a")
cache = TTLCache(maxsize=100, ttl=120)
log("INFO: desktop_rewrite.py listening to stdin")
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
