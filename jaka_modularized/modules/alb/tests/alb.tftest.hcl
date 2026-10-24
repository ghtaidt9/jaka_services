mock_provider "aws" {}

variables {
  name_prefix       = "jaka-test"
  public_subnet_ids = ["subnet-aaa", "subnet-bbb"]
  security_group_id = "sg-alb"
}

run "public_alb_with_404_default" {
  command = plan

  assert {
    condition     = aws_lb.this.internal == false
    error_message = "The ALB must be internet-facing."
  }

  assert {
    condition     = aws_lb_listener.http.default_action[0].fixed_response[0].status_code == "404"
    error_message = "Unmatched paths must get a 404, not be forwarded to a service."
  }
}

run "single_subnet_is_rejected" {
  command = plan

  variables {
    public_subnet_ids = ["subnet-aaa"]
  }

  expect_failures = [var.public_subnet_ids]
}
