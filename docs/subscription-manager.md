# Subscription manager

The experimental patch filters supported root cards in the Subscriptions feed. The existing watched filter extracts a video ID only from strict YouTube thumbnail URLs and can hide supported regular videos marked watched by the playback-time hook at the configured threshold. Parsing is schema-independent, bounded, and fail-open: malformed, ambiguous, oversized, or budget-limited cards remain visible. Bounded structural debug diagnostics omit raw identity values.

On YouTube 20.40.45, active identity transitions and startup hydration select a hashed per-account namespace. Incognito and unresolved identities use isolated, nonpersistent state. The hook reads only YouTube's stable identity ID and incognito flag; it never reads or stores the account name or email.

A separate **Experimental: Swipe to hide** setting is visible and off by default. A confirmed left swipe persists a local per-account hide only when the bound RecyclerView item and the exact attested source route agree on identity and position. Removal mutates the supported source leaf and otherwise fails open; a failed mutation rolls back a newly persisted hide. Persisted hides are reapplied when a supported entry binds again.

The swipe beta has not passed device validation. Pagination and live/upcoming entries remain unverified. Channel hiding and feed red-bar progress detection are not implemented. Every hook must demonstrate safe lifecycle and failure behavior before the patch declares another compatible YouTube version.

Validation commands:

```sh
./gradlew :extensions:subscriptionmanager:testDebugUnitTest
./gradlew :extensions:subscriptionmanager:assembleDebug :extensions:subscriptionmanager:lintDebug
./gradlew :patches:buildAndroid :patches:checkLegacyAbi
git diff --check
```
