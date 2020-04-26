#!/usr/bin/python

import sys
import subprocess
import time
import traceback
import os
from urlparse import urlparse

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

  ipAddress = segs[2]
  log("DEBUG: ipAddress=" + ipAddress)
  sessionID = segs[3]
  log("DEBUG: sessionID=" + sessionID)

  if ipAddress is None:
    log("WARN: IP Address not found")
    return None

  port = "6901"
  bport = "5901"

  idx = path.find(sessionID)
  endOfPath = path[(idx+8):]

  log("DEBUG: Segs[4]: " + segs[4])
  ret = ""
  if (segs[4] == "socket"):
    # do auth check on socket call
    log("DEBUG: getting session IP address")
    sessionIPAddress = getIPForSession(sessionID)
    if sessionID is None:
      return None
    if (sessionIPAddress != ipAddress):
      log("ERROR: wrong ip for session")
      return None
    else:
      log("DEBUG: ip addresses match, authorized")

    ret = "ws://" + ipAddress + ":" + bport + "/"
  else:
    ret = "http://" + ipAddress + ":" + port + endOfPath
  return ret

def getIPForSession(sessionID):
  command = ["kubectl", "--kubeconfig=/root/kube/k8s-config", "get", "pod", "--selector=canfar-net-sessionID=" + sessionID, "--no-headers=true", "-o", "custom-columns=IPADDR:.status.podIP"] 
  commandString = ' '.join([str(elem) for elem in command]) 
  log("DEBUG: kubectl command: " + commandString)
  sessionIPAddress = None
  try:
    sessionIPAddress = subprocess.check_output(command, stderr=subprocess.STDOUT)
  except subprocess.CalledProcessError as exc:
    log("ERROR: error calling kubectl: " + exc.output)
    return None
  else:
    log("DEBUG: sessionIPAddress: " + sessionIPAddress)
    return sessionIPAddress.strip()

def initK8S():
  command = ["kubectl", "config", "--kubeconfig=/www/bin/k8s-config", "use-context", "kanfarnetes-testing"]
  commandString = ' '.join([str(elem) for elem in command])
  log("DEBUG: kubectl init: " + commandString)
  try:
    result = subprocess.check_output(command, stderr=subprocess.STDOUT)
    log("DEBUG: success k8s init: " + result)
    command = ["kubectl", "config", "view"]
    subprocess.check_output(command, stderr=subprocess.STDOUT)
    log("DEBUG:" + result)
  except subprocess.CalledProcessError as exc:
    log("ERROR: error calling kubectl: " + exc.output)

def log(message):
  logfile.write(time.ctime() + " - " + message + "\n")
  logfile.flush()

logfile = open("/logs/carta-rewrite.log", "a")
log("INFO: carta_rewrite.py listening to stdin")
#initK8S()
os.environ['HOME'] = '/root'
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
