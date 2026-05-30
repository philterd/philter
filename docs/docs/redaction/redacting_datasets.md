# Redacting High-Volume Data Sets via S3

For organizations dealing with massive volumes of information, Philter offers a high-throughput dataset redaction capability. This feature allows you to process entire datasets stored directly in your own Amazon S3 buckets, providing a secure and scalable way to anonymize large-scale data for research, analytics, or secondary use.

## Architectural Overview: Secure S3 Integration

The dataset redaction process works by securely accessing your source data in an S3 bucket, processing it through our redaction engine, and writing the protected output back to a destination S3 bucket of your choice.

To maintain maximum security and data sovereignty, Philterd utilizes AWS IAM (Identity and Access Management) roles and cross-account permissions. This ensures that you never have to share your actual AWS credentials; instead, you grant our service temporary, scoped access to only the specific buckets required for the task.

### Recommended Configuration

We strongly recommend using two separate S3 buckets:

1.  **Source Bucket**: Contains your raw, sensitive datasets.
2.  **Destination Bucket**: A dedicated bucket for the redacted output. This prevents any risk of accidentally overwriting your original data and allows for tighter access controls on the protected results.

## Configuring AWS Permissions

To enable Philter to interact with your S3 buckets, you must establish the appropriate IAM policies and roles within your AWS account.

### 1. Granting Read Access to Source Data

First, create an IAM policy that permits the retrieval of objects from your source bucket.

**Example Read Policy (JSON)**:
```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": "s3:GetObject",
            "Resource": "arn:aws:s3:::your-source-bucket-name/*"
        }
    ]
}
```

### 2. Granting Write Access for Redacted Output

Next, create a second IAM policy that allows the engine to deposit the redacted files into your destination bucket.

**Example Write Policy (JSON)**:
```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": "s3:PutObject",
            "Resource": "arn:aws:s3:::your-destination-bucket-name/*"
        }
    ]
}
```

### 3. Establishing the Trust Relationship (IAM Role)

Once the policies are created, you must create an IAM Role that Philter can assume.

1.  **Trust Type**: When creating the role in the AWS Console, select **AWS account** as the trusted entity type.
2.  **Account ID**: Provide the Philter AWS Account ID. (Please [contact our support team](../support.md) to receive our current production Account ID).
3.  **Attach Policies**: Attach the Read and Write policies you created in the previous steps to this role.
4.  **Register the ARN**: After the role is created, copy its **Role ARN** (e.g., `arn:aws:iam::123456789012:role/Philterd-Dataset-Access`).
5.  **Configure Philterd**: Enter this ARN in your Philter Account Settings under the **Dataset Role ARN** section.

## Initiating a Dataset Redaction Task

With the AWS permissions in place, you can trigger a dataset redaction task through either the [Web Dashboard](../dashboard.md) or the [REST API](../developers/developer_quick_start.md).

1.  **Specify Source**: Provide the S3 URI of the dataset to be processed (e.g., `s3://your-source-bucket-name/folder/dataset.csv`).
2.  **Define Output**: Provide the S3 URI where the redacted data should be saved.
3.  **Select Policy**: Choose the [Redaction Policy](../redaction/policies.md) that defines the rules for the task.
4.  **Monitor Progress**: High-volume tasks are processed asynchronously. You can monitor the status and retrieve completion logs through the dashboard's Dataset section.

## Important Security Considerations

*   **Principle of Least Privilege**: Always scope your IAM policies to the specific buckets and folders required. Avoid using `*` for resources whenever possible.
*   **Encryption**: Ensure that both your source and destination buckets have server-side encryption enabled. Philter is fully compatible with S3-managed encryption (SSE-S3).
*   **Audit Trails**: Use AWS CloudTrail to monitor when and how the Philterd IAM role accesses your data, providing an extra layer of visibility and compliance.