#!/bin/bash -e

POLICY_NAME="default"
API_KEY=""

curl -k -s -X DELETE "https://localhost:8080/api/policies/${POLICY_NAME}" -H "Authorization: Bearer ${API_KEY}"
