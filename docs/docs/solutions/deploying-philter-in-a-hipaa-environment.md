# Deploying Philter in a HIPAA Environment

> This is not intended to be a comprehensive or legal HIPAA guide so please refer to your HIPAA compliance or security officer prior to deploying and using Philter in a PHI environment.


The steps below outline how to configure a Philter deployment for encryption of data at rest and in motion.

### Encryption of Data at Rest

#### Amazon Web Services

1. Stop the Philter EC2 instance.
2. Make an AMI of the instance.
3. Make an [encrypted copy](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/CopyingAMIs.html#ami-copy-encryption) of the Philter AMI.

The created AMI is encrypted. EC2 instances launched from the AMI will utilize an encrypted EBS volume and all snapshots will be encrypted. Refer to the AWS documentation [Creating an Amazon EBS-Backed Linux AMI](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/creating-an-ami-ebs.html) for assistance.

### Encryption of Data in Motion

#### Amazon Web Services

If launched from the Amazon Web Services, Google Cloud, or Microsoft Azure marketplace Philter's REST API will be pre-configured with a self-signed certificate. It is recommended you replace the self-signed certificate with a certificate from a trusted certificate authority.

1. Log in to the Philter EC2 instance via SSH. (On AWS the username is `ec2-user`. On Azure the username is `centos`.)
2. Stop the Philter service: `sudo systemctl stop philter.service`
3. Edit Philter's [settings](settings.md) to utilize an SSL certificate.
4. Start the Philter service: `sudo systemctl start philter.service`
5. Connect to Philter's API and verify the connection succeeds: `curl https://philter:8080/api/status` and returns `HTTP 200 OK`.

### Related Links

* [HIPAA Compliance in Amazon Web Services](https://aws.amazon.com/compliance/hipaa-compliance/)
* [Microsoft and HIPAA and HITECH Act](https://www.microsoft.com/en-us/trustcenter/compliance/hipaa)
