mock_provider "aws" {}
mock_provider "random" {}

variables {
  name_prefix        = "jaka-test"
  private_subnet_ids = ["subnet-aaa", "subnet-bbb"]
  security_group_id  = "sg-redis"
  kms_key_arn        = "arn:aws:kms:ap-southeast-1:123456789012:key/key-id"
}

run "encrypted_with_auth" {
  command = plan

  assert {
    # at_rest_encryption_enabled is a string attribute in provider v5.x
    condition     = tobool(aws_elasticache_replication_group.this.at_rest_encryption_enabled) && tobool(aws_elasticache_replication_group.this.transit_encryption_enabled)
    error_message = "Redis must be encrypted at rest and in transit."
  }
}

run "two_nodes_enable_failover" {
  command = plan

  assert {
    condition     = aws_elasticache_replication_group.this.automatic_failover_enabled && aws_elasticache_replication_group.this.multi_az_enabled
    error_message = "With 2+ nodes, automatic failover and Multi-AZ must be on."
  }

  assert {
    condition     = output.cluster_ids == ["jaka-test-redis-001", "jaka-test-redis-002"]
    error_message = "cluster_ids must follow the ElastiCache member naming scheme."
  }
}

run "single_node_for_dev" {
  command = plan

  variables {
    num_cache_clusters = 1
  }

  assert {
    condition     = !aws_elasticache_replication_group.this.automatic_failover_enabled && !aws_elasticache_replication_group.this.multi_az_enabled
    error_message = "A single node must run without failover (AWS rejects failover with 0 replicas)."
  }
}

run "zero_nodes_is_rejected" {
  command = plan

  variables {
    num_cache_clusters = 0
  }

  expect_failures = [var.num_cache_clusters]
}
