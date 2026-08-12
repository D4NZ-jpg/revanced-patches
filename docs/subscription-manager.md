# Subscription manager

The experimental patch filters supported root cards in the Subscriptions feed. The existing watched filter extracts a video ID only from strict YouTube thumbnail URLs and can hide supported regular videos marked watched by the playback-time hook at the configured threshold. Parsing is schema-independent, bounded, and fail-open: malformed, ambiguous, oversized, or budget-limited cards remain visible. Bounded structural debug diagnostics omit raw identity values.

On YouTube 20.40.45, active identity transitions and startup hydration select a hashed per-account namespace. Incognito and unresolved identities use isolated, nonpersistent state. The hook reads only YouTube's stable identity ID and incognito flag; it never reads or stores the account name or email.

A separate **Experimental: Swipe to hide** setting is visible and off by default. A confirmed left swipe persists a local per-account hide only when the bound RecyclerView item and the exact attested source route agree on identity and position. The tuned detector activates after 20dp of leftward travel, requires 1.75:1 horizontal intent, and commits after the larger of 80dp or 30% of the card width. Vertical-biased or ambiguous movement, reversal, and multitouch cancel the gesture. Removal mutates the supported source leaf when its delayed plan is still current. Once the swipe is confirmed and persisted, a stale visual-removal plan does not undo the user's decision; persisted hides are reapplied when a supported entry binds again.

On the verified YouTube `20.40.45` route, a committed swipe also attempts YouTube's original native **Hide** action. The bridge accepts only the exact synchronous transformed menu command owned by the swiped card, the same live dispatcher and sender view, and exactly one structurally attested Hide command. It copies the original endpoint map and preserves YouTube's interaction logger; it never synthesizes endpoints, tokens, or authenticated requests. The internally opened overflow menu is always suppressed for the active swipe; it must never flash open when native validation or dispatch fails. Any ownership, command, dispatcher, menu, or ABI ambiguity skips the native action and fails open to the authoritative local hide.

Native Hide dispatch and deliberate swipe behavior passed isolated physical-device validation without Frida or Gadget in the production build. Pagination, account/incognito switching, and live/upcoming entries still need broader validation. Channel hiding and feed red-bar progress detection are not implemented. Every hook must demonstrate safe lifecycle and failure behavior before the patch declares another compatible YouTube version.

Validation commands:

```sh
./gradlew :extensions:subscriptionmanager:testDebugUnitTest
./gradlew :extensions:subscriptionmanager:assembleDebug :extensions:subscriptionmanager:lintDebug
./gradlew :patches:buildAndroid :patches:checkLegacyAbi
git diff --check
```
