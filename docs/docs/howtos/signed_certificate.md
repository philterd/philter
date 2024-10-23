---
description: Describes how to use a signed SSL certificate with Philter.
---

# How to Use a Signed SSL Certificate with Philter

When Philter is deployed via the AWS Marketplace, Windows Azure Marketplace or other third-party cloud marketplace, SSL will already be enabled via a self-signed certificate. It is recommended you replace this self-signed certificate with a valid certificate issued to your organization by a trusted authority. The instructions for how to do this are described below.

First, create a private key and a certificate signing request (CSR) for Philter on your domain. In this walkthrough we are using the domain `philter.yourdomain.com` as an example.

```
openssl req -new -newkey rsa:2048 -nodes -keyout philter_yourdomain_com.key -out philter_youdomain_com.csr
```

Submit the CSR to your SSL certificate vendor of choice and complete the SSL certificate ordering process. If prompted for a web server during the process, select Apache or Nginx. Once the process is complete and the certificate is issued you will receive a few files. The files you will need are summarized in the table below. The file names may vary and you may also receive other files as well.

| File Name                          | Description                                                 | Creator                     |
| ---------------------------------- | ----------------------------------------------------------- | --------------------------- |
| `philter_yourdomain_com.csr`       | Certificate signing request                                 | Created by you              |
| `philter_yourdomain_com.key`       | Certificate private key                                     | Created by you              |
| `philter_yourdomain_com.ca-bundle` | Intermediate certificates provided by the issuing authority | Received from SSL authority |
| `philter_yourdomain_com.crt`       | The SSL certificate for philter.yourdomain.com              | Received from SSL authority |

When prompted for a keystore password we will use `changeit`. It's recommended you use a more secure password.

The first thing to do is to convert the certificate and the private key to PKCS12 format in `philter.p12`:

```
openssl pkcs12 -export -in philter_yourdomain_com.crt -inkey philter_yourdomain_com.key -name philter -out philter.p12
```

Now import the P12 file into a keystore `philter.jks`:

```
keytool -importkeystore -deststorepass changeit -destkeystore philter.jks -srckeystore philter.p12 -srcstoretype PKCS12
```

Add the intermediate certificate provided by the issuing authority to the keystore:

```
keytool -import -alias intermediate -trustcacerts -file philter_yourdomain.com.ca-bundle -keystore philter.jks
```

Update Philter's settings in `application.properties`:

```
# SSL certificate settings
server.ssl.key-store-type=JKS
server.ssl.key-store=/path/to/philter.jks
server.ssl.key-store-password=changeit
server.ssl.key-alias=philter
```

Restart Philter:

```
sudo systemctl restart philter
```

Execute an API status request to verify Philter is running as expected. With the `-v` option we can see the details of the SSL certificate:

```
curl -v https://philter.yourdomain.com:8080/api/status
```

Look in the response for details of the certificate. Our domain was `philter.mtnfog.dev`:

```
* Server certificate:
*  subject: CN=philter.mtnfog.dev
*  start date: Apr 21 00:00:00 2020 GMT
*  expire date: Apr 21 23:59:59 2021 GMT
*  subjectAltName: host "philter.mtnfog.dev" matched cert's "philter.mtnfog.dev"
*  issuer: C=GB; ST=Greater Manchester; L=Salford; O=Sectigo Limited; CN=Sectigo RSA Domain Validation Secure Server CA
*  SSL certificate verify ok.
```
