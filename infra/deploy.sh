#!/usr/bin/env bash
# Create or update the async plane. Safe to re-run.
#   ./infra/deploy.sh            deploy and print outputs
#   ./infra/deploy.sh --write-env  also patch .env with the values
set -euo pipefail
cd "$(dirname "$0")/.."

STACK="${STACK_NAME:-relevance-auditor}"
REGION="${AWS_REGION:-$(aws configure get region)}"

echo "deploying $STACK to $REGION ..."
aws cloudformation deploy \
  --template-file infra/relevance-auditor.yaml \
  --stack-name "$STACK" \
  --region "$REGION" \
  --no-fail-on-empty-changeset \
  --tags project=relevance-auditor

get() {
  aws cloudformation describe-stacks --stack-name "$STACK" --region "$REGION" \
    --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text
}

QUEUE_URL=$(get QueueUrl)
DLQ_URL=$(get DlqUrl)
TOPIC_ARN=$(get TopicArn)

echo
echo "AWS_REGION=$REGION"
echo "ENRICHMENT_QUEUE_URL=$QUEUE_URL"
echo "DEAD_LETTER_QUEUE_URL=$DLQ_URL"
echo "DECISIONS_TOPIC_ARN=$TOPIC_ARN"

if [[ "${1:-}" == "--write-env" ]]; then
  [[ -f .env ]] || cp .env.example .env
  for pair in "AWS_REGION=$REGION" "ENRICHMENT_QUEUE_URL=$QUEUE_URL" \
              "DEAD_LETTER_QUEUE_URL=$DLQ_URL" "DECISIONS_TOPIC_ARN=$TOPIC_ARN"; do
    key="${pair%%=*}"
    if grep -q "^${key}=" .env; then
      # '|' as the delimiter: URLs and ARNs are full of slashes and colons
      sed -i '' "s|^${key}=.*|${pair}|" .env
    else
      echo "$pair" >> .env
    fi
  done
  echo
  echo "wrote 4 values into .env"
fi
