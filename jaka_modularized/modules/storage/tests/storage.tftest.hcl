mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
    }
  }
}

variables {
  name_prefix     = "jaka-test"
  tiered_prefixes = ["orders", "payments"]
}

run "bucket_is_private_encrypted_and_versioned" {
  command = plan

  assert {
    condition     = aws_s3_bucket.this.bucket == "jaka-test-microservices-123456789012"
    error_message = "Bucket name must be <name_prefix>-microservices-<account_id>."
  }

  assert {
    condition = alltrue([
      aws_s3_bucket_public_access_block.this.block_public_acls,
      aws_s3_bucket_public_access_block.this.block_public_policy,
      aws_s3_bucket_public_access_block.this.ignore_public_acls,
      aws_s3_bucket_public_access_block.this.restrict_public_buckets,
    ])
    error_message = "All four public-access-block flags must be on."
  }

  assert {
    condition     = aws_s3_bucket_versioning.this.versioning_configuration[0].status == "Enabled"
    error_message = "Versioning must be enabled."
  }
}

run "one_lifecycle_rule_per_prefix" {
  command = plan

  assert {
    condition     = length(aws_s3_bucket_lifecycle_configuration.this.rule) == 2
    error_message = "Expected one tiering rule per prefix."
  }
}

run "prod_default_keeps_objects_on_destroy" {
  command = plan

  assert {
    condition     = aws_s3_bucket.this.force_destroy == false
    error_message = "force_destroy must default to false."
  }
}

run "ia_transition_too_early_is_rejected" {
  command = plan

  variables {
    ia_transition_days = 7
  }

  expect_failures = [var.ia_transition_days]
}
