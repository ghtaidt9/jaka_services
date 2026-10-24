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
}

variables {
  name_prefix            = "jaka-test"
  environment            = "test"
  service                = "order"
  container_port         = 8080
  health_check_path      = "/api/orders/actuator/health"
  path_patterns          = ["/api/orders", "/api/orders/*"]
  listener_arn           = "arn:aws:elasticloadbalancing:ap-southeast-1:123456789012:listener/app/x/y/z"
  listener_rule_priority = 100
  vpc_id                 = "vpc-123"
  private_subnet_ids     = ["subnet-aaa", "subnet-bbb"]
  security_group_ids     = ["sg-ec2"]
  kms_key_arn            = "arn:aws:kms:ap-southeast-1:123456789012:key/key-id"
  s3_bucket_arn          = "arn:aws:s3:::jaka-test-microservices-123456789012"

  database = {
    host               = "order-db.local"
    port               = 5432
    name               = "orders"
    admin_username     = "order_admin"
    admin_secret_arn   = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:rds-admin"
    app_username       = "order_user"
    app_secret_arn     = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:order-app"
    url_parameter_name = "/jaka-test/order-db-url"
  }

  redis = {
    primary_endpoint_parameter_name = "/jaka-test/redis-primary-endpoint"
    port_parameter_name             = "/jaka-test/redis-port"
    auth_secret_arn                 = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:redis-auth"
    database_index                  = 0
  }
}

run "instances_are_private_and_imdsv2_only" {
  command = plan

  assert {
    condition     = aws_autoscaling_group.this.vpc_zone_identifier == toset(["subnet-aaa", "subnet-bbb"])
    error_message = "The ASG must launch only into the private subnets it was given."
  }

  assert {
    condition     = aws_launch_template.this.metadata_options[0].http_tokens == "required"
    error_message = "IMDSv2 must be required."
  }
}

run "routing_matches_the_service" {
  command = plan

  assert {
    condition     = aws_lb_target_group.this.port == 8080 && aws_lb_target_group.this.health_check[0].path == "/api/orders/actuator/health"
    error_message = "Target group port / health check path must come from the inputs."
  }

  assert {
    condition     = aws_lb_listener_rule.this.priority == 100
    error_message = "Listener rule priority must come from the input."
  }
}

run "iam_is_scoped_to_this_service" {
  command = plan

  assert {
    condition = alltrue([
      for arn in local.readable_parameter_arns :
      startswith(arn, "arn:aws:ssm:ap-southeast-1:123456789012:parameter/jaka-test/")
    ])
    error_message = "The role may only read parameters under /<name_prefix>/."
  }

  assert {
    condition     = !contains(local.readable_secret_arns, "*")
    error_message = "Secrets must be listed explicitly, never *."
  }

  assert {
    condition     = length(aws_iam_role_policy_attachment.managed) == 3
    error_message = "Expected SSM core, ECR read-only and CloudWatch agent managed policies."
  }
}

run "explicit_ami_skips_the_lookup" {
  command = plan

  variables {
    ami_id = "ami-localstack"
  }

  assert {
    condition     = length(data.aws_ami.ubuntu) == 0 && aws_launch_template.this.image_id == "ami-localstack"
    error_message = "With ami_id set, the Ubuntu lookup must be skipped."
  }
}

run "uppercase_service_is_rejected" {
  command = plan

  variables {
    service = "Order"
  }

  expect_failures = [var.service]
}
