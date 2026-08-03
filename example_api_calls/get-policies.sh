#!/bin/bash -e

API_KEY=""

curl -k -s -X GET "https://localhost:8080/api/policies" -H "Accept: application/json" -H "Authorization: Bearer ${API_KEY}" | jq
