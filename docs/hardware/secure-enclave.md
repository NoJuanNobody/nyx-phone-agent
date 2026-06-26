# Secure Enclave — Pixel Titan M2

Status: **Draft / architecture spec.** No runtime or language is committed yet
(see [CLAUDE.md](../../CLAUDE.md)); this doc describes the hardware security
anchor the Nyx Agent is designed around and how the higher-level subsystems
plug into it.

> **Naming.** Earlier specs referred to a generic "secure enclave for key
> storage." The reference device is now a **Google Pixel**, whose dedicated
> security chip is the **Titan M2**. Where older docs say "secure enclave,"
> read **Titan M2**. Titan M2 is a *distinct* implementation — not a Qualcomm
> SPU and not an ARM TrustZone partition — exposed to apps through the standard
> **Android Keystore** API rather than a custom HAL.

---

## 1. What the Titan M2 is

Titan M2 is a discrete, tamper-resistant security microcontroller soldered onto
Pixel mainboards, physically separate from the main application SoC. It runs its
own minimal, formally-evaluated firmware and is the hardware root of trust for
the device. It is **CC EAL** / SESIP-evaluated silicon and backs several Android
platform security features.

What it provides that matters to Nyx:

| Capability | What it gives us |
|------------|------------------|
| **Hardware-backed keystore** | Private keys are generated and used **inside** Titan M2 and never leave it in plaintext — even a fully-rooted Android userspace cannot exfiltrate them. |
| **StrongBox Keymaster** | The Android Keymaster/KeyMint implementation running *on Titan M2* (as opposed to a TEE-backed implementation on the SoC). Requesting `setIsStrongBoxBacked(true)` routes a key to this chip. |
| **Key attestation** | Titan M2 can sign an attestation certificate chain (rooted in a Google CA) proving a key was generated in StrongBox, with which properties (purpose, auth-binding, rollback resistance), on a genuine, verified-boot device. |
| **Tamper resistance** | Hardware countermeasures against physical, side-channel, and fault-injection attacks; insider-resistant firmware update flow. |
| **Rollback / replay protection** | Hardware-backed monotonic counters and secure storage, usable to anchor an append-only structure against truncation/rollback. |
| **Rate-limiting / auth binding** | Keys can be bound to user authentication (lockscreen / biometric) and to per-use rate limits enforced in hardware. |

The key property for everything below: **a private key marked StrongBox-backed is
born and dies inside Titan M2.** Software only ever holds *handles* to it and
asks the chip to sign/decrypt on its behalf.

---

## 2. How Nyx uses it

The Nyx Agent runs on-device and can observe and act on the user's phone. That
makes its key material and its audit trail high-value targets. Titan M2 is the
anchor for three things.

### 2.1 Anchoring the Policy Engine's tamper-evident audit log

The [Policy Engine](../architecture/README.md) records **every agent action**
into a tamper-evident audit log (consent decisions, permission grants, tool
invocations). "Tamper-evident" only means something if the signing/anchoring
key cannot be silently stolen and used to forge or rewrite entries.

Design intent:

- The audit log is structured as an **append-only hash chain** (each entry
  commits to the hash of the previous entry).
- Each entry — or each periodic checkpoint over the chain head — is **signed by
  a StrongBox-backed key** that lives in Titan M2. A compromised userspace can
  *stop* logging, but it cannot **forge** entries or **rewrite history** without
  the hardware key, and gaps/forks break the hash chain visibly.
- Titan M2's **monotonic counters / rollback resistance** are used to bind
  checkpoints to a strictly increasing sequence, so an attacker cannot roll the
  log back to an earlier consistent state (truncation attack) undetected.

This is the load-bearing reason the secure element matters to Nyx: it converts
"tamper-evident" from a software promise into a hardware-enforced one.

### 2.2 Agent identity & attestation keys

- The agent device holds a **StrongBox-backed identity keypair**. Because
  Titan M2 is **attestation-capable**, this key can later support **remote
  attestation** of the agent device: a relying party (a cloud Nyx service, an
  MCP tool backend, a paired companion app) can verify, via the Google-rooted
  attestation chain, that it is talking to a genuine Pixel running verified boot
  with the agent key truly in StrongBox — not an emulator or a tampered build.
