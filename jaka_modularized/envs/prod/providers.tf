provider "aws" {
  region = var.region

  # LocalStack: endpoint comes from the AWS_ENDPOINT_URL env var (see README)
  s3_use_path_style           = var.use_localstack
  skip_credentials_validation = var.use_localstack
  skip_requesting_account_id  = var.use_localstack

  default_tags {
    tags = {
      Project     = local.project
      Environment = local.environment
      ManagedBy   = "Terraform"
    }
  }
}
