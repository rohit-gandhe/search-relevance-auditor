#!/usr/bin/env bash
# Delete everything the stack created. Nothing else is touched.
set -euo pipefail
STACK="${STACK_NAME:-relevance-auditor}"
REGION="${AWS_REGION:-$(aws configure get region)}"

read -rp "delete stack '$STACK' in $REGION? [y/N] " ok
[[ "$ok" == "y" || "$ok" == "Y" ]] || { echo "cancelled"; exit 0; }

aws cloudformation delete-stack --stack-name "$STACK" --region "$REGION"
echo "deleting ..."
aws cloudformation wait stack-delete-complete --stack-name "$STACK" --region "$REGION"
echo "gone."
echo "note: SQS blocks reusing a queue name for ~60s after deletion."
