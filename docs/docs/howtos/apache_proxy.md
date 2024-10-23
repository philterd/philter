---
description: Describes how to configure an Apache reverse proxy in front of Philter.
---

# How to Use an Apache Reverse Proxy with Philter

Running the Apache web server in front of Philter can have a few benefits. You can use Apache's authentication mechanisms to have greater control over who can access Philter's API, you can use SSL termination at Apache, use Apache's logs for access statistics, for example.

When terminating the SSL at Apache, make sure that the Apache reverse proxy and Philter are running on the same host so unencrypted traffic is not being sent over the network.
To install and configure Apache on CentOS, RHEL and Amazon Linux follow the steps below. First, install the Apache:

```
sudo yum install httpd
```

Create the Philter configuration by creating a configuration file at `/etc/httpd/conf.d/philter.conf`:

```
<VirtualHost *:80>

  ProxyPreserveHost On
  ServerName philter.mydomain.com

  LogLevel warn
  ErrorLog logs/philter.mydomain.com-error_log
  CustomLog logs/philter.mydomain.com-access_log combined

  <Location />
    ProxyPass http://localhost:8080/
    ProxyPassReverse http://localhost:8080/
  </Location>

</VirtualHost>
```

Start Apache:

```
sudo systemctl start httpd
```

Make sure it started successfully:

```
sudo systemctl status httpd
```

Set the Apache service to start automatically:

```
sudo systemctl enable httpd
```

Verify you can access Philter through the reverse proxy:

```
curl http://philter.mydomain.com/api/status
```
