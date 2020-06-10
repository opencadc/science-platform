#!/usr/bin/python

import sys
import subprocess
import time
import traceback
import os
from urlparse import urlparse

def getRedirect(input):

  log("DEBUG: proxying notebook session")
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

  port = "8888"
  wsPort = "8999"

  idx = path.find(sessionID)
  endOfPath = path[(idx+8):]
  log("DEBUG: endOfPath: " + endOfPath)

  ret = ""
  if (segs[3] == "socket"):
    ret = "ws://" + ipAddress + ":" + wsPort + "/"
  else:
    ret = "http://" + ipAddress + ":" + port + "/notebook/" + sessionID + endOfPath + "?token=" + sessionID
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

logfile = open("/logs/notebook-rewrite.log", "a")
log("INFO: notebook_rewrite.py listening to stdin")
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
