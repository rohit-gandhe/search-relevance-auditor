#!/usr/bin/env bash
# Empty both queues without destroying the stack — a clean slate before a demo run.
set -euo pipefail
STACK="${STACK_NAME:-relevance-auditor}"
REGION="${AWS_REGION:-$(aws configure get region)}"

get() {
  aws cloudformation describe-stacks --stack-name "$STACK" --region "$REGION" \
    --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text
}

for q in QueueUrl DlqUrl; do
  url=$(get "$q")
  aws sqs purge-queue --queue-url "$url" --region "$REGION"
  echo "purged $url"
done
echo "note: SQS allows one purge per queue per 60s."