- The same primitive lets the system prove **integrity of the running agent**
  (key bound to a verified-boot state) without trusting self-reported claims.

> Remote attestation is called out as a **future** capability in issue #61; the
> hardware supports it today, but the protocol/relying-party side is not yet
> specified.

### 2.3 Consent-grant integrity

- When a user grants a consent scope (e.g. "may read SMS," "may place calls"),
  the grant record is **signed by a StrongBox-backed key**, so a stored consent
  grant cannot be fabricated or upgraded by malware that lacks the hardware key.
- Consent grant keys can be **auth-bound** — Titan M2 will only permit a signing
  operation after a fresh lockscreen/biometric authentication — so high-risk
  grants require a real, recent human action, enforced in hardware rather than
  by the app.

### 2.4 Agent API key storage (open design question from #61)

Issue #61 asks whether the standard Android Keystore provider (Titan M2-backed)
is **sufficient for agent API key storage**.

- For **asymmetric** secrets the answer is a clean yes: keys are non-extractable
  and operations happen in-chip.
- The wrinkle is **third-party bearer secrets** (e.g. an opaque cloud API
  token / string). Those are not keypairs, so they can't *be* a StrongBox key
  directly. The pattern is to **wrap** them: generate a StrongBox AES/EC key and
  use it to encrypt the bearer token at rest; the token is only decrypted
  transiently in app memory when needed. The wrapping key never leaves Titan M2,
  but the **decrypted token is briefly exposed to userspace** at use time — see
  Limitations (§5).
- **Decision: TBD.** Whether wrap-with-StrongBox is sufficient, or whether
  high-value tokens should be brokered server-side (so the device never holds a
  long-lived bearer secret at all), is left to the Policy Engine + cloud-broker
  spec.

---

## 3. Android StrongBox / Keymaster touchpoints

Conceptual only — concrete API/language is **TBD** until the runtime is chosen.
On Pixel, **no custom HAL is needed**: requesting hardware-backed keys routes
through Titan M2 automatically via the platform Keystore.

- **Provider / backend.** Keys are created via the Android Keystore. Requesting
  `setIsStrongBoxBacked(true)` on the key spec selects the **Titan M2-backed
  StrongBox Keymaster/KeyMint** implementation. Omitting it may yield a
  TEE-backed key on the SoC instead — Nyx security-critical keys (audit anchor,
  identity, consent) **must** request StrongBox explicitly and fail closed if it
  is unavailable.
- **Attestation.** Supplying an attestation challenge at key generation makes
  Titan M2 emit an X.509 **attestation certificate chain** describing the key's
  properties and the device's verified-boot state. Relying parties validate the
  chain to a Google root.
- **Auth binding.** Keys can be configured to require user authentication
  (`setUserAuthenticationRequired`) with a validity window or per-operation,
  enforced by the chip — used for consent-grant keys (§2.3).
- **Rollback resistance.** Where the platform exposes it, request rollback
  resistance for the audit-anchor/checkpoint keys.
- **Capability discovery.** The runtime must check StrongBox availability and
  the attestation feature at startup and degrade explicitly (never silently fall
  back to software keys for security-critical material).

> **No custom HAL.** Because Pixel wires Android Keystore hardware-backed keys
> to Titan M2 in the platform, Nyx does **not** need to write or ship a HAL,
> vendor driver, or AOSP keystore extension to get hardware-backed keys. This
> simplifies both Path A (custom AOSP ROM) and Path B (sideloaded APK).

---

## 4. Mapping: generic-enclave references → Titan M2 specifics

For anyone updating older specs (architecture Section 5 — Security Architecture,
the AOSP Extension doc, and any future keystore code):

