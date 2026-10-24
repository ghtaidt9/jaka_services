locals {
  project     = "jaka-mod"
  environment = "prod"
}

# prod: HA everywhere, data protected from accidental destroy.
# Diff this file against ../dev/main.tf to see every dev/prod difference.
module "stack" {
  source = "../../stack"

  project     = local.project
  environment = local.environment

  azs                = ["${var.region}a", "${var.region}b"]
  single_nat_gateway = false

  instance_type = "t3.small"
  ami_id        = var.ami_id
  image_tag     = var.image_tag

  asg = {
    min_size         = 2
    max_size         = 6
    desired_capacity = 2
  }

  postgres = {
    multi_az                = true
    backup_retention_period = 7
    skip_final_snapshot     = false
    deletion_protection     = true
  }

  redis = {
    num_cache_clusters = 2
  }

  s3_force_destroy               = false
  secret_recovery_window_in_days = 7
  log_retention_days             = 30

  alarm_email = var.alarm_email
}
