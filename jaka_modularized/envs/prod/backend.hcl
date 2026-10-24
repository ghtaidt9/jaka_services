# Real AWS. Replace <ACCOUNT_ID> with the value printed by `terraform output` in bootstrap/.
bucket       = "jaka-mod-tfstate-<ACCOUNT_ID>"
key          = "prod/terraform.tfstate"
region       = "ap-southeast-1"
encrypt      = true
use_lockfile = true
