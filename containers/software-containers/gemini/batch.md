
# Notes on Building a Nifty batch VM (or, Nifty on Docker on VM on Batch)

- Got an Ubuntu VM running with some instructions:
	- https://www.canfar.net/en/docs/quick_start/
	- https://docs.computecanada.ca/wiki/Creating_a_Linux_VM

- ssh'd into the VM and installed software for docker:
	- https://docs.docker.com/engine/install/ubuntu/
  - https://docs.docker.com/engine/install/linux-postinstall/ #Because batch can't run docker with sudo

```bash
sudo apt-get update -y
# Remove old docker software
sudo apt-get remove docker docker-engine docker.io containerd runc
# Start Docker installation
sudo apt-get install -y \
    apt-transport-https \
    ca-certificates \
    curl \
    gnupg-agent \
    software-properties-common

curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo apt-key add -

# Check fingerprint is correct, perhaps we should do this differently with grep or something...
sudo apt-key fingerprint 0EBFCD88

sudo add-apt-repository \
   "deb [arch=amd64] https://download.docker.com/linux/ubuntu \
   $(lsb_release -cs) \
   stable"

sudo apt-get install -y docker-ce docker-ce-cli containerd.io

sudo groupadd docker
sudo usermod -aG docker $USER
wget https://raw.githubusercontent.com/canfar/canfarproc/master/worker/bin/canfar_create_user -p /usr/local/bin && chmod +x /usr/local/bin/canfar_create_user
sudo canfar_create_user nat
sudo  usermod -aG docker nat

# Run batch prepare
wget https://raw.githubusercontent.com/canfar/canfarproc/master/worker/bin/canfar_batch_prepare -P /usr/local/bin
chmod +x /usr/local/bin/canfar_batch_prepare
sudo canfar_batch_prepare
```

- Reboot the VM, and log in as the "nat" user on the VM. 

```bash
# Install vos and vos requirements
pip install vos --upgrade --user
# Here, if using old vos, make sure to edit the vos config file to point to Cavern!

# Run getCert for vos write permissions
mkdir ~/.ssl
getCert

# Test it to make sure everything works
docker run hello-world
docker run -it nat1405/nifty:0.1 runNifty nifsPipeline -s 'CADC' -f GN-2014A-Q-85
```


- Save an image snapshot.
- Now login to `nat@batch.canfar.net` and submit the batch jobs following the instructions [here](https://www.canfar.net/en/docs/batch_processing/).
    - I used the `c1-7.5gb-30` flavour at first, but found it was too small so now use the `c4-30gb-83` flavour with `request_cpus = 4` in the .jdl file.

- Some useful commands that let you attach to the docker container while it's running on batch:
    - Used `cloud_status -m | grep nat` to get hostname (starts with cc-arbutus) of running VM
    - Used `condor_q -better-analyze -reverse slot1@<hostname>` to see job info
    - Added my public key (and made sure it had the right permissions) for the VM I built to my batch profile, then I could ssh into the running VM from batch: `ssh -i ~/.ssh/<vm key> nat@<vm hostname>`
    - Could list the running containers with `docker ps` and attach with `docker attach <container id>` 

Awesome!

- To check if jobs terminated completely without error:

`grep "DATA REDUCTION COMPLETE" *.out`


- Here's a submission script that automatically copies all the results of a batch submission to Cavern (use this as the `executable = ...` in your .jdl files:

```bash
#!/bin/bash
  
id=$1

start_time=$(date -u +UT%F-%H%M%S)

scratchdir=${PWD}
echo $scratchdir

docker run -v ${scratchdir}:/scratch nat1405/nifty:0.1 runNifty nifsPipeline -s 'CADC' -f $1

echo "Starting copy of results to Cavern."
/home/nat/.local/bin/vmkdir vos:projects/Gemini/reductions/$1-${start_time}
for file in *; do
        if [[ "$file" != "pyraf" ]] && [ "$file" != "login.cl" ]; then
                /home/nat/.local/bin/vcp $file vos:projects/Gemini/reductions/$1-${start_time}/$file
        fi
done
echo "Done."
```



















