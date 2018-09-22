#!/bin/bash

set -e

pushd quorum-cloud/aws/templates/_terraform_init
${TERRAFORM_CMD} init
${TERRAFORM_CMD} apply -var network_name=ci-${CONSENSUS}-${TRAVIS_COMMIT::6} -auto-approve
popd
pushd quorum-cloud/aws/templates
${TERRAFORM_CMD} init -no-color -backend-config=terraform.auto.backend_config
cat <<EOF > terraform.tfvars
is_igw_subnets = "false"
subnet_ids = [${PRIVATE_SUBNETS}]
bastion_public_subnet_id = "${PUBLIC_SUBNET}"
consensus_mechanism = "${CONSENSUS}"
EOF
popd

echo "Provisioning Quorum Network"
pushd quorum-cloud/aws/templates
${TERRAFORM_CMD} apply -auto-approve
private_key_file=$(${TERRAFORM_CMD} output -json | jq .private_key_file.value)
bastion_host_ip=$(${TERRAFORM_CMD} output -json | jq .bastion_host_ip.value)
popd

echo "Wait for the Quorum Network being ready"
while [ ! -f "config/application-local.yml" ]; do
   scp -o ServerAliveInterval=30 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=quiet \
       -i ${private_key_file} ec2-user@${bastion_host_ip}:/qdata/quorum_metadata config/application.yml > /dev/null 2>&1
done

echo "Start SOCKS proxy for SSH tunnelling"
ssh -D 5000 -N -o ServerAliveInterval=30 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=quiet
      -i ${private_key_file} ec2-user@${bastion_host_ip} &