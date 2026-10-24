# Use together with terraform.tfvars when targeting LocalStack:
#   terraform plan -var-file terraform.tfvars -var-file localstack.tfvars
use_localstack = true

# LocalStack has no Ubuntu 22.04 image, so the default AMI lookup fails there.
# List the images LocalStack ships and paste one ID here (see README, step "LocalStack"):
#   aws --endpoint-url http://localhost:4566 ec2 describe-images --owners 099720109477 --query "Images[].[ImageId,Name]" --output table
# ami_id = "ami-xxxxxxxxxxxx"
