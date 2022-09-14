#!/bin/bash
JOBFILE=jobs.txt
WORKSPACE=skaha-workload

#Make a list off all jobs, excluding headless batch jobs:
kubectl -n $WORKSPACE get jobs | grep -Eiv "headless" | awk '{print $1}'ls > $JOBFILE

#Loop through jobs in list and set activeDeadlineSeconds to new value:
while read J; do
  kubectl -n $WORKSPACE patch job $J --type='json' -p '[{"op":"add","path":"/spec/activeDeadlineSeconds", "value":1814400}]'
done <$JOBFILE

