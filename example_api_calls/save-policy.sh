#!/bin/bash -e

API_KEY=""

curl -k -v -s -X POST "https://localhost:8080/api/policies" -H "Authorization: Bearer ${API_KEY}" -H "Content-Type: application/json"  -d @../distribution/policies/default.json
