#!/bin/bash -e

curl -s -X GET "http://localhost:8080/api/policies" -H "Accept: application/json" | jq
