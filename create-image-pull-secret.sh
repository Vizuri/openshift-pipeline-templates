oc create secret docker-registry quay-registry \
    --docker-server=52.91.247.224:30080 \
    --docker-username=admin \
    --docker-password=P@ssw0rd \
    --docker-email=admin@vizuri.com



oc secrets link deployer quay-registry --for=pull
