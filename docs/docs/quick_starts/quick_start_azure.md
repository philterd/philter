# Philter Quick Start on Microsoft Azure

Philter on Microsoft Azure is a virtual machine-based product. A free trial period is available during which there is no charge for the Philter software but there may be charges for the underlying Azure infrastructure.

> Cloud virtual machines launched from a cloud marketplace may not be immediately suitable for a HIPAA environment. Refer to your compliance officer for your organization's requirements to ensure compliance with all relevant regulations.


## Launch Philter on Microsoft Azure

1. Go to [Philter in the Azure Marketplace](https://azuremarketplace.microsoft.com/en-us/marketplace/apps/philterdllc1687189098111.philter?tab=Overview).
2. Click the **Get It Now** button.
3. Review the information that is shown on the popup and click **Continue** when ready.
4. You will now be asked to log in to your Microsoft Azure account if you were not already logged in.
5. Click the **Create** button to begin making a Philter virtual machine.
6. Enter the required details of the virtual machine and click the **Review + create** button.
7. Review the virtual machine details and click **Create** when ready!

Your Philter virtual machine will now be launching.

> Microsoft Azure will automatically open ports `22` (SSH) and `8080` (Philter API). These ports are required to be open but you may want to modify the security groups to limit their scope of availability by restricting access to specific CIDR ranges.


Congratulations! You have deployed Philter in Azure. You are now ready to filter text!

## Try it out!

Philter has no user interface; it is administered through its [API](../api_and_sdks/api.md). It does not start without two values, which must be set before its first start:

* `PHILTER_ENCRYPTION_KEY`: a base64-encoded 32-byte key (`openssl rand -base64 32`) that encrypts sensitive data at rest. Keep the same value for the life of the deployment and back it up: data encrypted with it cannot be recovered if it is lost or changed. See [Encryption](../settings.md#encryption).
* `PHILTER_BOOTSTRAP_API_KEY`: the first API key, `sk_` followed by 32 letters and digits (`echo "sk_$(openssl rand -hex 16)"`). Philter seeds it onto the `admin` user with every scope. See [bootstrap settings](../settings.md#bootstrap-and-in-memory-capacity).

Set `API_KEY` in your shell to the bootstrap key. Every request below authenticates with it.

For applications, create keys holding only the scopes they need, such as `redact`, with [`POST /api/api-keys`](../api_and_sdks/api/api_keys_api.md#create-a-key). To retire the bootstrap key, create a replacement administrator key holding every scope, then revoke the bootstrap key with the replacement: a key cannot revoke itself or a key holding scopes it lacks. [Philter UI](https://github.com/philterd/philter-ui), a separate web application in development, is being built to sign people in through Philter with their own username and password once `PASSWORD_SIGN_IN_ENABLED` is set; see [Sign-in Security](../sign_in_security.md).

With Philter now running we can take it for a spin. We will send some text to Philter and inspect at the response we get back. The Philter virtual machine running in your cloud account should have a public IP address (unless you customized the deployment). We will use that public IP address to interact with Philter.

Philter, by default, will be configured with an HTTPS listener on port 8080 using a self-signed certificate. It is recommended that prior to use in a production environment the self-signed certificate is replaced by a valid certificate owned by your organization.

In the command below, replace `<PUBLIC_IP>` with the virtual machine’s public IP address or public host name.

```
curl -k -X POST https://<PUBLIC_IP>:8080/api/filter --data "George Washington was a patient and his SSN is 123-45-6789." -H "Content-type: text/plain" -H "Authorization: Bearer $API_KEY"
```

With this command we are sending the text in the command to Philter for filtering. The selected policy controls which values are detected. The default policy includes person-name, SSN, and email filters; name detection requires a reachable PhEye service. Confirm that service is configured before expecting names to be removed. You can always use curl to send text to Philter as in these examples but there are also [SDKs](../api_and_sdks/sdks.md) you can use, too, to integrate Philter with your applications.

### Redacting Sensitive Information from Text

The types of sensitive information that Philter identifies and removes is controlled by policies. By default, Philter includes a filter profile that includes many of the types of sensitive information, such as names and social security numbers. We can send text to filter to Philter for filtering using this default filter profile with the following command:

```
curl -k -X POST https://localhost:8080/api/filter --data-binary @file.txt -H "Content-Type: text/plain" -H "Authorization: Bearer $API_KEY"
```

This command sends the contents of the file `file.txt` to Philter. Philter will apply the enabled filters and return a plain-text response consisting of the filtered text. (Replace localhost with the IP address or host name of Philter if you are not running the command where Philter is running.) You can also send text directly in the request instead of sending it as a file:

```
curl -k -X POST https://localhost:8080/api/filter --data "Your text goes here..." -H "Content-type: text/plain" -H "Authorization: Bearer $API_KEY"
```

## Next Steps

Now that you have Philter running and know how to send text to it, you are ready to integrate Philter into your existing workflow and systems. Philter’s API details how to send files to Philter. Clients for some languages for Philter’s API are available on GitHub.

> Be sure to check out [Policies](../policies/filter_policies.md) to see how you can customize the types of sensitive information Philter redacts!
