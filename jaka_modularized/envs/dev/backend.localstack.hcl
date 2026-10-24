# LocalStack. Its account ID is always 000000000000.
bucket       = "jaka-mod-tfstate-000000000000"
key          = "dev/terraform.tfstate"
region       = "ap-southeast-1"
encrypt      = true
use_lockfile = true

endpoints = {
  s3 = "http://localhost:4566"
}
use_path_style              = true
access_key                  = "test"
secret_key                  = "test"
skip_credentials_validation = true
skip_requesting_account_id  = true
skip_metadata_api_check     = true
skip_s3_checksum            = true
