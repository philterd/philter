# Alerts API

The Alerts API provides endpoints for retrieving and deleting alerts. Alerts can optionally be generated when a filter strategy's condition is met. See [Alerts](../../other_features/alerts.md) for more information on Philter alerts.

> The `curl` example commands shown on this page are written assuming Philter has been enabled for SSL and it is using a self-signed certificate. If launched from a cloud marketplace, SSL will be enabled automatically with a self-signed SSL certificate. See the [SSL/TLS ](../../settings.md) settings for more information.


## Get Alerts

| Method | Endpoint | Description |
| ------ | -------- | ----------- | 
| `GET` | `/api/alerts` | Get alerts. |

Example request:

```
curl -k https://localhost:8080/api/alerts
```

## Delete an Alert

| Method   | Endpoint                | Description                                                        |
|----------|-------------------------|--------------------------------------------------------------------| 
| `DELETE` | `/api/alerts/{alertId}` | Delete an alert, where `alertId` is the ID of the alert to delete. |

Example request to delete an alert with id `12345`:

```
curl -k -X DELETE https://localhost:8080/api/alerts/12345
```