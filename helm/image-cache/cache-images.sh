#!/bin/bash

harborHosts=$(echo $HARBOR_HOST | tr ' ' '\n')

URL="https://$harborHost/api/v2.0/projects?page_size=100"
PROJECT_URL="https:/$harborHost/api/v2.0/projects"

# checking if redis instance available or not
if [ -z "$HARBOR_HOST" ]; then
    echo "Redis server is not running. Please start the Redis server and try again."
    exit 1
else
    echo "Writing to ${REDIS_HOST}:${REDIS_PORT}"
fi

temp_key=$(date +%s)

# Fetch the data from the given URL
echo "$harborHosts" | while read -r harborHost; do
    URL="https://$harborHost/api/v2.0/projects?page_size=100"
    PROJECT_URL="https://$harborHost/api/v2.0/projects"

    echo "Fetching images from ${URL}..."
    response=$(curl -k $URL)

    # Parse the response and iterate over the list
    echo $response | jq -c '.[]' | while read -r project; do
        project_name=$(echo $project | jq -r '.name')

        project_data=$(curl -k -s "$PROJECT_URL/$project_name/repositories?page_size=-1")

        # Null check for project_data
        if [ -z "$project_data" ] || [ "$project_data" == "null" ]; then
            continue
        fi

        echo $project_data | jq -c '.[]' | while read -r repo; do
            repo_name=$(echo $repo | jq -r '.name')

            name=$(echo $repo_name | awk -F'/' '{print $NF}')
            repo_data=$(curl -k -s "$PROJECT_URL/$project_name/repositories/$name/artifacts?detail=true&with_label=true&page_size=-1")

            echo $repo_data | jq -c '.[]' | while read -r artifact; do
                tag=$(echo $artifact | jq -r .tags[0].name)

                if [ -z "$tag" ] || [ "$tag" == "null" ]; then
                    continue
                fi

                image_id="$harborHost/$project_name/$name:$tag"
                labelArray=$(echo $artifact | jq -r .labels)

                # check if labels are empty
                if [ -z "$labelArray" ] || [ "$labelArray" == "null" ]; then
                    continue
                fi

                labels=$(echo $artifact | jq -c [.labels[].name])

                refined_artifact=$(echo $artifact | jq -c --argjson labels "$labels" --arg id "$image_id" '{id: $id, types: $labels, digest: .digest}')
                echo $refined_artifact | redis-cli -h $REDIS_HOST -p $REDIS_PORT -x rpush "$temp_key"
                echo "Added ${image_id}"
            done
        done
    done
done && redis-cli -h $REDIS_HOST -p $REDIS_PORT rename "$temp_key" public
