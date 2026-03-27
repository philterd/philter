# Developer Quick Start

This guide provides a quick start for developers integrating with Philter. We'll walk through the process of creating a custom redaction policy, uploading a document for redaction, and downloading the result using both `curl` and Python.

## Step 1: Create a Redaction Policy

A redaction policy defines what information should be identified and how it should be handled. While you can use the `default` policy, creating a custom one allows for more control.

### The Policy (JSON)

Save the following as `my-policy.json`:

```json
{
  "version": "1.0",
  "name": "my-custom-policy",
  "filters": {
    "PERSON": [
      {
        "strategy": "REDACT"
      }
    ],
    "EMAIL_ADDRESS": [
      {
        "strategy": "MASK"
      }
    ]
  }
}
```

### Create the Policy via API

#### Using curl

```bash
curl -X POST "https://your-philter-endpoint:8080/api/policy/my-custom-policy" \
     -H "Authorization: Bearer YOUR_API_KEY" \
     -H "Content-Type: application/json" \
     -d @my-policy.json
```

#### Using Python

```python
import requests

api_key = "YOUR_API_KEY"
policy_name = "my-custom-policy"
url = f"https://your-philter-endpoint:8080/api/policy/{policy_name}"

with open("my-policy.json", "r") as f:
    policy_json = f.read()

headers = {
    "Authorization": f"Bearer {api_key}",
    "Content-Type": "application/json"
}

response = requests.post(url, headers=headers, data=policy_json)
print(response.json())
```

---

## Step 2: Upload a Document for Redaction

Now, let's upload a document (e.g., a PDF or .docx file) to be redacted using the policy we just created.

### Using curl

```bash
curl -X POST "https://your-philter-endpoint:8080/api/redact/documents?policy=my-custom-policy" \
     -H "Authorization: Bearer YOUR_API_KEY" \
     -H "Content-Type: application/pdf" \
     --data-binary @sample_document.pdf
```

### Using Python

```python
import requests

api_key = "YOUR_API_KEY"
policy_name = "my-custom-policy"
url = f"https://your-philter-endpoint:8080/api/redact/documents?policy={policy_name}"

headers = {
    "Authorization": f"Bearer {api_key}",
    "Content-Type": "application/pdf"
}

with open("sample_document.pdf", "rb") as f:
    response = requests.post(url, headers=headers, data=f)

result = response.json()
document_id = result["documentId"]
print(f"Document uploaded. Document ID: {document_id}")
```

The response will contain a `documentId`. Save this ID, as you'll need it to download the redacted file.

---

## Step 3: Download the Redacted Document

Once processing is complete (usually within seconds), you can download the redacted version using the document ID.

### Using curl

```bash
curl -X GET "https://your-philter-endpoint:8080/api/redact/documents/YOUR_DOCUMENT_ID" \
     -H "Authorization: Bearer YOUR_API_KEY" \
     --output redacted_document.pdf
```

### Using Python

```python
import requests

api_key = "YOUR_API_KEY"
document_id = "YOUR_DOCUMENT_ID"
url = f"https://your-philter-endpoint:8080/api/redact/documents/{document_id}"

headers = {
    "Authorization": f"Bearer {api_key}"
}

response = requests.get(url, headers=headers)

if response.status_code == 200:
    with open("redacted_document.pdf", "wb") as f:
        f.write(response.content)
    print("Redacted document downloaded successfully.")
else:
    print(f"Error: {response.status_code}")
```

## Next Steps

*   Explore the [API Integration](../developers/developer_quick_start.md) guide for more details.
*   Check out the [Policy Syntax Reference](../redaction/policy_syntax.md) for advanced configurations.
*   Visit the [Swagger UI](https://your-philter-endpoint:8080/swagger-ui/index.html) for interactive API documentation.