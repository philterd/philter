# Changesets

Changesets allow you to review and modify the redacted information in a document before final application to the original document.

## Enabling Changesets

Changesets are disabled by default. To enable changesets, go to your **Account** settings and toggle the **Enable Changesets** option. When enabled, the original uploaded document is retained so that the changeset can be applied to it later.

## How Changesets Work

When you upload a document for redaction with changesets enabled:

1. **Initial Redaction:** The document is processed according to your selected policy and context.
2. **Changeset Generation:** A changeset (version 1) is automatically generated. This changeset contains all the "spans" (pieces of information) identified for redaction, including their original text, replacement value, and location in the document.
3. **Review and Modify:** You can view the changeset in the **Redacted Documents** list. You can create new versions of the changeset and modify them by:
    - **Editing Replacements:** Change the text that will replace a sensitive token.
    - **Removing Spans:** Delete a span if you decide that a piece of information does not need to be redacted.
4. **Apply Changeset:** Once you are satisfied with the modifications, you can apply the changeset to the original document. This will generate a new version of the redacted document based on your modified spans.

Note that changesets do not allow you to create new spans. You can only modify existing spans or remove them.

## Using Changesets in the Dashboard

In the **Redact Documents** view, documents with changesets available will have the option to view its changeset.

1. Click the **Changeset** icon for a completed document.
2. The **Changeset** dialog will open, showing the identified spans.
3. To make modifications, click **Create New Version of Changeset**. This creates a new, editable version of the changeset.
4. In the new version, you can:
    - Click the **Edit** (pencil) icon to change a replacement value.
    - Click the **Remove** (trash) icon to delete a redaction span.
5. After making your changes, click **Apply Changeset to Original Document**. The modified document will be generated and downloaded to your computer.


