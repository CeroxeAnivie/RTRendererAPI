# Security Policy

## Supported versions

RTRendererAPI 1.x is the stable API line. Security fixes are provided for the latest patch of the current major line. Older Maven Central artifacts remain immutable and should be upgraded before reporting a defect.

| Version | Security fixes |
| --- | --- |
| `1.x` | Supported when running the latest patch |
| `< 1.0` | Not supported |

## Reporting a vulnerability

Do not disclose exploitable details in a public issue. Use the repository's [private security advisory form](https://github.com/CeroxeAnivie/RTRendererAPI/security/advisories/new). Include affected coordinates, platform and driver versions, a minimal reproducer, impact, and whether native code or untrusted assets are involved.

The maintainer targets acknowledgement within 7 days and an initial severity assessment within 14 days. These are response targets, not a guaranteed SLA. A confirmed issue is handled in a private advisory, assigned a compatible patch or an explicitly breaking release, and published with affected-version and mitigation details. Signing keys and Central credentials must be rotated if exposure cannot be excluded.

Public, non-sensitive defects belong in GitHub Issues. Dependency vulnerabilities are assessed against the shipped runtime graph rather than scanner presence alone; a suppression must identify the exact component, reachability rationale and review date.
