#!/bin/bash

quarkus_version=999-SNAPSHOT

read -p "Service name: " service_name
proxy_deployment_name="proxy-$service_name"

echo "Creating files..."

cat src/main/kubernetes/service-template.yml | sed "s/SERVICE_SELECTOR/$proxy_deployment_name/g" > tmp.yml
mv tmp.yml service.yml
cat service.yml | sed "s/SERVICE_NAME/$service_name/g" > tmp.yml
mv tmp.yml service.yml
cat src/main/kubernetes/client-template.yml | sed "s/SERVICE_SELECTOR/$proxy_deployment_name/g" > tmp.yml
mv tmp.yml client.yml
cat client.yml | sed "s/SERVICE_NAME/$service_name/g" > tmp.yml
mv tmp.yml client.yml
cat src/main/kubernetes/deployment-template.yml | sed "s/PROXY_DEPLOYMENT_NAME/$proxy_deployment_name/g" > tmp.yml
mv tmp.yml deployment.yml
cat deployment.yml | sed "s/QUARKUS_VERSION/$quarkus_version/g" > tmp.yml
mv tmp.yml deployment.yml
cat deployment.yml | sed "s/PROXY_DEPLOYMENT_NAME/$proxy_deployment_name/g" > tmp.yml
mv tmp.yml deployment.yml
cat deployment.yml | sed "s/SERVICE_NAME_VALUE/$service_name/g" > tmp.yml
mv tmp.yml deployment.yml
cat deployment.yml | sed "s/SERVICE_HOST_VALUE/origin-$service_name/g" > tmp.yml
mv tmp.yml deployment.yml
cat deployment.yml | sed "s/SERVICE_PORT_VALUE/80/g" > tmp.yml
mv tmp.yml deployment.yml
cat deployment.yml | sed "s/SERVICE_SSL_VALUE/false/g" > tmp.yml
mv tmp.yml deployment.yml

cat src/main/kubernetes/origin-template.yml | sed "s/SERVICE_SELECTOR/$service_name/g" > tmp.yml
mv tmp.yml origin.yml
cat origin.yml | sed "s/SERVICE_NAME/origin-$service_name/g" > tmp.yml
mv tmp.yml origin.yml

echo "Done!"

