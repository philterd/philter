#!/bin/bash -e

POLICY_NAME="default"
API_KEY=""

curl -s -X GET "http://localhost:8080/api/policies/${POLICY_NAME}" -H "Authorization: Bearer ${API_KEY}" -H "Accept: application/json" | jq
