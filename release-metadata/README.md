# Release Metadata

Every formal `vX.Y.Z` tag must include `release-metadata/vX.Y.Z.json` in the tagged commit. The Release workflow rejects a tag without this file, a tag mismatch, or an invalid schema.

Routine releases use this form:

```json
{
  "releaseTag": "vX.Y.Z",
  "hostContractRequired": false,
  "minimumAgentVersion": 1
}
```

Set `hostContractRequired` to `true` when the release changes Compose, the update agent, systemd, directory permissions, Docker requirements, or another host contract. Agent v4 and later verify and install the signed, allowlisted host package themselves by default, then resume the same target Release. The `manual` text remains required as the safe fallback shown by old agents or by installations that explicitly disable automatic host-package application:

```json
{
  "releaseTag": "vX.Y.Z",
  "hostContractRequired": true,
  "minimumAgentVersion": 2,
  "manual": {
    "reason": "The update agent and its systemd permissions changed.",
    "instructions": "An old agent must bootstrap the verified host package once; agent v4 and later apply this package automatically."
  }
}
```

The manifest keeps the legacy wire value `host-package-manual-required` so older agents stop safely instead of treating a host contract change as a normal image update. Agent v4 and later interpret that signed type as an automatic root-agent operation when `AUTO_APPLY_HOST_PACKAGE_UPDATES=true`.

`minimumAgentVersion` cannot exceed `deploy/update/agent-version` packaged by the same tag. Any agent version increase must be delivered through the signed host package; do not use `workflow_dispatch` inputs to reclassify an existing tag.
