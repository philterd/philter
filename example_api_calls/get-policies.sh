#!/bin/bash -e

API_KEY=""

curl -s -X GET "http://localhost:8080/api/policies" -H "Accept: application/json" -H "Authorization: Bearer ${API_KEY}" | jq
