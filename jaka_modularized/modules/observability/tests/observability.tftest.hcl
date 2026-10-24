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

  # A mocked policy document would otherwise return random text
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{}"
    }
  }
}

variables {
  name_prefix = "jaka-test"
  environment = "test"
  alarm_email = "ops@example.com"

  services = {
    order = {
      container_name          = "order-service"
      asg_name                = "jaka-test-order-asg"
      target_group_arn_suffix = "targetgroup/order/1"
      log_group_name          = "/microservice/jaka-test/order-service"
    }
    payment = {
      container_name          = "payment-service"
      asg_name                = "jaka-test-payment-asg"
      target_group_arn_suffix = "targetgroup/payment/2"
      log_group_name          = "/microservice/jaka-test/payment-service"
    }
  }

  databases = {
    order   = "jaka-test-order-db"
    payment = "jaka-test-payment-db"
  }

  redis_cluster_ids = ["jaka-test-redis-001", "jaka-test-redis-002"]
  alb_arn_suffix    = "app/jaka-test-alb/abc"
}

run "one_alarm_set_per_service_db_and_node" {
  command = plan

  assert {
    condition = alltrue([
      length(aws_cloudwatch_metric_alarm.asg_cpu_high) == 2,
      length(aws_cloudwatch_metric_alarm.hikari_pool_high) == 2,
      length(aws_cloudwatch_metric_alarm.app_log_errors_high) == 2,
      length(aws_cloudwatch_metric_alarm.db_cpu_high) == 2,
      length(aws_cloudwatch_metric_alarm.redis_cpu_high) == 2,
    ])
    error_message = "Expected one alarm per service / database / redis node."
  }

  assert {
    condition     = aws_cloudwatch_metric_alarm.asg_cpu_high["order"].alarm_name == "jaka-test-order-asg-cpu-high"
    error_message = "Alarm names must keep the <name_prefix>-<service>-... convention."
  }
}

run "every_alarm_notifies_the_topic" {
  command = plan

  assert {
    condition = alltrue([
      for a in concat(
        values(aws_cloudwatch_metric_alarm.asg_cpu_high),
        values(aws_cloudwatch_metric_alarm.db_free_storage_low),
        values(aws_cloudwatch_metric_alarm.redis_memory_high),
        [aws_cloudwatch_metric_alarm.alb_5xx_high],
      ) : length(a.alarm_actions) == 1
    ])
    error_message = "Every alarm must have the SNS topic as its action."
  }
}

run "dashboard_scales_with_services" {
  command = plan

  variables {
    services = {
      order = {
        container_name          = "order-service"
        asg_name                = "a"
        target_group_arn_suffix = "t"
        log_group_name          = "/l/order"
      }
    }
    databases = { order = "jaka-test-order-db" }
  }

  assert {
    condition     = length(local.dashboard_widgets[0].properties.metrics) == 1
    error_message = "With one service the CPU widget must have exactly one line."
  }
}

run "every_dashboard_metric_row_is_a_list" {
  command = plan

  # A row starts with its namespace string, or with an { expression = ... } object for
  # metric math. flatten() leaves loose strings (row[0] fails); concat() without the
  # spread leaves groups of rows (row[0] is itself a row, so a list)
  assert {
    condition = alltrue(flatten([
      for w in local.dashboard_widgets : [
        for row in try(w.properties.metrics, []) : can(tostring(row[0])) || can(keys(row[0]))
      ]
    ]))
    error_message = "Every dashboard metrics entry must be one row like [\"AWS/...\", \"MetricName\", ...], not a loose string or a group of rows."
  }
}

run "no_email_means_no_subscription" {
  command = plan

  variables {
    alarm_email = null
  }

  assert {
    condition     = length(aws_sns_topic_subscription.alarms_email) == 0
    error_message = "alarm_email = null must create no subscription."
  }
}

run "disk_threshold_out_of_range_is_rejected" {
  command = plan

  variables {
    thresholds = { asg_disk_percent = 99 }
  }

  expect_failures = [var.thresholds]
}
