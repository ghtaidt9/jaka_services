mock_provider "aws" {}
mock_provider "random" {}

variables {
  name_prefix        = "jaka-test"
  service            = "order"
  db_name            = "orders"
  admin_username     = "order_admin"
  app_username       = "order_user"
  private_subnet_ids = ["subnet-aaa", "subnet-bbb"]
  security_group_id  = "sg-rds"
  kms_key_id         = "key-id"
  kms_key_arn        = "arn:aws:kms:ap-southeast-1:123456789012:key/key-id"
}

run "private_encrypted_ssl_only" {
  command = plan

  assert {
    condition     = aws_db_instance.this.publicly_accessible == false
    error_message = "The DB must not be publicly accessible."
  }

  assert {
    condition     = aws_db_instance.this.storage_encrypted == true
    error_message = "Storage must be encrypted."
  }

  assert {
    condition     = one([for p in aws_db_parameter_group.this.parameter : p.value if p.name == "rds.force_ssl"]) == "1"
    error_message = "rds.force_ssl must be 1."
  }

  assert {
    condition     = aws_db_parameter_group.this.family == "postgres16"
    error_message = "Parameter group family must follow the engine major version."
  }
}

run "names_follow_the_service" {
  command = plan

  assert {
    condition     = aws_db_instance.this.identifier == "jaka-test-order-db"
    error_message = "Identifier must be <name_prefix>-<service>-db."
  }

  assert {
    condition     = aws_ssm_parameter.db_url.name == "/jaka-test/order-db-url"
    error_message = "JDBC URL parameter must be /<name_prefix>/<service>-db-url."
  }

  assert {
    condition     = aws_secretsmanager_secret.app.name == "jaka-test/order-db/app-credentials"
    error_message = "App secret must be <name_prefix>/<service>-db/app-credentials."
  }
}

run "prod_safe_defaults" {
  command = plan

  assert {
    condition     = aws_db_instance.this.multi_az && aws_db_instance.this.deletion_protection && !aws_db_instance.this.skip_final_snapshot
    error_message = "Defaults must be Multi-AZ, deletion-protected, with a final snapshot."
  }
}

run "dev_overrides" {
  command = plan

  variables {
    multi_az            = false
    deletion_protection = false
    skip_final_snapshot = true
  }

  assert {
    condition     = !aws_db_instance.this.multi_az && !aws_db_instance.this.deletion_protection
    error_message = "dev overrides must turn off Multi-AZ and deletion protection."
  }
}

run "sql_unsafe_app_username_is_rejected" {
  command = plan

  variables {
    app_username = "order-user; DROP ROLE x"
  }

  expect_failures = [var.app_username]
}
