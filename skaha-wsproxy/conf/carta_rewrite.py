#!/usr/bin/python

import sys
import subprocess
import time
import traceback
import os
from urlparse import urlparse
from cachetools import TTLCache

def getRedirect(input):

  log("DEBUG: proxying carta session")
  log("DEBUG: input=" + input)
  if input is None:
    log("WARN: no input")
    return None
  
  params = input.split(",")

  url = urlparse(params[0])
  path = url.path
  segs = path.split("/")
  log("DEBUG: len(segs): " + str(len(segs)))

  sessionID = segs[2]
  log("DEBUG: sessionID=" + sessionID)

  ipAddress = getIPForSession(sessionID)

  if ipAddress is None:
    log("WARN: IP Address not found")
    return None

  port = "6901"
  bport = "5901"

  idx = path.find(sessionID)
  endOfPath = path[(idx+8):]

  log("DEBUG: Segs[3]: " + segs[3])
  ret = ""
  if (segs[3] == "socket"):
    ret = "ws://" + ipAddress + ":" + bport + "/"
  else:
    ret = "http://" + ipAddress + ":" + port + endOfPath
  return ret

def getIPForSession(sessionID):
  sessionIPAddress = getIPFromCache(sessionID)
  if sessionIPAddress:
    return sessionIPAddress
  else:
    try:
      command = ["kubectl", "-n", "skaha-workload", "--kubeconfig=/root/kube/k8s-config", "get", "pod", "--selector=canfar-net-sessionID=" + sessionID, "--no-headers=true", "-o", "custom-columns=IPADDR:.status.podIP,DT:.metadata.deletionTimestamp"]
      commandString = ' '.join([str(elem) for elem in command])
      log("DEBUG: kubectl command: " + commandString)
      commandOutput = subprocess.check_output(command, stderr=subprocess.STDOUT)
      lines = commandOutput.splitlines()
      for line in lines:
        parts = line.split()
        if (parts[1].strip() == "<none>"):
          sessionIPAddress = parts[0]
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

logfile = open("/logs/carta-rewrite.log", "a")
log("INFO: carta_rewrite.py listening to stdin")
os.environ['HOME'] = '/root'
cache = TTLCache(maxsize=100, ttl=120)
log("INFO: entering listen loop")

while True:
  try:
    request = sys.stdin.readline().strip()
    log("INFO: Start request: " + request)
    response = getRedirect(request)
    if response:
      log("INFO: End response: " + response)
      sys.stdout.write(response + '\n')
    else:
      log("INFO: End response: None")
      sys.stdout.write('https://www.canfar.net/notfound.html\n')
  except Exception as e:
    tb = traceback.format_exc()
    log("ERROR: unexpected: " + str(e) + ":" + tb) 
    sys.stdout.write('https://www.canfar.net/notfound.html\n')
  except:
    log("ERROR: unclassified error")
    sys.stdout.write('https://www.canfar.net/notfound.html\n')
  sys.stdout.flush()
