mock_provider "aws" {}

variables {
  name_prefix          = "jaka-test"
  vpc_cidr             = "10.0.0.0/16"
  azs                  = ["ap-southeast-1a", "ap-southeast-1b"]
  public_subnet_cidrs  = ["10.0.1.0/24", "10.0.2.0/24"]
  private_subnet_cidrs = ["10.0.11.0/24", "10.0.12.0/24"]
}

run "one_public_and_one_private_subnet_per_az" {
  command = plan

  assert {
    condition     = length(aws_subnet.public) == 2 && length(aws_subnet.private) == 2
    error_message = "Expected exactly one public and one private subnet per AZ."
  }

  assert {
    condition     = alltrue([for s in aws_subnet.private : s.map_public_ip_on_launch != true])
    error_message = "Private subnets must not auto-assign public IPs."
  }
}

run "nat_per_az_by_default" {
  command = plan

  assert {
    condition     = length(aws_nat_gateway.this) == 2
    error_message = "Default must be one NAT gateway per AZ (HA)."
  }
}

run "single_nat_gateway_for_dev" {
  command = plan

  variables {
    single_nat_gateway = true
  }

  assert {
    condition     = length(aws_nat_gateway.this) == 1 && length(aws_route.private_nat) == 2
    error_message = "single_nat_gateway must create 1 NAT shared by both private route tables."
  }
}

run "data_tier_not_reachable_from_internet" {
  command = plan

  assert {
    condition     = aws_vpc_security_group_ingress_rule.rds_from_ec2.cidr_ipv4 == null && aws_vpc_security_group_ingress_rule.redis_from_ec2.cidr_ipv4 == null
    error_message = "RDS / Redis ingress must reference the EC2 SG, never a CIDR."
  }
}

run "no_ssh_rule_unless_asked" {
  command = plan

  assert {
    condition     = length(aws_vpc_security_group_ingress_rule.ec2_ssh) == 0
    error_message = "SSH must be closed by default (access is through SSM)."
  }
}

run "invalid_cidr_is_rejected" {
  command = plan

  variables {
    vpc_cidr = "not-a-cidr"
  }

  expect_failures = [var.vpc_cidr]
}

run "subnet_count_must_match_azs" {
  command = plan

  variables {
    private_subnet_cidrs = ["10.0.11.0/24"]
  }

  expect_failures = [var.private_subnet_cidrs]
}
