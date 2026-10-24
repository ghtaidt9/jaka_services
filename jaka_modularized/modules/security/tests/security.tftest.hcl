mock_provider "aws" {}

variables {
  name_prefix = "jaka-test"
}

run "kms_key_rotates" {
  command = plan

  assert {
    condition     = aws_kms_key.secrets.enable_key_rotation == true
    error_message = "The secrets KMS key must have automatic rotation enabled."
  }

  assert {
    condition     = aws_kms_alias.secrets.name == "alias/jaka-test-secrets"
    error_message = "KMS alias must be alias/<name_prefix>-secrets."
  }
}

run "ssm_caller_user_is_optional" {
  command = plan

  variables {
    create_ssm_caller_user = false
  }

  assert {
    condition     = length(aws_iam_user.ssm_caller) == 0 && length(aws_iam_policy.ssm_caller) == 0
    error_message = "create_ssm_caller_user = false must create no IAM user or policy."
  }
}

run "deletion_window_out_of_range_is_rejected" {
  command = plan

  variables {
    kms_deletion_window_in_days = 3
  }

  expect_failures = [var.kms_deletion_window_in_days]
}
