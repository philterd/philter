# Upgrading Philter

We recommend reviewing the [Philter Release Notes](https://www.philterd.ai/philter-release-notes/) prior to upgrading.

## Upgrading from a 2.x Version

Upgrading Philter to the newest version requires moving Philter's configuration to the new version of Philter. To upgrade Philter from a 2.x version, follow the steps below.

1. Launch a new instance of the newest version of Philter.
2. Copy your policies from /opt/philter/policies to the new instance.
3. Copy your /opt/philter/philter.properties to the new instance.
4. Copy your /opt/philter/philter-ui.properties to the new instance.
5. Replace the new virtual machine's properties file with your copy from step 1.
6. Copy your policies from /opt/philter/policies to the new instance.
7. If you have configured any SSL certificates for Philter, copy those files over to the new instance.
8. Restart Philter: sudo systemctl restart philter.service && sudo systemctl restart philter-ui.service && sudo systemctl restart philter-ner.service
9. Test the new Philter virtual machine to make sure it is behaving as expected.
10. Decommission the old Philter instance.

## Upgrading from a 1.x Version

Upgrading Philter to the newest version requires moving Philter's configuration to the new version of Philter. To upgrade Philter from a 1.x version, follow the steps below.

1. Make local copies of your current Philter's properties files.

  * `/opt/philter/philter.properties` (prior to 1.10.1 the filename was /opt/philter/application.properties)
  * `/opt/philter/philter-ui.properties` (not applicable prior to version 1.10)

2. Launch a new instance of the newest version of Philter.
3. Replace the new virtual machine's properties file with your copy from step 1.
4. Restart Philter: sudo systemctl restart philter.service sudo systemctl restart philter-ui.service sudo systemctl restart philter-ner.service
5. Test the new Philter virtual machine to make sure it is behaving appropriately.
6. Decommission the old Philter instance.