| Generic reference (old) | Titan M2 specific (use this) |
|-------------------------|------------------------------|
| "secure enclave" | **Titan M2** (Pixel security chip) |
| "secure enclave for key storage" | **StrongBox Keymaster keys on Titan M2** |
| "store keys in the enclave" | Android Keystore key with **`setIsStrongBoxBacked(true)`** |
| "TEE / TrustZone" (if used loosely) | **Discrete Titan M2 chip**, *not* TrustZone/SPU — a separate die |
| "custom keystore HAL" | **None needed** — platform routes hardware-backed keys to Titan M2 automatically |
| "prove the device is genuine" | **Titan M2 key attestation** (Google-rooted X.509 chain) + verified boot |
| "tamper-proof log" | **Tamper-*evident*** hash-chain anchored by a StrongBox signing key + monotonic counters |
| "device identity key" | **StrongBox-backed identity keypair**, attestation-capable |

---

## 5. Threat model & limitations

**What Titan M2 protects against**

- Extraction of private keys by rooted/compromised Android userspace — keys are
  non-extractable.
- Silent forgery or rewriting of the audit log — anchoring key is in hardware;
  hash-chain + monotonic counters expose tampering and truncation.
- Spoofed/emulated devices — attestation + verified boot let a relying party
  reject non-genuine endpoints.
- A class of physical / side-channel / fault-injection attacks — hardware
  countermeasures raise the cost substantially.

**What it does NOT protect against (limitations)**

- **Data in use.** Titan M2 protects keys, not plaintext. Anything decrypted for
  use (a wrapped bearer token in §2.4, message contents, model I/O) is exposed
  in application memory and is only as safe as the OS/app. The chip is a key
  vault, not a confidential-compute enclave for arbitrary code.
- **Availability, not integrity.** A compromised userspace can **stop** the
  agent from logging or refuse to sign. Titan M2 makes tampering *detectable*,
  not *impossible*; detection requires an independent verifier to actually check
  the chain.
- **Auth-binding is only as strong as the lockscreen.** Auth-bound consent keys
  inherit the weaknesses of the user's PIN/biometric.
- **Attestation requires a relying party.** The attestation chain is worthless
  unless something validates it against the Google root and checks
  verified-boot state and revocation. No verifier ⇒ no security benefit.
- **Throughput / latency.** Discrete security chips are slow relative to the
  SoC. Per-entry signing of a high-volume audit log may be impractical;
  **checkpoint-signing** the chain head periodically is the likely design.
- **Device-bound = portability cost.** Non-extractable keys cannot be backed up
  or migrated to a new device; identity/recovery flows must plan for re-enrolling
  and re-attesting a replacement device.
- **Single hardware vendor / supply chain.** Trust roots in Google's CA and
  Pixel firmware; the attestation root and revocation infra are out of our
  control.

---

## 6. Open questions

- [ ] **API key storage (#61):** Is wrap-with-StrongBox sufficient for agent API
      keys, or should long-lived bearer tokens be brokered server-side so the
      device never holds them? (See §2.4.)
- [ ] **Audit-anchor granularity:** Per-entry signing vs. periodic checkpoint
      signing of the hash-chain head — what throughput does the Policy Engine
      need, and what tamper-detection latency is acceptable?
- [ ] **Remote attestation protocol:** Who are the relying parties, what
      challenge/response flow, and how is verified-boot state + revocation
      checked? (#61 marks attestation as a future capability.)
- [ ] **Rollback-resistance availability:** Confirm Titan M2 / KeyMint exposes
      rollback resistance for our key purposes on the target Pixel + Android
      version, and define behavior if it doesn't.
- [ ] **Fail-closed policy:** Exact behavior when StrongBox/attestation is
      unavailable (e.g. non-Pixel test device, downlevel API) — refuse to start,
      run degraded with explicit warnings, or block only security-critical ops?
- [ ] **Key lifecycle:** Rotation, revocation, and re-enrollment for the
      identity and audit-anchor keys, including device-replacement recovery.
- [ ] **Path A vs Path B differences:** Any StrongBox/attestation behavior that
      differs between a custom AOSP ROM (Path A) and a sideloaded APK (Path B)?

---

## References (within this repo)

- [`docs/hardware/README.md`](./README.md) — hardware docs index
- [`docs/architecture/README.md`](../architecture/README.md) — Policy Engine and
  other subsystem specs
- Issue **#61** — *Update secure enclave references from generic to Pixel
  Titan M2*
