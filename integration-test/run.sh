#! /bin/bash

# it will push the data.sql content into the db container
docker exec -i db mysql -uroot -ppwd iam < data.sql

./gradlew clean test --tests org.opencadc.skaha.ContextTest.userIsPartOfGroup

