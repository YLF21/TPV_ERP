# License Issuer Implementation Plan

**Goal:** Build an isolated Java 25, Spring Boot 4.0.6 desktop tool that creates signed, installation-bound license files.

**Architecture:** Keep domain models, validation, key-store management, cryptography, and file emission independent from Swing. A thin CLI and a thin Swing form call the same `LicenseIssuanceService`.

**Tech stack:** Java 25, Spring Boot 4.0.6, Swing, Gson, Bouncy Castle, JUnit 5, Maven Wrapper.

1. Define request and license input records with boundary validation.
2. Parse installation JSON and PEM-encoded RSA public keys.
3. Create or load a password-protected PKCS#12 issuer identity.
4. Encrypt a canonical payload with AES-256-GCM, wrap its key with RSA-OAEP SHA-256, and sign the canonical envelope with RSA-PSS SHA-256.
5. Export JSON envelopes through a testable service and CLI.
6. Add a Swing adapter and safe-operation documentation.
7. Run the complete test suite and package the executable jar.

All implementation follows red-green-refactor. No commits are made by this plan.
