# Subscription manager

The experimental patch currently filters supported root cards in the Subscriptions feed. It extracts a video ID only from strict YouTube thumbnail URLs and can hide videos marked watched by the existing playback-time hook at the configured threshold. Parsing is schema-independent, bounded, and fail-open: malformed, ambiguous, oversized, or budget-limited cards remain visible. Debug diagnostics fingerprint identities rather than logging raw IDs.

On verified YouTube 20.40.45, active identity transitions and startup hydration select a hashed per-account namespace. Incognito and unresolved identities use isolated, nonpersistent state. The hook reads only YouTube's stable identity ID and incognito flag; it never reads or stores the account name or email.

Swipe handling, a channel menu action, and feed red-bar progress detection are not implemented; they remain evidence-gated. Their target-APK evidence must establish stable card-to-view ownership, per-menu channel identity, and the feed protobuf field/path and scale that represent red-bar progress. Every hook must demonstrate safe lifecycle and failure behavior before the patch declares another compatible YouTube version.

Validation commands:

```sh
./gradlew :extensions:subscriptionmanager:testDebugUnitTest
./gradlew :extensions:subscriptionmanager:assembleDebug :extensions:subscriptionmanager:lintDebug
./gradlew :patches:buildAndroid :patches:checkLegacyAbi
git diff --check
```
