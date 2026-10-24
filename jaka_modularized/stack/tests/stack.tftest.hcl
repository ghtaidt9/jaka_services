mock_provider "aws" {
  mock_data "aws_region" {
    defaults = {
      name = "ap-southeast-1"
    }
  }

  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
    }
  }

  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{}"
    }
  }
}

mock_provider "random" {}

variables {
  environment = "dev"
  azs         = ["ap-southeast-1a", "ap-southeast-1b"]

  asg = {
    min_size         = 1
    max_size         = 2
    desired_capacity = 1
  }

  postgres = {
    multi_az                = false
    backup_retention_period = 1
    skip_final_snapshot     = true
    deletion_protection     = false
  }

  redis = {
    num_cache_clusters = 1
  }
}

run "one_db_and_one_asg_per_service" {
  command = plan

  assert {
    condition     = length(module.postgres) == 2 && length(module.service) == 2
    error_message = "Default services (order, payment) must each get a database and an ASG."
  }

  assert {
    condition     = output.asg_names == { order = "jaka-mod-dev-order-asg", payment = "jaka-mod-dev-payment-asg" }
    error_message = "ASG names must be <project>-<environment>-<service>-asg."
  }
}

run "third_service_is_one_map_entry" {
  command = plan

  variables {
    services = {
      order     = { container_port = 8080, listener_rule_priority = 100, db_name = "orders", redis_database_index = 0 }
      payment   = { container_port = 8081, listener_rule_priority = 200, db_name = "payments", redis_database_index = 1 }
      inventory = { container_port = 8082, listener_rule_priority = 300, db_name = "inventory", redis_database_index = 2 }
    }
  }

  assert {
    condition     = length(module.service) == 3 && length(module.postgres) == 3
    error_message = "Adding a map entry must add a database and a service."
  }
}

run "staging_is_rejected" {
  command = plan

  variables {
    environment = "staging"
  }

  expect_failures = [var.environment]
}

run "duplicate_listener_priority_is_rejected" {
  command = plan

  variables {
    services = {
      order   = { container_port = 8080, listener_rule_priority = 100, db_name = "orders", redis_database_index = 0 }
      payment = { container_port = 8081, listener_rule_priority = 100, db_name = "payments", redis_database_index = 1 }
    }
  }

  expect_failures = [var.services]
}
