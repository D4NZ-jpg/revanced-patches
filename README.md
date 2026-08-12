<div align="center">
  <picture>
    <source width="256" media="(prefers-color-scheme: dark)" srcset="assets/revanced-headline/revanced-headline-vertical-dark.svg">
    <img width="256" src="assets/revanced-headline/revanced-headline-vertical-light.svg" alt="ReVanced">
  </picture>

  # D4NZ ReVanced Patches

  **Custom patches that install alongside ReVanced Manager's default patch source.**

  [![Release](https://img.shields.io/github/v/release/D4NZ-jpg/revanced-patches?style=flat-square)](https://github.com/D4NZ-jpg/revanced-patches/releases/latest)
  [![Build](https://img.shields.io/github/actions/workflow/status/D4NZ-jpg/revanced-patches/release.yml?style=flat-square)](https://github.com/D4NZ-jpg/revanced-patches/actions/workflows/release.yml)
</div>

> [!IMPORTANT]
> This is an unofficial, custom-only patch bundle. It does not replace, redistribute, or duplicate the default ReVanced patch bundle.

## Add to ReVanced Manager

Keep ReVanced Manager's default patch source enabled, then add this source:

1. Open **Patches** in ReVanced Manager.
2. Tap the edit button, then **+**.
3. Choose **Enter URL**.
4. Enter:

   ```text
   https://raw.githubusercontent.com/D4NZ-jpg/revanced-patches/main/manager.json
   ```

Manager loads this bundle together with the official bundle. The URL remains the same for future custom patches and other apps.

## Included patches

| App | Patch | Supported version | Status |
| --- | --- | --- | --- |
| YouTube | Subscription manager | `20.40.45` | Experimental |

### Subscription manager

A local, opt-in filter for regular videos in YouTube's Subscriptions feed.

- Hides supported regular videos when local playback reaches the configured watched threshold.
- Offers a separate, default-off **Experimental: Swipe to hide** beta that persistently hides supported entries after a deliberate left swipe and, when exact card ownership and YouTube's native command route are proven, also invokes the original native **Hide** action with its Undo banner.
- Separates persistent state using hashed per-account namespaces.
- Keeps incognito and unresolved sessions isolated and nonpersistent.
- Fails open when card identity or progress cannot be established safely.
- Never reads or stores account names, email addresses, or raw account identifiers.

Enable it under ReVanced's **Feed** settings after patching.

> [!WARNING]
> Swipe-to-hide remains experimental. Native Hide dispatch and deliberate swipe behavior have passed isolated device validation on YouTube `20.40.45`; pagination, account/incognito switching, and live/upcoming entries still need broader validation. The **Hide channel** menu action and feed red-bar progress detection are not implemented.

See [Subscription manager technical notes](docs/subscription-manager.md) for design and validation details.

## How this source stays custom-only

The custom patch compiles against a pinned official ReVanced bundle so it can declare cross-bundle dependencies on settings, Litho filtering, navigation, and video-information patches. That upstream bundle is `compileOnly`: it is never packaged into this repository's `.rvp`.

The runtime code is packaged in a separate `subscriptionmanager.rve` namespace. Official extension classes are represented only by compile/test stubs and are supplied at patch time by Manager's default source.

## Build locally

Requirements:

- JDK 17
- GitHub Packages credentials with `read:packages`

```bash
export ORG_GRADLE_PROJECT_githubPackagesUsername="YOUR_GITHUB_USERNAME"
export ORG_GRADLE_PROJECT_githubPackagesPassword="YOUR_GITHUB_TOKEN"

./gradlew \
  :extensions:subscriptionmanager:testDebugUnitTest \
  :patches:buildAndroid \
  --no-daemon
```

The bundle is written to `patches/build/libs/`.

> [!WARNING]
> Never commit credentials, APKs, signing keys, or raw account/video/channel identities.

## Project layout

| Path | Purpose |
| --- | --- |
| `patches/` | Custom patch definition, fingerprints, settings resources, and bundle build |
| `extensions/subscriptionmanager/` | Custom runtime extension and focused JVM tests |
| `extensions/subscriptionmanager/stub/` | Compile/test-only official runtime API stubs |
| `vendor/` | Pinned, compile-only official patch ABI |
| `manager.json` | Stable Manager source descriptor |

## Publish a release

Run **Actions → Release custom patches**, provide a new `v`-prefixed version and description, and start the workflow. It updates the Manager descriptor, builds and checks the custom-only bundle, publishes the `.rvp`, and creates an artifact attestation.
