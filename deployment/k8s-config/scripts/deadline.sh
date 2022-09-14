#!/bin/bash

DEADLINE=1814400
WORKSPACE=skaha-workload
JOBFILE=jobs.txt

#Make a list off all jobs, excluding headless batch jobs:
kubectl -n $WORKSPACE get jobs | grep -Eiv "headless" | awk '{print $1}'ls > $JOBFILE

#Loop through jobs in list and set activeDeadlineSeconds to new value:
while read J; do
  kubectl -n $WORKSPACE patch job $J --type='json' -p '[{"op":"add","path":"/spec/activeDeadlineSeconds", "value":'$DEADLINE'}]'
done <$JOBFILE

