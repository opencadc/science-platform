# Skaha Api test

### To clone the dependent components  

```bash
sh setup.sh
```

It will take a clone of the github repos inside the dependencies folder. If it's already cloned then it will take a pull to fetch all the recent changes. (for skaha,reg,iam it is not working currently as target war/jar files are required by the specific DockerFile. For these services we have to create images manually)
It will also start the docker compose with all the components

### To test the skaha api

```bash
sh run.sh
```

It will insert the records into the DB(mysql) and then it will start the gradle test

### To stop the environment

```bash
sh destroy.sh
```

It will delete the dependencies folder and stop the docker compose